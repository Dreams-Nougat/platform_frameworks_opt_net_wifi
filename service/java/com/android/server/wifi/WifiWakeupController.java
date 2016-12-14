/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Handles enabling Wi-Fi for the Wi-Fi Wakeup feature.
 *
 * <p>
 * This class enables Wi-Fi when the user is near a network that would would autojoined if Wi-Fi
 * were enabled. When a user disables Wi-Fi, Wi-Fi Wakeup will not enable Wi-Fi until the
 * user's context has changed. For saved networks, this context change is defined by the user
 * leaving the range of the saved SSIDs that were in range when the user disabled Wi-Fi.
 *
 * @hide
 */
class WifiWakeupController {
    private static final String TAG = "WifiWakeupController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiNetworkSelector mWifiNetworkSelector;
    private final Handler mHandler;
    @VisibleForTesting final ContentObserver mContentObserver;

    private final Set<String> mSavedSsidsInLastScan = new ArraySet<>();
    private final Set<String> mSavedSsids = new ArraySet<>();
    private final Set<String> mSavedSsidsOnDisable = new ArraySet<>();
    private int mWifiState;
    private int mWifiApState;
    private boolean mWifiWakeupEnabled;
    private boolean mNetworkRecommendationsEnabled;
    private boolean mAirplaneModeEnabled;

    WifiWakeupController(Context context, Looper looper, FrameworkFacade frameworkFacade,
            WifiNetworkSelector wifiNetworkSelector) {
        mContext = context;
        mFrameworkFacade = frameworkFacade;
        mWifiNetworkSelector = wifiNetworkSelector;
        mHandler = new Handler(looper);
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        // TODO(b/33695273): conditionally register this receiver based on setting
        mContext.registerReceiver(mBroadcastReceiver, filter, null, mHandler);
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mWifiWakeupEnabled = mFrameworkFacade.getIntegerSetting(mContext,
                        Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1;
                mNetworkRecommendationsEnabled = mFrameworkFacade.getIntegerSetting(mContext,
                        Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0) == 1;
                mAirplaneModeEnabled = mFrameworkFacade.getIntegerSetting(mContext,
                        Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            }
        };
        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_WAKEUP_ENABLED), true, mContentObserver);
        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED), true, mContentObserver);
        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AIRPLANE_MODE_ON), true, mContentObserver);
        mContentObserver.onChange(true);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mWifiWakeupEnabled || !mNetworkRecommendationsEnabled) {
                return;
            }

            if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                handleWifiApStateChanged(intent);
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                handleWifiStateChanged(intent);
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                handleScanResultsAvailable();
            } else if (WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION.equals(intent.getAction())) {
                handleConfiguredNetworksChanged();
            }
        }
    };

    private void handleWifiApStateChanged(Intent intent) {
        mWifiApState = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_STATE,
                WifiManager.WIFI_AP_STATE_DISABLED);
    }

    private void handleConfiguredNetworksChanged() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        List<WifiConfiguration> wifiConfigurations = wifiManager.getConfiguredNetworks();
        if (wifiConfigurations == null) {
            return;
        }

        mSavedSsids.clear();
        for (int i = 0; i < wifiConfigurations.size(); i++) {
            WifiConfiguration wifiConfiguration = wifiConfigurations.get(i);
            if (wifiConfiguration.useExternalScores) {
                // Deciding whether to enable wifi for externally scored networks
                // will be deferred to the RecommendedNetworkEvaluator.
                continue;
            }
            String ssid = wifiConfiguration.SSID;
            if (TextUtils.isEmpty(ssid)) {
                continue;
            }
            mSavedSsids.add(ssid);
        }
        mSavedSsidsInLastScan.retainAll(mSavedSsids);
        mSavedSsidsOnDisable.retainAll(mSavedSsids);
    }

    private void handleWifiStateChanged(Intent intent) {
        mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
        switch (mWifiState) {
            case WifiManager.WIFI_STATE_ENABLED:
                mSavedSsidsOnDisable.clear();
                break;
            case WifiManager.WIFI_STATE_DISABLED:
                mSavedSsidsOnDisable.addAll(mSavedSsidsInLastScan);
                break;
        }
    }

    private void handleScanResultsAvailable() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        List<ScanResult> scanResults = wifiManager.getScanResults();
        mSavedSsidsInLastScan.clear();
        for (int i = 0; i < scanResults.size(); i++) {
            String ssid = ScanResultUtil.createQuotedSSID(scanResults.get(i).SSID);
            if (mSavedSsids.contains(ssid)) {
                mSavedSsidsInLastScan.add(ssid);
            }
        }

        if (mAirplaneModeEnabled
                || mWifiState != WifiManager.WIFI_STATE_DISABLED
                || mWifiApState != WifiManager.WIFI_AP_STATE_DISABLED) {
            return;
        }

        mSavedSsidsOnDisable.retainAll(mSavedSsidsInLastScan);
        if (!mSavedSsidsOnDisable.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "latest scan result contains ssids from the disabled set: "
                        + mSavedSsidsOnDisable);
            }
            return;
        }

        mSavedSsidsOnDisable.clear();
        List<ScanDetail> scanDetails = new ArrayList<>(scanResults.size());
        for (int i = 0; i < scanResults.size(); i++) {
            scanDetails.add(ScanResultUtil.toScanDetail(scanResults.get(i)));
        }

        // TODO(b/33662479): refactor WifiInfo to be created in WifiInjector instead of WSM
        WifiInfo wifiInfo = new WifiInfo();
        WifiConfiguration selectedNetwork = mWifiNetworkSelector.selectNetwork(scanDetails,
                wifiInfo, false /* connected */, true /* disconnected */,
                true /* untrustedNetworkAllowed */);
        if (selectedNetwork != null) {
            // TODO(b/33677088): show notification for wifi enablement
            if (DEBUG) {
                Log.d(TAG, "Enabling wifi for ssid: " + selectedNetwork.SSID);
            }
            wifiManager.setWifiEnabled(true /* enabled */);
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mWifiWakeupEnabled: " + mWifiWakeupEnabled);
        pw.println("mSavedSsids: " + mSavedSsids);
        pw.println("mSavedSsidsInLastScan: " + mSavedSsidsInLastScan);
        pw.println("mSavedSsidsOnDisable: " + mSavedSsidsOnDisable);
    }
}
