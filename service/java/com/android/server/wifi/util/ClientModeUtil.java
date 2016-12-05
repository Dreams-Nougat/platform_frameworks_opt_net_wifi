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

package com.android.server.wifi.util;

import android.net.NetworkCapabilities;

/**
 * Client Mode Manager utility for {@link ClientModeManager} related operations.
 */
public class ClientModeUtil {

    /**
     * Helper method to create the default NetworkCapabilities object to be used in
     * {@link ClientModeManager}.
     *
     * @return NetworkCapabilities Newly created default NetworkCapabilities object.
     */
    public static NetworkCapabilities createDefaultNetworkCapabilities() {
        NetworkCapabilities networkCapabilities = new NetworkCapabilities();

        networkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        networkCapabilities.setLinkUpstreamBandwidthKbps(1024 * 1024);
        networkCapabilities.setLinkDownstreamBandwidthKbps(1024 * 1024);

        return networkCapabilities;
    }
}
