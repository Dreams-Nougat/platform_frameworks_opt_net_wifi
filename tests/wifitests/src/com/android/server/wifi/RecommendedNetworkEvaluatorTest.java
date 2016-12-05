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

import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.net.NetworkCapabilities;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiSsid;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.LocalLog;

import com.android.server.wifi.util.ScanResultUtil;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Unit tests for {@link RecommendedNetworkEvaluator}.
 */
@SmallTest
public class RecommendedNetworkEvaluatorTest {
    private static final ScanDetail SCAN_DETAIL1 = buildScanDetail("ssid");
    private static final ScanDetail SCAN_DETAIL2 = buildScanDetail("ssid2");

    @Mock private NetworkScoreManager mNetworkScoreManager;
    @Mock private LocalLog mLocalLog;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private NetworkScoreManager mScoreManager;
    @Mock private WifiNetworkScoreCache mNetworkScoreCache;

    @Captor private ArgumentCaptor<NetworkKey[]> mNetworkKeyArrayCaptor;
    @Captor private ArgumentCaptor<RecommendationRequest> mRecommendationRequestCaptor;

    private RecommendedNetworkEvaluator mRecommendedNetworkEvaluator;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mRecommendedNetworkEvaluator = new RecommendedNetworkEvaluator(
                mNetworkScoreCache, mNetworkScoreManager, mWifiConfigManager, mLocalLog);
    }

    @Test
    public void testUpdate_emptyScanList() {
        mRecommendedNetworkEvaluator.update(new ArrayList<ScanDetail>());

        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testUpdate_allNetworksScored() {
        when(mNetworkScoreCache.isScoredNetwork(SCAN_DETAIL1.getScanResult())).thenReturn(true);
        when(mNetworkScoreCache.isScoredNetwork(SCAN_DETAIL2.getScanResult())).thenReturn(true);

        mRecommendedNetworkEvaluator.update(Lists.newArrayList(SCAN_DETAIL1, SCAN_DETAIL2));

        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testUpdate_oneScored_oneUnscored() {
        when(mNetworkScoreCache.isScoredNetwork(SCAN_DETAIL1.getScanResult())).thenReturn(true);
        when(mNetworkScoreCache.isScoredNetwork(SCAN_DETAIL2.getScanResult())).thenReturn(false);

        mRecommendedNetworkEvaluator.update(Lists.newArrayList(SCAN_DETAIL1, SCAN_DETAIL2));

        verify(mNetworkScoreManager).requestScores(mNetworkKeyArrayCaptor.capture());

        assertEquals(1, mNetworkKeyArrayCaptor.getValue().length);
        assertEquals(new NetworkKey(new WifiKey(SCAN_DETAIL2.getSSID(),
                SCAN_DETAIL2.getBSSIDString())), mNetworkKeyArrayCaptor.getValue()[0]);
    }

    @Test
    public void testEvaluateNetworks_emptyScanList() {
        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                new ArrayList<ScanDetail>(), null, null, false, false, null);

        assertNull(result);
        verifyZeroInteractions(mWifiConfigManager, mNetworkScoreManager);
    }

    @Test
    public void testEvaluateNetworks_trusted_recommendation_oneScored_oneDeletedEphemeral() {
        WifiConfiguration recommendedWifiConfiguration = new WifiConfiguration();
        recommendedWifiConfiguration.networkId = 5;
        recommendedWifiConfiguration.SSID = SCAN_DETAIL1.getSSID();
        recommendedWifiConfiguration.getNetworkSelectionStatus().setCandidate(
                SCAN_DETAIL1.getScanResult());

        when(mWifiConfigManager.wasEphemeralNetworkDeleted(
                ScanResultUtil.createQuotedSSID(SCAN_DETAIL1.getSSID()))).thenReturn(false);
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(
                ScanResultUtil.createQuotedSSID(SCAN_DETAIL2.getSSID()))).thenReturn(true);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(new RecommendationResult(recommendedWifiConfiguration));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(SCAN_DETAIL1, SCAN_DETAIL2),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        assertEquals(recommendedWifiConfiguration, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(new NetworkCapabilities(),
                mRecommendationRequestCaptor.getValue().getRequiredCapabilities());
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                recommendedWifiConfiguration.networkId,
                recommendedWifiConfiguration.getNetworkSelectionStatus().getCandidate(),
                0);
    }

    @Test
    public void testEvaluateNetworks_untrusted_noRecommendation() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(new RecommendationResult(null));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(SCAN_DETAIL1, SCAN_DETAIL2),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(new NetworkCapabilities()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED),
                mRecommendationRequestCaptor.getValue().getRequiredCapabilities());
        verify(mWifiConfigManager, never())
                .setNetworkCandidateScanResult(anyInt(), any(ScanResult.class), anyInt());
    }

    @Test
    public void testEvaluateNetworks_nullRecommendation() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(null);

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(SCAN_DETAIL1, SCAN_DETAIL2),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verify(mNetworkScoreManager).requestRecommendation(any(RecommendationRequest.class));
        verify(mWifiConfigManager, never())
                .setNetworkCandidateScanResult(anyInt(), any(ScanResult.class), anyInt());
    }

    private static ScanDetail buildScanDetail(String ssid) {
        return new ScanDetail(WifiSsid.createFromAsciiEncoded(ssid),
                "00:00:00:00:00:00", "", 0, 0, 0, 0);
    }
}
