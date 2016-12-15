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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiChipEventCallback;
import android.hardware.wifi.V1_0.IWifiEventCallback;
import android.hardware.wifi.V1_0.IWifiIface;
import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.IfaceType;

import com.android.server.wifi.HidlMockUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Unit test harness for WifiAwareNativeManager
 */
public class WifiAwareNativeManagerTest {
    private WifiAwareNativeManager mDut;

    // mocks
    @Mock private WifiAwareStateManager mWifiAwareStateManagerMock;
    @Mock private IWifi mWifiMock;
    @Mock private IWifiChip mWifiChipMock;
    @Mock private IWifiNanIface mWifiNanIfaceMock;

    // capture objects
    private ArgumentCaptor<IWifiEventCallback> mWifiEventCallbackCaptor = ArgumentCaptor.forClass(
            IWifiEventCallback.class);
    private ArgumentCaptor<IWifiChipEventCallback> mWifiChipEventCallbackCaptor =
            ArgumentCaptor.forClass(IWifiChipEventCallback.class);

    private InOrder mInOrder;

    // some constants
    private static final String NAN_IFACE_NAME = "aware0";
    private static final int CHIP_ID = 100;
    private static final ArrayList<Integer> CHIP_IDS = new ArrayList<>(Arrays.asList(CHIP_ID, 20));

    private class WifiAwareNativeManagerSpy extends WifiAwareNativeManager {
        @Override
        public IWifi getServiceMockable() {
            return mWifiMock;
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mDut = new WifiAwareNativeManagerSpy();
        mDut.setStateManager(mWifiAwareStateManagerMock);

        when(mWifiMock.registerEventCallback(any(IWifiEventCallback.class))).thenReturn(
                HidlMockUtil.statusOk);
        when(mWifiMock.isStarted()).thenReturn(true);
        doAnswer(new HidlMockUtil.GetChipIdsAnswer(true, false, CHIP_IDS)).when(
                mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        doAnswer(new HidlMockUtil.GetChipAnswer(true, false, mWifiChipMock)).when(
                mWifiMock).getChip(anyInt(), any(IWifi.getChipCallback.class));
        when(mWifiChipMock.registerEventCallback(any(IWifiChipEventCallback.class))).thenReturn(
                HidlMockUtil.statusOk);
        doAnswer(new HidlMockUtil.CreateNanIfaceAnswer(true, false, mWifiNanIfaceMock)).when(
                mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        doAnswer(new HidlMockUtil.GetNameAnswer(true, false, NAN_IFACE_NAME)).when(
                mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));

        mInOrder = inOrder(mWifiAwareStateManagerMock, mWifiMock, mWifiChipMock, mWifiNanIfaceMock);
    }

    @After
    public void verifyDone() {
        verifyNoMoreInteractions(mWifiAwareStateManagerMock, mWifiMock, mWifiChipMock,
                mWifiNanIfaceMock);
    }

    /**
     * Test setup flow when service is already started
     */
    @Test
    public void testStartWithServiceAlreadyStarted() {
        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(any(IWifiEventCallback.class));
        mInOrder.verify(mWifiMock).isStarted();
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(any(IWifiChipEventCallback.class));
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();
    }

    /**
     * Test correct enable/disable sequence on: onStart/onStop (i.e. not previously started)
     */
    @Test
    public void testStartStop() {
        when(mWifiMock.isStarted()).thenReturn(false);

        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());
        mInOrder.verify(mWifiMock).isStarted();

        // act
        mWifiEventCallbackCaptor.getValue().onStart();
        mWifiEventCallbackCaptor.getValue().onStop();

        // verify
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(any(IWifiChipEventCallback.class));
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();
        mInOrder.verify(mWifiAwareStateManagerMock).disableUsage();
    }

    /**
     * Test correct enable/disable sequence on: (not originally started)/onStart/onChipReconfigured
     */
    @Test
    public void testStartChipReconf() {
        when(mWifiMock.isStarted()).thenReturn(false);

        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());
        mInOrder.verify(mWifiMock).isStarted();

        // act
        mWifiEventCallbackCaptor.getValue().onStart();

        // verify
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(
                mWifiChipEventCallbackCaptor.capture());
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();

        // act
        mWifiChipEventCallbackCaptor.getValue().onChipReconfigured(4);

        // verify
        mInOrder.verify(mWifiAwareStateManagerMock).disableUsage();
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();
    }

    /**
     * Test correct enable/disable sequence on: onStart/onChipReconfiguredFailure
     */
    @Test
    public void testStartChipReconfFail() {
        when(mWifiMock.isStarted()).thenReturn(false);

        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());
        mInOrder.verify(mWifiMock).isStarted();

        // act
        mWifiEventCallbackCaptor.getValue().onStart();

        // verify
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(
                mWifiChipEventCallbackCaptor.capture());
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();

        // act
        mWifiChipEventCallbackCaptor.getValue().onChipReconfigureFailure(HidlMockUtil.statusFail);

        // verify
        mInOrder.verify(mWifiAwareStateManagerMock).disableUsage();
    }

    /**
     * Test correct enable/disable sequence on: onStart/onIfaceRemoved(me)
     */
    @Test
    public void testStartIfaceMeRemoved() {
        when(mWifiMock.isStarted()).thenReturn(false);

        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());
        mInOrder.verify(mWifiMock).isStarted();

        // act
        mWifiEventCallbackCaptor.getValue().onStart();

        // verify
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(
                mWifiChipEventCallbackCaptor.capture());
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();

        // act
        mWifiChipEventCallbackCaptor.getValue().onIfaceRemoved(IfaceType.NAN, NAN_IFACE_NAME);

        // verify
        mInOrder.verify(mWifiAwareStateManagerMock).disableUsage();
    }

    /**
     * Test correct enable/disable sequence on: onStart/onIfaceRemoved(other)
     */
    @Test
    public void testStartIfaceOtherRemoved() {
        when(mWifiMock.isStarted()).thenReturn(false);

        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());
        mInOrder.verify(mWifiMock).isStarted();

        // act
        mWifiEventCallbackCaptor.getValue().onStart();

        // verify
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(
                mWifiChipEventCallbackCaptor.capture());
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();

        // act
        mWifiChipEventCallbackCaptor.getValue().onIfaceRemoved(IfaceType.NAN, "garbage");
    }

    /**
     * Test correct enable/disable sequence on: onStart/onIfaceRemoved(me)/onIfaceRemoved(other)
     */
    @Test
    public void testStartIfaceMeRemovedThenOtherRemoved() {
        when(mWifiMock.isStarted()).thenReturn(false);

        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());
        mInOrder.verify(mWifiMock).isStarted();

        // act
        mWifiEventCallbackCaptor.getValue().onStart();

        // verify
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(
                mWifiChipEventCallbackCaptor.capture());
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();

        // act
        mWifiChipEventCallbackCaptor.getValue().onIfaceRemoved(IfaceType.NAN, NAN_IFACE_NAME);

        // verify
        mInOrder.verify(mWifiAwareStateManagerMock).disableUsage();

        // act
        mWifiChipEventCallbackCaptor.getValue().onIfaceRemoved(IfaceType.NAN, "garbage");

        // verify
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();
    }

    /**
     * Test correct behavior when service died (RuntimeException). Start with normal flow
     * and simulate failure when trying to create NAN interface the second time around.
     */
    @Test
    public void testServiceDiedException() {
        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(any(IWifiEventCallback.class));
        mInOrder.verify(mWifiMock).isStarted();
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(
                mWifiChipEventCallbackCaptor.capture());
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();

        // configure service exception
        doAnswer(new HidlMockUtil.CreateNanIfaceAnswer(true, true, mWifiNanIfaceMock)).when(
                mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));

        // act
        mWifiChipEventCallbackCaptor.getValue().onIfaceRemoved(IfaceType.NAN, NAN_IFACE_NAME);
        mWifiChipEventCallbackCaptor.getValue().onIfaceRemoved(IfaceType.NAN, "garbage");

        // verify
        mInOrder.verify(mWifiAwareStateManagerMock).disableUsage();
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));

        mInOrder.verify(mWifiMock).registerEventCallback(any(IWifiEventCallback.class));
        mInOrder.verify(mWifiMock).isStarted();
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(any(IWifiChipEventCallback.class));
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();
    }

    /**
     * Test correct behavior when have a failure when registering event callback on top-level
     * handler: expect to retry from start.
     */
    @Test
    public void testFailureOnRegisteringWifiCallback() {
        when(mWifiMock.registerEventCallback(any(IWifiEventCallback.class))).thenReturn(
                HidlMockUtil.statusFail);

        // act
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());

        // re-configure and act
        when(mWifiMock.registerEventCallback(any(IWifiEventCallback.class))).thenReturn(
                HidlMockUtil.statusOk);
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).registerEventCallback(any(IWifiEventCallback.class));
        mInOrder.verify(mWifiMock).isStarted();
        mInOrder.verify(mWifiMock).getChipIds(any(IWifi.getChipIdsCallback.class));
        mInOrder.verify(mWifiMock).getChip(eq(CHIP_ID), any(IWifi.getChipCallback.class));
        mInOrder.verify(mWifiChipMock).registerEventCallback(
                mWifiChipEventCallbackCaptor.capture());
        mInOrder.verify(mWifiChipMock).createNanIface(any(IWifiChip.createNanIfaceCallback.class));
        mInOrder.verify(mWifiNanIfaceMock).getName(any(IWifiIface.getNameCallback.class));
        mInOrder.verify(mWifiAwareStateManagerMock).enableUsage();
    }
}
