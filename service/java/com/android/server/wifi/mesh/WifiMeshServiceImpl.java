/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.wifi.mesh;

import java.util.ArrayList;
import java.util.Properties;
import java.util.regex.Pattern;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.DhcpStateMachine;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.wifi.ScanResult;
import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiNative;
import android.net.wifi.WifiSsid;
import com.android.server.wifi.WifiStateMachine;
import android.net.wifi.mesh.IWifiMeshManager;
import android.net.wifi.mesh.WifiMeshManager;
import android.net.wifi.mesh.WifiMeshGroup;
import android.net.wifi.mesh.WifiMeshGroupList;
import android.net.wifi.mesh.WifiMeshScanResults;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

/**
 * WifiMeshService includes a state machine to perform Wi-Fi mesh operations.
 * Applications communicate with this service to issue scan and connectivity
 * requests through the WifiMeshManager interface. The state machine
 * communicates with the wifi driver through wpa_supplicant and handles the
 * event responses through WifiMonitor.
 *
 * Note that the term Wifi when used without a mesh suffix refers to the client
 * mode of Wifi operation
 * @hide
 */
public final class WifiMeshServiceImpl extends IWifiMeshManager.Stub {

    private static final String TAG = "WifiMeshService";
    private static final boolean DBG = false;
    private static final String NETWORKTYPE = "WIFI_MESH";
    private static final String ID_STR = "id=";
    private static final String BSSID_STR = "bssid=";
    private static final String FREQ_STR = "freq=";
    private static final String LEVEL_STR = "level=";
    private static final String TSF_STR = "tsf=";
    private static final String MESH_STR = "mesh_id=";
    private static final String DELIMITER_STR = "====";
    private static final String END_STR = "####";

    private final Context mContext;
    private final String mInterface;
    INetworkManagementService mNwService;
    private DhcpStateMachine mDhcpStateMachine;

    private final NetworkInfo mNetworkInfo;
    private final boolean mMeshSupported;

    private final MeshStateMachine mMeshStateMachine;
    private final AsyncChannel mReplyChannel = new AsyncChannel();
    private AsyncChannel mWifiChannel;

    private static final String ACTION_SAFE_WIFI_CHANNELS_CHANGED =
            "qualcomm.intent.action.SAFE_WIFI_CHANNELS_CHANGED";

    private static final int BASE = Protocol.BASE_WIFI_MESH_SERVICE;

    /*
     * During dhcp (and perhaps other times) we can't afford to drop packets
     * but Discovery will switch our channel enough we will.
     * msg.arg1 = ENABLED for blocking, DISABLED for resumed.
     * msg.arg2 = msg to send when blocked
     * msg.obj  = StateMachine to send to when blocked
     */
    public static final int BLOCK_DISCOVERY                 =   BASE + 1;

    public WifiMeshServiceImpl(Context context) {
        mContext = context;

        mInterface = SystemProperties.get("wifi.meshinterface", "mesh0");
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI_MESH, 0, NETWORKTYPE, "");

        //TODO Get the capability from system feature.
        mMeshSupported = true;
        /*
        mMeshSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_MESH);
         */

        mMeshStateMachine = new MeshStateMachine(TAG, mMeshSupported);
        mMeshStateMachine.start();

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SAFE_WIFI_CHANNELS_CHANGED);
        mContext.registerReceiver(new WifiStateReceiver(), filter);
    }

    public void connectivityServiceReady() {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "WifiMeshService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiMeshService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "WifiMeshService");
    }

    @Override
    public Messenger getMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mMeshStateMachine.getHandler());
    }

    private class WifiStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_SAFE_WIFI_CHANNELS_CHANGED)) {
                 Slog.d(TAG, "Received WIFI_CHANNELS_CHANGED broadcast");
                 //TODO  Restart mesh group with safe channel list received
                 // from android telephony layer, whenever mesh group is
                 // operating on an channel interfering with LTE.
            }
        }
    }

    /**
     * Handles interaction with WifiStateMachine
     */
    private class MeshStateMachine extends StateMachine {

        private final DefaultState mDefaultState = new DefaultState();
        private final MeshNotSupportedState mMeshNotSupportedState = new MeshNotSupportedState();
        private final MeshDisablingState mMeshDisablingState = new MeshDisablingState();
        private final MeshDisabledState mMeshDisabledState = new MeshDisabledState();
        private final MeshEnablingState mMeshEnablingState = new MeshEnablingState();
        private final MeshEnabledState mMeshEnabledState = new MeshEnabledState();
        // Inactive is when mesh is enabled with no connectivity
        private final MeshInactiveState mInactiveState = new MeshInactiveState();
        private final MeshJoinedState mJoinedState = new MeshJoinedState();

        private final WifiMeshGroupList mConfigs = new WifiMeshGroupList();
        private WifiMeshGroup mGroup;
        private WifiMeshScanResults mScanResults = new WifiMeshScanResults();
        private final Pattern scanResultPattern = Pattern.compile("\t+");

        private final WifiNative mWifiNative = new WifiNative(mInterface);
        private final WifiMonitor mWifiMonitor = new WifiMonitor(this, mWifiNative);

        MeshStateMachine(String name, boolean meshSupported) {
            super(name);

            addState(mDefaultState);
                addState(mMeshNotSupportedState, mDefaultState);
                addState(mMeshDisablingState, mDefaultState);
                addState(mMeshDisabledState, mDefaultState);
                addState(mMeshEnablingState, mDefaultState);
                addState(mMeshEnabledState, mDefaultState);
                    addState(mInactiveState, mMeshEnabledState);
                    addState(mJoinedState, mMeshEnabledState);

            if (meshSupported) {
                setInitialState(mMeshDisabledState);
            } else {
                setInitialState(mMeshNotSupportedState);
            }
            setLogRecSize(50);
            setLogOnlyTransitions(true);
        }


        class DefaultState extends State {
            @Override
            public boolean processMessage(Message message) {
                if (DBG) logd(getName() + message.toString());
                switch (message.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            if (DBG) logd("Full connection with WifiStateMachine established");
                            mWifiChannel = (AsyncChannel) message.obj;
                        } else {
                            loge("Full connection failure, error = " + message.arg1);
                            mWifiChannel = null;
                        }
                        break;

                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (message.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                            loge("Send failed, client connection lost");
                        } else {
                            loge("Client connection lost with reason: " + message.arg1);
                        }
                        mWifiChannel = null;
                        break;

                    case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                        AsyncChannel ac = new AsyncChannel();
                        ac.connect(mContext, getHandler(), message.replyTo);
                        break;
                    case BLOCK_DISCOVERY:
                        //TODO
                        /*
                         * When station is connecting to AP i.e wifi state is either
                         * connecting or obtaing ip address, Wi-Fi state machine will
                         * send BLOCK_DISCOVERY event to P2P service manager and discovery
                         * is postponed until DHCP success/failure. During this if we
                         * receive DISCOVER_PEERS command then p2p service manager will
                         * return discovery failure because "mDiscoveryPostponed"
                         * variable is not getting updated properly due to improper
                         * condition checking.
                         */
                        break;
                    case WifiMeshManager.SCAN:
                        replyToMessage(message, WifiMeshManager.SCAN_FAILED,
                                WifiMeshManager.BUSY);
                        break;
                    case WifiMeshManager.CONNECT_NETWORK:
                        replyToMessage(message, WifiMeshManager.CONNECT_NETWORK_FAILED,
                                WifiMeshManager.BUSY);
                        break;
                    case WifiMeshManager.FORGET_NETWORK:
                        replyToMessage(message, WifiMeshManager.FORGET_NETWORK_FAILED,
                                WifiMeshManager.BUSY);
                        break;
                    case WifiMeshManager.REQUEST_SCAN_RESULTS:
                        replyToMessage(message, WifiMeshManager.RESPONSE_SCAN_RESULTS,
                                new WifiMeshScanResults(mScanResults));
                        break;
                    case WifiMeshManager.REQUEST_GROUP_CONFIGS:
                        replyToMessage(message, WifiMeshManager.RESPONSE_GROUP_CONFIGS,
                                new WifiMeshGroupList(mConfigs));
                        break;
                    case WifiStateMachine.CMD_DISABLE_MESH_REQ:
                        mWifiChannel.sendMessage(WifiStateMachine.CMD_DISABLE_MESH_RSP);
                        break;
                    case WifiStateMachine.CMD_ENABLE_MESH_REQ:
                        mWifiChannel.sendMessage(WifiStateMachine.CMD_ENABLE_MESH_RSP, -1);
                        break;
                        //TODO Ignore
                    case WifiMonitor.SCAN_RESULTS_EVENT:
                    case WifiMonitor.SUP_CONNECTION_EVENT:
                    case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                    case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                    case DhcpStateMachine.CMD_ON_QUIT:
                        break;
                    default:
                        loge("Unhandled message " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MeshNotSupportedState extends State {
            @Override
            public void enter() {
                Log.i(TAG, "Enter " + getName());
                sendStateChangedEvent(WifiMeshManager.WIFI_MESH_STATE_FAILED);
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                case WifiMeshManager.SCAN:
                    replyToMessage(message, WifiMeshManager.SCAN_FAILED,
                            WifiMeshManager.MESH_UNSUPPORTED);
                    break;
                case WifiMeshManager.CONNECT_NETWORK:
                    replyToMessage(message, WifiMeshManager.CONNECT_NETWORK_FAILED,
                            WifiMeshManager.MESH_UNSUPPORTED);
                    break;
                case WifiMeshManager.FORGET_NETWORK:
                    replyToMessage(message, WifiMeshManager.FORGET_NETWORK_FAILED,
                            WifiMeshManager.MESH_UNSUPPORTED);
                    break;
                default:
                    return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MeshDisablingState extends State {
            @Override
            public void enter() {
                Log.i(TAG, "Enter " + getName());
                sendStateChangedEvent(WifiMeshManager.WIFI_MESH_STATE_DISABLING);

                // TODO : fix to send this event from WifiMonitor.
                sendMessage(WifiMonitor.SUP_DISCONNECTION_EVENT);
            }

            @Override
            public boolean processMessage(Message message) {
                if (DBG) logd(getName() + message.toString());
                switch (message.what) {
                case WifiStateMachine.CMD_ENABLE_MESH_REQ:
                case WifiStateMachine.CMD_DISABLE_MESH_REQ:
                    deferMessage(message);
                    break;
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    Log.i(TAG, "SUP_CONNECTION_EVENT");
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    Log.i(TAG, "SUP_DISCONNECTION_EVENT");
                    handleSupplicantConnectionLoss();
                    transitionTo(mMeshDisabledState);
                    mWifiChannel.sendMessage(WifiStateMachine.CMD_DISABLE_MESH_RSP);
                    break;
                default:
                    return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MeshDisabledState extends State {
            @Override
            public void enter() {
                Log.i(TAG, "Enter " + getName());
                sendStateChangedEvent(WifiMeshManager.WIFI_MESH_STATE_DISABLED);
            }

            @Override
            public boolean processMessage(Message message) {
                if (DBG) logd(getName() + message.toString());
                switch (message.what) {
                case WifiStateMachine.CMD_ENABLE_MESH_REQ:
                    if (!WifiNative.startSupplicant(WifiStateMachine.IFACE_TYPE_MESH)) {
                        transitionTo(mMeshDisabledState);
                        mWifiChannel.sendMessage(WifiStateMachine.CMD_ENABLE_MESH_RSP, -1);
                        break;
                    }
                    mWifiMonitor.startMonitoring();
                    transitionTo(mMeshEnablingState);
                    break;
                default:
                    return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MeshEnablingState extends State {
            @Override
            public void enter() {
                Log.i(TAG, "Enter " + getName());
                sendStateChangedEvent(WifiMeshManager.WIFI_MESH_STATE_ENABLING);
            }

            @Override
            public boolean processMessage(Message message) {
                if (DBG) logd(getName() + message.toString());
                switch (message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    Log.i(TAG, "SUP_CONNECTION_EVENT");
                    transitionTo(mInactiveState);
                    mWifiChannel.sendMessage(WifiStateMachine.CMD_ENABLE_MESH_RSP, 0);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    Log.i(TAG, "SUP_DISCONNECTION_EVENT");
                    handleSupplicantConnectionLoss();
                    transitionTo(mMeshDisabledState);
                    mWifiChannel.sendMessage(WifiStateMachine.CMD_ENABLE_MESH_RSP, -1);
                    break;
                case WifiStateMachine.CMD_ENABLE_MESH_REQ:
                case WifiStateMachine.CMD_DISABLE_MESH_REQ:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MeshEnabledState extends State {
            @Override
            public void enter() {
                Log.i(TAG, "Enter " + getName());
                sendStateChangedEvent(WifiMeshManager.WIFI_MESH_STATE_ENABLED);
                updateConfigurations(true);
            }

            @Override
            public void exit() {
                mConfigs.clear();
            }

            @Override
            public boolean processMessage(Message message) {
                if (DBG) logd(getName() + message.toString());
                switch (message.what) {
                case WifiStateMachine.CMD_ENABLE_MESH_REQ:
                    Log.i(TAG, "already enabled");
                    mWifiChannel.sendMessage(WifiStateMachine.CMD_ENABLE_MESH_RSP);
                    break;
                case WifiStateMachine.CMD_DISABLE_MESH_REQ:
                    WifiNative.killSupplicant(WifiStateMachine.IFACE_TYPE_MESH);
                    transitionTo(mMeshDisablingState);
                    break;
                case WifiMeshManager.SCAN:
                    if (!mWifiNative.scan()) {
                        replyToMessage(message, WifiMeshManager.SCAN_FAILED,
                                WifiMeshManager.BUSY);
                        break;
                    }
                    replyToMessage(message, WifiMeshManager.SCAN_SUCCEEDED);
                    break;
                case WifiMeshManager.FORGET_NETWORK:
                    if (mWifiNative.removeNetwork(message.arg1)) {
                        replyToMessage(message, WifiMeshManager.FORGET_NETWORK_SUCCEEDED);
                        mConfigs.remove(message.arg1);
                        updateConfigurations(true);
                    } else {
                        replyToMessage(message, WifiMeshManager.FORGET_NETWORK_FAILED);
                    }
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    Log.i(TAG, "suddenly SUP_DISCONNECTION_EVENT");
                    handleSupplicantConnectionLoss();
                    transitionTo(mMeshDisabledState);
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                    Log.i(TAG, "SCAN_RESULTS_EVENT");
                    setScanResults();
                    sendScanResultsAvailableEvent();
                    break;
                default:
                    return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MeshInactiveState extends State {
            @Override
            public void enter() {
                Log.i(TAG, "Enter " + getName());
            }

            @Override
            public boolean processMessage(Message message) {
                if (DBG) logd(getName() + message.toString());
                switch (message.what) {
                case WifiMeshManager.CONNECT_NETWORK:
                    if (connect(message.arg1, (WifiMeshGroup)message.obj)) {
                        replyToMessage(message, WifiMeshManager.CONNECT_NETWORK_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiMeshManager.CONNECT_NETWORK_FAILED,
                                WifiMeshManager.ERROR);
                    }
                    break;
                case WifiMeshManager.DISCONNECT_NETWORK:
                    Log.i(TAG, "DISCONNECT_NETWORK in InactiveState");
                    replyToMessage(message, WifiMeshManager.CONNECT_NETWORK_FAILED,
                            WifiMeshManager.BUSY);
                    break;
                case WifiMonitor.MESH_GROUP_STARTED:
                    Log.i(TAG, "MESH_GROUP_STARTED");
                    mGroup = (WifiMeshGroup)message.obj;
                    sendMeshConnectionChangedEvent();
                    SystemProperties.set("ctl.start", "join_mesh");    // to kick linux domain command per join mesh
                    transitionTo(mJoinedState);
                    break;
                default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MeshJoinedState extends State {
            @Override
            public void enter() {
                Log.i(TAG, "Enter " + getName());
                mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(mContext,
                        MeshStateMachine.this, mInterface);
                // TODO: We should use DHCP state machine PRE message like WifiStateMachine
                mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
            }

            @Override
            public boolean processMessage(Message message) {
                if (DBG) logd(getName() + message.toString());
                switch (message.what) {
                case WifiMeshManager.CONNECT_NETWORK:
                    Log.i(TAG, "CONNECT_NETWORK in Joined Status");
                    mWifiNative.meshGroupRemove();
                    deferMessage(message);
                    break;
                case WifiMeshManager.FORGET_NETWORK:
                    Log.i(TAG, "FORGET_NETWORK");
                    if (message.arg1 == mGroup.getNetworkId()) {
                        mWifiNative.meshGroupRemove();
                        mGroup = null;
                    }
                    // handled in upper state.
                    return NOT_HANDLED;
                case WifiMeshManager.DISCONNECT_NETWORK:
                    Log.i(TAG, "DISCONNECT_NETWORK in Joined Status");
                    mWifiNative.meshGroupRemove();
                    break;
                case WifiStateMachine.CMD_DISABLE_MESH_REQ:
                    mWifiNative.meshGroupRemove();
                    mWifiNative.disableNetwork(mGroup.getNetworkId());
                    mWifiNative.saveConfig();
                    mGroup = null;
                    // handled in upper state.
                    return NOT_HANDLED;
                case WifiMonitor.MESH_GROUP_STOPPED:
                    Log.i(TAG, "MESH_GROUP_STOPPED");
                    SystemProperties.set("ctl.start", "leave_mesh");    // to kick linux domain command per leave mesh
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.MESH_PEER_CONNECTED:
                    Log.i(TAG, "MESH_PEER_CONNECTED " + message.obj.toString());
                    sendMeshPeerConnected((String)message.obj);
                    break;
                case WifiMonitor.MESH_PEER_DISCONNECTED:
                    Log.i(TAG, "MESH_PEER_DISCONNECTED " + message.obj.toString());
                    sendMeshPeerDisconnected((String)message.obj);
                    break;
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                    DhcpResults dhcpResults = (DhcpResults) message.obj;
                    if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS &&
                            dhcpResults != null) {
                        if (DBG) logd("DhcpResults: " + dhcpResults);
                    } else {
                        loge("DHCP failed");
                        //mWifiNative.meshGroupRemove();
                    }
                    break;
                default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                if (DBG) logd("stop DHCP client");
                mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
                mDhcpStateMachine.doQuit();
                mDhcpStateMachine = null;
                try {
                    mNwService.clearInterfaceAddresses(mInterface);
                } catch (Exception e) {
                    loge("Failed to clear addresses " + e);
                }
                NetworkUtils.resetConnections(mInterface, NetworkUtils.RESET_ALL_ADDRESSES);

                if (mGroup != null) {
                    mWifiNative.disableNetwork(mGroup.getNetworkId());
                    mWifiNative.saveConfig();
                }
                mGroup = null;
                sendMeshConnectionChangedEvent();
            }
        }

        private boolean connect(int netId, WifiMeshGroup group) {
            Log.i(TAG, "connect() id=" + netId + " " + group);

            if (group == null) {
                Log.i(TAG, "meshGroupAdd() netId=" + netId);
                return mWifiNative.meshGroupAdd(netId);
            }

            if (group.getMeshId() == null) {
                return false;
            }
            if (group.isSecure() && group.getPassphrase() == null) {
                return false;
            }

            netId = mWifiNative.addNetwork();
            mWifiNative.setNetworkVariable(netId, "ssid", group.getMeshIdQuote());
            mWifiNative.setNetworkVariable(netId, "mode", "5");
            mWifiNative.setNetworkVariable(netId, "key_mgmt",
                    group.isSecure() ? "SAE" : "NONE");
            if (group.isSecure()) {
                mWifiNative.setNetworkVariable(netId, "psk", group.getPassphrase());
            }
            mWifiNative.setNetworkVariable(netId, "mode", "5");
            if (group.getFrequency() > 0) {
                mWifiNative.setNetworkVariable(netId, "frequency",
                        Integer.toString(group.getFrequency()));
            }
            mWifiNative.setNetworkVariable(netId, "dtim_period", "2");
            /*
            mWifiNative.setNetworkVariable(netId, "mesh_ht_mode", "HT20");
            */

            if (!mWifiNative.meshGroupAdd(netId)) {
                mWifiNative.removeNetwork(netId);
                return false;
            }
            updateConfigurations(false);

            return true;
        }

        /**
         * scanResults input format
         * ====
         * id=13
         * bssid=00:22:43:4c:10:fa
         * freq=2412
         * level=-53
         * tsf=0000004443248902
         * age=2
         * flags=
         * ssid=commell_2X_mmm
         * mesh_id=commell_2X_mmm
         * active_path_selection_protocol_id=0x01
         * active_path_selection_metric_id=0x01
         * congestion_control_mode_id=0x00
         * synchronization_method_id=0x01
         * authentication_protocol_id=0x00
         * mesh_formation_info=0x00
         * mesh_capability=0x09
         * bss_basic_rate_set=10 20 55 110 60 120 240
         */
        private void setScanResults() {
            String scanResults;
            String tmpResults;
            StringBuffer scanResultsBuf = new StringBuffer();
            int sid = 0;

            while (true) {
                tmpResults = mWifiNative.scanResults(sid, "0x61B87");
                if (TextUtils.isEmpty(tmpResults)) break;
                scanResultsBuf.append(tmpResults);
                scanResultsBuf.append("\n");
                String[] lines = tmpResults.split("\n");
                sid = -1;
                for (int i=lines.length - 1; i >= 0; i--) {
                    if (lines[i].startsWith(END_STR)) {
                        break;
                    } else if (lines[i].startsWith(ID_STR)) {
                        try {
                            sid = Integer.parseInt(lines[i].substring(ID_STR.length())) + 1;
                        } catch (NumberFormatException e) {
                            // Nothing to do
                        }
                        break;
                    }
                }
                if (sid == -1) break;
            }

            scanResults = scanResultsBuf.toString();
            if (TextUtils.isEmpty(scanResults)) {
               mScanResults.clear();
               return;
            }

            int freq = -1, level = -100;
            long tsf = 0;
            String bssid = null, mesh_id = null;
            String[] lines = scanResults.split("\n");
            final int bssidStrLen = BSSID_STR.length();

            mScanResults = new WifiMeshScanResults();
            for (String line : lines) {
                if (line.startsWith(BSSID_STR)) {
                    bssid = new String(line.getBytes(), bssidStrLen, line.length() - bssidStrLen);
                } else if (line.startsWith(FREQ_STR)) {
                    try {
                        freq = Integer.parseInt(line.substring(FREQ_STR.length()));
                    } catch (NumberFormatException e) {
                        freq = 0;
                    }
                } else if (line.startsWith(LEVEL_STR)) {
                    try {
                        level = Integer.parseInt(line.substring(LEVEL_STR.length()));
                        /* some implementations avoid negative values by adding 256
                         * so we need to adjust for that here.
                         */
                        if (level > 0) level -= 256;
                    } catch(NumberFormatException e) {
                        level = 0;
                    }
                } else if (line.startsWith(TSF_STR)) {
                    try {
                        tsf = Long.parseLong(line.substring(TSF_STR.length()));
                    } catch (NumberFormatException e) {
                        tsf = 0;
                    }
                } else if (line.startsWith(MESH_STR)) {
                    mesh_id = line.substring(MESH_STR.length());
                } else if (line.startsWith(DELIMITER_STR) || line.startsWith(END_STR)) {
                    if (bssid != null && mesh_id != null) {
                        mScanResults.update(new ScanResult(
                            WifiSsid.createFromAsciiEncoded(mesh_id), bssid,
                            "[MESH]", level, freq, tsf));
                    }
                    bssid = mesh_id = null;
                    level = freq = 0;
                    tsf = 0;
                }
            }
        }

        /**
         * Synchronize the network configuration list between
         * wpa_supplicant and mConfigs.
         */
        private void updateConfigurations(boolean reload) {
            String listStr = mWifiNative.listNetworks();
            if (listStr == null) return;
            boolean isSaveRequired = false;
            String[] lines = listStr.split("\n");
            if (lines == null) return;

            if (reload) mConfigs.clear();

            // Skip the first line, which is a header
            for (int i = 1; i < lines.length; i++) {
                String[] result = lines[i].split("\t");
                if (result == null || result.length < 3) {
                    continue;
                }
                // network-id | ssid | bssid | flags
                int netId = -1;
                String ssid = result[1];
                try {
                    netId = Integer.parseInt(result[0]);
                } catch(NumberFormatException e) {
                    e.printStackTrace();
                    continue;
                }

                if (mConfigs.contains(netId)) {
                    continue;
                }

                WifiMeshGroup group = new WifiMeshGroup();
                group.setMeshId(ssid);
                group.setNetworkId(netId);
                String val = mWifiNative.getNetworkVariable(netId, "key_mgmt");
                if (val != null && val.contains("SAE")) {
                    group.setSecure(true);
                }
                val = mWifiNative.getNetworkVariable(netId, "frequency");
                if (val != null) {
                    group.setFrequency(Integer.parseInt(val));
                }
                mConfigs.add(group);
                isSaveRequired = true;
            }

            if (reload || isSaveRequired) {
                mWifiNative.saveConfig();
                sendMeshGroupConfigChangedBroadcast();
            }
        }

        //State machine initiated requests can have replyTo set to null indicating
        //there are no recipients, we ignore those reply actions
        private void replyToMessage(Message msg, int what) {
            if (msg.replyTo == null) return;
            Message dstMsg = obtainMessage(msg);
            dstMsg.what = what;
            mReplyChannel.replyToMessage(msg, dstMsg);
        }

        private void replyToMessage(Message msg, int what, int arg1) {
            if (msg.replyTo == null) return;
            Message dstMsg = obtainMessage(msg);
            dstMsg.what = what;
            dstMsg.arg1 = arg1;
            mReplyChannel.replyToMessage(msg, dstMsg);
        }

        private void replyToMessage(Message msg, int what, Object obj) {
            if (msg.replyTo == null) return;
            Message dstMsg = obtainMessage(msg);
            dstMsg.what = what;
            dstMsg.obj = obj;
            mReplyChannel.replyToMessage(msg, dstMsg);
        }

        /* arg2 on the source message has a hash code that needs to be retained in replies
         * see WifiP2pManager for details */
        private Message obtainMessage(Message srcMsg) {
            Message msg = Message.obtain();
            msg.arg2 = srcMsg.arg2;
            return msg;
        }

        private void sendStateChangedEvent(int state) {
            final Intent intent = new Intent(WifiMeshManager.WIFI_MESH_STATE_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiMeshManager.EXTRA_WIFI_MESH_STATE, state);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            Log.i(TAG, "send state changed event. state=" + state);
        }

        private void sendScanResultsAvailableEvent() {
            final Intent intent = new Intent(WifiMeshManager.WIFI_MESH_SCAN_RESULTS_AVAILABLE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            Log.i(TAG, "send scan results available event.");
        }

        private void sendMeshGroupConfigChangedBroadcast() {
            if (DBG) logd("sending mesh groups changed broadcast");
            Intent intent = new Intent(WifiMeshManager.WIFI_MESH_GROUP_CONFIG_AVAILABLE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            Log.i(TAG, "send scan mesh groups available event.");
        }

        private void sendMeshPeerConnected(String addr) {
            final Intent intent = new Intent(WifiMeshManager.WIFI_MESH_PEER_CONNECTED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiMeshManager.EXTRA_WIFI_MESH_ADDR, addr);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            Log.i(TAG, "send peer connected event. addr=" + addr);
        }

        private void sendMeshPeerDisconnected(String addr) {
            final Intent intent = new Intent(WifiMeshManager.WIFI_MESH_PEER_DISCONNECTED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiMeshManager.EXTRA_WIFI_MESH_ADDR, addr);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            Log.i(TAG, "send peer disconnected event. addr=" + addr);
        }

        private void sendMeshConnectionChangedEvent() {
            Intent intent = new Intent(WifiMeshManager.WIFI_MESH_CONNECTION_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                    | Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(WifiMeshManager.EXTRA_WIFI_MESH_GROUP, mGroup == null ?
                    null : new WifiMeshGroup(mGroup));
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void handleSupplicantConnectionLoss() {
            mWifiNative.closeSupplicantConnection();
        }
    }
}
