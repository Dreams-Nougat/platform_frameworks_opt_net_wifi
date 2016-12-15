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

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiChipEventCallback;
import android.hardware.wifi.V1_0.IWifiEventCallback;
import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.WifiDebugRingBufferStatus;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.util.Log;
import android.util.MutableBoolean;

import java.util.ArrayList;

/**
 * Manages the interface to Wi-Fi Aware HIDL (HAL).
 */
public class WifiAwareNativeManager {
    private static final String TAG = "WifiAwareNativeManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final String HAL_HIDL_SERVICE_NAME = "wifi";

    private final Object mLock = new Object();

    private WifiAwareStateManager mWifiAwareStateManager;

    private IWifi mWifi;
    private boolean mWifiIsStarted = false;

    private IWifiChip mWifiChip;
    private IWifiNanIface mWifiNanIface;
    private String mWifiNanIfaceName;

    private IWifiEventCallback mWifiEventCallback = new WifiEventCallback();
    private IWifiChipEventCallback mWifiChipEventCallback = new WifiChipEventCallback();

    public WifiAwareNativeManager() {
        // empty
    }

    public void setStateManager(WifiAwareStateManager awareStateManager) {
        mWifiAwareStateManager = awareStateManager;
    }

    /**
     * Wrapper function to access the HIDL Wi-Fi service. Created to be mockable in unit-tests.
     */
    public IWifi getServiceMockable() {
        return IWifi.getService(HAL_HIDL_SERVICE_NAME);
    }

    /**
     * Initializes all the components of HIDL needed for NAN operation.
     *
     * Returns: true if NAN ready to work, false otherwise.
     */
    public boolean start() {
        if (VDBG) Log.v(TAG, "start");
        synchronized (mLock) {
            if (mWifi == null) {
                initWifiHidl();
                if (VDBG) Log.v(TAG, "start: mWiFi (after init) = " + mWifi);
                if (mWifi == null) {
                    return false;
                }
            }
            if (VDBG) Log.v(TAG, "start: mWifiIsStarted = " + mWifiIsStarted);
            if (!mWifiIsStarted) {
                return false;
            }

            if (mWifiChip == null) {
                initWifiChipHidl();
                if (VDBG) Log.v(TAG, "start: mWifiChip (after init) = " + mWifiChip);
                if (mWifiChip == null) {
                    return false;
                }
            }

            if (mWifiNanIface == null) {
                initWifiNanIface();
                if (VDBG) Log.v(TAG, "start: mWifiNanIface (after init) = " + mWifiNanIface);
                if (mWifiNanIface == null) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Clean-up after service died.
     */
    private void serviceDiedHandler() {
        if (VDBG) Log.v(TAG, "serviceDiedHandler");
        synchronized (mLock) {
            mWifi = null;
            mWifiIsStarted = false;
            invalidateChip();
        }
        start();
    }

    /**
     * Clean-up after chip configuration becomes invalid.
     */
    private void invalidateChip() {
        if (VDBG) Log.v(TAG, "invalidateChip");
        synchronized (mLock) {
            mWifiChip = null;
            invalidateNanIface();
        }
    }

    /**
     * Clean-up after NAN interface becomes invalid.
     */
    private void invalidateNanIface() {
        if (VDBG) Log.v(TAG, "invalidateNanIface");
        synchronized (mLock) {
            if (mWifiNanIface != null) {
                mWifiAwareStateManager.disableUsage();
            }

            mWifiNanIface = null;
            mWifiNanIfaceName = null;
        }
    }

    /**
     * Initialize IWifi HIDL interface and register callbacks. Only needed once - most
     * likely valid when started.
     */
    private void initWifiHidl() {
        if (VDBG) Log.v(TAG, "initWifiHidl");
        synchronized (mLock) {
            try {
                mWifi = getServiceMockable();
                if (mWifi == null) {
                    Log.e(TAG, "initWifiHidl: service not ready yet - null");
                    return;
                }

                WifiStatus status = mWifi.registerEventCallback(mWifiEventCallback);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "initWifiHidl: registerEventCallback failed: " + status.description);
                    mWifi = null; // try again later?
                    return;
                }

                mWifiIsStarted = mWifi.isStarted();
            } catch (RuntimeException e) {
                Log.e(TAG, "initWifiHidl: exception: " + e);
                serviceDiedHandler();
            }
        }
    }

    /**
     * Loops through all chips on the device and returns the chip ID which supports NAN.
     *
     * Assumes that:
     * 1. Wifi is started
     * 2. There is a NAN supporting chip (otherwise whole module should not run)
     *
     * Will throw an IllegalStateException if assumptions are not met.
     *
     * Note: right now will simply return the first chip ID.
     */
    private int getNanSupportingChipId() throws IllegalStateException {
        if (VDBG) Log.v(TAG, "getNanSupportingChipId");

        MutableBoolean statusOk = new MutableBoolean(false);

        synchronized (mLock) {
            if (!mWifiIsStarted) {
                Log.wtf(TAG, "getNanSupportingChipId: called with Wifi not started!");
                throw new IllegalStateException("Wifi is not started");
            }

            // get all chip IDs
            Mutable<ArrayList<Integer>> chipIdsResp = new Mutable<>();
            mWifi.getChipIds((WifiStatus status, ArrayList<Integer> chipIds) -> {
                statusOk.value = status.code == WifiStatusCode.SUCCESS;
                if (statusOk.value) {
                    chipIdsResp.value = chipIds;
                } else {
                    Log.e(TAG, "getNanSupportingChipId: getChipIds error: " + status.description);
                }
            });
            if (!statusOk.value) {
                throw new IllegalStateException("Cannot obtain chip IDs");
            }

            if (VDBG) Log.v(TAG, "getNanSupportingChipId: chipIds=" + chipIdsResp.value);

            // grab the first chip
            if (chipIdsResp.value.size() == 0) {
                Log.e(TAG, "startChip: should have at least 1 chip!");
                throw new IllegalStateException("No chip IDs!?");
            }

            return chipIdsResp.value.get(0);
        }
    }

    /**
     * Initialize IWifiChip HIDL interface and register callbacks. Needed whenever chip
     * becomes invalidated.
     */
    private void initWifiChipHidl() {
        if (VDBG) Log.v(TAG, "initWifiChipHidl");
        synchronized (mLock) {
            if (!mWifiIsStarted) {
                Log.e(TAG, "initWifiChipHidl: called before Wifi is 'started'");
                return;
            }

            int chipId;
            try {
                chipId = getNanSupportingChipId();
            } catch (IllegalStateException e) {
                Log.e(TAG, "initWifiChipHidl: getNanSupportingChipId exception: " + e);
                return;
            }

            MutableBoolean statusOk = new MutableBoolean(false);
            try {
                mWifi.getChip(chipId, (WifiStatus status, IWifiChip chip) -> {
                    statusOk.value = status.code == WifiStatusCode.SUCCESS;
                    if (statusOk.value) {
                        mWifiChip = chip;
                    } else {
                        Log.e(TAG, "initWifiChipHidl: getChip error: " + status.description);
                    }
                });
                if (!statusOk.value) {
                    return;
                }

                WifiStatus status = mWifiChip.registerEventCallback(mWifiChipEventCallback);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "initWifiChipHidl: registerEventCallback error: "
                            + status.description);
                    mWifiChip = null;
                    return;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "initWifiChipHidl: exception: " + e);
                serviceDiedHandler();
            }
        }
    }

    /**
     * Initialize the IWifiNanIface interface.
     */
    private void initWifiNanIface() {
        if (VDBG) Log.v(TAG, "initWifiNanIface");
        synchronized (mLock) {
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mWifiChip.createNanIface((WifiStatus status, IWifiNanIface iface) -> {
                    statusOk.value = status.code == WifiStatusCode.SUCCESS;
                    if (statusOk.value) {
                        mWifiNanIface = iface;
                    } else {
                        Log.e(TAG, "initWifiNanIface: createNanIface error: " + status.description);
                    }
                });
                if (!statusOk.value) {
                    return;
                }

                mWifiNanIface.getName((WifiStatus status, String name) -> {
                    statusOk.value = status.code == WifiStatusCode.SUCCESS;
                    if (statusOk.value) {
                        mWifiNanIfaceName = name;
                    } else {
                        Log.e(TAG, "initWifiNanIface: getName error: " + status.description);
                        mWifiNanIface = null;
                    }
                });
                if (!statusOk.value) {
                    return;
                }

                mWifiAwareStateManager.enableUsage();
            } catch (RuntimeException e) {
                Log.e(TAG, "initWifiNanIface: exception: " + e);
                serviceDiedHandler();
            }
        }
    }

    private class WifiEventCallback extends IWifiEventCallback.Stub {
        @Override
        public void onStart() {
            if (VDBG) Log.v(TAG, "WifiEventCallback.onStart");
            synchronized (mLock) {
                mWifiIsStarted = true;
                start();
            }
        }

        @Override
        public void onStop() {
            if (VDBG) Log.v(TAG, "WifiEventCallback.onStop");
            invalidateChip();
        }

        @Override
        public void onFailure(WifiStatus status) {
            if (VDBG) {
                Log.v(TAG, "WifiEventCallback.onFailure: code=" + status.code + ", description="
                        + status.description);
            }
            invalidateChip();
        }
    }

    private class WifiChipEventCallback extends IWifiChipEventCallback.Stub {
        @Override
        public void onChipReconfigured(int modeId) {
            if (VDBG) Log.v(TAG, "WifiChipEventCallback.onChipReconfigured: modeId=" + modeId);
            invalidateNanIface();
            start();
        }

        @Override
        public void onChipReconfigureFailure(WifiStatus status) {
            if (VDBG) {
                Log.v(TAG, "WifiChipEventCallback.onChipReconfigureFailure: code=" + status.code
                        + ", description=" + status.description);
            }
            invalidateNanIface();

            /* someone else manages the chip configuration: they'll try again and then
               we may get a success chip reconfiguration and try starting NAN again */
        }

        @Override
        public void onIfaceAdded(int type, String name) {
            /* empty: we're going to be the ones creating a NAN iface and will get it directly -
               not through the callback */
        }

        @Override
        public void onIfaceRemoved(int type, String name) {
            if (VDBG) {
                Log.v(TAG, "WifiChipEventCallback.onIfaceRemoved: type=" + type + ", name=" + name);
            }
            synchronized (mLock) {
                if (type == IfaceType.NAN && mWifiNanIface != null && mWifiNanIfaceName.equals(
                        name)) {
                    invalidateNanIface();
                } else if (mWifiNanIface == null) {
                    start(); // try getting NAN interface again
                }
            }
        }

        @Override
        public void onDebugRingBufferDataAvailable(WifiDebugRingBufferStatus status,
                ArrayList<Byte> data) {
            // empty
        }

        @Override
        public void onDebugErrorAlert(int errorCode, ArrayList<Byte> debugData) {
            // empty
        }
    }

    private static class Mutable<E> {
        public E value;

        Mutable() {
            value = null;
        }

        Mutable(E value) {
            this.value = value;
        }
    }
}
