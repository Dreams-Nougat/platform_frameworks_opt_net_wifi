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
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0)).thenReturn(1);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.AIRPLANE_MODE_ON, 0)).thenReturn(0);

        mWifiWakeupController = new WifiWakeupController(
                mContext, new TestLooper().getLooper(), mFrameworkFacade, mWifiNetworkSelector);

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(IntentFilter.class), anyString(), any(Handler.class));
        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
        TestUtil.sendWifiApStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_AP_STATE_DISABLED);
    }

    /**
     * When Wi-Fi is disabled and a saved network is in the scan list, and then this network is not
     * in the scan list, and then it is, Wi-Fi should be enabled.
     */
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

    /**
     * When Wi-Fi is disabled and a saved network is in the scan list, and then another scan result
     * comes in with only a different saved network, Wi-Fi should be enabled.
     */
    @Test
    public void wifiEnabled_userDisabledWifiNearSavedNetwork_thenMovesToAnotherSavedNetwork() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION2));
        when(mWifiManager.getScanResults()).thenReturn(
                Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()),
                Lists.newArrayList(SAVED_SCAN_DETAIL2.getScanResult()));
        when(mWifiNetworkSelector.selectNetwork(anyListOf(ScanDetail.class), any(WifiInfo.class),
                eq(false), eq(true), eq(true))).thenReturn(SAVED_WIFI_CONFIGURATION2);

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);

        verify(mWifiManager, never()).setWifiEnabled(true);

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    /**
     * When a user disables Wi-Fi when there is a saved network marked with
     * {@link WifiConfiguration#useExternalScores} in the scan list, Wi-Fi should not be enabled if
     * the{@link WifiNetworkSelector} does not return a {@link WifiConfiguration}.
     *
     * When {@link WifiNetworkSelector} does return a {@link WifiConfiguration}, Wi-Fi should
     * be enabled (in absence of a scan list without the saved network) because networks marked
     * with external scores are not tracked by {@link WifiWakeupController}.
     */
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

        verify(mWifiManager, never()).setWifiEnabled(true);

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    /**
     * When Wi-Fi is enabled and a saved network is in the scan list, Wi-Fi should not be enabled.
     */
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

    /**
     * When Wi-Fi is disabled and a saved network is in the scan list, but
     * {@link WifiNetworkSelector}, does not choose this network, Wi-Fi should not be enabled.
     */
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

    /**
     * If Wi-Fi is disabled and a saved network is in the scan list, Wi-Fi should not be enabled
     * if the user has not enabled the wifi wakeup feature.
     */
    @Test
    public void wifiNotEnabled_userDisablesWifiWakeupFeature() {
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

    /**
     * If Wi-Fi is disabled and a saved network is in the scan list, Wi-Fi should not be enabled if
     * the user has not enabled the network recommendations feature.
     */
    @Test
    public void wifiNotEnabled_userDisablesNetworkRecommendations() {
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0)).thenReturn(0);
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

    /**
     * If Wi-Fi is disabled and a saved network is in the scan list, Wi-Fi should not be enabled if
     * the user is in airplane mode.
     */
    @Test
    public void wifiNotEnabled_userIsInAirplaneMode() {
        when(mFrameworkFacade.getIntegerSetting(mContext, Settings.Global.AIRPLANE_MODE_ON, 0))
                .thenReturn(1);
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

    /**
     * If Wi-Fi is disabled and a saved network is in the scan list, Wi-Fi should not be enabled if
     * the wifi AP state is not disabled.
     */
    @Test
    public void wifiNotEnabled_wifiApStateIsNotDisabled() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));

        mWifiWakeupController.mContentObserver.onChange(true);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_AP_STATE_ENABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled when a saved network is the scan list, Wi-Fi should not be enabled no
     * matter how many scans are performed that include the saved network.
     */
    @Test
    public void wifiNotEnabled_userDisablesWifiNearSavedNetwork_thenDoesNotLeave() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()));

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled when a saved network is in the scan list, and then that saved network
     * is removed, Wi-Fi is not enabled even if the user leaves range of that network and returns.
     */
    @Test
    public void wifiNotEnabled_userDisablesWifiNearSavedNetwork_thenRemovesNetwork_thenStays() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION),
                Lists.<WifiConfiguration>newArrayList());
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_DETAIL.getScanResult()))
                .thenReturn(Lists.<ScanResult>newArrayList())
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
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled when 2 saved networks are in the scan list, and then a scan result
     * comes in with only 1 saved network, Wi-Fi should not be enabled.
     */
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
