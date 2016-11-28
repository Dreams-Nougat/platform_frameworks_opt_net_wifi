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

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.test.TestLooper;

import com.android.internal.util.AsyncChannel;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** TODO: http://go/java-style#javadoc */
class MockWifiServiceImpl extends WifiServiceImpl {
    @Mock WifiInjector mWifiInjector;
    @Mock Context mContext;
    @Mock WifiStateMachine mWifiStateMachine;
    @Mock WifiNotificationController mWifiNotificationController;
    @Mock HandlerThread mClientHandler;
    @Mock WifiStateMachineHandler mWifiStateMachineHandler;
    @Mock AsyncChannel mAsyncChannel;
    @Mock Resources mResources;
    @Mock WifiConfigManager mWifiConfigManager;

    TestLooper mTestLooper;

    MockWifiServiceImpl(Context context) {
        super(context);
    }

    @Override
    WifiInjector createWifiInjector(Context context) {
        MockitoAnnotations.initMocks(this);
        mContext = context;
        mTestLooper = new TestLooper();
        when(mWifiInjector.getWifiStateMachine()).thenReturn(mWifiStateMachine);
        when(mWifiInjector.getWifiNotificationController()).thenReturn(mWifiNotificationController);
        when(mWifiInjector.getWifiServiceHandlerThread()).thenReturn(mClientHandler);
        when(mClientHandler.getLooper()).thenReturn(mTestLooper.getLooper());
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getBoolean(anyInt())).thenReturn(true);
        when(mWifiInjector.getWifiConfigManager()).thenReturn(mWifiConfigManager);

        return mWifiInjector;
    }

    @Override
    void createWifiStateMachineHandler(Looper looper) {
        // don't want to attempt to create a handler
    }

    @Override
    public int getVerboseLoggingLevel() {
        return 0;
    }

    @Override
    void enableVerboseLoggingInternal(int verbose) {
        // don't attempt to set logging
    }

    public WifiInjector getMockWifiInjector() {
        return mWifiInjector;
    }
}
