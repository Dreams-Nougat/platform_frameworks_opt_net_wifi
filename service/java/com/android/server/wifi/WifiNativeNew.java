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
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
//import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.NoSuchElementException;

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
    private         ArrayList<IWifiChip.ChipMode> mHidlWifiChipAvailableModes = null;
    private         IWifiStaIface mIWifiStaIface;
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
        try {
            mHidlWifi = IWifi.getService(HAL_HIDL_SERVICE_NAME);
        } catch (NoSuchElementException e) {
            Log.e(TAG, "HIDL service not registered: " + HAL_HIDL_SERVICE_NAME);
//        } catch (RemoteException e) {
//            Log.e(TAG, "HIDL service not running");
        }
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
        /** Configure Chip Mode for STA */
        mHidlWifiChip.getAvailableModes(new IWifiChip.getAvailableModesCallback() {
            public void onValues(WifiStatus status, ArrayList<IWifiChip.ChipMode> modes) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting chip modes failed. Error code: " + status.code);
                    return;
                }
                mHidlWifiChipAvailableModes = modes;
            }
        });
        if (mHidlWifiChipAvailableModes == null) {
            Log.e(TAG, "Failed to get WifiChip HIDL chip available modes.");
            return false;
        }
        int modeId = -1;
        int hits = 0;
        int wantedType = IfaceType.STA;
        for (IWifiChip.ChipMode mode : mHidlWifiChipAvailableModes) {
            for (IWifiChip.ChipIfaceCombination combination : mode.availableCombinations) {
                for (IWifiChip.ChipIfaceCombinationLimit ifaceLimit : combination.limits) {
                    for (Integer ifaceType : ifaceLimit.types) {
                        if (ifaceType.equals(wantedType)) {
                            modeId = mode.id;
                            hits++;
                        }
                    }
                }
            }
        }
        if (hits == 0) {
            Log.e(TAG, "Failed to find matching mode");
            return false;
        }
        if (hits > 1) {
            Log.d(TAG, "number of matching modes: " + hits + ", picked modeId " + modeId);
        }
        WifiStatus wifiStatus = mHidlWifiChip.configureChip(modeId);
        if (wifiStatus.code != WifiStatusCode.SUCCESS) {
            Log.e(TAG, "configureChip failed. code: " + wifiStatus.code);
        }

        /** Create Wifi STA Interface */

        IWifiChip.createStaIfaceCallback createStaIfaceCb =
                new IWifiChip.createStaIfaceCallback() {
            public void onValues(WifiStatus status, IWifiStaIface iface) {
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "Create STA Iface failed. Error code: " + status.code);
                    return;
                }
                mIWifiStaIface = iface;
            }
        };
        mHidlWifiChip.createStaIface(createStaIfaceCb);
        if (mIWifiStaIface == null) {
            return false;
        }

        Log.i(TAG, "Retrieved the HIDL interface objects.");
        return true;
    }

    private boolean startSupplicantHal() {
        return true;
    }
}
