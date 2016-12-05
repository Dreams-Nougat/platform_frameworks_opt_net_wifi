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

import android.net.NetworkCapabilities;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WifiNetworkSelector.NetworkEvaluator} implementation that uses
 * {@link NetworkScoreManager#requestRecommendation(RecommendationRequest)}.
 */
public class RecommendedNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private final NetworkScoreManager mNetworkScoreManager;
    private final WifiNetworkScoreCache mNetworkScoreCache;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;

    RecommendedNetworkEvaluator(WifiNetworkScoreCache networkScoreCache,
            NetworkScoreManager networkScoreManager, WifiConfigManager wifiConfigManager,
            LocalLog localLog) {
        mNetworkScoreCache = networkScoreCache;
        mNetworkScoreManager = networkScoreManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;

    }
    @Override
    public void update(List<ScanDetail> scanDetails) {
        updateNetworkScoreCache(scanDetails);
    }

    private void updateNetworkScoreCache(List<ScanDetail> scanDetails) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            // Is there a score for this network? If not, request a score.
            if (!mNetworkScoreCache.isScoredNetwork(scanResult)) {
                try {
                    WifiKey wifiKey = new WifiKey("\"" + scanResult.SSID + "\"", scanResult.BSSID);
                    unscoredNetworks.add(new NetworkKey(wifiKey));
                } catch (IllegalArgumentException e) {
                    mLocalLog.log("Invalid SSID=" + scanResult.SSID + " BSSID=" + scanResult.BSSID
                            + " for network score. Skip.");
                }
            }
        }

        // Kick the score manager if there are any unscored network.
        if (!unscoredNetworks.isEmpty()) {
            NetworkKey[] unscoredNetworkKeys =
                    unscoredNetworks.toArray(new NetworkKey[unscoredNetworks.size()]);
            mNetworkScoreManager.requestScores(unscoredNetworkKeys);
        }
    }

    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        List<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < scanDetails.size(); i++) {
            ScanResult scanResult = scanDetails.get(i).getScanResult();
            if (!mWifiConfigManager.wasEphemeralNetworkDeleted(
                    ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                scanResults.add(scanResult);
            }
        }

        if (scanResults.isEmpty()) {
            return null;
        }

        NetworkCapabilities networkCapabilities = new NetworkCapabilities();
        if (untrustedNetworkAllowed) {
            networkCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        }

        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(scanResults.toArray(new ScanResult[scanResults.size()]))
                .setNetworkCapabilities(networkCapabilities)
                // TODO: pass in currently recommended network
                .build();
        RecommendationResult result = mNetworkScoreManager.requestRecommendation(request);
        if (result != null && result.getWifiConfiguration() != null) {
            WifiConfiguration wifiConfiguration = result.getWifiConfiguration();
            mWifiConfigManager.setNetworkCandidateScanResult(wifiConfiguration.networkId,
                    wifiConfiguration.getNetworkSelectionStatus().getCandidate(), 0);
            return wifiConfiguration;
        }
        return null;
    }

    @Override
    public String getName() {
        return "RecommendedNetworkEvaluator";
    }
}
