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

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiStaIface;
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.StaBackgroundScanCapabilities;
import android.hardware.wifi.V1_0.StaLinkLayerStats;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.net.wifi.WifiLinkLayerStats;
import android.util.Log;

import java.util.ArrayList;
import java.util.NoSuchElementException;

//import android.os.RemoteException;

/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * waitForEvent() is called on the monitor thread for events. All other methods
 * must be serialized from the framework.
 *
 * {@hide}
 */
public class WifiNativeNew {
    private static final boolean DBG = false;
    private static final String TAG = "WifiNativeNew";

    // Vendor HAL HIDL interface objects.
    private static final String HAL_HIDL_SERVICE_NAME = "wifi";
    private IWifi mHidlWifi;
    private     IWifiChip mHidlWifiChip;
    private         ArrayList<IWifiChip.ChipMode> mHidlWifiChipAvailableModes = null;
    private         IWifiStaIface mIWifiStaIface;
    private         IWifiRttController mHidlWifiRttController;

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
//        } catch (RemoteException e) {
//            Log.e(TAG, "HIDL service not running");
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
        if (hits > 1) {
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

    private boolean mOk; // why does a local not work for this?

    /** Gets the scan capabilities
     *
     * @param capabilities object to be filled in
     * @return true for success. false for failure
     */
    public boolean getScanCapabilities(ScanCapabilities capabilities) {
        mOk = false;
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
                        mOk = true;
                    }
                }
        );
        return mOk;
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
        mOk = false;
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
                        mOk = true;
                    }
                }
        );
        return mOk ? out : null;
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

    private boolean startSupplicantHal() {
        return true;
    }
}
