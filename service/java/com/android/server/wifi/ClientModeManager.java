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
import android.net.wifi.IClientInterface;
import android.os.INetworkManagementService;
import android.os.Looper;

/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class ClientModeManager implements ActiveModeManager {

    private static final String TAG = "ClientModeManager";

    private final Context mContext;
    private final WifiNative mWifiNative;
    private final Listener mListener;
    private final IClientInterface mClientInterface;
    private final INetworkManagementService mNwService;
    private final WifiMonitor mWifiMonitor;

    ClientModeManager(Context context,
                      Looper looper,
                      WifiNative wifiNative,
                      Listener listener,
                      IClientInterface clientInterface,
                      INetworkManagementService nms,
                      WifiMonitor wifiMonitor) {
        mContext = context;
        mWifiNative = wifiNative;
        mListener = listener;
        mClientInterface = clientInterface;
        mNwService = nms;
        mWifiMonitor = wifiMonitor;
    }

    /**
     * Start client mode.
     */
    public void start() {

    }

    /**
     * Disconnect from any currently connected networks and stop client mode.
     */
    public void stop() {

    }

    /**
     * Listener for ClientMode state changes.
     */
    public interface Listener {
        /**
         * Invoke when wifi state changes.
         * @param state new wifi state
         */
        void onStateChanged(int state);
    }
}
