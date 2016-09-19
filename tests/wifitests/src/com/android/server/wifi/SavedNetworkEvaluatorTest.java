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

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_NONE;
import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;

import com.android.server.wifi.WifiNetworkSelectorTestUtil.ScanDetailsAndWifiConfigs;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.SavedNetworkEvaluator}.
 */
@SmallTest
public class SavedNetworkEvaluatorTest {

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        mResource = getResource();
        mContext = getContext();
        mWifiConfigManager = getWifiConfigManager();
        mWifiInfo = getWifiInfo();

        mSavedNetworkEvaluator = new SavedNetworkEvaluator();
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime());

        mThresholdMinimumRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mThresholdMinimumRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdQualifiedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdSaturatedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mThresholdSaturatedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);

        mSavedNetworkEvaluator.initialize(mContext, mWifiConfigManager, mWifiInfo, mClock, null);
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private SavedNetworkEvaluator mSavedNetworkEvaluator;
    private WifiConfigManager mWifiConfigManager;
    private Context mContext;
    private Resources mResource;
    private WifiInfo mWifiInfo;
    private Clock mClock = mock(Clock.class);
    private int mThresholdMinimumRssi2G;
    private int mThresholdMinimumRssi5G;
    private int mThresholdQualifiedRssi2G;
    private int mThresholdQualifiedRssi5G;
    private int mThresholdSaturatedRssi2G;
    private int mThresholdSaturatedRssi5G;
    private static final String TAG = "Saved Network Evaluator Unit Test";

    Context getContext() {
        Context context = mock(Context.class);
        Resources resource = mock(Resources.class);

        when(context.getResources()).thenReturn(mResource);
        return context;
    }

    Resources getResource() {
        Resources resource = mock(Resources.class);

        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz))
                .thenReturn(-70);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz))
                .thenReturn(-73);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz))
                .thenReturn(-70);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz))
                .thenReturn(-73);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz))
                .thenReturn(-82);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz))
                .thenReturn(-85);
        when(resource.getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE))
                .thenReturn(4);
        when(resource.getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET))
                .thenReturn(85);
        when(resource.getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD))
                .thenReturn(24);
        when(resource.getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD))
                .thenReturn(80);
        when(resource.getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor))
                .thenReturn(16);
        when(resource.getInteger(
                R.integer.config_wifi_framework_current_network_boost))
                .thenReturn(16);

        return resource;
    }

    WifiInfo getWifiInfo() {
        WifiInfo wifiInfo = mock(WifiInfo.class);

        // simulate a disconnected state
        when(wifiInfo.is24GHz()).thenReturn(true);
        when(wifiInfo.is5GHz()).thenReturn(false);
        when(wifiInfo.getRssi()).thenReturn(-70);
        when(wifiInfo.getNetworkId()).thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        when(wifiInfo.getBSSID()).thenReturn(null);
        return wifiInfo;
    }

    WifiConfigManager getWifiConfigManager() {
        WifiConfigManager wifiConfigManager = mock(WifiConfigManager.class);
        when(wifiConfigManager.getLastSelectedNetwork())
                .thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        return wifiConfigManager;
    }


    /**
     * Between two 2G networks, choose the one with stronger RSSI value if other conditions
     * are the same and the RSSI values are not satuarted.
     */
    @Test
    public void chooseStrongerRssi2GNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 10};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Between two 5G networks, choose the one with stronger RSSI value if other conditions
     * are the same and the RSSI values are not satuarted.
     */
    @Test
    public void chooseStrongerRssi5GNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8, mThresholdQualifiedRssi5G + 10};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Choose secure network over open network if other conditions are the same.
     */
    @Test
    public void chooseSecureNetworkOverOpenNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G, mThresholdQualifiedRssi5G};
        int[] securities = {SECURITY_NONE, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Choose 5G network over 2G network if other conditions are the same.
     */
    @Test
    public void choose5GNetworkOver2GNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G, mThresholdQualifiedRssi5G};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Verify that we stick to the currently connected network if the other one is
     * just slightly better scored.
     */
    @Test
    public void stickToCurrentNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        // test2 has slightly stronger RSSI value than test1
        int[] levels = {mThresholdMinimumRssi5G + 2, mThresholdMinimumRssi5G + 4};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // Simuluate we are connected to SSID test1 already.
        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                savedConfigs[0], null, true, false, null, null);

        // Even though test2 has higher RSSI value, test1 is chosen because of the
        // currently connected network bonus.
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Verify that we stick to the currently connected BSSID if the other one is
     * just slightly better scored.
     */
    @Test
    public void stickToCurrentBSSID() {
        // Same SSID
        String[] ssids = {"\"test1\"", "\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        // test2 has slightly stronger RSSI value than test1
        int[] levels = {mThresholdMinimumRssi5G + 2, mThresholdMinimumRssi5G + 6};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // Simuluate we are connected to BSSID "6c:f3:7f:ae:8c:f3" already
        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, bssids[0], true, false, null, null);

        // Even though test2 has higher RSSI value, test1 is chosen because of the
        // currently connected BSSID bonus.
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
    }
}
