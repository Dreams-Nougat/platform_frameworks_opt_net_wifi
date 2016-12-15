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

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActionListener;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNotificationController}.
 */
public class WifiNotificationControllerTest {
    public static final String TAG = "WifiNotificationControllerTest";

    @Mock private Context mContext;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private NotificationManager mNotificationManager;
    @Mock private WifiManager mWifiManager;
    @Mock private WifiInjector mWifiInjector;
    @Mock private WifiScanner mWifiScanner;
    @Mock private NetworkScoreManager mScoreManager;
    @Mock private WifiNotificationHelper mWifiNotificationHelper;
    @Mock private Notification mNotification;
    WifiNotificationController mWifiNotificationController;

    /**
     * Internal BroadcastReceiver that WifiNotificationController uses to listen for broadcasts
     * this is initialized by calling startServiceAndLoadDriver
     */
    BroadcastReceiver mBroadcastReceiver;

    /** Initialize objects before each test run. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Needed for the NotificationEnabledSettingObserver.
        when(mContext.getContentResolver()).thenReturn(mock(ContentResolver.class));

        when(mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .thenReturn(mNotificationManager);
        when(mContext.getSystemService(Context.WIFI_SERVICE))
                .thenReturn(mWifiManager);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1)).thenReturn(1);

        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);

        when(mWifiNotificationHelper.createMainNotification(anyString()))
                .thenReturn(mNotification);
        when(mWifiNotificationHelper.createConnectingNotification(anyString()))
                .thenReturn(mNotification);
        when(mWifiNotificationHelper.createConnectedNotification(anyString()))
                .thenReturn(mNotification);

        TestLooper mock_looper = new TestLooper();
        mWifiNotificationController = new WifiNotificationController(
                mContext, mock_looper.getLooper(), mScoreManager,
                mFrameworkFacade, mWifiInjector, mWifiNotificationHelper);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        verify(mContext)
                .registerReceiver(
                        broadcastReceiverCaptor.capture(),
                        any(IntentFilter.class),
                        anyString(),
                        any(Handler.class));
        mBroadcastReceiver = broadcastReceiverCaptor.getValue();
    }

    private void setOpenAccessPoints(int numAccessPoints) {
        List<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < numAccessPoints; i++) {
            ScanResult scanResult = createScanResult("testSSID" + i, "00:00:00:00:00:00");
            scanResults.add(scanResult);
        }
        when(mWifiScanner.getSingleScanResults()).thenReturn(scanResults);
    }

    private ScanResult createScanResult(String ssid, String bssid) {
        ScanResult scanResult = new ScanResult();
        scanResult.capabilities = "[ESS]";
        scanResult.SSID = ssid;
        scanResult.BSSID = bssid;
        return scanResult;
    }

    /** Verifies that a notification is displayed (and retracted) given system events. */
    @Test
    public void verifyNotificationDisplayedWhenNetworkRecommended() throws Exception {
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext, WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);
        setOpenAccessPoints(3);

        when(mScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(new RecommendationResult(new WifiConfiguration()));

        // The notification should not be displayed after only two scan results.
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mNotificationManager, never())
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // Changing to and from "SCANNING" state should not affect the counter.
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.SCANNING);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);

        // Needed while WifiNotificationController creates its notification.
        when(mContext.getResources()).thenReturn(mock(Resources.class));

        // The third scan result notification will trigger the notification.
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mWifiNotificationHelper).createMainNotification(anyString());
        verify(mNotificationManager)
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));
        verify(mNotificationManager, never())
                .cancelAsUser(any(String.class), anyInt(), any(UserHandle.class));
    }

    /** Verifies that a notification is not displayed for bad networks. */
    @Test
    public void verifyNotificationNotDisplayedWhenNoNetworkRecommended() throws Exception {
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext, WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);
        setOpenAccessPoints(3);

        // Recommendation result with no WifiConfiguration returned.
        when(mScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(new RecommendationResult(null));

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mNotificationManager, never())
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // null Recommendation result.
        when(mScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(new RecommendationResult(null));
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mNotificationManager, never())
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));
    }

    /**
     * Verifies the notifications flow (Connect -> connecting -> connected) when user clicks
     * on Connect button.
     */
    @Test
    public void verifyNotificationsFlowOnConnectToNetwork() {
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext, WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);
        setOpenAccessPoints(3);
        when(mScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(new RecommendationResult(new WifiConfiguration()));

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mWifiNotificationHelper).createMainNotification(anyString());
        verify(mNotificationManager)
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // Send connect intent, should attempt to connect to Wi-Fi
        Intent intent = new Intent(
                WifiNotificationController.ACTION_CONNECT_TO_RECOMMENDED_NETWORK);
        mBroadcastReceiver.onReceive(mContext, intent);
        verify(mWifiManager).connect(any(WifiConfiguration.class), any(ActionListener.class));
        verify(mWifiNotificationHelper).createConnectingNotification(anyString());
        verify(mNotificationManager, times(2))
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // Verify show connected notification.
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.CONNECTED);
        verify(mWifiNotificationHelper).createConnectedNotification(anyString());
        verify(mNotificationManager, times(3))
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));
    }
}
