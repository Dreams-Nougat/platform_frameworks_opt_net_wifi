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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.DhcpResults;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.dhcp.DhcpClient;
import android.net.ip.IpManager;
import android.net.wifi.IClientInterface;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.aware.IWifiAwareManager;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.p2p.IWifiP2pManager;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;
import com.android.server.wifi.util.ClientModeUtil;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class ClientModeManager implements ActiveModeManager {

    private static final String TAG = "ClientModeManager";

    private static final String GOOGLE_OUI = "DA-A1-19";

    // Value to set in wpa_supplicant "bssid" field when we don't want to restrict connection to
    // a specific AP.
    private static final String SUPPLICANT_BSSID_ANY = "any";

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiNative mWifiNative;
    private final ClientModeStateMachine mStateMachine;
    private final Listener mListener;
    private final IClientInterface mClientInterface;
    private final WifiCountryCode mCountryCode;
    private final INetworkManagementService mNwService;
    private final WifiMonitor mWifiMonitor;
    private final WifiSupplicantControl mWifiSupplicantControl;
    private final SupplicantStateTracker mSupplicantStateTracker;
    private final PropertyService mPropertyService;
    private final WifiConfigManager mWifiConfigManager;
    private final NetworkInfo mNetworkInfo;
    private final WifiInfo mWifiInfo;
    private final WifiScanner mWifiScanner;
    private final WifiConnectivityManager mWifiConnectivityManager;
    private final PowerManager mPowerManager;
    private final WifiMetrics mWifiMetrics;
    private final IBatteryStats mBatteryStats;
    private BaseWifiDiagnostics mWifiDiagnostics;
    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final WifiScoreReport mWifiScoreReport;
    private final NetworkCapabilities mDefaultNetworkCapabilities;

    private String mInterfaceName;
    private boolean mScreenOn = false;
    // This will not be needed after logging is moved to the new logger
    private boolean mVerboseLoggingEnabled = true;

    // Class variables that are used in the existing WSM implementation.  Will revist further
    // refactoring to move (or remove) in a later CL.
    private WakeLock mWakeLock;
    private WakeLock mSuspendWakeLock;
    private String mTcpBufferSizes;
    private int mThresholdQualifiedRssi24;
    private int mThresholdQualifiedRssi5;
    private int mThresholdSaturatedRssi24;
    private int mThresholdSaturatedRssi5;
    private int mThresholdMinimumRssi5;
    private int mThresholdMinimumRssi24;
    private boolean mEnableLinkDebouncing;
    private WifiP2pServiceImpl mWifiP2pServiceImpl = null;
    private WifiAwareManager mWifiAwareManager = null;
    // Scan period for the NO_NETWORKS_PERIIDOC_SCAN_FEATURE
    private int mNoNetworksPeriodicScan;

    // Tracks if user has enabled suspend optimizations through settings
    private AtomicBoolean mUserWantsSuspendOpt = new AtomicBoolean(true);

    // The link properties of the wifi interface. Do not modify this directly; use
    // updateLinkProperties instead.
    private LinkProperties mLinkProperties;

    private int mLastSignalLevel;
    private String mLastBssid;
    private int mLastNetworkId; // The network Id we successfully joined
    // This one is used to track whta is the current target network ID. This is used for error
    // handling during connection setup since many error message from supplicant does not report
    // SSID Once connected, it will be set to invalid
    private int mTargetNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    // This is the BSSID we are trying to associate to, it can be set to SUPPLICANT_BSSID_ANY
    // if we havent selected a BSSID for joining.
    private String mTargetRoamBssid = SUPPLICANT_BSSID_ANY;

    private IpManager mIpManager;


    ClientModeManager(Context context,
                      FrameworkFacade facade,
                      Looper looper,
                      WifiNative wifiNative,
                      Listener listener,
                      IClientInterface clientInterface,
                      WifiCountryCode countryCode,
                      INetworkManagementService nms,
                      WifiMonitor wifiMonitor,
                      WifiSupplicantControl wifiSupplicantControl,
                      SupplicantStateTracker supplicantStateTracker,
                      PropertyService propertyService,
                      WifiConfigManager wifiConfigManager,
                      NetworkInfo networkInfo,
                      WifiInfo wifiInfo,
                      WifiScanner wifiScanner,
                      WifiConnectivityManager wifiConnectivityManager,
                      PowerManager powerManager,
                      WifiMetrics wifiMetrics,
                      IBatteryStats batteryStats,
                      BaseWifiDiagnostics wifiDiagnostics,
                      WifiLastResortWatchdog lastResortWatchdog,
                      WifiScoreReport wifiScoreReport) {
        mContext = context;
        mFrameworkFacade = facade;
        mStateMachine = new ClientModeStateMachine(looper);
        mWifiNative = wifiNative;
        mListener = listener;
        mClientInterface = clientInterface;
        mCountryCode = countryCode;
        mNwService = nms;
        mWifiMonitor = wifiMonitor;
        mWifiSupplicantControl = wifiSupplicantControl;
        mSupplicantStateTracker = supplicantStateTracker;
        mPropertyService = propertyService;
        mWifiConfigManager = wifiConfigManager;
        mNetworkInfo = networkInfo;
        mWifiInfo = wifiInfo;
        mWifiScanner = wifiScanner;
        mWifiConnectivityManager = wifiConnectivityManager;
        mPowerManager = powerManager;
        mWifiMetrics = wifiMetrics;
        mBatteryStats = batteryStats;
        mWifiDiagnostics = wifiDiagnostics;
        mWifiLastResortWatchdog = lastResortWatchdog;
        mWifiScoreReport = wifiScoreReport;
        mDefaultNetworkCapabilities = ClientModeUtil.createDefaultNetworkCapabilities();

        // now that we have instances set, use an initialize method to set other local variables
        initializeClientModeManager();
    }

    void initializeClientModeManager() {
        // Learn the initial state of whether the screen is on.
        // We update this field when we receive broadcasts from the system.
        handleScreenStateChanged(mPowerManager.isInteractive());
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mSuspendWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiSuspend");
        mSuspendWakeLock.setReferenceCounted(false);

        mTcpBufferSizes = mContext.getResources().getString(
                com.android.internal.R.string.config_wifi_tcp_buffers);

        // Load Device configs
        mThresholdQualifiedRssi24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdSaturatedRssi24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mThresholdSaturatedRssi5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);
        mThresholdMinimumRssi5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdMinimumRssi24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mEnableLinkDebouncing = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_disconnection_debounce);

        mInterfaceName = mWifiNative.getInterfaceName();

        // if WiFi Aware is available, get the manager.
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            IBinder awareBinder = ServiceManager.getService(Context.WIFI_AWARE_SERVICE);
            mWifiAwareManager = (WifiAwareManager) IWifiAwareManager.Stub.asInterface(awareBinder);
        }

        // if Wifi P2P is available, get the service.
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            IBinder p2pBinder = ServiceManager.getService(Context.WIFI_P2P_SERVICE);
            mWifiP2pServiceImpl = (WifiP2pServiceImpl) IWifiP2pManager.Stub.asInterface(p2pBinder);
        }

        mLinkProperties = new LinkProperties();

        mNetworkInfo.setIsAvailable(false);
        mLastBssid = null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSignalLevel = -1;

        mIpManager = mFrameworkFacade.makeIpManager(mContext,
                                                    mInterfaceName,
                                                    new IpManagerCallback());
        mIpManager.setMulticastFilter(true);

        mNoNetworksPeriodicScan = mContext.getResources().getInteger(
                R.integer.config_wifi_no_network_periodic_scan_interval);

        mUserWantsSuspendOpt.set(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);

        registerScreenStateChanges(new IntentFilter());
        registerSuspendOptimizationsObserver();
        registerBootCompletedFilter(new IntentFilter((Intent.ACTION_LOCKED_BOOT_COMPLETED)));
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

        // Commands (and numbers) from original WSM implementation
        static final int BASE = Protocol.BASE_WIFI;
        static final int CMD_ENABLE_RSSI_POLL                               = BASE + 82;

        // Screen change intent handling
        static final int CMD_SCREEN_STATE_CHANGED                           = BASE + 95;

        public static final int CMD_BOOT_COMPLETED                          = BASE + 134;

        // We now have a valid IP configuration.
        static final int CMD_IP_CONFIGURATION_SUCCESSFUL                    = BASE + 138;
        // We no longer have a valid IP configuration.
        static final int CMD_IP_CONFIGURATION_LOST                          = BASE + 139;
        // Link configuration (IP address, DNS, ...) changes notified via netlink
        static final int CMD_UPDATE_LINKPROPERTIES                          = BASE + 140;

        static final int CMD_TARGET_BSSID                                   = BASE + 141;
        static final int CMD_ASSOCIATED_BSSID                               = BASE + 147;

        // A layer 3 neighbor on the Wi-Fi link became unreachable.
        static final int CMD_IP_REACHABILITY_LOST                           = BASE + 149;

        // Used to handle messages bounced between WifiStateMachine and IpManager.
        static final int CMD_IPV4_PROVISIONING_SUCCESS                      = BASE + 200;
        static final int CMD_IPV4_PROVISIONING_FAILURE                      = BASE + 201;

        // Push a new APF program to the HAL
        static final int CMD_INSTALL_PACKET_FILTER                          = BASE + 202;
        // Enable/disable fallback packet filtering
        static final int CMD_SET_FALLBACK_PACKET_FILTERING                  = BASE + 203;

        // Enable/disable Neighbor Discovery offload functionality.
        static final int CMD_CONFIG_ND_OFFLOAD                              = BASE + 204;


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
            mWifiMonitor.registerHandler(mInterfaceName, CMD_TARGET_BSSID, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName, CMD_ASSOCIATED_BSSID, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.ANQP_DONE_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.DRIVER_HUNG_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.GAS_QUERY_DONE_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.GAS_QUERY_START_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.HS20_REMEDIATION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.NETWORK_CONNECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.NETWORK_DISCONNECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.RX_HS20_ANQP_ICON_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SCAN_FAILED_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SCAN_RESULTS_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.SSID_REENABLED, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SSID_TEMP_DISABLED,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SUP_CONNECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SUP_DISCONNECTION_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SUP_REQUEST_IDENTITY,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.SUP_REQUEST_SIM_AUTH,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.WPS_FAIL_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.WPS_OVERLAP_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.WPS_SUCCESS_EVENT,
                                         getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                                         WifiMonitor.WPS_TIMEOUT_EVENT,
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
                    Log.e(TAG, "Unable to change interface settings: " + re);
                } catch (IllegalStateException ie) {
                    Log.e(TAG, "Unable to change interface settings: " + ie);
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

                mWifiConnectivityManager.handleScreenStateChanged(mScreenOn);

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

        private void initializeWpsDetails() {
            String detail;
            detail = mPropertyService.get("ro.product.name", "");
            if (!mWifiNative.setDeviceName(detail)) {
                Log.e(TAG, "Failed to set device name " +  detail);
            }
            detail = mPropertyService.get("ro.product.manufacturer", "");
            if (!mWifiNative.setManufacturer(detail)) {
                Log.e(TAG, "Failed to set manufacturer " + detail);
            }
            detail = mPropertyService.get("ro.product.model", "");
            if (!mWifiNative.setModelName(detail)) {
                Log.e(TAG, "Failed to set model name " + detail);
            }
            detail = mPropertyService.get("ro.product.model", "");
            if (!mWifiNative.setModelNumber(detail)) {
                Log.e(TAG, "Failed to set model number " + detail);
            }
            detail = mPropertyService.get("ro.serialno", "");
            if (!mWifiNative.setSerialNumber(detail)) {
                Log.e(TAG, "Failed to set serial number " + detail);
            }
            if (!mWifiNative.setConfigMethods("physical_display virtual_push_button")) {
                Log.e(TAG, "Failed to set WPS config methods");
            }
            detail = mContext.getResources().getString(R.string.config_wifi_p2p_device_type);
            if (!mWifiNative.setDeviceType(detail)) {
                Log.e(TAG, "Failed to set primary device type " + detail);
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

            Log.d(TAG, "Setting OUI to " + oui);
            return mWifiNative.setScanningMacOui(ouiBytes);
        }

        private void logStateAndMessage(Message message, State state) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, state.getClass().getSimpleName() + " " + getLogRecString(message));
            }
        }
    }

    // Helper methods used in ClientModeManager are located below.

    private void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, " handleScreenStateChanged Enter: screenOn=" + screenOn
                    //+ " mUserWantsSuspendOpt=" + mUserWantsSuspendOpt
                    + " state " + mStateMachine.getCurrentState().getName()
                    + " suppState:" + mSupplicantStateTracker.getSupplicantStateName());
        }
        enableRssiPolling(screenOn);

        /* TODO: need to handle the code in this comment.
        if (mUserWantsSuspendOpt.get()) {
            int shouldReleaseWakeLock = 0;
            if (screenOn) {
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 0, shouldReleaseWakeLock);
            } else {
                if (isConnected()) {
                    // Allow 2s for suspend optimizations to be set
                    mSuspendWakeLock.acquire(2000);
                    shouldReleaseWakeLock = 1;
                }
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 1, shouldReleaseWakeLock);
            }
        }

        getWifiLinkLayerStats();
        mOnTimeScreenStateChange = mOnTime;
        lastScreenStateChangeTimeStamp = lastLinkLayerStatsUpdate;
        */

        mWifiMetrics.setScreenState(screenOn);

        if (mWifiConnectivityManager != null) {
            mWifiConnectivityManager.handleScreenStateChanged(screenOn);
        }

        if (mVerboseLoggingEnabled) Log.d(TAG, "handleScreenStateChanged Exit: " + screenOn);
    }

    /**
     * Method used to enable/disable RSSI polling.
     *
     * @param enabled Boolean indicating if RSSI polling should be on or off when connected.
     */
    public void enableRssiPolling(boolean enabled) {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_ENABLE_RSSI_POLL, enabled ? 1 : 0, 0);
    }

    /**
     * Gets the SSID from the WifiConfiguration pointed at by 'mTargetNetworkId'
     * This should match the network config framework is attempting to connect to.
     */
    private String getTargetSsid() {
        WifiConfiguration currentConfig = mWifiConfigManager.getConfiguredNetwork(mTargetNetworkId);
        if (currentConfig != null) {
            return currentConfig.SSID;
        }
        return null;
    }

    /**
     * Helper class to register a filter for screen changes.
     * The onReceive callback will be used to send a CMD_SCREEN_STATE_CHANGED action to the private
     * state machine.
     */
    private void registerScreenStateChanges(IntentFilter filter) {
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();

                        if (action.equals(Intent.ACTION_SCREEN_ON)) {
                            mStateMachine.sendMessage(
                                    ClientModeStateMachine.CMD_SCREEN_STATE_CHANGED, 1);
                        } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                            mStateMachine.sendMessage(
                                    ClientModeStateMachine.CMD_SCREEN_STATE_CHANGED, 0);
                        }
                    }
                }, filter);
    }

    private void registerSuspendOptimizationsObserver() {
        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED), false,
                new ContentObserver(mStateMachine.getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mUserWantsSuspendOpt.set(
                                Settings.Global.getInt(mContext.getContentResolver(),
                                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);
                    }
                });
    }

    private void registerBootCompletedFilter(IntentFilter filter) {
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        mStateMachine.sendMessage(ClientModeStateMachine.CMD_BOOT_COMPLETED);
                    }
                }, filter);
    }

    class IpManagerCallback extends IpManager.Callback {
        @Override
        public void onPreDhcpAction() {
            mStateMachine.sendMessage(DhcpClient.CMD_PRE_DHCP_ACTION);
        }

        @Override
        public void onPostDhcpAction() {
            mStateMachine.sendMessage(DhcpClient.CMD_POST_DHCP_ACTION);
        }

        @Override
        public void onNewDhcpResults(DhcpResults dhcpResults) {
            if (dhcpResults != null) {
                mStateMachine.sendMessage(ClientModeStateMachine.CMD_IPV4_PROVISIONING_SUCCESS,
                                          dhcpResults);
            } else {
                mStateMachine.sendMessage(ClientModeStateMachine.CMD_IPV4_PROVISIONING_FAILURE);
                mWifiLastResortWatchdog.noteConnectionFailureAndTriggerIfNeeded(
                        getTargetSsid(), mTargetRoamBssid,
                        WifiLastResortWatchdog.FAILURE_CODE_DHCP);
            }
        }

        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_UPDATE_LINKPROPERTIES, newLp);
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_IP_CONFIGURATION_SUCCESSFUL);
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_IP_CONFIGURATION_LOST);
        }

        @Override
        public void onLinkPropertiesChange(LinkProperties newLp) {
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_UPDATE_LINKPROPERTIES, newLp);
        }

        @Override
        public void onReachabilityLost(String logMsg) {
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_IP_REACHABILITY_LOST, logMsg);
        }

        @Override
        public void installPacketFilter(byte[] filter) {
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_INSTALL_PACKET_FILTER, filter);
        }

        @Override
        public void setFallbackMulticastFilter(boolean enabled) {
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_SET_FALLBACK_PACKET_FILTERING,
                                      enabled);
        }

        @Override
        public void setNeighborDiscoveryOffload(boolean enabled) {
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_CONFIG_ND_OFFLOAD,
                                      (enabled ? 1 : 0));
        }
    }

}
