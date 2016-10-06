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

import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.IApInterface;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.util.ApConfigUtil;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under "WifiStateMachine" thread context.
 */
public class SoftApManager implements ActiveModeManager {
    private static final String TAG = "SoftApManager";

    private final Context mContext;

    private final WifiNative mWifiNative;

    private final String mCountryCode;

    private final SoftApStateMachine mStateMachine;

    private final Listener mListener;

    private final IApInterface mApInterface;

    private final WifiApConfigStore mWifiApConfigStore;

    private int mCurrentState;

    /**
     * Listener for soft AP state changes.
     */
    public interface Listener {
        /**
         * Invoke when AP state changed.
         * @param state new AP state
         * @param failureReason reason when in failed state
         */
        void onStateChanged(int state, int failureReason);
    }

    public SoftApManager(Context context,
                         Looper looper,
                         WifiNative wifiNative,
                         String countryCode,
                         Listener listener,
                         IApInterface apInterface,
                         WifiApConfigStore wifiApConfigStore,
                         WifiConfiguration config) {
        mStateMachine = new SoftApStateMachine(looper);
        mContext = context;
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mListener = listener;
        mApInterface = apInterface;
        mWifiApConfigStore = wifiApConfigStore;
        if (config != null) {
            mWifiApConfigStore.setApConfiguration(config);
        }
        mCurrentState = WifiManager.WIFI_AP_STATE_DISABLED;
    }

    /**
     * Start soft AP with the current saved config.
     */
    public void start() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START,
                                  mWifiApConfigStore.getApConfiguration());
    }

    /**
     * Stop soft AP.
     */
    public void stop() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_STOP);
    }

    /**
     * Update AP state.
     * @param state new AP state
     * @param reason Failure reason if the new AP state is in failure state
     */
    private void updateApState(int state, int reason) {
        if (mListener != null) {
            mListener.onStateChanged(state, reason);
        }
        int previousState = mCurrentState;
        mCurrentState = state;
        // now send out broadcast updating the state
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, state);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousState);
        if (state == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        Log.d(TAG, "Sending StickyBroadcast: new AP state: " + getApStateName(state)
                + " previous state: " + getApStateName(previousState));
    }

    /**
     * Start a soft AP instance with the given configuration.
     * @param config AP configuration
     * @return integer result code
     */
    private int startSoftAp(WifiConfiguration config) {
        if (config == null || config.SSID == null) {
            Log.e(TAG, "Unable to start soft AP without valid configuration");
            return ERROR_GENERIC;
        }
        Log.d(TAG, "Starting SoftAp with SSID: " + config.SSID);

        /* Make a copy of configuration for updating AP band and channel. */
        WifiConfiguration localConfig = new WifiConfiguration(config);

        int result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mCountryCode,
                mWifiApConfigStore.getAllowed2GChannel(), localConfig);
        if (result != SUCCESS) {
            Log.e(TAG, "Failed to update AP band and channel");
            return result;
        }

        /* Setup country code if it is provide. */
        if (mCountryCode != null) {
            /**
             * Country code is mandatory for 5GHz band, return an error if failed to set
             * country code when AP is configured for 5GHz band.
             */
            if (!mWifiNative.setCountryCodeHal(mCountryCode.toUpperCase(Locale.ROOT))
                    && config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                Log.e(TAG, "Failed to set country code, required for setting up "
                        + "soft ap in 5GHz");
                return ERROR_GENERIC;
            }
        }

        int encryptionType = getIApInterfaceEncryptionType(localConfig);

        try {
            // Note that localConfig.SSID is intended to be either a hex string or "double quoted".
            // However, it seems that whatever is handing us these configurations does not obey
            // this convention.
            boolean success = mApInterface.writeHostapdConfig(
                    localConfig.SSID.getBytes(StandardCharsets.UTF_8), false,
                    localConfig.apChannel, encryptionType,
                    (localConfig.preSharedKey != null)
                            ? localConfig.preSharedKey.getBytes(StandardCharsets.UTF_8)
                            : new byte[0]);
            if (!success) {
                Log.e(TAG, "Failed to write hostapd configuration");
                return ERROR_GENERIC;
            }

            success = mApInterface.startHostapd();
            if (!success) {
                Log.e(TAG, "Failed to start hostapd.");
                return ERROR_GENERIC;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in starting soft AP: " + e);
        }

        Log.d(TAG, "Soft AP is started");

        return SUCCESS;
    }

    private static int getIApInterfaceEncryptionType(WifiConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getAuthType()) {
            case KeyMgmt.NONE:
                encryptionType = IApInterface.ENCRYPTION_TYPE_NONE;
                break;
            case KeyMgmt.WPA_PSK:
                encryptionType = IApInterface.ENCRYPTION_TYPE_WPA;
                break;
            case KeyMgmt.WPA2_PSK:
                encryptionType = IApInterface.ENCRYPTION_TYPE_WPA2;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = IApInterface.ENCRYPTION_TYPE_NONE;
                break;
        }
        return encryptionType;
    }

    /**
     * Teardown soft AP.
     */
    private void stopSoftAp() {
        try {
            mApInterface.stopHostapd();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in stopping soft AP: " + e);
            return;
        }
        Log.d(TAG, "Soft AP is stopped");
    }

    private class SoftApStateMachine extends StateMachine {
        /* Commands for the state machine. */
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_AP_INTERFACE_BINDER_DEATH = 2;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final StateMachineDeathRecipient mDeathRecipient =
                new StateMachineDeathRecipient(this, CMD_AP_INTERFACE_BINDER_DEATH);

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                mDeathRecipient.unlinkToDeath();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING, 0);
                        if (!mDeathRecipient.linkToDeath(mApInterface.asBinder())) {
                            mDeathRecipient.unlinkToDeath();
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            break;
                        }

                        int result = startSoftAp((WifiConfiguration) message.obj);
                        if (result != SUCCESS) {
                            int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            }
                            mDeathRecipient.unlinkToDeath();
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED, failureReason);
                            break;
                        }

                        updateApState(WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        transitionTo(mStartedState);
                        break;
                    default:
                        /* Ignore all other commands. */
                        break;
                }

                return HANDLED;
            }
        }

        private class StartedState extends State {
            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        /* Already started, ignore this command. */
                        break;
                    case CMD_AP_INTERFACE_BINDER_DEATH:
                    case CMD_STOP:
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING, 0);
                        stopSoftAp();
                        if (message.what == CMD_AP_INTERFACE_BINDER_DEATH) {
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                        } else {
                            updateApState(WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        }
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }

    /**
     * Helper class mapping the SoftAP states to Strings.
     * @param int state State value
     * @return String word describing state
     */
    protected String getApStateName(int state) {
        switch (state) {
            case WIFI_AP_STATE_DISABLING:
                return "disabling";
            case WIFI_AP_STATE_DISABLED:
                return "disabled";
            case WIFI_AP_STATE_ENABLING:
                return "enabling";
            case WIFI_AP_STATE_ENABLED:
                return "enabled";
            case WIFI_AP_STATE_FAILED:
                return "failed";
            default:
                return "[invalid state]";
        }
    }
}
