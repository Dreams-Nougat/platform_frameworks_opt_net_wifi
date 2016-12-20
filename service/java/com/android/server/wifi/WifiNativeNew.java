/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wifi;

import android.annotation.Nullable;
import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiStaIface;
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.RttConfig;
import android.hardware.wifi.V1_0.RttResponder;
import android.hardware.wifi.V1_0.StaBackgroundScanCapabilities;
import android.hardware.wifi.V1_0.StaLinkLayerStats;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.net.wifi.RttManager;
import android.net.wifi.RttManager.ResponderConfig;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.util.MutableBoolean;
import android.util.MutableInt;

import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * waitForEvent() is called on the monitor thread for events. All other methods
 * must be serialized from the framework.
 */
public class WifiNativeNew {

    private static boolean sDGB = true; // TODO(mplass): false for production
    private static final String TAG = "WifiNativeNew";

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            sDGB = true;
        } else {
            sDGB = false;
        }
    }

    // TODO(mplass): figure out where we need locking in hidl world. b/33383725
    public static final Object sLock = new Object();

    // HAL command ids
    private static int sCmdId = 1;
    private static int getNewCmdIdLocked() {
        return sCmdId++;
    }

    // Vendor HAL HIDL interface objects.
    private static final String HAL_HIDL_SERVICE_NAME = "wifi";
    private IWifi mHidlWifi;
    private     IWifiChip mHidlWifiChip;
    private         ArrayList<IWifiChip.ChipMode> mHidlWifiChipAvailableModes = null;
    private         IWifiStaIface mIWifiStaIface;
    private         IWifiRttController mIWifiRttController;

    // Supplicant HAL HIDL interface objects
    private ISupplicant mHidlSupplicant;
    private     ISupplicantStaIface mHidlSupplicantStaIface;

    /**
     * Bring up the HIDL Vendor HAL and configure for STA mode
     */
    public boolean startHidlHal() {
        /** Get the Wifi Service */
        try {
            mHidlWifi = IWifi.getService(HAL_HIDL_SERVICE_NAME);
        } catch (NoSuchElementException e) {
            Log.e(TAG, "HIDL service not registered: " + HAL_HIDL_SERVICE_NAME);
        }
        if (mHidlWifi == null) {
            Log.e(TAG, "Failed to get Wifi HIDL interface objects.");
            return false;
        }
        /** Start the Wifi Service*/
        WifiStatus status = mHidlWifi.start();
        if (status.code != WifiStatusCode.SUCCESS) {
            Log.e(TAG, "Starting wifi hal failed. Error code: " + status.code);
            return false;
        }
        /** List available Wifi chips */
        final ArrayList<Integer> chipIdsInternal = new ArrayList<>();
        // Use lambda's here.
        IWifi.getChipIdsCallback chipIdsCb = new IWifi.getChipIdsCallback() {
            public void onValues(WifiStatus status, java.util.ArrayList<Integer> chipIds) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting chip ids failed. Error code: " + status.code);
                    return;
                }
                chipIdsInternal.addAll(chipIds);
            }
        };
        mHidlWifi.getChipIds(chipIdsCb);
        if (chipIdsInternal.size() != 1) {
            Log.e(TAG, "Expected 1 chip to be present, got: " + chipIdsInternal.size());
            return false;
        }
        /** Get chip 0 */
        // Use lambda's here.
        IWifi.getChipCallback chipCb = new IWifi.getChipCallback() {
            public void onValues(WifiStatus status, IWifiChip chip) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting chip failed. Error code: " + status.code);
                    return;
                }
                mHidlWifiChip = chip;
            }
        };
        mHidlWifi.getChip(chipIdsInternal.get(0).intValue(), chipCb);
        if (mHidlWifiChip == null) {
            Log.e(TAG, "Failed to get WifiChip HIDL interface objects.");
            return false;
        }
        /** Configure Chip Mode for STA */
        mHidlWifiChip.getAvailableModes(new IWifiChip.getAvailableModesCallback() {
            public void onValues(WifiStatus status, ArrayList<IWifiChip.ChipMode> modes) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting chip modes failed. Error code: " + status.code);
                    return;
                }
                mHidlWifiChipAvailableModes = modes;
            }
        });
        if (mHidlWifiChipAvailableModes == null) {
            Log.e(TAG, "Failed to get WifiChip HIDL chip available modes.");
            return false;
        }
        int modeId = -1;
        int hits = 0;
        int wantedType = IfaceType.STA;
        for (IWifiChip.ChipMode mode : mHidlWifiChipAvailableModes) {
            for (IWifiChip.ChipIfaceCombination combination : mode.availableCombinations) {
                for (IWifiChip.ChipIfaceCombinationLimit ifaceLimit : combination.limits) {
                    for (Integer ifaceType : ifaceLimit.types) {
                        if (ifaceType.equals(wantedType)) {
                            modeId = mode.id;
                            hits++;
                        }
                    }
                }
            }
        }
        if (hits == 0) {
            Log.e(TAG, "Failed to find matching mode");
            return false;
        }
        if (hits > 1 && sDGB) {
            Log.d(TAG, "number of matching modes: " + hits + ", picked modeId " + modeId);
        }
        WifiStatus wifiStatus = mHidlWifiChip.configureChip(modeId);
        if (wifiStatus.code != WifiStatusCode.SUCCESS) {
            Log.e(TAG, "configureChip failed. code: " + wifiStatus.code);
        }

        /** Create Wifi STA Interface */

        IWifiChip.createStaIfaceCallback createStaIfaceCb =
                new IWifiChip.createStaIfaceCallback() {
            public void onValues(WifiStatus status, IWifiStaIface iface) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Create STA Iface failed. Error code: " + status.code);
                    return;
                }
                mIWifiStaIface = iface;
            }
        };
        mHidlWifiChip.createStaIface(createStaIfaceCb);
        if (mIWifiStaIface == null) {
            return false;
        }

        /** Create the RTT controller */
        mIWifiRttController = null;
        mHidlWifiChip.createRttController(
                mIWifiStaIface,
                new IWifiChip.createRttControllerCallback() {
                    public void onValues(WifiStatus status, IWifiRttController rtt) {
                        if (status.code ==  WifiStatusCode.SUCCESS) {
                            mIWifiRttController = rtt;
                            Log.e(TAG, "Made " + mIWifiRttController);
                        }
                    }
                }
        );

        Log.i(TAG, "Retrieved the HIDL interface objects.");
        return true;
    }

    /** Stops the HAL
     * (SOMEDAY)
     */
    public void stopHal() {
        // XXX
    }

    /** Tests whether the HAL is running or not
     */
    public boolean isHalStarted() {
        return mIWifiStaIface != null;
    }

    /** Gets the interface index given a name
     */
    public int queryInterfaceIndex(String interfaceName) {
        // XXX
        return -1;
    }

    /** Gets the interface name given an index
     *
     * @param index of interface
     * @return interface name, or null if index is unknown
     */
    public String getInterfaceName(int index) {
        // XXX
        return "notyet0";
    }

    /** Holds the result of getScanCapabilities */
    public static class ScanCapabilities {
        public int max_scan_cache_size;
        public int max_scan_buckets;
        public int max_ap_cache_per_scan;
        public int max_rssi_sample_size;
        public int max_scan_reporting_threshold;
        public int max_hotlist_bssids;
        public int max_significant_wifi_change_aps;
        public int max_bssid_history_entries;
        public int max_number_epno_networks;
        public int max_number_epno_networks_by_ssid;
        public int max_number_of_white_listed_ssid;
    }

    /** Gets the scan capabilities
     *
     * @param capabilities object to be filled in
     * @return true for success. false for failure
     */
    public boolean getScanCapabilities(ScanCapabilities capabilities) {
        MutableBoolean ok = new MutableBoolean(false);
        ScanCapabilities out = capabilities;
        if (!isHalStarted()) {
            return false;
        }
        mIWifiStaIface.getBackgroundScanCapabilities(
                new IWifiStaIface.getBackgroundScanCapabilitiesCallback() {
                    public void onValues(
                            WifiStatus status,
                            StaBackgroundScanCapabilities capabilities) {
                        if (status.code != WifiStatusCode.SUCCESS) return;
                        out.max_scan_cache_size = capabilities.maxCacheSize;
                        out.max_ap_cache_per_scan = capabilities.maxApCachePerScan;
                        out.max_scan_buckets = capabilities.maxBuckets;
                        out.max_rssi_sample_size = 0;
                        out.max_scan_reporting_threshold = capabilities.maxReportingThreshold;
                        out.max_hotlist_bssids = 0;
                        out.max_significant_wifi_change_aps = 0;
                        out.max_bssid_history_entries = 0;
                        out.max_number_epno_networks = 0;
                        out.max_number_epno_networks_by_ssid = 0;
                        out.max_number_of_white_listed_ssid = 0;
                        ok.value = true;
                    }
                }
        );
        return ok.value;
    }

    /** Get the link layer statistics
     *
     * @param iface is the name of the wifi interface (checked for null, otherwise ignored)
     * @return the statistics, or null if unable to do so
     */
    public WifiLinkLayerStats getWifiLinkLayerStats(String iface) {
        if (iface == null) return null;
        if (!isHalStarted()) return null;
        WifiLinkLayerStats out = new WifiLinkLayerStats();
        MutableBoolean ok = new MutableBoolean(false);
        mIWifiStaIface.getLinkLayerStats(
                new IWifiStaIface.getLinkLayerStatsCallback() {
                    public void onValues(
                            WifiStatus status,
                            StaLinkLayerStats stats) {
                        if (status.code != WifiStatusCode.SUCCESS) return;
                        out.status = 0; // TODO
                        out.SSID = null; // TODO
                        out.BSSID = null; // TODO
                        out.beacon_rx = stats.iface.beaconRx;
                        out.rssi_mgmt = stats.iface.avgRssiMgmt;
                        /* WME Best Effort Access Category */
                        out.rxmpdu_be = stats.iface.wmeBePktStats.rxMpdu;
                        out.txmpdu_be = stats.iface.wmeBePktStats.txMpdu;
                        out.lostmpdu_be = stats.iface.wmeBePktStats.lostMpdu;
                        out.retries_be = stats.iface.wmeBePktStats.retries;
                        /* WME Background Access Category */
                        out.rxmpdu_bk = stats.iface.wmeBkPktStats.rxMpdu;
                        out.txmpdu_bk = stats.iface.wmeBkPktStats.txMpdu;
                        out.lostmpdu_bk = stats.iface.wmeBkPktStats.lostMpdu;
                        out.retries_bk = stats.iface.wmeBkPktStats.retries;
                        /* WME Video Access Category */
                        out.rxmpdu_vi = stats.iface.wmeViPktStats.rxMpdu;
                        out.txmpdu_vi = stats.iface.wmeViPktStats.txMpdu;
                        out.lostmpdu_vi = stats.iface.wmeViPktStats.lostMpdu;
                        out.retries_vi = stats.iface.wmeViPktStats.retries;
                        /* WME Voice Access Category */
                        out.rxmpdu_vo = stats.iface.wmeVoPktStats.rxMpdu;
                        out.txmpdu_vo = stats.iface.wmeVoPktStats.txMpdu;
                        out.lostmpdu_vo = stats.iface.wmeVoPktStats.lostMpdu;
                        out.retries_vo = stats.iface.wmeVoPktStats.retries;
                        out.on_time = stats.radio.onTimeInMs;
                        out.tx_time = stats.radio.txTimeInMs;
                        out.tx_time_per_level = new int[stats.radio.txTimeInMsPerLevel.size()];
                        for (int i = 0; i < out.tx_time_per_level.length; i++) {
                            out.tx_time_per_level[i] = stats.radio.txTimeInMsPerLevel.get(i);
                        }
                        out.rx_time = stats.radio.rxTimeInMs;
                        out.on_time_scan = stats.radio.onTimeInMsForScan;
                        ok.value = true;
                    }
                }
        );
        return ok.value ? out : null;
    }

    /** Enable link layer stats collection
     *
     * @param iface is the name of the wifi interface (checked for null, otherwise ignored)
     * @param enable must be 1
     */
    public void setWifiLinkLayerStats(String iface, int enable) {
        if (iface == null) return;
        if (enable != 1) {
            Log.e(TAG, "setWifiLinkLayerStats called with enable != 1");
            return;
        }
        boolean debug = false;
        WifiStatus status = mIWifiStaIface.enableLinkLayerStatsCollection(debug);
        if (status.code != WifiStatusCode.SUCCESS) {
            Log.e(TAG, "unable to enable link layer stats collection");
        }
    }

    /** Tranlation table used by getSupportedFeatureSet */
    private static final int[][] sFeatureCapabilityTranslation = {
            {WifiManager.WIFI_FEATURE_INFRA, -1}, // will be set if anything else is set
            {WifiManager.WIFI_FEATURE_INFRA_5G,
                    IWifiStaIface.StaIfaceCapabilityMask.STA_5G
            },
            {WifiManager.WIFI_FEATURE_PASSPOINT,
                    IWifiStaIface.StaIfaceCapabilityMask.HOTSPOT
            },
//            {WifiManager.WIFI_FEATURE_P2P,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_MOBILE_HOTSPOT,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_SCANNER
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_AWARE
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_D2D_RTT,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_D2AP_RTT,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_BATCH_SCAN,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
            {WifiManager.WIFI_FEATURE_PNO,
                    IWifiStaIface.StaIfaceCapabilityMask.PNO
            },
//            {WifiManager.WIFI_FEATURE_ADDITONAL_STA,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
            {WifiManager.WIFI_FEATURE_TDLS,
                    IWifiStaIface.StaIfaceCapabilityMask.TDLS
            },
            {WifiManager.WIFI_FEATURE_TDLS_OFFCHANNEL,
                    IWifiStaIface.StaIfaceCapabilityMask.TDLS_OFFCHANNEL
            },
//            {WifiManager.WIFI_FEATURE_EPR,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_AP_STA,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
            {WifiManager.WIFI_FEATURE_LINK_LAYER_STATS,
                    IWifiStaIface.StaIfaceCapabilityMask.LINK_LAYER_STATS
            },
//            {WifiManager.WIFI_FEATURE_LOGGER,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
//            {WifiManager.WIFI_FEATURE_HAL_EPNO,
//                    IWifiStaIface.StaIfaceCapabilityMask.
//            },
    };
    /** Get the supported features
     *
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    public int getSupportedFeatureSet() {
        if (!isHalStarted()) return 0;
        final MutableInt feat = new MutableInt(0);
        mIWifiStaIface.getCapabilities(
                new IWifiStaIface.getCapabilitiesCallback() {
                    public void onValues(WifiStatus status, int capabilities) {
                        if (status.code != WifiStatusCode.SUCCESS) return;
                        int features = 0;
                        for (int i = 0; i < sFeatureCapabilityTranslation.length; i++) {
                            if ((capabilities & sFeatureCapabilityTranslation[i][1]) != 0) {
                                features |= sFeatureCapabilityTranslation[i][0];
                            }
                        }
                        feat.value = features;
                    }
                }
        );
        return feat.value;
    }

    /* Rtt related commands/events */
    /** Interface for handling RTT events */
    public interface RttEventHandler {
        /** rtt result callback */
        void onRttResults(RttManager.RttResult[] result);
    }

    private RttConfig rttConfigFromRttParams(RttManager.RttParams params) {
        RttConfig rttConfig = new RttConfig();
        for (int i = 0; i < rttConfig.addr.length; i++) {
            rttConfig.addr[i] = 0; // TODO(mplass): translate from the string params.bssid !!
        }
        rttConfig.type = params.deviceType; //TODO(mplass): does this need translation?
        //rttConfig.peer =
        rttConfig.channel.width = params.channelWidth; //TODO(mplass): verify no translation needed.
        rttConfig.channel.centerFreq = params.frequency;
        rttConfig.channel.centerFreq0 = params.centerFreq0;
        rttConfig.channel.centerFreq1 = params.centerFreq1;
        //rttConfig.burstPeriod =
        rttConfig.numBurst = params.numberBurst;
        rttConfig.numFramesPerBurst = params.numSamplesPerBurst;
        rttConfig.numRetriesPerRttFrame = params.numRetriesPerMeasurementFrame;
        rttConfig.numRetriesPerFtmr = params.numRetriesPerFTMR;
        rttConfig.mustRequestLci = params.LCIRequest;
        rttConfig.mustRequestLcr = params.LCRRequest;
        rttConfig.burstDuration = params.burstTimeout;
        rttConfig.preamble = params.preamble; //TODO(mplass): verify no translation needed.
        rttConfig.bw = params.bandwidth; //TODO(mplass): verify no translation needed.
        return rttConfig;
    }

    private ArrayList<RttConfig> rttConfigsFromRttParams(RttManager.RttParams[] params) {
        final int length = params.length;
        ArrayList<RttConfig> config = new ArrayList<RttConfig>(length);
        for (int i = 0; i < length; i++) {
            config.add(rttConfigFromRttParams(params[i]));
        }
        return config;
    }

    private static RttEventHandler sRttEventHandler;
    private static int sRttCmdId;

    /** Starts a new rtt request
     *
     * @param params
     * @param handler
     * @return success indication
     */
    public boolean requestRtt(RttManager.RttParams[] params, RttEventHandler handler) {
        ArrayList<RttConfig> rttConfigs = rttConfigsFromRttParams(params);
        synchronized (sLock) {
            if (isHalStarted()) {
                if (sRttCmdId != 0) {
                    Log.w(TAG, "Last one is still under measurement!");
                    return false;
                }
                sRttCmdId = getNewCmdIdLocked();
                sRttEventHandler = handler;
                WifiStatus status = mIWifiRttController.rangeRequest(sRttCmdId, rttConfigs);
                return status.code == WifiStatusCode.SUCCESS;
            } else {
                return false;
            }
        }
    }

    /** Cancels an outstanding rtt request
     *
     * @param params
     * @return true if there was an outstanding request and it was successfully cancelled
     */
    public boolean cancelRtt(RttManager.RttParams[] params) {
        synchronized (sLock) {
            if (isHalStarted()) {
                if (sRttCmdId == 0) return false;
                ArrayList<byte[/* 6 */]> addrs = null; //TODO(mplass): is null OK here?
                WifiStatus status = mIWifiRttController.rangeCancel(sRttCmdId, addrs);
                sRttCmdId = 0;
                return status.code == WifiStatusCode.SUCCESS;
            } else {
                return false;
            }
        }
    }

    private static int sRttResponderCmdId = 0;
    /**
     * Enables RTT responder role on the device.
     *
     * @return {@link ResponderConfig} if the responder role is successfully enabled,
     * {@code null} otherwise.
     */
    @Nullable
    public ResponderConfig enableRttResponder(int timeoutSeconds) {
        synchronized (sLock) {
            if (!isHalStarted()) return null;
            if (mIWifiRttController == null) return null;
            if (sRttResponderCmdId != 0) {
                Log.e(TAG, "responder mode already enabled - this shouldn't happen");
                return null;
            }
            ResponderConfig config = null;
            int id = getNewCmdIdLocked();
            RttResponder info = new RttResponder();
            WifiStatus status = mIWifiRttController.enableResponder(
                    /* cmdId */id,
                    /* WifiChannelInfo channelHint */null,
                    timeoutSeconds, info);
            if (status.code == WifiStatusCode.SUCCESS) {
                sRttResponderCmdId = id;
                config = new ResponderConfig(); //TODO(mplass): populate this
                if (sDGB) Log.d(TAG, "enabling rtt " + sRttResponderCmdId);
            }
            return config;
        }
    }

    /**
     * Disables RTT responder role.
     *
     * @return {@code true} if responder role is successfully disabled,
     * {@code false} otherwise.
     */
    public boolean disableRttResponder() {
        synchronized (sLock) {
            if (!isHalStarted()) return false;
            if (sRttResponderCmdId == 0) {
                Log.e(TAG, "responder role not enabled yet");
                return true; // TODO(mplass) - why is this false?
            }
            WifiStatus status = mIWifiRttController.disableResponder(sRttResponderCmdId);
            sRttResponderCmdId = 0;
            return status.code == WifiStatusCode.SUCCESS;
        }
    }

    /** not supported */
    public boolean setScanningMacOui(byte[] oui) {
        Log.e(TAG, "setScanningMacOui does nothing");
        return false;
    }

    /** not supported */
    public int [] getChannelsForBand(int band) {
        // stub - only known caller is ApConfigUtil.updateApChannelConfig()
        return null;
    }

    /** not supported */
    public boolean isGetChannelsForBandSupported() {
        // see getChannelsForBand
        return false;
    }

    /** Set DFS - actually, this is always on.
     *
     * @param dfsOn
     * @return success indication
     */
    public boolean setDfsFlag(boolean dfsOn) {
        if (dfsOn) return true;
        return false;
    }



    private boolean startSupplicantHal() {
        return true;
    }
}
