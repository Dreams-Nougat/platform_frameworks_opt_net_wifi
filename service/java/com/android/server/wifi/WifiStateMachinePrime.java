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

import android.content.Context;
import android.content.Intent;
import android.net.wifi.IApInterface;
import android.net.wifi.IWificond;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This class provides the implementation for different WiFi operating modes.
 *
 * NOTE: The class is a WIP and is in active development.  It is intended to replace the existing
 * WifiStateMachine.java class when the rearchitecture is complete.
 */
public class WifiStateMachinePrime {
    private static final String TAG = "WifiStateMachinePrime";

    private ModeStateMachine mModeStateMachine;

    private WifiInjector mWifiInjector;
    private Looper mLooper;
    private Context mContext;
    private IWificond mWificond;

    private Queue<WifiConfiguration> mApConfigQueue = new ConcurrentLinkedQueue<>();

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;

    /* Start the soft access point */
    static final int CMD_START_AP                                       = BASE + 21;
    /* Indicates soft ap start failed */
    static final int CMD_START_AP_FAILURE                               = BASE + 22;
    /* Stop the soft access point */
    static final int CMD_STOP_AP                                        = BASE + 23;
    /* Soft access point teardown is completed. */
    static final int CMD_AP_STOPPED                                     = BASE + 24;

    WifiStateMachinePrime(WifiInjector wifiInjector, Looper looper, Context context) {
        mWifiInjector = wifiInjector;
        mLooper = looper;
        mContext = context;

        // Clean up existing interfaces in wificond.
        // This ensures that wificond continue to work if java framework restarts.
        try {
            mWificond = mWifiInjector.makeWificond();
            if (mWificond != null) {
                mWificond.tearDownInterfaces();
            }
        } catch (RemoteException e) {
            // There is very little we can do here
            Log.e(TAG, "Failed to tear down interfaces via wificond");
        }
    }

    /**
     * Method to switch wifi into client mode where connections to configured networks will be
     * attempted.
     */
    public void enterClientMode() {
        changeMode(ModeStateMachine.CMD_START_CLIENT_MODE);
    }

    /**
     * Method to switch wifi into scan only mode where network connection attempts will not be made.
     *
     * This mode is utilized by location scans.  If wifi is disabled by a user, but they have
     * previously configured their device to perform location scans, this mode allows wifi to
     * fulfill the location scan requests but will not be used for connectivity.
     */
    public void enterScanOnlyMode() {
        changeMode(ModeStateMachine.CMD_START_SCAN_ONLY_MODE);
    }

    /**
     * Method to enable soft ap for wifi hotspot.
     *
     * @param wifiConfig WifiConfiguration for the hostapd softap
     */
    public void enterSoftAPMode(WifiConfiguration wifiConfig) {
        if (wifiConfig == null) {
            wifiConfig = new WifiConfiguration();
        }
        mApConfigQueue.offer(wifiConfig);
        changeMode(ModeStateMachine.CMD_START_SOFT_AP_MODE);
    }

    /**
     * Method to fully disable wifi.
     *
     * This mode will completely shut down wifi and will not perform any network scans.
     */
    public void disableWifi() {
        changeMode(ModeStateMachine.CMD_DISABLE_WIFI);
    }

    protected String getCurrentMode() {
        if (mModeStateMachine != null) {
            return mModeStateMachine.getCurrentMode();
        }
        return "WifiDisabled";
    }

    private void changeMode(int newMode) {
        if (mModeStateMachine == null) {
            if (newMode == ModeStateMachine.CMD_DISABLE_WIFI) {
                // command is to disable wifi, but it is already disabled.
                Log.e(TAG, "Received call to disable wifi when it is already disabled.");
                return;
            }
            // state machine was not initialized yet, we must be starting up
            mModeStateMachine = new ModeStateMachine(newMode);
        } else {
            // we have a current state, go ahead and prep for the new state
            mModeStateMachine.sendMessage(newMode);
        }
    }

    private class ModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START_CLIENT_MODE    = 0;
        public static final int CMD_START_SCAN_ONLY_MODE = 1;
        public static final int CMD_START_SOFT_AP_MODE   = 2;
        public static final int CMD_DISABLE_WIFI         = 3;

        // Create the base modes for WSM.
        private State mClientModeState = new ClientModeState();
        private State mScanOnlyModeState = new ScanOnlyModeState();
        private State mSoftAPModeState = new SoftAPModeState();

        // Create the active versions of the modes for WSM.
        private State mClientModeActiveState = new ClientModeActiveState();
        private State mScanOnlyModeActiveState = new ScanOnlyModeActiveState();
        private State mSoftAPModeActiveState = new SoftAPModeActiveState();
        private ArrayMap<Integer, State> mCommandToStateMap;

        ModeStateMachine(int initialMode) {
            super(TAG, mLooper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mClientModeState);
              addState(mClientModeActiveState, mClientModeState);
            addState(mScanOnlyModeState);
              addState(mScanOnlyModeActiveState, mScanOnlyModeState);
            addState(mSoftAPModeState);
              addState(mSoftAPModeActiveState, mSoftAPModeState);
            // CHECKSTYLE:ON IndentationCheck

            mCommandToStateMap = new ArrayMap<>();
            mCommandToStateMap.put(CMD_START_CLIENT_MODE, mClientModeState);
            mCommandToStateMap.put(CMD_START_SCAN_ONLY_MODE, mScanOnlyModeState);
            mCommandToStateMap.put(CMD_START_SOFT_AP_MODE, mSoftAPModeState);

            setInitialState(initialMode);
            start();
        }

        private String getCurrentMode() {
            return getCurrentState().getName();
        }

        private void setInitialState(int mode) {
            State startingState = mCommandToStateMap.get(mode);
            Log.d(TAG, "Switching from WifiDisabled to " + startingState.getName());
            setInitialState(startingState);
        }

        private boolean checkForAndHandleModeChange(Message message) {
            switch(message.what) {
                case ModeStateMachine.CMD_START_CLIENT_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to ClientMode");
                    mModeStateMachine.transitionTo(mClientModeState);
                    break;
                case ModeStateMachine.CMD_START_SCAN_ONLY_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to ScanOnlyMode");
                    mModeStateMachine.transitionTo(mScanOnlyModeState);
                    break;
                case ModeStateMachine.CMD_START_SOFT_AP_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to SoftApMode");
                    mModeStateMachine.transitionTo(mSoftAPModeState);
                    break;
                case ModeStateMachine.CMD_DISABLE_WIFI:
                    if (mModeStateMachine != null) {
                        Log.d(TAG, "disabling wifi, calling exit on " + getCurrentMode());
                        mModeStateMachine.getCurrentState().exit();
                        mModeStateMachine = null;
                    }
                    // clean out any ap configs that might be lingering
                    if (!mApConfigQueue.isEmpty()) {
                        Log.e(TAG, "AP config queue was not empty.");
                        mApConfigQueue.clear();
                    }
                    break;
                default:
                    return false;
            }
            return true;
        }

        private void tearDownInterfaces() {
            if (mWificond != null) {
                try {
                    mWificond.tearDownInterfaces();
                } catch (RemoteException e) {
                    // There is very little we can do here
                    Log.e(TAG, "Failed to tear down interfaces via wificond");
                }
                mWificond = null;
            }
            return;
        }

        class ClientModeState extends State {
            @Override
            public void enter() {
                mWificond = mWifiInjector.makeWificond();
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }
                return NOT_HANDLED;
            }

            @Override
            public void exit() {
                // Do not tear down interfaces here since this mode is not actively controlled yet.
                // tearDownInterfaces();
            }
        }

        class ScanOnlyModeState extends State {
            @Override
            public void enter() {
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }
                return NOT_HANDLED;
            }

            @Override
            public void exit() {
                // Do not tear down interfaces yet since this mode is not actively controlled yet.
                // tearDownInterfaces();
            }
        }

        class SoftAPModeState extends State {
            IApInterface mApInterface = null;

            @Override
            public void enter() {
                mApInterface = null;
                mWificond = mWifiInjector.makeWificond();
                if (mWificond == null) {
                    Log.e(TAG, "Failed to get reference to wificond");
                    mModeStateMachine.sendMessage(CMD_START_AP_FAILURE);
                    return;
                }

                try {
                    // When the other modes are activated - tearDownInterfaces can be removed.
                    mWificond.tearDownInterfaces();
                    mApInterface = mWificond.createApInterface();
                } catch (RemoteException e1) { }

                if (mApInterface == null) {
                    Log.e(TAG, "Could not get IApInterface instance from wificond");
                    mModeStateMachine.sendMessage(CMD_START_AP_FAILURE);
                    return;
                }
                mModeStateMachine.sendMessage(CMD_START_AP);
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }

                switch(message.what) {
                    case CMD_START_AP:
                        Log.d(TAG, "Transitioning to softapmodeactivestate");
                        mModeStateMachine.transitionTo(mSoftAPModeActiveState);
                        break;
                    case CMD_STOP_AP:
                        // not in active state, nothing to stop.
                        break;
                    case CMD_START_AP_FAILURE:
                        Log.e(TAG, "Failed to start SoftApMode.  Wait for next mode command.");
                        // since we did not use the config, remove the first one.
                        WifiConfiguration config = mApConfigQueue.poll();
                        if (config != null && config.SSID != null) {
                            // Save valid configs for future calls.
                            mWifiInjector.getWifiApConfigStore().setApConfiguration(config);
                        }
                        // send a broadcast that SoftAp Failed to start.
                        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE,
                                        WifiManager.WIFI_AP_STATE_FAILED);
                        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE,
                                        WifiManager.WIFI_AP_STATE_DISABLED);
                        intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON,
                                        WifiManager.SAP_START_FAILURE_GENERAL);
                        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
                        break;
                    case CMD_AP_STOPPED:
                        Log.d(TAG, "SoftApModeActiveState stopped.  Wait for next mode command.");
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                tearDownInterfaces();
            }

            protected IApInterface getInterface() {
                return mApInterface;
            }
        }

        class ModeActiveState extends State {
            ActiveModeManager mActiveModeManager;

            @Override
            public boolean processMessage(Message message) {
                // handle messages for changing modes here
                return NOT_HANDLED;
            }

            @Override
            public void exit() {
                // clean up objects from an active state - check with mode handlers to make
                // sure they are stopping properly.
                try {
                    mActiveModeManager.stop();
                    Log.e(TAG, "Called stop on active mode manager "
                            + "- cleaning up from errored exit.");
                } catch (NullPointerException e) {
                    Log.d(TAG, "ActiveModeManager was null, must have been properly stopped.");
                }
            }
        }

        class ClientModeActiveState extends ModeActiveState {
            @Override
            public void enter() {
                this.mActiveModeManager = new ClientModeManager();
            }
        }

        class ScanOnlyModeActiveState extends ModeActiveState {
            @Override
            public void enter() {
                this.mActiveModeManager = new ScanOnlyModeManager();
            }
        }

        class SoftAPModeActiveState extends ModeActiveState {
            private class SoftApListener implements SoftApManager.Listener {
                @Override
                public void onStateChanged(int state, int reason) {
                    if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                        try {
                            mModeStateMachine.sendMessage(CMD_AP_STOPPED);
                        } catch (NullPointerException e) {
                            Log.d(TAG, "SoftApModeActiveState stopped and now wifi is disabled.");
                        }
                    } else if (state == WifiManager.WIFI_AP_STATE_FAILED) {
                        try {
                            mModeStateMachine.sendMessage(CMD_START_AP_FAILURE);
                        } catch (NullPointerException e) {
                            Log.e(TAG, "Failed to start SoftApMode "
                                    + "and wifi must have been disabled.");
                        }
                    }
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "Entering SoftApModeActiveState");
                final Message message = mModeStateMachine.getCurrentMessage();
                if (message.what != CMD_START_AP) {
                    throw new RuntimeException("Illegal transition to SoftApState: " + message);
                }
                WifiConfiguration config = mApConfigQueue.poll();
                if (config != null && config.SSID != null) {
                    Log.d(TAG, "Passing config to SoftApManager! " + config);
                } else {
                    config = null;
                }

                this.mActiveModeManager = mWifiInjector.makeSoftApManager(
                        new SoftApListener(), ((SoftAPModeState) mSoftAPModeState).getInterface(),
                        config);
                mActiveModeManager.start();
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START_AP:
                        Log.d(TAG, "Received CMD_START_AP with SoftApMode already active.  drop");
                        break;
                    case CMD_STOP_AP:
                        mActiveModeManager.stop();
                        break;
                    case CMD_START_AP_FAILURE:
                        Log.d(TAG, "Failed to start SoftApMode.  Return to SoftApMode (inactive).");
                        mModeStateMachine.transitionTo(mSoftAPModeState);
                        break;
                    case CMD_AP_STOPPED:
                        try {
                            mModeStateMachine.transitionTo(mSoftAPModeState);
                            Log.d(TAG, "SoftApModeActiveState stopped. "
                                    + " Return to SoftApMode (inactive).");
                        } catch (NullPointerException e) {
                            Log.d(TAG, "SoftApModeActiveState stopped and now wifi is disabled.");
                        }
                        mActiveModeManager = null;
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }  // class ModeStateMachine
}
