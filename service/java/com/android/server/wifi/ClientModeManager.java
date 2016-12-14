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
import android.net.NetworkInfo;
import android.net.wifi.IClientInterface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.BaseNetworkObserver;

/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class ClientModeManager implements ActiveModeManager {

    private static final String TAG = "ClientModeManager";

    private final Context mContext;
    private final WifiNative mWifiNative;
    private final ClientModeStateMachine mStateMachine;
    private final Listener mListener;
    private final IClientInterface mClientInterface;
    private final WifiCountryCode mCountryCode;
    private final INetworkManagementService mNwService;
    private final WifiMonitor mWifiMonitor;
    private final SupplicantStateTracker mSupplicantStateTracker;
    private final PropertyService mPropertyService;
    private final WifiConfigManager mWifiConfigManager;
    private final NetworkInfo mNetworkInfo;
    private final WifiInfo mWifiInfo;

    ClientModeManager(Context context,
                      Looper looper,
                      WifiNative wifiNative,
                      Listener listener,
                      IClientInterface clientInterface,
                      WifiCountryCode countryCode,
                      INetworkManagementService nms,
                      WifiMonitor wifiMonitor,
                      SupplicantStateTracker supplicantStateTracker,
                      PropertyService propertyService,
                      WifiConfigManager wifiConfigManager,
                      NetworkInfo networkInfo, WifiInfo wifiInfo) {
        mContext = context;
        mStateMachine = new ClientModeStateMachine(looper);
        mWifiNative = wifiNative;
        mListener = listener;
        mClientInterface = clientInterface;
        mCountryCode = countryCode;
        mNwService = nms;
        mWifiMonitor = wifiMonitor;
        mSupplicantStateTracker = supplicantStateTracker;
        mPropertyService = propertyService;
        mWifiConfigManager = wifiConfigManager;
        mNetworkInfo = networkInfo;
        mWifiInfo = wifiInfo;
    }

    /**
     * Start client mode.
     */
    public void start() {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_START);
    }

    /**
     * Disconnect from any currently connected networks and stop client mode.
     */
    public void stop() {
        // Explicitly exit the StartedState.
        mStateMachine.stopNow();
    }

    /**
     * Listener for ClientMode state changes.
     */
    public interface Listener {
        /**
         * Invoke when wifi state changes.
         * @param state new wifi state
         */
        void onStateChanged(int state);
    }

    /**
     * Update wifi state.
     * @param state new Wifi state
     * @param prevState previous Wifi state
     */
    private void updateWifiState(int state, int prevState) {
        if (mListener != null) {
            mListener.onStateChanged(state);
        }
        sendWifiStateUpdateBroadcast(state, prevState);
    }

    /**
     * Send sticky broadcast to system services with an update to wifi state.
     *
     * @param newWifiState the new wifi state
     * @param prevWifiState the previous wifi state
     */
    private void sendWifiStateUpdateBroadcast(int newWifiState, int prevWifiState) {
        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, newWifiState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, prevWifiState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private class ClientModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_CLIENT_INTERFACE_BINDER_DEATH = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();
        private final State mL2ConnectedState = new L2ConnectedState();
        private final State mObtainingIpState = new ObtainingIpState();
        private final State mConnectedState = new ConnectedState();
        private final State mRoamingState = new RoamingState();
        private final State mDisconnectingState = new DisconnectingState();
        private final State mDisconnectedState = new DisconnectedState();
        private final State mWpsRunningState = new WpsRunningState();

        private final StateMachineDeathRecipient mDeathRecipient =
                new StateMachineDeathRecipient(this, CMD_CLIENT_INTERFACE_BINDER_DEATH);

        private NetworkObserver mNetworkObserver;
        private boolean mIfaceIsUp = false;

        private class NetworkObserver extends BaseNetworkObserver {
            private final String mIfaceName;
            NetworkObserver(String ifaceName) {
                mIfaceName = ifaceName;
            }

            @Override
            public void interfaceLinkStateChanged(String iface, boolean up) {
                if (mIfaceName.equals(iface)) {
                    ClientModeStateMachine.this.sendMessage(
                            CMD_INTERFACE_STATUS_CHANGED, up ? 1 : 0 , 0, this);
                }
            }
        }

        private void registerWifiMonitorHandlers() throws RemoteException {
            mWifiMonitor.registerHandler(mClientInterface.getInterfaceName(),
                                         WifiMonitor.SUP_CONNECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mClientInterface.getInterfaceName(),
                                         WifiMonitor.SUP_DISCONNECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mClientInterface.getInterfaceName(),
                                         WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT,
                                         getHandler());
        }

        private void unregisterObserver() {
            if (mNetworkObserver == null) {
                return;
            }
            try {
                mNwService.unregisterObserver(mNetworkObserver);
            } catch (RemoteException e) { }
            mNetworkObserver = null;
        }

        ClientModeStateMachine(Looper looper) {
            super(TAG, looper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mIdleState);
            addState(mStartedState);
                addState(mL2ConnectedState, mStartedState);
                    addState(mObtainingIpState, mL2ConnectedState);
                    addState(mConnectedState, mL2ConnectedState);
                    addState(mRoamingState, mL2ConnectedState);
                addState(mDisconnectingState, mStartedState);
                addState(mDisconnectedState, mStartedState);
                addState(mWpsRunningState, mStartedState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            private boolean mStartCalled = false;

            @Override
            public void enter() {
                mDeathRecipient.unlinkToDeath();
                unregisterObserver();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        updateWifiState(WifiManager.WIFI_STATE_ENABLING,
                                                    WifiManager.WIFI_STATE_DISABLED);
                        if (!mDeathRecipient.linkToDeath(mClientInterface.asBinder())) {
                            mDeathRecipient.unlinkToDeath();
                            updateWifiState(WifiManager.WIFI_STATE_DISABLED,
                                            WifiManager.WIFI_STATE_ENABLING);
                            break;
                        }

                        try {
                            mNetworkObserver =
                                    new NetworkObserver(mClientInterface.getInterfaceName());
                            mNwService.registerObserver(mNetworkObserver);
                        } catch (RemoteException e) {
                            mDeathRecipient.unlinkToDeath();
                            unregisterObserver();
                            updateWifiState(WifiManager.WIFI_STATE_DISABLED,
                                            WifiManager.WIFI_STATE_ENABLING);
                            break;
                        }

                        if (!mWifiNative.startHal()) {
                            // starting HAL is optional
                            Log.e(TAG, "Failed to start HAL for ClientMode");
                        }

                        // now try to start supplicant and receive updates
                        try {
                            if (!mClientInterface.enableSupplicant()) {
                                Log.e(TAG, "Failed to start supplicant!");
                                mDeathRecipient.unlinkToDeath();
                                unregisterObserver();
                                updateWifiState(WifiManager.WIFI_STATE_DISABLED,
                                                WifiManager.WIFI_STATE_ENABLING);
                                break;
                            }
                            registerWifiMonitorHandlers();
                            mWifiMonitor.startMonitoring(mClientInterface.getInterfaceName());
                        } catch (RemoteException e) {
                            Log.e(TAG, "RemoteException when starting supplicant");
                            mDeathRecipient.unlinkToDeath();
                            unregisterObserver();
                            mWifiNative.stopSupplicant();
                            mWifiNative.closeSupplicantConnection();

                            updateWifiState(WifiManager.WIFI_STATE_DISABLED,
                                            WifiManager.WIFI_STATE_ENABLING);
                            break;
                        }
                        mStartCalled = true;
                        // wait here until supplicant is started
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        if (message.obj != mNetworkObserver) {
                            // This is not from our current observer
                            break;
                        }
                        mIfaceIsUp = message.arg1 == 1;
                        break;
                    case WifiMonitor.SUP_CONNECTION_EVENT:
                        if (!mStartCalled) {
                            // TODO: remove the mStartCalled checks and variable when client mode is
                            // activated in WSMP - control split between WSM and WSMP causes issues
                            // with supplicant start/stop.
                            break;
                        }
                        // unset start called since we are starting
                        mStartCalled = false;
                        transitionTo(mStartedState);
                        break;
                    case WifiMonitor.SUP_DISCONNECTION_EVENT:
                        // we are not in the started state so it is ok to drop this.
                        Log.d(TAG, "received supplicant disconnect event when idle, ignoring");
                        break;
                    case CMD_STOP:
                        // We are not in an active mode and the only way to receive a CMD_STOP is if
                        // we were listening to interface down when we were running.  This should be
                        // safe to ignore.
                        Log.d(TAG, "received CMD_STOP when idle, ignoring");
                        break;
                    default:
                        Log.d(TAG, "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            /**
             * Helper method to handle interface changes.
             *
             * @param boolean interface status.  up = true
             * @return boolean returns true if the interface has changed and ClientMode needs to
             * react.
             */
            private boolean onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return false;  // no change
                }
                mIfaceIsUp = isUp;
                return true;
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
                sendSupplicantConnectionChangedBroadcast(true);

                if (mIfaceIsUp) {
                    // we already received the interface up notification when we were starting
                    // supplicant.
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED,
                                    WifiManager.WIFI_STATE_ENABLING);
                    sendScanAvailableBroadcast(true);

                    // Since Wifi is ready, head to Disconnected state.
                    transitionTo(mDisconnectedState);
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_INTERFACE_STATUS_CHANGED:
                        if (message.obj != mNetworkObserver) {
                            Log.d(TAG, "got a message for an old networkobserver.");
                            // This is not from our current observer
                            break;
                        }
                        boolean isUp = message.arg1 == 1;
                        if (onUpChanged(isUp)) {
                            if (isUp) {
                                // interface changed from down to up.  we should indicate we are
                                // ready for ClientMode and send out updates.
                                Log.d(TAG, "Wifi is ready to use for client mode");
                                updateWifiState(WifiManager.WIFI_STATE_ENABLED,
                                                WifiManager.WIFI_STATE_ENABLING);
                                sendScanAvailableBroadcast(true);

                                // we are ready to go, head to disconnected state.
                                transitionTo(mDisconnectedState);
                            } else {
                                // interface has gone down, we need to stop ClientMode.
                                transitionTo(mIdleState);
                            }
                        }
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_CLIENT_INTERFACE_BINDER_DEATH:
                        Log.d(TAG, "interface binder death!  restart services?");
                        transitionTo(mIdleState);
                        break;
                    case CMD_STOP:
                        Log.d(TAG, "Stopping client mode.");
                        transitionTo(mIdleState);
                        break;
                    case WifiMonitor.SUP_DISCONNECTION_EVENT:
                        // Supplicant disconnected, we should stop
                        Log.d(TAG, "received supplicant disconnect.  Stop client mode.");
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Stop supplicant and send broadcast to tell WifiScanner that wifi is disabled.
             */
            @Override
            public void exit() {
                // let system know wifi is disabling
                updateWifiState(WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_ENABLED);
                // let WifiScanner know that wifi is down.
                sendScanAvailableBroadcast(false);

                mDeathRecipient.unlinkToDeath();
                unregisterObserver();

                // stop supplicant
                if (mWifiNative.stopSupplicant()) {
                    Log.d(TAG, "Successfully called stopSupplicant().");
                } else {
                    Log.d(TAG, "Failed calling stopSupplicant().");
                }
                mWifiNative.closeSupplicantConnection();
                mWifiMonitor.stopAllMonitoring();
                sendSupplicantConnectionChangedBroadcast(false);
                // let system know wifi is disabled
                updateWifiState(WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING);
            }
        }

        private class L2ConnectedState extends State {
            @Override
            public void enter() {
            }

            @Override
            public void exit() {

            }

            @Override
            public boolean processMessage(Message message) {
                logStateAndMessage(message, this);
                return NOT_HANDLED;
            }
        }

        private class ObtainingIpState extends State {
            @Override
            public void enter() {
            }

            @Override
            public void exit() {
            }

            @Override
            public boolean processMessage(Message message) {
                logStateAndMessage(message, this);
                return NOT_HANDLED;
            }
        }

        private class ConnectedState extends State {
            @Override
            public void enter() {
            }

            @Override
            public void exit() {
            }

            @Override
            public boolean processMessage(Message message) {
                logStateAndMessage(message, this);
                return NOT_HANDLED;
            }
        }

        private class RoamingState extends State {
            @Override
            public void enter() {
            }

            @Override
            public void exit() {
            }
            @Override
            public boolean processMessage(Message message) {
                logStateAndMessage(message, this);
                return NOT_HANDLED;
            }
        }

        private class DisconnectingState extends State {
            @Override
            public void enter() {
            }
            @Override
            public void exit() {
            }

            @Override
            public boolean processMessage(Message message) {
                logStateAndMessage(message, this);
                return NOT_HANDLED;
            }
        }

        private class DisconnectedState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "entering DisconnectedState");
                // TODO: is this the best place?
                //mWifiConnectivityManager.handleConnectionStateChanged(
                //        WifiConnectivityManager.WIFI_STATE_DISCONNECTED);
            }

            @Override
            public void exit() {
            }

            @Override
            public boolean processMessage(Message message) {
                logStateAndMessage(message, this);
                return NOT_HANDLED;
            }
        }

        private class WpsRunningState extends State {
            @Override
            public void enter() {
            }

            @Override
            public void exit() {
            }

            @Override
            public boolean processMessage(Message message) {
                logStateAndMessage(message, this);
                return NOT_HANDLED;
            }
        }

        private void sendScanAvailableBroadcast(boolean available) {
            Log.d(TAG, "sending scan available broadcast: " + available);
            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (available) {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_ENABLED);
            } else {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
            }
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void sendSupplicantConnectionChangedBroadcast(boolean connected) {
            Log.d(TAG, "sending supplicant connection changed broadcast: " + connected);
            final Intent intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, connected);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void logStateAndMessage(Message message, State state) {
            Log.d(TAG, state.getClass().getSimpleName() + " " + getLogRecString(message));
        }

        protected void stopNow() {
            if (getCurrentState() != mIdleState) {
                Log.d(TAG, "Calling StartedState.exit to stop client mode now");
                mStartedState.exit();
            }
        }
    }
}
