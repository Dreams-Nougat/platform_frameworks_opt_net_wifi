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

import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link WifiServiceImpl}.
 *
 * Note: this is intended to build up over time and will not immediately cover the entire file.
 */
@SmallTest
public class WifiServiceImplTest {

    private static final String TAG = "WifiServiceImplTest";

    @Mock Context mContext;
    @Mock WifiInjector mWifiInjector;
    MockWifiServiceImpl mWifiServiceImpl;

    @Mock WifiTrafficPoller mWifiTrafficPoller;
    @Mock WifiStateMachine mWifiStateMachine;

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);

        mWifiServiceImpl = new MockWifiServiceImpl(mContext);
        mWifiInjector = mWifiServiceImpl.getMockWifiInjector();
        mWifiStateMachine = mWifiInjector.getWifiStateMachine();
    }

    @Test
    public void testRemoveNetworkUnknown() {
        assertFalse(mWifiServiceImpl.removeNetwork(-1));
    }
}
