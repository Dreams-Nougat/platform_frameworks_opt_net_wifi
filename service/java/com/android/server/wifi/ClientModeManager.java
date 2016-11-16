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
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.BaseNetworkObserver;

/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class ClientModeManager implements ActiveModeManager {

    private static final String TAG = "ClientModeManager";

    private static final String GOOGLE_OUI = "DA-A1-19";

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
        // Explicitly exit the current state.  This is a no-op if it is not running, otherwise it
        // will stop and clean up the state.
        mStateMachine.getCurrentState().exit();
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
     */
    private void updateWifiState(int state) {
        if (mListener != null) {
            mListener.onStateChanged(state);
        }
    }

    private class ClientModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_CLIENT_INTERFACE_BINDER_DEATH = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final StateMachineDeathRecipient mDeathRecipient =
                new StateMachineDeathRecipient(this, CMD_CLIENT_INTERFACE_BINDER_DEATH);

        private NetworkObserver mNetworkObserver;
        private boolean mIfaceIsUp = false;

        private String mInterfaceName = null;

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
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SUP_CONNECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SUP_DISCONNECTION_EVENT,
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

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            private boolean mStartCalled = false;
            private String mInterfaceName;

            @Override
            public void enter() {
                mDeathRecipient.unlinkToDeath();
                unregisterObserver();
                // make sure we clean up any configured IP addresses.
                try {
                    mInterfaceName = mClientInterface.getInterfaceName();
                    // A runtime crash or shutting down AP mode can leave
                    // IP addresses configured, and this affects
                    // connectivity when supplicant starts up.
                    // Ensure we have no IP addresses before a supplicant start.
                    mNwService.clearInterfaceAddresses(mInterfaceName);

                    // Set privacy extensions
                    mNwService.setInterfaceIpv6PrivacyExtensions(mInterfaceName, true);

                    // IPv6 is enabled only as long as access point is connected since:
                    // - IPv6 addresses and routes stick around after disconnection
                    // - kernel is unaware when connected and fails to start IPv6 negotiation
                    // - kernel can start autoconfiguration when 802.1x is not complete
                    mNwService.disableIpv6(mInterfaceName);
                } catch (RemoteException re) {
                    loge("Unable to change interface settings: " + re);
                } catch (IllegalStateException ie) {
                    loge("Unable to change interface settings: " + ie);
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        updateWifiState(WifiManager.WIFI_STATE_ENABLING);
                        if (!mDeathRecipient.linkToDeath(mClientInterface.asBinder())) {
                            mDeathRecipient.unlinkToDeath();
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }

                        try {
                            mNetworkObserver =
                                    new NetworkObserver(mInterfaceName);
                            mNwService.registerObserver(mNetworkObserver);
                        } catch (RemoteException e) {
                            mDeathRecipient.unlinkToDeath();
                            unregisterObserver();
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
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
                                updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                                break;
                            }
                            registerWifiMonitorHandlers();
                            mWifiMonitor.startMonitoring(mInterfaceName);
                        } catch (RemoteException e) {
                            Log.e(TAG, "RemoteException when starting supplicant");
                            mDeathRecipient.unlinkToDeath();
                            unregisterObserver();
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
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
                        Log.d(TAG, "Supplicant connection established");
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

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "Wifi is ready to use for client mode");
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED);
                    sendScanAvailableBroadcast(true);
                } else {
                    // if the interface goes down we should exit and go back to idle state.
                    mStateMachine.sendMessage(CMD_STOP);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
                // finish setting up supplicant
                // initialize details for WPS
                initializeWpsDetails();

                // Reset supplicant state to indicate the supplicant state is not known at this
                // time. TODO: this is done in WSM - is it really necessary?
                //mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                mWifiInfo.setMacAddress(mWifiNative.getMacAddress());
                mWifiConfigManager.loadFromStore();

                int defaultInterval = mContext.getResources().getInteger(
                        R.integer.config_wifi_supplicant_scan_interval);
                long supplicantScanIntervalMs =
                        Settings.Global.getLong(mContext.getContentResolver(),
                                                Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                                                defaultInterval);

                mWifiNative.setScanInterval((int) supplicantScanIntervalMs / 1000);
                mWifiNative.setExternalSim(true);

                // turn on use of DFS channels
                mWifiNative.setDfsFlag(true);

                setRandomMacOui();
                mWifiNative.enableAutoConnect(false);
                mCountryCode.setReadyForChange(true);

                sendSupplicantConnectionChangedBroadcast(true);

                if (mIfaceIsUp) {
                    // we already received the interface up notification when we were starting
                    // supplicant.
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED);
                    sendScanAvailableBroadcast(true);
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_INTERFACE_STATUS_CHANGED:
                        if (message.obj != mNetworkObserver) {
                            // This is not from our current observer
                            break;
                        }
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_CLIENT_INTERFACE_BINDER_DEATH:
                        Log.d(TAG, "interface binder death!  restart services?");
                        updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                        transitionTo(mIdleState);
                        break;
                    case CMD_STOP:
                        Log.d(TAG, "Stopping client mode.");
                        updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
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
                // let system know wifi is disabled
                updateWifiState(WifiManager.WIFI_STATE_DISABLING);
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

        private void initializeWpsDetails() {
            String detail;
            detail = mPropertyService.get("ro.product.name", "");
            if (!mWifiNative.setDeviceName(detail)) {
                loge("Failed to set device name " +  detail);
            }
            detail = mPropertyService.get("ro.product.manufacturer", "");
            if (!mWifiNative.setManufacturer(detail)) {
                loge("Failed to set manufacturer " + detail);
            }
            detail = mPropertyService.get("ro.product.model", "");
            if (!mWifiNative.setModelName(detail)) {
                loge("Failed to set model name " + detail);
            }
            detail = mPropertyService.get("ro.product.model", "");
            if (!mWifiNative.setModelNumber(detail)) {
                loge("Failed to set model number " + detail);
            }
            detail = mPropertyService.get("ro.serialno", "");
            if (!mWifiNative.setSerialNumber(detail)) {
                loge("Failed to set serial number " + detail);
            }
            if (!mWifiNative.setConfigMethods("physical_display virtual_push_button")) {
                loge("Failed to set WPS config methods");
            }
            detail = mContext.getResources().getString(R.string.config_wifi_p2p_device_type);
            if (!mWifiNative.setDeviceType(detail)) {
                loge("Failed to set primary device type " + detail);
            }
        }

        private boolean setRandomMacOui() {
            String oui = mContext.getResources().getString(R.string.config_wifi_random_mac_oui);
            if (TextUtils.isEmpty(oui)) {
                oui = GOOGLE_OUI;
            }
            String[] ouiParts = oui.split("-");
            byte[] ouiBytes = new byte[3];
            ouiBytes[0] = (byte) (Integer.parseInt(ouiParts[0], 16) & 0xFF);
            ouiBytes[1] = (byte) (Integer.parseInt(ouiParts[1], 16) & 0xFF);
            ouiBytes[2] = (byte) (Integer.parseInt(ouiParts[2], 16) & 0xFF);

            logd("Setting OUI to " + oui);
            return mWifiNative.setScanningMacOui(ouiBytes);
        }
    }
}
