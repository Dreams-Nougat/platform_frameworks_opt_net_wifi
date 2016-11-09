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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.InterfaceConfiguration;
import android.net.NetworkInfo;
import android.net.wifi.IClientInterface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.net.BaseNetworkObserver;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/** Unit tests for {@link ClientModeManager}. */
@SmallTest
public class ClientModeManagerTest {

    private static final String TAG = "ClientModeManagerTest";
    private static final String TEST_INTERFACE_NAME = "testif0";

    @Mock Context mContext;
    TestLooper mLooper;
    @Mock WifiNative mWifiNative;
    @Mock ClientModeManager.Listener mListener;
    @Mock InterfaceConfiguration mInterfaceConfiguration;
    @Mock IBinder mClientInterfaceBinder;
    @Mock IClientInterface mClientInterface;
    @Mock INetworkManagementService mNmService;
    MockWifiMonitor mWifiMonitorMock;
    WifiMonitor mWifiMonitor;
    @Mock WifiCountryCode mCountryCode;
    @Mock SupplicantStateTracker mSupplicantStateTracker;
    @Mock PropertyService mPropertyService;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock NetworkInfo mNetworkInfo;
    @Mock WifiInfo mWifiInfo;

    final ArgumentCaptor<DeathRecipient> mDeathListenerCaptor =
            ArgumentCaptor.forClass(DeathRecipient.class);
    final ArgumentCaptor<BaseNetworkObserver> mNetworkObserverCaptor =
            ArgumentCaptor.forClass(BaseNetworkObserver.class);

    ClientModeManager mClientModeManager;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mWifiMonitorMock = new MockWifiMonitor();
        mWifiMonitor = mWifiMonitorMock.getWifiMonitor();

        when(mClientInterface.asBinder()).thenReturn(mClientInterfaceBinder);
        when(mClientInterface.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
    }

    private ClientModeManager createClientModeManager() throws Exception {
        when(mClientInterface.asBinder()).thenReturn(mClientInterfaceBinder);
        ClientModeManager newClientModeManager = new ClientModeManager(mContext,
                mLooper.getLooper(), mWifiNative, mListener, mClientInterface, mCountryCode,
                mNmService, mWifiMonitorMock.getWifiMonitor(), mSupplicantStateTracker,
                mPropertyService, mWifiConfigManager, mNetworkInfo, mWifiInfo);
        return newClientModeManager;
    }

    /** Verifies startClientMode . */
    @Test
    public void startClientMode() throws Exception {
        startClientModeAndVerifyEnabled();
        verifyClientModeNotDisabled();
    }

    /**
     * Variant of startClientMode test where interface up notification comes in after supplicant
     * is connected.
     *
     * This test starts ClientMode and verifies that it is enabled successfully.
     */
    @Test
    public void startClientModeInterfaceUpAfterSupplicantUp() throws Exception {
        InOrder order = inOrder(mListener, mClientInterfaceBinder, mClientInterface,
                mNmService, mContext);
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        when(mWifiNative.startHal()).thenReturn(true);
        when(mClientInterface.enableSupplicant()).thenReturn(true);

        mClientModeManager = createClientModeManager();
        mClientModeManager.start();
        mLooper.dispatchAll();

        order.verify(mClientInterfaceBinder).linkToDeath(mDeathListenerCaptor.capture(), eq(0));
        order.verify(mNmService).registerObserver(mNetworkObserverCaptor.capture());

        mWifiMonitorMock.sendMessage(TEST_INTERFACE_NAME, WifiMonitor.SUP_CONNECTION_EVENT);
        mLooper.dispatchAll();

        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, true);
        mLooper.dispatchAll();

        order.verify(mContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        List<Intent> intents = intent.getAllValues();
        assertTrue(intents.get(0).getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, false));

        order.verify(mContext).sendStickyBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(WifiManager.WIFI_STATE_ENABLED,
                     intents.get(1).getIntExtra(WifiManager.EXTRA_SCAN_AVAILABLE, -1));
    }

    /**
     * Test that WifiListener is notified of an error with an update to WIFI_STATE_UKNOWN when
     * supplicant fails to start.
     *
     * Start ClientMode except return false for enableSupplicant.
     * Expectation: WifiListener should be called with an updated state of WIFI_STATE_UNKNOWN.
     */
    @Test
    public void testStartClientModeSupplicantStartFails() throws Exception {
        mClientModeManager = createClientModeManager();
        when(mWifiNative.startHal()).thenReturn(true);
        when(mClientInterface.enableSupplicant()).thenReturn(false);
        mClientModeManager.start();
        mLooper.dispatchAll();
        verify(mWifiMonitor, never()).startMonitoring(anyString());
        verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
    }

    /**
     * Test ClientModeManager when attempting to start supplicant throws a RemoteException.
     *
     * Expectation: Exception should be caught and we should clean up from partial setup by
     * unregistering the network observer and update the WifiListener state to WIFI_STATE_UNKNOWN.
     */
    @Test
    public void testStartClientModeSupplicantStartThrowsRemoteException() throws Exception {
        mClientModeManager = createClientModeManager();
        when(mWifiNative.startHal()).thenReturn(true);
        doThrow(new RemoteException()).when(mClientInterface).enableSupplicant();
        mClientModeManager.start();
        mLooper.dispatchAll();
        verify(mWifiMonitor, never()).startMonitoring(anyString());
        verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
    }

    /**
     * Test ClientModeManager when registerObserver throws a remote exception.
     *
     * Expectation: The exception should be caught and trigger cleanup of the startup state that has
     * completed.
     */
    @Test
    public void startClientModeRegisterObserverThrowsException() throws Exception {
        mClientModeManager = createClientModeManager();
        when(mWifiNative.startHal()).thenReturn(true);
        when(mClientInterface.enableSupplicant()).thenReturn(true);
        doThrow(new RemoteException()).when(mNmService).registerObserver(any());
        mClientModeManager.start();
        mLooper.dispatchAll();
        verify(mWifiMonitor, never()).startMonitoring(anyString());
        verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
    }

    /**
     * Tests ClientMode startup if fail to link interface DeathRecipient.
     *
     * Expectation: When the DeathRecipient fails to link we should tear down the state we have
     * setup and should not notify Scanning service that scans are available.  This should update
     * the WifiListener to WIFI_STATE_UNKNOWN.
     */
    @Test
    public void startClientModeFailToLinkDeathRecipient() throws Exception {
        mClientModeManager = createClientModeManager();
        doThrow(new RemoteException()).when(mClientInterfaceBinder).linkToDeath(any(), anyInt());
        mClientModeManager.start();
        mLooper.dispatchAll();
        verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        verify(mWifiMonitor, never()).startMonitoring(anyString());
    }

    /** Tests the handling of stop command when ClientMode is not started. */
    @Test
    public void stopWhenNotStarted() throws Exception {
        mClientModeManager = createClientModeManager();
        mClientModeManager.stop();
        mLooper.dispatchAll();

        //Verify no state changes.
        verify(mListener, never()).onStateChanged(anyInt());
        verify(mContext, never()).sendStickyBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL));
    }

    /** Tests the handling of stop command when ClientModeManager is started. */
    @Test
    public void stopWhenStarted() throws Exception {
        startClientModeAndVerifyEnabled();
        // clear expectations from the verification for starting ClientMode on mContext
        reset(mContext);

        InOrder order = inOrder(mContext, mWifiNative, mWifiMonitor);
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        when(mWifiNative.stopSupplicant()).thenReturn(true);
        mClientModeManager.stop();
        mLooper.dispatchAll();

        order.verify(mContext).sendStickyBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        List<Intent> intents = intent.getAllValues();
        assertEquals(WifiManager.WIFI_STATE_DISABLED,
                     intents.get(0).getIntExtra(WifiManager.EXTRA_SCAN_AVAILABLE, -1));

        order.verify(mWifiNative).stopSupplicant();
        order.verify(mWifiNative).closeSupplicantConnection();
        order.verify(mWifiMonitor).stopAllMonitoring();
        order.verify(mContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertFalse(intents.get(1).getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, true));
    }

    /**
     * Test ClientModeManager in started state will process wificond interface death and tell
     * Scanning service that scanning is not available.
     */
    @Test
    public void handlesWificondInterfaceDeath() throws Exception {
        startClientModeAndVerifyEnabled();
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        when(mWifiNative.stopSupplicant()).thenReturn(true);
        mDeathListenerCaptor.getValue().binderDied();
        mLooper.dispatchAll();
        InOrder order = inOrder(mListener, mContext);
        order.verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        order.verify(mContext).sendStickyBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(WifiManager.WIFI_STATE_DISABLED,
                     intent.getValue().getIntExtra(WifiManager.EXTRA_SCAN_AVAILABLE,
                                                   WifiManager.WIFI_STATE_ENABLED));
    }

    /**
     * Test ClientModeManager handles the interface down notification when already started.
     *
     * Expectation: ClientModeManager should exit the started state and inform ScanningService
     * that wifi is no longer enabled.
     */
    @Test
    public void handlesInterfaceDownWhenStarted() throws Exception  {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        startClientModeAndVerifyEnabled();
        when(mWifiNative.stopSupplicant()).thenReturn(true);

        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, false);
        mLooper.dispatchAll();

        verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        InOrder order = inOrder(mListener, mContext);
        order.verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        order.verify(mContext).sendStickyBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(WifiManager.WIFI_STATE_DISABLED,
                     intent.getValue().getIntExtra(WifiManager.EXTRA_SCAN_AVAILABLE,
                                                   WifiManager.WIFI_STATE_ENABLED));
    }

    /**
     * Test ClientModeManager ignores a CMD_STOP in idle mode.
     *
     * Expectation: ClientModeManager should do make extra calls to clean up if the interface down
     * notification is delivered twice while active.
     */
    @Test
    public void handlesDuplicateInterfaceDown() throws Exception  {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        startClientModeAndVerifyEnabled();

        when(mWifiNative.stopSupplicant()).thenReturn(true);

        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, false);
        // and now flip the interface again to send a second CMD_STOP to the internal state machine.
        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, true);
        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, false);
        mLooper.dispatchAll();

        verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        InOrder order = inOrder(mListener, mContext);
        order.verify(mListener).onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        order.verify(mContext).sendStickyBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(WifiManager.WIFI_STATE_DISABLED,
                     intent.getValue().getIntExtra(WifiManager.EXTRA_SCAN_AVAILABLE,
                                                   WifiManager.WIFI_STATE_ENABLED));
    }

    /**
     * Test that a message from a previous instance of NetworkObserver does not stop ClientMode.
     *
     * This test creates a Clientmanager and verifies the mode, then holds on to the previous
     * NetworkObserver to use for an out of date callback.  The message created from this callback
     * should be dropped and the ClientModeManager should remain active.
     */
    @Test
    public void startClientModeTwiceAndTriggerOldNetworkObserverStaysActive() throws Exception {
        startClientModeAndVerifyEnabled();
        BaseNetworkObserver firstNetworkObserver = mNetworkObserverCaptor.getValue();

        when(mWifiNative.stopSupplicant()).thenReturn(true);
        mClientModeManager.stop();
        mLooper.dispatchAll();

        // now start another ClientModeManager
        startClientModeAndVerifyEnabled();

        // clear expectations for mContext since we should not be updating anything when we get the
        // old interface status change.
        reset(mContext);
        // now trigger callback on old NetworkObserver
        firstNetworkObserver.interfaceLinkStateChanged(TEST_INTERFACE_NAME, false);
        mLooper.dispatchAll();
        // since we drop the interface status change, we should not signal that wifi status changed
        verify(mContext, never()).sendStickyBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL));
    }

    /**
     * Test that starts ClientMode and verifies that it is enabled successfully even when
     * the HAL fails to start.
     *
     * This test creates a ClientManager and verifies that the broadcasts for supplicant
     * connected and scanning available are sent, even in the case that the startHal call returns
     * false.
     */
    @Test
    public void startClientModeAndVerifyEnabledWithHalFailure() throws Exception {
        InOrder order = inOrder(mListener, mClientInterfaceBinder, mClientInterface,
                mNmService, mContext);
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        when(mWifiNative.startHal()).thenReturn(false);
        when(mClientInterface.enableSupplicant()).thenReturn(true);

        mClientModeManager = createClientModeManager();
        mClientModeManager.start();
        mLooper.dispatchAll();

        order.verify(mClientInterfaceBinder).linkToDeath(mDeathListenerCaptor.capture(), eq(0));
        order.verify(mNmService).registerObserver(mNetworkObserverCaptor.capture());
        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, true);

        mWifiMonitorMock.sendMessage(TEST_INTERFACE_NAME, WifiMonitor.SUP_CONNECTION_EVENT);
        mLooper.dispatchAll();

        order.verify(mContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        List<Intent> intents = intent.getAllValues();
        assertTrue(intents.get(0).getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, false));

        order.verify(mContext).sendStickyBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(WifiManager.WIFI_STATE_ENABLED,
                     intents.get(1).getIntExtra(WifiManager.EXTRA_SCAN_AVAILABLE, -1));
    }

    /**
     * Starts ClientMode and verifies that it is enabled successfully.
     *
     * This method creates a ClientManager and verifies that the broadcasts for supplicant
     * connected and scanning available are send.
     */
    protected void startClientModeAndVerifyEnabled() throws Exception {
        InOrder order = inOrder(mListener, mClientInterfaceBinder, mClientInterface,
                mNmService, mContext);
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        when(mWifiNative.startHal()).thenReturn(true);
        when(mClientInterface.enableSupplicant()).thenReturn(true);

        mClientModeManager = createClientModeManager();
        mClientModeManager.start();
        mLooper.dispatchAll();

        order.verify(mClientInterfaceBinder).linkToDeath(mDeathListenerCaptor.capture(), eq(0));
        order.verify(mNmService).registerObserver(mNetworkObserverCaptor.capture());
        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, true);

        mWifiMonitorMock.sendMessage(TEST_INTERFACE_NAME, WifiMonitor.SUP_CONNECTION_EVENT);
        mLooper.dispatchAll();

        order.verify(mContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        List<Intent> intents = intent.getAllValues();
        assertTrue(intents.get(0).getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, false));

        order.verify(mContext).sendStickyBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(WifiManager.WIFI_STATE_ENABLED,
                     intents.get(1).getIntExtra(WifiManager.EXTRA_SCAN_AVAILABLE, -1));
    }

    /**
     * Verifies that ClientMode was not disabled (assuming it was started).
     */
    protected void verifyClientModeNotDisabled() throws Exception {
        // A single sticky broadcast should be sent when scanning service is enabled.  Any
        // additional sticky broadcasts would be disabling the scanning service.
        // Explicitly noted times(1) to make this stand out.
        verify(mContext, times(1)).sendStickyBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL));
        // A single broadcast should be sent when supplicant connects.  Any additional broadcasts
        // (not sticky) would be supplicant disconnecting.
        verify(mContext, times(1)).sendBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL));
        verify(mWifiNative, never()).closeSupplicantConnection();
        verify(mWifiMonitor, never()).stopAllMonitoring();
    }
}
