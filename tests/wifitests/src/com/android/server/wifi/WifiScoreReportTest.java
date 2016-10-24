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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkAgent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

/**
 * Unit tests for {@link com.android.server.wifi.WifiScoreReport}.
 */
public class WifiScoreReportTest {

    WifiScoreReport mWifiScoreReport;
    ScanDetailCache mScanDetailCache;
    @Mock Context mContext;
    @Mock NetworkAgent mNetworkAgent;
    @Mock Resources mResources;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiInfo mWifiInfo;
    @Mock WifiMetrics mWifiMetrics;


    private void setUpResources(Resources resources) {
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz))
            .thenReturn(-82);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz))
            .thenReturn(-70);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz))
            .thenReturn(-57);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz))
            .thenReturn(-85);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz))
            .thenReturn(-73);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz))
            .thenReturn(-60);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_24))
            .thenReturn(6); // Mbps
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_5))
            .thenReturn(12);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_24))
            .thenReturn(24);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_5))
            .thenReturn(36);
        when(resources.getBoolean(
                R.bool.config_wifi_framework_cellular_handover_enable_user_triggered_adjustment))
            .thenReturn(true);
    }
    /**
     * Set up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setUpResources(mResources);
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "nooooooooooo";
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.hiddenSSID = false;
        when(mWifiConfigManager.getSavedNetworks()).thenReturn(Arrays.asList(config));
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);
        int maxSize = 10;
        int trimSize = 5;
        mScanDetailCache = new ScanDetailCache(config, maxSize, trimSize);
        // TODO: populate the cache, but probably in the test cases, not here.
        when(mWifiConfigManager.getScanDetailCacheForNetwork(anyInt()))
                .thenReturn(mScanDetailCache);
        when(mContext.getResources()).thenReturn(mResources);
        WifiConfigManager wifiConfigManager = mWifiConfigManager;
        mWifiScoreReport = new WifiScoreReport(mContext, wifiConfigManager);
    }

    /**
     * Cleanup after test
     */
    @After
    public void tearDown() throws Exception {
        mResources = null;
        mWifiScoreReport = null;
        mWifiConfigManager = null;
        mWifiMetrics = null;
    }

    /**
     * Test that setUp succeeds
     */
    @Test
    public void alwaysSucceed() throws Exception {
        assertTrue(mNetworkAgent != null);
        int score = 42;
        mNetworkAgent.sendNetworkScore(score);
        verify(mNetworkAgent, atLeast(1)).sendNetworkScore(score);
        assertTrue(mWifiConfigManager != null);
    }

    /**
     * Test for smoke
     */
    @Test
    public void calculateAndReportScoreSucceeds() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-77);
        WifiInfo wifiInfo = mWifiInfo;
        NetworkAgent networkAgent = mNetworkAgent;
        int aggressiveHandover = 0;
        WifiMetrics wifiMetrics = mWifiMetrics;
        mWifiScoreReport.calculateAndReportScore(wifiInfo,
                networkAgent, aggressiveHandover, wifiMetrics);
        verify(mWifiMetrics, atLeast(1)).incrementWifiScoreCount(anyInt());
        verify(mNetworkAgent, atLeast(1)).sendNetworkScore(anyInt());
    }

    /**
     * Test for operation with null networkAgent
     */
    @Test
    public void networkAgentMayBeNull() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-33);
        assertFalse(mWifiScoreReport.isLastReportValid());
        mWifiScoreReport.enableVerboseLogging(true);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        verify(mWifiMetrics, atLeast(1)).incrementWifiScoreCount(anyInt());
        assertTrue(mWifiScoreReport.isLastReportValid());
    }

    /**
     * Test bad linkspeed counter
     */
    @Test
    public void badLinkspeedCounter() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-123);
        when(mWifiInfo.getLinkSpeed()).thenReturn(1, 1, 1, 300, 1);
        when(mWifiInfo.is24GHz()).thenReturn(true);
        assertFalse(mWifiScoreReport.isLastReportValid());
        mWifiScoreReport.enableVerboseLogging(true);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 1, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 1, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 1, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        verify(mWifiMetrics, atLeast(8)).incrementWifiScoreCount(anyInt());
        verify(mWifiInfo, atLeast(8)).is24GHz();
        assertTrue(mWifiScoreReport.isLastReportValid());
        assertTrue(mWifiScoreReport.getLastBadLinkspeedcount() > 3);
    }
}
