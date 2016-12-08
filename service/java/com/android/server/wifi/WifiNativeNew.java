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

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiStaIface;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.util.Log;

import java.util.ArrayList;
/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * waitForEvent() is called on the monitor thread for events. All other methods
 * must be serialized from the framework.
 *
 * {@hide}
 */
public class WifiNativeNew {
    private static final boolean DBG = false;
    private static final String TAG = "WifiNativeNew";

    // Vendor HAL HIDL interface objects.
    private static final String HAL_HIDL_SERVICE_NAME = "wifi";
    private IWifi mHidlWifi;
    private     IWifiChip mHidlWifiChip;
    private         IWifiStaIface mHidlWifiStaIface;
    private         IWifiRttController mHidlWifiRttController;

    // Supplicant HAL HIDL interface objects
    private ISupplicant mHidlSupplicant;
    private     ISupplicantStaIface mHidlSupplicantStaIface;

    /**
     * Bring up the HIDL Vendor HAL and configure for STA mode
     * (CHIP CONFIGURATION CODE NOT YET COMPLETE)
     */
    public boolean startHidlHal() {
        /** Get the Wifi Service */
        mHidlWifi = IWifi.getService(HAL_HIDL_SERVICE_NAME);
        if (mHidlWifi == null) {
            Log.e(TAG, "Failed to get Wifi HIDL interface objects.");
            return false;
        }
        /** Start the Wifi Service*/
        WifiStatus status = mHidlWifi.start();
        if (status.code != WifiStatusCode.SUCCESS) {
            Log.e(TAG, "Starting wifi hal failed. Error code: " + status.code);
            return false;
        }

        /** List available Wifi chips */
        final ArrayList<Integer> chipIdsInternal = new ArrayList<>();
        // Use lambda's here.
        IWifi.getChipIdsCallback chipIdsCb = new IWifi.getChipIdsCallback() {
            public void onValues(WifiStatus status, java.util.ArrayList<Integer> chipIds) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting chip ids failed. Error code: " + status.code);
                    return;
                }
                chipIdsInternal.addAll(chipIds);
            }
        };
        mHidlWifi.getChipIds(chipIdsCb);
        if (chipIdsInternal.size() != 1) {
            Log.e(TAG, "Expected 1 chip to be present, got: " + chipIdsInternal.size());
            return false;
        }

        /** Get chip 0 */
        // Use lambda's here.
        IWifi.getChipCallback chipCb = new IWifi.getChipCallback() {
            public void onValues(WifiStatus status, IWifiChip chip) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting chip failed. Error code: " + status.code);
                    return;
                }
                mHidlWifiChip = chip;
            }
        };
        mHidlWifi.getChip(chipIdsInternal.get(0).intValue(), chipCb);
        if (mHidlWifiChip == null) {
            Log.e(TAG, "Failed to get WifiChip HIDL interface objects.");
            return false;
        }
        /** Configure Chip Mode for STA**/
            // see https://android-review.googlesource.com/#/c/310925/8/wifi/1.0/vts/functional/wifi_hidl_test_utils.cpp@224

        /** Get Wifi STA Interface names */
        final ArrayList<String> staIfNames = new ArrayList<>();
        IWifiChip.getStaIfaceNamesCallback staIfaceNamesCb =
                new IWifiChip.getStaIfaceNamesCallback() {
            public void onValues(WifiStatus status, java.util.ArrayList<String> ifnames) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting STA Iface Names failed. Error code: " + status.code);
                    return;
                }
                staIfNames.addAll(ifnames);
            }
        };
        mHidlWifiChip.getStaIfaceNames(staIfaceNamesCb);
        if (staIfNames.size() != 1) {
            Log.e(TAG, "Expected exactly 1 StaIface to be present, got: " + staIfNames.size());
            return false;
        }

        /** Get Wifi STA Interface */
        IWifiChip.getStaIfaceCallback staIfaceCallback = new IWifiChip.getStaIfaceCallback() {
            public void onValues(WifiStatus status, IWifiStaIface iface) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting STA Iface " + staIfNames.get(0) + " failed. Error code: "
                            + status.code);
                    return;
                }
                mHidlWifiStaIface = iface;
            }
        };

        Log.i(TAG, "Retrieved the HIDL interface objects.");
        return true;
    }

    private boolean startSupplicantHal() {
        return true;
    }
}
