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

package com.android.server.wifi.hotspot2;

import android.net.wifi.WifiConfiguration;
import android.os.Process;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiNetworkSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkEvaluator implementation for
 * Passpoint networks.
 */
public class PasspointNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String NAME = "PasspointNetworkEvaluator";

    private final PasspointManager mPasspointManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;

    public PasspointNetworkEvaluator(PasspointManager passpointManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog) {
        mPasspointManager = passpointManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {}

    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid,
                    boolean connected, boolean untrustedNetworkAllowed,
                    List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        // Go through each ScanDetail and match a best provider for each ScanDetail.
        List<Pair<ScanDetail, Pair<PasspointProvider, PasspointMatchInfo>>> providerList =
                new ArrayList<>();
        for (ScanDetail scanDetail : scanDetails) {
            // Skip non-Passpoint APs.
            if (!scanDetail.getNetworkDetail().isInterworking()) {
                continue;
            }

            List<Pair<PasspointProvider, PasspointMatch>> matchedProviders =
                    mPasspointManager.matchProvider(scanDetail);

            // Find the best provider for this ScanDetail.
            Pair<PasspointProvider, PasspointMatchInfo> bestMatch =
                    getBestMatch(scanDetail, matchedProviders);
            if (bestMatch != null) {
                providerList.add(Pair.create(scanDetail, bestMatch));
            }
        }

        // Find the best provider from all scan details.
        Pair<ScanDetail, Pair<PasspointProvider, PasspointMatchInfo>> bestMatch = null;
        for (Pair<ScanDetail, Pair<PasspointProvider, PasspointMatchInfo>> match : providerList) {
            if (bestMatch == null || bestMatch.second.second.compareTo(match.second.second) < 0) {
                bestMatch = match;
            }
        }

        // No matching provider found.
        if (bestMatch == null) {
            return null;
        }

        WifiConfiguration config =
                createWifiConfigForProvider(bestMatch.second.first, bestMatch.first);
        connectableNetworks.add(Pair.create(bestMatch.first, config));
        return config;
    }

    /**
     * Create and return a WifiConfiguration for the given ScanDetail and PasspointProvider.
     * The newly created WifiConfiguration will also be added to WifiConfigManager.
     *
     * @param provider The provider to create WifiConfiguration from
     * @param scanDetail The ScanDetail to create WifiConfiguration from
     * @return {@link WifiConfiguration}
     */
    private WifiConfiguration createWifiConfigForProvider(PasspointProvider provider,
            ScanDetail scanDetail) {
        WifiConfiguration config = provider.getWifiConfig();
        config.ephemeral = true;
        config.SSID = "\"" + scanDetail.getSSID() + "\"";

        // Add the newly created WifiConfiguration to WifiConfigManager.
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(config, Process.WIFI_UID);
        if (!result.isSuccess()) {
            localLog("Failed to add passpoint network");
            return null;
        }
        mWifiConfigManager.setNetworkCandidateScanResult(result.getNetworkId(),
                scanDetail.getScanResult(), 0);
        return mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
    }

    /**
     * Given a list of provider associated with a ScanDetail, determine and return the best
     * provider (with highest score) from the list.
     *
     * A null will be returned if no match is found (providerList is empty).
     *
     * @param scanDetail The ScanDetail associated with matching providers
     * @param providerList The list of matched providers
     * @return Pair of {@link PasspointProvider} and its match info (score)
     */
    private Pair<PasspointProvider, PasspointMatchInfo> getBestMatch(ScanDetail scanDetail,
            List<Pair<PasspointProvider, PasspointMatch>> providerList) {
        PasspointProvider bestProvider = null;
        PasspointMatchInfo bestMatch = null;
        for (Pair<PasspointProvider, PasspointMatch> providerMatch : providerList) {
            PasspointMatchInfo currentMatch =
                    new PasspointMatchInfo(providerMatch.second, scanDetail);
            if (bestMatch == null || bestMatch.compareTo(currentMatch) < 0) {
                bestProvider = providerMatch.first;
                bestMatch = currentMatch;
            }
        }
        if (bestMatch == null) {
            return null;
        }
        return Pair.create(bestProvider, bestMatch);
    }

    private void localLog(String log) {
        if (mLocalLog != null) {
            mLocalLog.log(log);
        }
    }
}
