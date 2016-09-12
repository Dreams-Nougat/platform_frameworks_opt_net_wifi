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

import static com.android.server.wifi.WifiConfigurationTestUtil.generateWifiConfig;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiSsid;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper for WifiNetworkSelector unit tests.
 */
@SmallTest
public class WifiNetworkSelectorTestUtil {

    /**
     * A class that holds a list of scanDetail and their associated WifiConfiguration.
     */
    public static class ScanDetailsAndWifiConfigs {
        List<ScanDetail> mScanDetails;
        WifiConfiguration[] mWifiConfigs;

        ScanDetailsAndWifiConfigs(List<ScanDetail> scanDetails, WifiConfiguration[] configs) {
            mScanDetails = scanDetails;
            mWifiConfigs = configs;
        }

        List<ScanDetail> getScanDetails() {
            return mScanDetails;
        }

        WifiConfiguration[] getWifiConfigs() {
            return mWifiConfigs;
        }
    }

    /**
     * Build a list of ScanDetail based on the caller supplied network SSID, BSSID,
     * frequency, capability and RSSI level information. Create the corresponding
     * WifiConfiguration for these networks and set up the mocked WifiConfigManager.
     *
     * @param ssids an array of SSIDs
     * @param bssids an array of BSSIDs
     * @param freqs an array of the network's frequency
     * @param caps an array of the network's capability
     * @param levels an array of the network's RSSI levels
     * @param securities an array of the network's security setting
     * @param wifiConfigManager the mocked WifiConfigManager
     * @return the constructed ScanDetail list and WifiConfiguration array
     */
    public static ScanDetailsAndWifiConfigs setupScanDetailsAndConfigStore(String[] ssids,
                String[] bssids, int[] freqs, String[] caps, int[] levels, int[] securities,
                WifiConfigManager wifiConfigManager, Clock clock) {
        List<ScanDetail> scanDetails = buildScanDetails(ssids, bssids, freqs, caps, levels, clock);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, securities);
        prepareConfigStore(wifiConfigManager, savedConfigs);
        scanResultLinkConfiguration(wifiConfigManager, savedConfigs, scanDetails);

        return new ScanDetailsAndWifiConfigs(scanDetails, savedConfigs);
    }

    /**
     * Verify whether the WifiConfiguration chosen by WifiNetworkSelector matches
     * with the chosen scan result.
     *
     * @param chosenScanResult the chosen scan result
     * @param chosenCandidate  the chosen configuration
     */
    public static void verifySelectedScanResult(WifiConfigManager wifiConfigManager,
            ScanResult chosenScanResult, WifiConfiguration chosenCandidate) {
        verify(wifiConfigManager, atLeastOnce()).setNetworkCandidateScanResult(
                eq(chosenCandidate.networkId), eq(chosenScanResult), anyInt());
    }


    /**
     * Build a list of scanDetails based on the caller supplied network SSID, BSSID,
     * frequency, capability and RSSI level information.
     *
     * @param ssids an array of SSIDs
     * @param bssids an array of BSSIDs
     * @param freqs an array of the network's frequency
     * @param caps an array of the network's capability
     * @param levels an array of the network's RSSI levels
     * @return the constructed list of ScanDetail
     */
    private static List<ScanDetail> buildScanDetails(String[] ssids, String[] bssids, int[] freq,
                                            String[] caps, int[] levels, Clock clock) {
        List<ScanDetail> scanDetailList = new ArrayList<ScanDetail>();

        long timeStamp = clock.getElapsedSinceBootMillis();
        for (int index = 0; index < ssids.length; index++) {
            ScanDetail scanDetail = new ScanDetail(WifiSsid.createFromAsciiEncoded(ssids[index]),
                    bssids[index], caps[index], levels[index], freq[index], timeStamp, 0);
            scanDetailList.add(scanDetail);
        }
        return scanDetailList;
    }


    /**
     * Generate an array of {@link android.net.wifi.WifiConfiguration} based on the caller
     * supplied network SSID and sencurity information.
     *
     * @param ssids an array of SSIDs
     * @param caps an array of the network's security setting
     * @return the constructed array of {@link android.net.wifi.WifiConfiguration}
     */
    private static WifiConfiguration[] generateWifiConfigurations(String[] ssids,
                int[] securities) {
        if (ssids == null || securities == null || ssids.length != securities.length
                || ssids.length == 0) {
            return null;
        }

        WifiConfiguration[] configs = new WifiConfiguration[ssids.length];
        for (int index = 0; index < ssids.length; index++) {
            configs[index] = generateWifiConfig(index, 0, ssids[index], false, true, null, null,
                    securities[index]);
        }

        return configs;
    }

    /**
     * Add the Configurations to WifiConfigManager (WifiConfigureStore can take them out according
     * to the networkd ID) and setup the WifiConfigManager mocks for these networks.
     * This simulates the WifiConfigManager class behaviour.
     *
     * @param wifiConfigManager the mocked WifiConfigManager
     * @param configs input configuration need to be added to WifiConfigureStore
     */
    private static void prepareConfigStore(WifiConfigManager wifiConfigManager,
                final WifiConfiguration[] configs) {
        when(wifiConfigManager.getConfiguredNetwork(anyInt()))
                .then(new AnswerWithArguments() {
                    public WifiConfiguration answer(int netId) {
                        if (netId >= 0 && netId < configs.length) {
                            return new WifiConfiguration(configs[netId]);
                        } else {
                            return null;
                        }
                    }
                });
        when(wifiConfigManager.getConfiguredNetwork(anyString()))
                .then(new AnswerWithArguments() {
                    public WifiConfiguration answer(String configKey) {
                        for (int netId = 0; netId < configs.length; netId++) {
                            if (TextUtils.equals(configs[netId].configKey(), configKey)) {
                                return new WifiConfiguration(configs[netId]);
                            }
                        }
                        return null;
                    }
                });
        when(wifiConfigManager.getSavedNetworks())
                .then(new AnswerWithArguments() {
                    public List<WifiConfiguration> answer() {
                        List<WifiConfiguration> savedNetworks = new ArrayList<>();
                        for (int netId = 0; netId < configs.length; netId++) {
                            savedNetworks.add(new WifiConfiguration(configs[netId]));
                        }
                        return savedNetworks;
                    }
                });
        when(wifiConfigManager.clearNetworkCandidateScanResult(anyInt()))
                .then(new AnswerWithArguments() {
                    public boolean answer(int netId) {
                        if (netId >= 0 && netId < configs.length) {
                            configs[netId].getNetworkSelectionStatus().setCandidate(null);
                            configs[netId].getNetworkSelectionStatus()
                                    .setCandidateScore(Integer.MIN_VALUE);
                            configs[netId].getNetworkSelectionStatus()
                                    .setSeenInLastQualifiedNetworkSelection(false);
                            return true;
                        } else {
                            return false;
                        }
                    }
                });
        when(wifiConfigManager.setNetworkCandidateScanResult(
                anyInt(), any(ScanResult.class), anyInt()))
                .then(new AnswerWithArguments() {
                    public boolean answer(int netId, ScanResult scanResult, int score) {
                        if (netId >= 0 && netId < configs.length) {
                            configs[netId].getNetworkSelectionStatus().setCandidate(scanResult);
                            configs[netId].getNetworkSelectionStatus().setCandidateScore(score);
                            configs[netId].getNetworkSelectionStatus()
                                    .setSeenInLastQualifiedNetworkSelection(true);
                            return true;
                        } else {
                            return false;
                        }
                    }
                });
        when(wifiConfigManager.clearNetworkConnectChoice(anyInt()))
                .then(new AnswerWithArguments() {
                    public boolean answer(int netId) {
                        if (netId >= 0 && netId < configs.length) {
                            configs[netId].getNetworkSelectionStatus().setConnectChoice(null);
                            configs[netId].getNetworkSelectionStatus()
                                    .setConnectChoiceTimestamp(
                                            NetworkSelectionStatus
                                                    .INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
                            return true;
                        } else {
                            return false;
                        }
                    }
                });
        when(wifiConfigManager.setNetworkConnectChoice(anyInt(), anyString(), anyLong()))
                .then(new AnswerWithArguments() {
                    public boolean answer(int netId, String configKey, long timestamp) {
                        if (netId >= 0 && netId < configs.length) {
                            configs[netId].getNetworkSelectionStatus().setConnectChoice(configKey);
                            configs[netId].getNetworkSelectionStatus().setConnectChoiceTimestamp(
                                    timestamp);
                            return true;
                        } else {
                            return false;
                        }
                    }
                });
    }


    /**
     * Link scan results to the saved configurations.
     *
     * The shorter of the 2 input params will be used to loop over so the inputs don't
     * need to be of equal length. If there are more scan details then configs the remaining scan
     * details will be associated with a NULL config.
     *
     * @param wifiConfigManager the mocked WifiConfigManager
     * @param configs     saved configurations
     * @param scanDetails come in scan results
     */
    private static void scanResultLinkConfiguration(WifiConfigManager wifiConfigManager,
                WifiConfiguration[] configs, List<ScanDetail> scanDetails) {
        if (configs == null || scanDetails == null) {
            return;
        }

        if (scanDetails.size() <= configs.length) {
            for (int i = 0; i < scanDetails.size(); i++) {
                ScanDetail scanDetail = scanDetails.get(i);
                when(wifiConfigManager.getSavedNetworkForScanDetailAndCache(eq(scanDetail)))
                        .thenReturn(configs[i]);
            }
        } else {
            for (int i = 0; i < configs.length; i++) {
                ScanDetail scanDetail = scanDetails.get(i);
                when(wifiConfigManager.getSavedNetworkForScanDetailAndCache(eq(scanDetail)))
                        .thenReturn(configs[i]);
            }

            // associated the remaining scan details with a NULL config.
            for (int i = configs.length; i < scanDetails.size(); i++) {
                when(wifiConfigManager.getSavedNetworkForScanDetailAndCache(
                        eq(scanDetails.get(i)))).thenReturn(null);
            }
        }
    }
}
