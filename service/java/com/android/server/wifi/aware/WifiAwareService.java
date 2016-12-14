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

import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.wifi.WifiInjector;

/**
 * Service implementing Wi-Fi Aware functionality. Delegates actual interface
 * implementation to WifiAwareServiceImpl.
 */
public final class WifiAwareService extends SystemService {
    private static final String TAG = "WifiAwareService";
    final WifiAwareServiceImpl mImpl;

    public WifiAwareService(Context context) {
        super(context);
        mImpl = new WifiAwareServiceImpl(context);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering " + Context.WIFI_AWARE_SERVICE);
        publishBinderService(Context.WIFI_AWARE_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            WifiInjector wifiInjector = WifiInjector.getInstance();
            if (wifiInjector == null) {
                Log.e(TAG, "onBootPhase(PHASE_SYSTEM_SERVICES_READY): NULL injector!");
                return;
            }
            mImpl.start(wifiInjector);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mImpl.startLate();
        }
    }
}

