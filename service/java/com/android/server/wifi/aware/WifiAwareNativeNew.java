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

package com.android.server.wifi.aware;

import com.android.server.wifi.WifiInjector;
import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.WifiStatus;
import android.util.Log;

import java.util.Optional;

/**
 * Interface to Wi-Fi Aware HIDL (HAL).
 */
public class WifiAwareNativeNew {
    private static final String TAG = "WifiAwareNativeNew";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final String HAL_HIDL_SERVICE_NAME = "wifi";

    private final WifiInjector mWifiInjector;
    private IWifi mHidlWifi;

    public WifiAwareNativeNew(WifiInjector wifiInjector) {
        mWifiInjector = wifiInjector;

        mHidlWifi = IWifi.getService(HAL_HIDL_SERVICE_NAME);
        if (mHidlWifi == null) {
            Log.e(TAG, "IWifi - can't get service: " + HAL_HIDL_SERVICE_NAME);
        }
    }

    /**
     * Checks to see if Wi-Fi Aware is supported by the current mode of the specified chip.
     */
    public boolean isSupportedByCurrentMode(IWifiChip chip) {
        Optional<Integer> currentModeId = Optional.ofNullable(0);
        chip.getMode((WifiStatus status, int modeId) -> {
            currentModeId = modeId;
        });
        chip.getMode(new IWifiChip.getModeCallback() {
            @Override
            public void onValues(WifiStatus status, int modeId) {
                currentModeId = modeId;
            }
        });
        return false;
    }

    /**
     * Returns the first chip which can currently support Wi-Fi Aware (based on its mode).
     * Returns null if no chip can currently support it.
     */
    public IWifiChip getChipCurrentlySupporting() {
        return null;
    }

    /**
     * Initializes the connection to the Wi-Fi Aware HAL:
     * - Validate that Aware is supported by chip (should be if get here since platform feature
     *   is configured)
     * - Check if current 'mode' supports Aware: will NOT force a mode change!
     * - Check if Wi-Fi is already started: will NOT start it on its own
     * - Get NAN interface
     * - Register for change is status or mode (which may disable/enable Aware usage)
     *
     * @return true if connection successful, false otherwise (if failed)
     */
    public boolean connectToAwareHal() {
        return false;
    }
}
