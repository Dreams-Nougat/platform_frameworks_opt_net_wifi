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

import android.os.Message;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.HashMap;

/**
 * This class provides the implementation for different WiFi operating modes.
 *
 * NOTE: The class is a WIP and is in active development.  It is intended to replace the existing
 * WifiStateMachine.java class when the rearchitecture is complete.
 */
public class WifiStateMachinePrime {
    private static final String TAG = "WifiStateMachinePrime";

    private ModeStateMachine mModeStateMachine;
    private State mCurrentState;

    // Create the base modes for WSM.
    private State mClientModeState = new ClientModeState();
    private State mScanOnlyModeState = new ScanOnlyModeState();
    private State mSoftAPModeState = new SoftAPModeState();

    // Create the active versions of the modes for WSM.
    private State mClientModeActiveState = new ClientModeActiveState();
    private State mScanOnlyModeActiveState = new ScanOnlyModeActiveState();
    private State mSoftAPModeActiveState = new SoftAPModeActiveState();
    private HashMap<Integer, State> mCommandToStateMap;

    WifiStateMachinePrime() {
        mModeStateMachine = new ModeStateMachine(TAG);

        mCommandToStateMap = new HashMap<Integer, State>();
        mCommandToStateMap.put(ModeStateMachine.CMD_START_CLIENT_MODE,
                               mClientModeState);
        mCommandToStateMap.put(ModeStateMachine.CMD_START_SCAN_ONLY_MODE,
                               mScanOnlyModeState);
        mCommandToStateMap.put(ModeStateMachine.CMD_START_SOFT_AP_MODE,
                               mSoftAPModeState);
    }

    private class ModeStateMachine extends StateMachine {
        /* Commands for the state machine. */
        public static final int CMD_START_CLIENT_MODE    = 0;
        public static final int CMD_START_SCAN_ONLY_MODE = 1;
        public static final int CMD_START_SOFT_AP_MODE   = 2;

        ModeStateMachine(String name) {
            super(name);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mClientModeState);
              addState(mClientModeActiveState, mClientModeState);
            addState(mScanOnlyModeState);
              addState(mScanOnlyModeActiveState, mScanOnlyModeState);
            addState(mSoftAPModeState);
              addState(mSoftAPModeActiveState, mSoftAPModeState);
            // CHECKSTYLE:ON IndentationCheck
        }

        private String getCurrentMode() {
            try {
                return getCurrentState().getName();
            } catch (NullPointerException e) {
                // current state is not set
                return null;
            }
        }

        private void setInitialState(int mode) {
            setInitialState(mCommandToStateMap.get(mode));
        }
    }

    private class ModeState extends State {
        String mStateTag;
        String mMode;
    }

    class ClientModeState extends ModeState {
        @Override
        public void enter() {
            mMode = "STA";
            mStateTag = "ClientMode";
        }

        @Override
        public boolean processMessage(Message message) {

            return false;
        }

        @Override
        public void exit() {

        }
    }

    class ScanOnlyModeState extends ModeState {
        @Override
        public void enter() {
            mMode = "STA";
            mStateTag = "ScanOnlyMode";
        }

        @Override
        public boolean processMessage(Message message) {
            // handle Mode changes and any events requiring setup or restarting services

            return false;
        }

        @Override
        public void exit() {
            // tear down running services
        }
    }

    class SoftAPModeState extends ModeState {

        @Override
        public void enter() {
            mMode = "AP";
            mStateTag = "SoftApMode";
        }

        @Override
        public boolean processMessage(Message message) {
            return false;
        }

        @Override
        public void exit() {
        }
    }

    class ModeActiveState extends State {
        ActiveModeManager mActiveModeManager;

        @Override
        public boolean processMessage(Message message) {
            // handle messages for changing modes here
            return false;
        }

        @Override
        public void exit() {
            // clean up objects from an active state - check with mode handlers to make sure they
            // are stopping properly.
            mActiveModeManager.stop();
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
        @Override
        public void enter() {
            // The SoftApManager is not empty at this time, will populate in later CLs.
            //this.mActiveModeManager = new SoftApManager();
        }
    }
}
