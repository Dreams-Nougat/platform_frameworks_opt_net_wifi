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

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/* Takes care of handling the "open wi-fi network available" notification @hide */
class WifiNotificationController {
    /**
     * The icon to show in the 'available networks' notification. This will also
     * be the ID of the Notification given to the NotificationManager.
     */
    private static final int ICON_NETWORKS_AVAILABLE =
            com.android.internal.R.drawable.stat_notify_wifi_in_range;
    /**
     * When a notification is shown, we wait this amount before possibly showing it again.
     */
    private final long NOTIFICATION_REPEAT_DELAY_MS;
    /**
     * Whether the user has set the setting to show the 'available networks' notification.
     */
    private boolean mNotificationEnabled;
    /**
     * Observes the user setting to keep {@link #mNotificationEnabled} in sync.
     */
    private NotificationEnabledSettingObserver mNotificationEnabledSettingObserver;
    /**
     * The {@link System#currentTimeMillis()} must be at least this value for us
     * to show the notification again.
     */
    private long mNotificationRepeatTime;
    /**
     * Whether the notification is being shown.
     */
    private boolean mNotificationShown;
    /**
     * The number of continuous scans that must occur before consider the
     * supplicant in a scanning state. This allows supplicant to associate with
     * remembered networks that are in the scan results.
     */
    private static final int NUM_SCANS_BEFORE_ACTUALLY_SCANNING = 3;
    /**
     * The number of scans since the last network state change. When this
     * exceeds {@link #NUM_SCANS_BEFORE_ACTUALLY_SCANNING}, we consider the
     * supplicant to actually be scanning. When the network state changes to
     * something other than scanning, we reset this to 0.
     */
    private int mNumScansSinceNetworkStateChange;
    /**
     * Time in milliseconds to display the Connecting notification.
     */
    private static final int TIME_TO_SHOW_CONNECTING_MILLIS = 5000;
    /**
     * Time in milliseconds to display the Connected notification.
     */
    private static final int TIME_TO_SHOW_CONNECTED_MILLIS = 3000;
    /**
     * Try to connect to provided WifiConfiguration since user wants to
     * connect to the recommended open access point.
     */
    static final String ACTION_CONNECT_TO_RECOMMENDED_NETWORK =
            "com.android.server.wifi.CONNECT_TO_RECOMMENDED_NETWORK";
    /**
     * Handles behavior when notification is deleted.
     */
    static final String ACTION_NOTIFICATION_DELETED =
            "com.android.server.wifi.NOTIFICATION_DELETED";
    /**
     * Network recommended by {@link NetworkScoreManager#requestRecommendation}.
     */
    private WifiConfiguration mRecommendedNetwork;

    /**
     * Runnable to reset and clear active notifications.
     */
    private final Runnable mResetNotificationRunnable = () -> {
        resetNotification();
    };

    private final Context mContext;
    private final NetworkScoreManager mScoreManager;
    private final Handler mHandler;
    private final WifiNotificationHelper mWifiNotificationHelper;
    private NetworkInfo mNetworkInfo;
    private NetworkInfo.DetailedState mDetailedState;
    private volatile int mWifiState;
    private FrameworkFacade mFrameworkFacade;
    private WifiInjector mWifiInjector;
    private WifiScanner mWifiScanner;

    WifiNotificationController(Context context, Looper looper,
            NetworkScoreManager networkScoreManager, FrameworkFacade framework,
            WifiInjector wifiInjector) {
        this(context, looper, networkScoreManager, framework, wifiInjector,
                new WifiNotificationHelper(context));
    }

    @VisibleForTesting
    WifiNotificationController(Context context, Looper looper,
            NetworkScoreManager networkScoreManager, FrameworkFacade framework,
            WifiInjector wifiInjector, WifiNotificationHelper helper) {
        mContext = context;
        mFrameworkFacade = framework;
        mWifiInjector = wifiInjector;
        mScoreManager = networkScoreManager;
        mHandler = new Handler(looper);
        mWifiNotificationHelper = helper;
        mWifiState = WifiManager.WIFI_STATE_UNKNOWN;
        mDetailedState = NetworkInfo.DetailedState.IDLE;

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(ACTION_CONNECT_TO_RECOMMENDED_NETWORK);
        filter.addAction(ACTION_NOTIFICATION_DELETED);

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    resetNotification();
                } else if (intent.getAction().equals(
                        WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                            WifiManager.EXTRA_NETWORK_INFO);
                    NetworkInfo.DetailedState detailedState =
                            mNetworkInfo.getDetailedState();
                    if (detailedState != NetworkInfo.DetailedState.SCANNING
                            && detailedState != mDetailedState) {
                        mDetailedState = detailedState;
                        switch(mDetailedState) {
                            case CONNECTED:
                                updateNotificationOnConnect();
                                break;
                            case DISCONNECTED:
                            case CAPTIVE_PORTAL_CHECK:
                                resetNotification();
                                break;

                            // TODO: figure out if these are failure cases when connecting
                            case IDLE:
                            case SCANNING:
                            case CONNECTING:
                            case AUTHENTICATING:
                            case OBTAINING_IPADDR:
                            case SUSPENDED:
                            case FAILED:
                            case BLOCKED:
                            case VERIFYING_POOR_LINK:
                                break;
                        }
                    }
                } else if (intent.getAction().equals(
                        WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                    if (mWifiScanner == null) {
                        mWifiScanner = mWifiInjector.getWifiScanner();
                    }
                    checkAndSetNotification(mNetworkInfo,
                            mWifiScanner.getSingleScanResults());
                } else if (intent.getAction().equals(ACTION_CONNECT_TO_RECOMMENDED_NETWORK)) {
                    connectToRecommendedNetwork();
                } else if (intent.getAction().equals(ACTION_NOTIFICATION_DELETED)) {
                    handleNotificationDeleted();
                }
            }
        }, filter, null /* broadcastPermission */, mHandler);

        // Setting is in seconds
        NOTIFICATION_REPEAT_DELAY_MS = mFrameworkFacade.getIntegerSetting(context,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY, 900) * 1000l;
        mNotificationEnabledSettingObserver = new NotificationEnabledSettingObserver(mHandler);
        mNotificationEnabledSettingObserver.register();
    }

    private void checkAndSetNotification(NetworkInfo networkInfo,
            List<ScanResult> scanResults) {

        // TODO: unregister broadcast so we do not have to check here
        // If we shouldn't place a notification on available networks, then
        // don't bother doing any of the following
        if (!mNotificationEnabled) return;
        if (mWifiState != WifiManager.WIFI_STATE_ENABLED) return;

        NetworkInfo.State state = NetworkInfo.State.DISCONNECTED;
        if (networkInfo != null)
            state = networkInfo.getState();

        if (state == NetworkInfo.State.DISCONNECTED
                || state == NetworkInfo.State.UNKNOWN) {
            RecommendationResult result = getOpenNetworkRecommendation(scanResults);
            if (result != null
                    && result.getWifiConfiguration() != null) {
                mRecommendedNetwork = result.getWifiConfiguration();
                if (++mNumScansSinceNetworkStateChange >= NUM_SCANS_BEFORE_ACTUALLY_SCANNING) {
                    /*
                     * We have scanned continuously at least
                     * NUM_SCANS_BEFORE_NOTIFICATION times. The user
                     * probably does not have a remembered network in range,
                     * since otherwise supplicant would have tried to
                     * associate and thus resetting this counter.
                     */
                    displayNotification();
                }
                return;
            }
        }

        // No open networks in range, remove the notification
        removeNotification();
    }

    /**
     * Uses the score cache to see if open access points with a good score exist.
     * @return returns the best qualified open networks, if any.
     */
    @Nullable
    private RecommendationResult getOpenNetworkRecommendation(List<ScanResult> scanResults) {
        if (scanResults == null || scanResults.isEmpty()) {
            return null;
        }
        ArrayList<ScanResult> openNetworks = new ArrayList<>();
        for (ScanResult scanResult : scanResults) {
            //A capability of [ESS] represents an open access point
            //that is available for an STA to connect
            if ("[ESS]".equals(scanResult.capabilities)) {
                openNetworks.add(scanResult);
            }
        }
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(openNetworks.toArray(new ScanResult[openNetworks.size()]))
                .build();
        return mScoreManager.requestRecommendation(request);
    }

    /**
     * Display's a notification that there are open Wi-Fi networks.
     */
    private void displayNotification() {

        // Since we use auto cancel on the notification, when the
        // mNetworksAvailableNotificationShown is true, the notification may
        // have actually been canceled.  However, when it is false we know
        // for sure that it is not being shown (it will not be shown any other
        // place than here)

        // Not enough time has passed to show the notification again
        if (System.currentTimeMillis() < mNotificationRepeatTime) {
            return;
        }
        Notification notification =
                mWifiNotificationHelper.createMainNotification(mRecommendedNetwork.SSID);
        mNotificationRepeatTime = System.currentTimeMillis() + NOTIFICATION_REPEAT_DELAY_MS;
        notify(notification);
        mNotificationShown = true;
    }


    /**
     * Attempts to connect to recommended network and updates the notification to
     * show Connecting state.
     * TODO(33668991): work with UX to polish notification UI and figure out failure states
     */
    private void connectToRecommendedNetwork() {
        if (mRecommendedNetwork == null) {
            return;
        }
        // Attempts to connect to recommended network.
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wifiManager.connect(mRecommendedNetwork, null /* actionListener */);

        // Update notification to connecting status.
        Notification notification =
                mWifiNotificationHelper.createConnectingNotification(mRecommendedNetwork.SSID);
        notify(notification);
        mHandler.postDelayed(mResetNotificationRunnable, TIME_TO_SHOW_CONNECTING_MILLIS);
    }

    /**
     * When detailed state changes to CONNECTED, show connected notification or
     * reset notification.
     * TODO: determine failure state where main notification shows but connected.
     */
    private void updateNotificationOnConnect() {
        // if notification not showing, reset notification immediately
        if (!mNotificationShown) {
            resetNotification();
            return;
        }
        Notification notification =
                mWifiNotificationHelper.createConnectedNotification(mRecommendedNetwork.SSID);
        notify(notification);
        // Remove any previous reset notification callbacks.
        mHandler.removeCallbacks(mResetNotificationRunnable);
        mHandler.postDelayed(mResetNotificationRunnable, TIME_TO_SHOW_CONNECTED_MILLIS);
    }

    /**
     * Handles behavior when notification is dismissed.
     */
    private void handleNotificationDeleted() {
        mNotificationShown = false;
        mRecommendedNetwork = null;
    }

    private void notify(Notification notification) {
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notifyAsUser(null /* tag */, ICON_NETWORKS_AVAILABLE,
                notification, UserHandle.ALL);
    }

    /**
     * Clears variables related to tracking whether a notification has been
     * shown recently and clears the current notification.
     */
    private void resetNotification() {
        mNotificationRepeatTime = 0;
        mNumScansSinceNetworkStateChange = 0;
        if (mNotificationShown) {
            removeNotification();
        }
    }

    private void removeNotification() {
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAsUser(null /* tag */, ICON_NETWORKS_AVAILABLE, UserHandle.ALL);
        mNotificationShown = false;
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mNotificationEnabled " + mNotificationEnabled);
        pw.println("mNotificationRepeatTime " + mNotificationRepeatTime);
        pw.println("mNotificationShown " + mNotificationShown);
        pw.println("mNumScansSinceNetworkStateChange " + mNumScansSinceNetworkStateChange);
    }

    private class NotificationEnabledSettingObserver extends ContentObserver {
        public NotificationEnabledSettingObserver(Handler handler) {
            super(handler);
        }

        public void register() {
            ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON), true, this);
            mNotificationEnabled = getValue();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            mNotificationEnabled = getValue();
            resetNotification();
        }

        private boolean getValue() {
            return mFrameworkFacade.getIntegerSetting(mContext,
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1) == 1;
        }
    }
}
