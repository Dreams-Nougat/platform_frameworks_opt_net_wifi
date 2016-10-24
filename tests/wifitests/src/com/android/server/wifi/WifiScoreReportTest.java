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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.NetworkAgent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;

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
    @Mock NetworkAgent mNetworkAgent;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiInfo mWifiInfo;
    @Mock WifiMetrics mWifiMetrics;

    private Context getContext() throws Exception {
        Context context = mock(Context.class);
        MockResources resources = new com.android.server.wifi.MockResources();
        when(context.getResources()).thenReturn(resources);
        return context;
    }

    /**
     * Set up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "nooooooooooo";
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.hiddenSSID = false;
        when(mWifiConfigManager.getSavedNetworks()).thenReturn(Arrays.asList(config));
        Context context = getContext();
        WifiConfigManager wifiConfigManager = mWifiConfigManager;
        mWifiScoreReport = new WifiScoreReport(context, wifiConfigManager);
    }

    /**
     * Cleanup after test
     */
    @After
    public void tearDown() throws Exception {
        mWifiInfo = null;
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
        mWifiScoreReport.calculateAndReportScore(wifiInfo, networkAgent, aggressiveHandover,
                wifiMetrics);
        // TODO: make sure wifiMetrics has been updated
        verify(mNetworkAgent, atLeast(1)).sendNetworkScore(anyInt());
    }
}
