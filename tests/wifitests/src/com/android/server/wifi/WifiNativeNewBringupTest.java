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

import android.content.Context;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNativeNew}.
 *
 * Just the vendor stuff.
 */
public class WifiNativeNewBringupTest {

    WifiNativeNew mWifiNativeNew;

    @Mock Context mContext;

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mWifiNativeNew = new WifiNativeNew();
    }

    /**
     * Cleans up after test
     */
    @After
    public void tearDown() throws Exception {
        mWifiNativeNew.stopHal();
    }

    /**
     * Test for
     *
     * The ... should be ...
     */
    @Test
    public void firstLight() {
        boolean ok = mWifiNativeNew.startHidlHal();
        Assert.assertTrue("hey", ok);
//        when(mWifiInfo.getRssi()).thenReturn(-77);
//        int aggressiveHandover = 0;
//        mWifiNativeNewBringup.calculateAndReportScore(mWifiInfo,
//                mNetworkAgent, aggressiveHandover, mWifiMetrics);
//        verify(mNetworkAgent).sendNetworkScore(anyInt());
//        verify(mWifiMetrics).incrementWifiScoreCount(anyInt());
    }
}
