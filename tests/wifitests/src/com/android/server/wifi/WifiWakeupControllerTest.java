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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.os.test.TestLooper;
import android.provider.Settings;

import com.android.server.wifi.util.ScanResultUtil;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit tests for {@link com.android.server.wifi.WifiWakeupController}.
 */
public class WifiWakeupControllerTest {
    private static final ScanDetail OPEN_SCAN_DETAIL = buildScanDetail("ssid");
    private static final ScanDetail SAVED_SCAN_DETAIL = buildScanDetail("ssid1");
    private static final ScanDetail SAVED_SCAN_DETAIL2 = buildScanDetail("ssid2");
    private static final ScanDetail SAVED_SCAN_DETAIL_EXTERNAL = buildScanDetail("ssid3");

    private static final WifiConfiguration SAVED_WIFI_CONFIGURATION = new WifiConfiguration();
    private static final WifiConfiguration SAVED_WIFI_CONFIGURATION2 = new WifiConfiguration();
    private static final WifiConfiguration SAVED_WIFI_CONFIGURATION_EXTERNAL =
            new WifiConfiguration();

    static {
        SAVED_WIFI_CONFIGURATION.SSID =
                ScanResultUtil.createQuotedSSID(SAVED_SCAN_DETAIL.getSSID());
        SAVED_WIFI_CONFIGURATION2.SSID =
                ScanResultUtil.createQuotedSSID(SAVED_SCAN_DETAIL.getSSID());
        SAVED_WIFI_CONFIGURATION_EXTERNAL.SSID =
                ScanResultUtil.createQuotedSSID(SAVED_SCAN_DETAIL_EXTERNAL.getSSID());
        SAVED_WIFI_CONFIGURATION_EXTERNAL.useExternalScores = true;
    }

    private static ScanDetail buildScanDetail(String ssid) {
        ScanDetail scanDetail =  new ScanDetail(WifiSsid.createFromAsciiEncoded(ssid),
                "00:00:00:00:00:00", "", 0, 0, 0, 0);
        scanDetail.getScanResult().informationElements = new ScanResult.InformationElement[0];
        return scanDetail;
    }

    @Mock private Context mContext;
    @Mock private WifiStateMachine mWifiStateMachine;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private NotificationManager mNotificationManager;
    @Mock private WifiScanner mWifiScanner;
    @Mock private ContentResolver mContentResolver;
    @Mock private WifiNetworkSelector mWifiNetworkSelector;
    @Mock private WifiManager mWifiManager;
    @Captor private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;

    private WifiWakeupController mWifiWakeupController;
    private BroadcastReceiver mBroadcastReceiver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mWifiManager);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(1);

        mWifiWakeupController = new WifiWakeupController(
                mContext, new TestLooper().getLooper(), mFrameworkFacade, mWifiNetworkSelector);

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(IntentFilter.class), anyString(), any(Handler.class));
        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
    }

    @Test
    public void wifiEnabled_userNearSavedNetwork() {
        when(mWifiManager.getConfiguredNetworks())
                .thenReturn(Lists.newArrayList(SAVED_WIFI_CONFIGURATION));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(SAVED_WIFI_CONFIGURATION);

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    @Test
    public void wifiEnabled_userNearExternallyScoredSavedNetwork() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(SAVED_WIFI_CONFIGURATION_EXTERNAL);
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    @Test
    public void wifiEnabled_userDisabledWifiNearSavedNetwork_thenLeaves_thenMovesBack() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION));
        when(mWifiManager.getScanResults()).thenReturn(
                Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()),
                Lists.newArrayList(OPEN_SCAN_DETAIL.getScanResult()),
                Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(null, SAVED_WIFI_CONFIGURATION);

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    @Test
    public void wifiEnabled_userDisablesWifiNearExternallyScoredNetwork_thenNetworkIsSelected() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL_EXTERNAL.getScanResult()));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(null, SAVED_WIFI_CONFIGURATION_EXTERNAL);

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);

        verify(mWifiManager, never()).setWifiEnabled(true);

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    @Test
    public void wifiNotEnabled_wifiAlreadyEnabled() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    @Test
    public void wifiNotEnabled_userNearSavedNetworkButNotSelected() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(null);

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    @Test
    public void wifiNotEnabled_userDisablesFeature() {
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(0);
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));

        mWifiWakeupController.mContentObserver.onChange(true);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    @Test
    public void wifiNotEnabled_noWifiConfigurationIsSelected() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(null);

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    @Test
    public void wifiNotEnabled_userDisablesWifiNearSavedNetwork_thenDoesNotLeave() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults()).thenReturn(
                Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()),
                Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    @Test
    public void wifiNotEnabled_userDisablesWifiNearSavedNetwork_thenRemovesNetwork_thenStays() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL),
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(null);

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    @Test
    public void wifiNotEnabled_userDisablesWifiNear2SavedNetworks_thenLeavesRangeOfOneOfThem() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION2));
        when(mWifiManager.getScanResults()).thenReturn(
                Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult(),
                        SAVED_SCAN_DETAIL2.getScanResult()),
                Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /** Test dump() does not crash. */
    @Test
    public void testDump() {
        StringWriter stringWriter = new StringWriter();
        mWifiWakeupController.dump(
                new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);
    }
}
