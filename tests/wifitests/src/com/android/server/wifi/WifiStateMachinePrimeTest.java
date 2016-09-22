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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.net.wifi.IWificond;
import android.net.wifi.IApInterface;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Message;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link com.android.server.wifi.WifiStateMachinePrime}.
 */
@SmallTest
public class WifiStateMachinePrimeTest {
    public static final String TAG = "WifiStateMachinePrimeTest";

    @Mock WifiInjector mWifiInjector;
    TestLooper mLooper;
    @Mock IWificond mWificond;
    @Mock IApInterface mApInterface;
    @Mock SoftApManager mSoftApManager;
    SoftApManager.Listener mSoftApListener;
    @Mock WifiConfiguration mApConfig;
    WifiStateMachinePrime mWifiStateMachinePrime;

    public WifiStateMachinePrimeTest() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Setting up ...");

        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();

        mWifiInjector = mock(WifiInjector.class);
        mWifiStateMachinePrime = createWifiStateMachinePrime();
    }

    private WifiStateMachinePrime createWifiStateMachinePrime() {
        when(mWifiInjector.makeWificond()).thenReturn(null);
        return new WifiStateMachinePrime(mWifiInjector, mLooper.getLooper());
    }

    @After
    public void cleanUp() throws Exception {
        mWifiStateMachinePrime = null;
    }

    private void enterSoftApActiveMode() throws Exception {
        String fromState = mWifiStateMachinePrime.getCurrentMode();
        when(mWifiInjector.makeWificond()).thenReturn(mWificond);
        when(mWificond.createApInterface()).thenReturn(mApInterface);
        doAnswer(new Answer<Object> () {
            public SoftApManager answer(InvocationOnMock invocation){
                mSoftApListener = (SoftApManager.Listener) invocation.getArguments()[0];
                return mSoftApManager;
            }

        }).when(mWifiInjector).makeSoftApManager(any(SoftApManager.Listener.class),
                                                 any(IApInterface.class),
                                                 any(WifiConfiguration.class));
        mWifiStateMachinePrime.enterSoftAPMode();
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
        Log.e("WifiStateMachinePrimeTest", "check fromState: " + fromState);
        if (!fromState.equals("WifiDisabled")) {
            verify(mWificond).tearDownInterfaces();
        }
        mLooper.dispatchNext();
        assertEquals("SoftAPModeActiveState", mWifiStateMachinePrime.getCurrentMode());
        verify(mSoftApManager).start();
    }

    @Test
    public void testWificondExistsOnStartup() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(mWificond);
        WifiStateMachinePrime testWifiStateMachinePrime
                = new WifiStateMachinePrime(mWifiInjector, mLooper.getLooper());
        verify(mWificond).tearDownInterfaces();
    }

    @Test
    public void testEnterSoftApModeFromDisabled() throws Exception {
        enterSoftApActiveMode();
    }

    @Test
    public void testEnterSoftApModeFromDifferentState() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(mWificond);
        mWifiStateMachinePrime.enterClientMode();
        mLooper.dispatchNext();
        assertEquals("ClientModeState", mWifiStateMachinePrime.getCurrentMode());
        enterSoftApActiveMode();
    }

    @Test
    public void testDisableWifiFromSoftApModeActiveState() throws Exception {
        enterSoftApActiveMode();

        mWifiStateMachinePrime.disableWifi();
        mLooper.dispatchNext();
        verify(mSoftApManager).stop();
        verify(mWificond).tearDownInterfaces();
        assertEquals("WifiDisabled", mWifiStateMachinePrime.getCurrentMode());
    }

    @Test
    public void testDisableWifiFromSoftApModeState() throws Exception {
        // Use a failure getting wificond to stay in the SoftAPModeState
        when(mWifiInjector.makeWificond()).thenReturn(null);
        mWifiStateMachinePrime.enterSoftAPMode();
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());

        mWifiStateMachinePrime.disableWifi();
        mLooper.dispatchNext();
        // mWificond will be null due to this test, no call to tearDownInterfaces here.
        assertEquals("WifiDisabled", mWifiStateMachinePrime.getCurrentMode());
    }

    @Test
    public void testSwitchModeWhenSoftApActiveMode() throws Exception {
        enterSoftApActiveMode();

        mWifiStateMachinePrime.enterClientMode();
        mLooper.dispatchNext();
        verify(mSoftApManager).stop();
        assertEquals("ClientModeState", mWifiStateMachinePrime.getCurrentMode());
    }

    @Test
    public void testWificondNullWhenSwitchingToApMode() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(null);
        mWifiStateMachinePrime.enterSoftAPMode();
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
    }

    @Test
    public void testAPInterfaceFailedWhenSwitchingToApMode() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(mWificond);
        when(mWificond.createApInterface()).thenReturn(null);
        mWifiStateMachinePrime.enterSoftAPMode();
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
    }

    @Test
    public void testEnterSoftApModeActiveWhenAlreadyInSoftApMode() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(mWificond);
        when(mWificond.createApInterface()).thenReturn(null);
        mWifiStateMachinePrime.enterSoftAPMode();
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());

        enterSoftApActiveMode();
        verify(mWificond).tearDownInterfaces();
    }

    @Test
    public void testSoftApFailureWhenActive() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApListener.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
        mLooper.dispatchNext();
        assertEquals("SoftAPModeState", mWifiStateMachinePrime.getCurrentMode());
    }

    @Test
    public void disableWifiWhenAlreadyOff() throws Exception {
        verifyNoMoreInteractions(mWificond);
        mWifiStateMachinePrime.disableWifi();
    }
}
