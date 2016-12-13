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

import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
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
    private static final String SUPPLICANT_HIDL_SERVICE_NAME = "wpa_supplicant";
    // Supplicant HAL HIDL interface objects
    private ISupplicant mHidlSupplicant;
    private ISupplicantIface mHidlSupplicantIface;
    private ISupplicantStaIface mHidlSupplicantStaIface;

    /**
     * Register for service notification & bring up Supplicant HIDL interfaces when service is ready
     */
    public boolean registerForServiceNotification() {
        Log.i(TAG, "Registering SupplicantHidl service ready callback.");
        final IServiceManager serviceManager = IServiceManager.getService("manager");
        if (serviceManager == null) {
            Log.e(TAG, "Failed to get HIDL Service Manager");
            return false;
        }
        IServiceNotification serviceNotificationCb = new IServiceNotification.Stub() {
            public void onRegistration(String fqName, String name, boolean preexisting) {
                Log.i(TAG, "Registered " + fqName + ", " + name + " preexisting=" + preexisting);
                startSupplicantHidl();
            }
        };
        if (!serviceManager.registerForNotifications(ISupplicant.kInterfaceName,
                "", serviceNotificationCb)) {
            Log.e(TAG, "Failed to register for notifcations to " + ISupplicant.kInterfaceName
                    + ", " + SUPPLICANT_HIDL_SERVICE_NAME);
            return false;
        }
        return true;
    }


    /**
     * Bring up the HIDL Vendor HAL and configure for STA mode
     */
    public boolean startHidlHal() {
        /** TBC **/
        return true;
    }

    /**
     * Bring up the Supplicant Hal
     */
    public boolean startSupplicantHidl() {
        Log.i(TAG, "Bringing up supplicant HIDL");
        mHidlSupplicantStaIface = null;
        mHidlSupplicant = ISupplicant.getService(SUPPLICANT_HIDL_SERVICE_NAME);
        if (mHidlSupplicant == null) {
            Log.e(TAG, "Failed to get wpa_supplicant HIDL service.");
            return false;
        }

        /** get interfaces controlled by Supplicant*/
        /** List available Wifi chips */
        final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList<>();
        // Use lambda's here.
        ISupplicant.listInterfacesCallback listIfacesCb = new ISupplicant.listInterfacesCallback() {
            public void onValues(SupplicantStatus status,
                    java.util.ArrayList<ISupplicant.IfaceInfo> ifaces) {
                if (status.code != SupplicantStatusCode.SUCCESS) {
                    Log.e(TAG, "Getting Supplicant Interfaces failed: " + status.code);
                    return;
                }
                supplicantIfaces.addAll(ifaces);
            }
        };
        mHidlSupplicant.listInterfaces(listIfacesCb);
        if (supplicantIfaces.size() == 0) {
            Log.e(TAG, "Got zero HIDL supplicant ifaces.");
            return false;
        }

        /**
         * Get Supplicant HIDL iface
         */
        ISupplicant.getInterfaceCallback getIfaceCb = new ISupplicant.getInterfaceCallback() {
            public void onValues(SupplicantStatus status, ISupplicantIface iface) {
                if (status.code != SupplicantStatusCode.SUCCESS) {
                    Log.e(TAG, "Failed to get Supplicant STA Interface " + status.code);
                    return;
                }
                mHidlSupplicantIface = iface;
            }
        };
        boolean hasStaIface = false;
        for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
            if (ifaceInfo.type == IfaceType.STA) {
                hasStaIface = true;
                mHidlSupplicant.getInterface(ifaceInfo, getIfaceCb);
                break;
            }
        }
        if (!hasStaIface) {
            Log.e(TAG, "Supplicant HIDL has no STA interface, got " + supplicantIfaces.size()
                    + " ifaces.");
        }
        if (mHidlSupplicantIface == null) {
            Log.e(TAG, "Supplicant STA Interface is null");
            return false;
        } else {
            mHidlSupplicantStaIface =
                ISupplicantStaIface.asInterface(mHidlSupplicantIface.asBinder());
            Log.i(TAG, "Got supplicant STA HIDL Iface");
            return true;
        }
    }
}
