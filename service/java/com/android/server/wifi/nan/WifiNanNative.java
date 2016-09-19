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

package com.android.server.wifi.nan;

import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanDiscoverySessionCallback;
import android.net.wifi.nan.WifiNanEventCallback;
import android.util.Log;

import com.android.server.wifi.WifiNative;

import libcore.util.HexEncoding;

import java.util.Arrays;

/**
 * Native calls to access the Wi-Fi NAN HAL.
 *
 * Relies on WifiNative to perform the actual HAL registration.
 */
public class WifiNanNative {
    private static final String TAG = "WifiNanNative";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int WIFI_SUCCESS = 0;

    private static WifiNanNative sWifiNanNativeSingleton;

    private boolean mNativeHandlersIsInitialized = false;

    private static native int registerNanNatives();

    /**
     * Returns the singleton WifiNanNative used to manage the actual NAN HAL
     * interface.
     *
     * @return Singleton object.
     */
    public static WifiNanNative getInstance() {
        // dummy reference - used to make sure that WifiNative is loaded before
        // us since it is the one to load the shared library and starts its
        // initialization.
        WifiNative dummy = WifiNative.getWlanNativeInterface();
        if (dummy == null) {
            Log.w(TAG, "can't get access to WifiNative");
            return null;
        }

        if (sWifiNanNativeSingleton == null) {
            sWifiNanNativeSingleton = new WifiNanNative();
            registerNanNatives();
        }

        return sWifiNanNativeSingleton;
    }

    /**
     * A container class for NAN (vendor) implementation capabilities (or
     * limitations). Filled-in by the firmware.
     */
    public static class Capabilities {
        public int maxConcurrentNanClusters;
        public int maxPublishes;
        public int maxSubscribes;
        public int maxServiceNameLen;
        public int maxMatchFilterLen;
        public int maxTotalMatchFilterLen;
        public int maxServiceSpecificInfoLen;
        public int maxVsaDataLen;
        public int maxMeshDataLen;
        public int maxNdiInterfaces;
        public int maxNdpSessions;
        public int maxAppInfoLen;
        public int maxQueuedTransmitMessages;

        @Override
        public String toString() {
            return "Capabilities [maxConcurrentNanClusters=" + maxConcurrentNanClusters
                    + ", maxPublishes=" + maxPublishes + ", maxSubscribes=" + maxSubscribes
                    + ", maxServiceNameLen=" + maxServiceNameLen + ", maxMatchFilterLen="
                    + maxMatchFilterLen + ", maxTotalMatchFilterLen=" + maxTotalMatchFilterLen
                    + ", maxServiceSpecificInfoLen=" + maxServiceSpecificInfoLen
                    + ", maxVsaDataLen=" + maxVsaDataLen + ", maxMeshDataLen=" + maxMeshDataLen
                    + ", maxNdiInterfaces=" + maxNdiInterfaces + ", maxNdpSessions="
                    + maxNdpSessions + ", maxAppInfoLen=" + maxAppInfoLen
                    + ", maxQueuedTransmitMessages=" + maxQueuedTransmitMessages + "]";
        }
    }

    /* package */ static native int initNanHandlersNative(Class<WifiNative> cls, int iface);

    private boolean isNanInit() {
        synchronized (WifiNative.sLock) {
            if (!WifiNative.getWlanNativeInterface().isHalStarted()) {
                /*
                 * We should never start the HAL - that's done at a higher level
                 * by the Wi-Fi state machine.
                 */
                mNativeHandlersIsInitialized = false;
                return false;
            } else if (!mNativeHandlersIsInitialized) {
                int ret = initNanHandlersNative(WifiNative.class, WifiNative.sWlan0Index);
                if (DBG) Log.d(TAG, "initNanHandlersNative: res=" + ret);
                mNativeHandlersIsInitialized = ret == WIFI_SUCCESS;

                return mNativeHandlersIsInitialized;
            } else {
                return true;
            }
        }
    }

    /**
     * Tell the NAN JNI to re-initialize the NAN callback pointers next time it starts up.
     */
    public void deInitNan() {
        if (VDBG) {
            Log.v(TAG, "deInitNan: mNativeHandlersIsInitialized=" + mNativeHandlersIsInitialized);
        }
        mNativeHandlersIsInitialized = false;
    }

    private WifiNanNative() {
        // do nothing
    }

    private static native int getCapabilitiesNative(short transactionId, Class<WifiNative> cls,
            int iface);

    /**
     * Query the NAN firmware's capabilities.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     */
    public boolean getCapabilities(short transactionId) {
        if (VDBG) Log.d(TAG, "getCapabilities");
        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = getCapabilitiesNative(transactionId, WifiNative.class,
                        WifiNative.sWlan0Index);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "getCapabilities: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "getCapabilities: cannot initialize NAN");
            return false;
        }
    }

    private static native int enableAndConfigureNative(short transactionId, Class<WifiNative> cls,
            int iface, ConfigRequest configRequest);

    private static native int updateConfigurationNative(short transactionId, Class<WifiNative> cls,
            int iface, ConfigRequest configRequest);

    /**
     * Enable and configure NAN.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param configRequest Requested NAN configuration.
     * @param initialConfiguration Specifies whether initial configuration
     *            (true) or an update (false) to the configuration.
     */
    public boolean enableAndConfigure(short transactionId, ConfigRequest configRequest,
            boolean initialConfiguration) {
        if (VDBG) Log.d(TAG, "enableAndConfigure: configRequest=" + configRequest);
        if (isNanInit()) {
            int ret;
            if (initialConfiguration) {
                synchronized (WifiNative.sLock) {
                    ret = enableAndConfigureNative(transactionId, WifiNative.class,
                            WifiNative.sWlan0Index, configRequest);
                }
                if (ret != WIFI_SUCCESS) {
                    Log.w(TAG, "enableAndConfigureNative: HAL API returned non-success -- " + ret);
                }
            } else {
                synchronized (WifiNative.sLock) {
                    ret = updateConfigurationNative(transactionId, WifiNative.class,
                            WifiNative.sWlan0Index, configRequest);
                }
                if (ret != WIFI_SUCCESS) {
                    Log.w(TAG, "updateConfigurationNative: HAL API returned non-success -- " + ret);
                }
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "enableAndConfigure: NanInit fails");
            return false;
        }
    }

    private static native int disableNative(short transactionId, Class<WifiNative> cls, int iface);

    /**
     * Disable NAN.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     */
    public boolean disable(short transactionId) {
        if (VDBG) Log.d(TAG, "disableNan");
        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = disableNative(transactionId, WifiNative.class, WifiNative.sWlan0Index);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "disableNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "disable: cannot initialize NAN");
            return false;
        }
    }

    private static native int publishNative(short transactionId, int publishId,
            Class<WifiNative> cls, int iface, PublishConfig publishConfig);

    /**
     * Start or modify a service publish session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param publishId ID of the requested session - 0 to request a new publish
     *            session.
     * @param publishConfig Configuration of the discovery session.
     */
    public boolean publish(short transactionId, int publishId, PublishConfig publishConfig) {
        if (VDBG) {
            Log.d(TAG, "publish: transactionId=" + transactionId + ", config=" + publishConfig);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = publishNative(transactionId, publishId, WifiNative.class,
                        WifiNative.sWlan0Index, publishConfig);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "publishNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "publish: cannot initialize NAN");
            return false;
        }
    }

    private static native int subscribeNative(short transactionId, int subscribeId,
            Class<WifiNative> cls, int iface, SubscribeConfig subscribeConfig);

    /**
     * Start or modify a service subscription session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param subscribeId ID of the requested session - 0 to request a new
     *            subscribe session.
     * @param subscribeConfig Configuration of the discovery session.
     */
    public boolean subscribe(short transactionId, int subscribeId,
            SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.d(TAG, "subscribe: transactionId=" + transactionId + ", config=" + subscribeConfig);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = subscribeNative(transactionId, subscribeId, WifiNative.class,
                        WifiNative.sWlan0Index, subscribeConfig);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "subscribeNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "subscribe: cannot initialize NAN");
            return false;
        }
    }

    private static native int sendMessageNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId, int requestorInstanceId, byte[] dest, byte[] message);

    /**
     * Send a message through an existing discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the existing publish/subscribe session.
     * @param requestorInstanceId ID of the peer to communicate with - obtained
     *            through a previous discovery (match) operation with that peer.
     * @param dest MAC address of the peer to communicate with - obtained
     *            together with requestorInstanceId.
     * @param message Message.
     * @param messageId Arbitary integer from host (not sent to HAL - useful for
     *                  testing/debugging at this level)
     */
    public boolean sendMessage(short transactionId, int pubSubId, int requestorInstanceId,
            byte[] dest, byte[] message, int messageId) {
        if (VDBG) {
            Log.d(TAG,
                    "sendMessage: transactionId=" + transactionId + ", pubSubId=" + pubSubId
                            + ", requestorInstanceId=" + requestorInstanceId + ", dest="
                            + String.valueOf(HexEncoding.encode(dest)) + ", messageId="
                            + messageId);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = sendMessageNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId, requestorInstanceId, dest, message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "sendMessageNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "sendMessage: cannot initialize NAN");
            return false;
        }
    }

    private static native int stopPublishNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId);

    /**
     * Terminate a publish discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopPublish(short transactionId, int pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopPublish: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = stopPublishNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "stopPublishNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "stopPublish: cannot initialize NAN");
            return false;
        }
    }

    private static native int stopSubscribeNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId);

    /**
     * Terminate a subscribe discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopSubscribe(short transactionId, int pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopSubscribe: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = stopSubscribeNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "stopSubscribeNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "stopSubscribe: cannot initialize NAN");
            return false;
        }
    }

    private static native int createNanNetworkInterfaceNative(short transactionId,
                                                              Class<WifiNative> cls, int iface,
                                                              String interfaceName);

    /**
     * Create a NAN network interface. This only creates the Linux interface - it doesn't actually
     * create the data connection.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "nan0".
     */
    public boolean createNanNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "createNanNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = createNanNetworkInterfaceNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, interfaceName);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG,
                        "createNanNetworkInterfaceNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "createNanNetworkInterface: cannot initialize NAN");
            return false;
        }
    }

    private static native int deleteNanNetworkInterfaceNative(short transactionId,
                                                              Class<WifiNative> cls, int iface,
                                                              String interfaceName);

    /**
     * Deletes a NAN network interface. The data connection can (should?) be torn down previously.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "nan0".
     */
    public boolean deleteNanNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "deleteNanNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = deleteNanNetworkInterfaceNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, interfaceName);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG,
                        "deleteNanNetworkInterfaceNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "deleteNanNetworkInterface: cannot initialize NAN");
            return false;
        }
    }

    private static native int initiateDataPathNative(short transactionId, Class<WifiNative> cls,
            int iface, int peerId, int channelRequestType, int channel, byte[] peer,
            String interfaceName, byte[] message);

    public static final int CHANNEL_REQUEST_TYPE_NONE = 0;
    public static final int CHANNEL_REQUEST_TYPE_REQUESTED = 1;
    public static final int CHANNEL_REQUEST_TYPE_REQUIRED = 2;

    /**
     * Initiates setting up a data-path between device and peer.
     *
     * @param transactionId      Transaction ID for the transaction - used in the async callback to
     *                           match with the original request.
     * @param peerId             ID of the peer ID to associate the data path with. A value of 0
     *                           indicates that not associated with an existing session.
     * @param channelRequestType Indicates whether the specified channel is available, if available
     *                           requested or forced (resulting in failure if cannot be
     *                           accommodated).
     * @param channel            The channel on which to set up the data-path.
     * @param peer               The MAC address of the peer to create a connection with.
     * @param interfaceName      The interface on which to create the data connection.
     * @param message An arbitrary byte array to forward to the peer as part of the data path
     *                request.
     */
    public boolean initiateDataPath(short transactionId, int peerId, int channelRequestType,
            int channel, byte[] peer, String interfaceName, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "initiateDataPath: transactionId=" + transactionId + ", peerId=" + peerId
                    + ", channelRequestType=" + channelRequestType + ", channel=" + channel
                    + ", peer=" + String.valueOf(HexEncoding.encode(peer)) + ", interfaceName="
                    + interfaceName);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = initiateDataPathNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, peerId, channelRequestType, channel, peer, interfaceName,
                        message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "initiateDataPathNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "initiateDataPath: cannot initialize NAN");
            return false;
        }
    }

    private static native int respondToDataPathRequestNative(short transactionId,
            Class<WifiNative> cls, int iface, boolean accept, int ndpId, String interfaceName,
            byte[] message);

    /**
     * Responds to a data request from a peer.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param accept Accept (true) or reject (false) the original call.
     * @param ndpId The NDP (NAN data path) ID. Obtained from the request callback.
     * @param interfaceName The interface on which the data path will be setup. Obtained from the
     *                      request callback.
     * @param message An arbitrary byte array to forward to the peer in the respond message.
     */
    public boolean respondToDataPathRequest(short transactionId, boolean accept, int ndpId,
            String interfaceName, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "respondToDataPathRequest: transactionId=" + transactionId + ", accept="
                    + accept + ", int ndpId=" + ndpId + ", interfaceName=" + interfaceName);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = respondToDataPathRequestNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, accept, ndpId, interfaceName, message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG,
                        "respondToDataPathRequestNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "respondToDataPathRequest: cannot initialize NAN");
            return false;
        }
    }

    private static native int endDataPathNative(short transactionId, Class<WifiNative> cls,
            int iface, int ndpId);

    /**
     * Terminate an existing data-path (does not delete the interface).
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param ndpId The NDP (NAN data path) ID to be terminated.
     */
    public boolean endDataPath(short transactionId, int ndpId) {
        if (VDBG) {
            Log.v(TAG, "endDataPath: transactionId=" + transactionId + ", ndpId=" + ndpId);
        }

        if (isNanInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = endDataPathNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        ndpId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "endDataPathNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "endDataPath: cannot initialize NAN");
            return false;
        }
    }

    // EVENTS

    // NanResponseType for API responses: will add values as needed
    public static final int NAN_RESPONSE_ENABLED = 0;
    public static final int NAN_RESPONSE_PUBLISH = 2;
    public static final int NAN_RESPONSE_PUBLISH_CANCEL = 3;
    public static final int NAN_RESPONSE_TRANSMIT_FOLLOWUP = 4;
    public static final int NAN_RESPONSE_SUBSCRIBE = 5;
    public static final int NAN_RESPONSE_SUBSCRIBE_CANCEL = 6;
    public static final int NAN_RESPONSE_CONFIG = 8;
    public static final int NAN_RESPONSE_GET_CAPABILITIES = 12;
    public static final int NAN_RESPONSE_DP_INTERFACE_CREATE = 13;
    public static final int NAN_RESPONSE_DP_INTERFACE_DELETE = 14;
    public static final int NAN_RESPONSE_DP_INITIATOR_RESPONSE = 15;
    public static final int NAN_RESPONSE_DP_RESPONDER_RESPONSE = 16;
    public static final int NAN_RESPONSE_DP_END = 17;

    // direct copy from wifi_nan.h: need to keep in sync
    /* NAN Protocol Response Codes */
    public static final int NAN_STATUS_SUCCESS = 0;
    public static final int NAN_STATUS_TIMEOUT = 1;
    public static final int NAN_STATUS_DE_FAILURE = 2;
    public static final int NAN_STATUS_INVALID_MSG_VERSION = 3;
    public static final int NAN_STATUS_INVALID_MSG_LEN = 4;
    public static final int NAN_STATUS_INVALID_MSG_ID = 5;
    public static final int NAN_STATUS_INVALID_HANDLE = 6;
    public static final int NAN_STATUS_NO_SPACE_AVAILABLE = 7;
    public static final int NAN_STATUS_INVALID_PUBLISH_TYPE = 8;
    public static final int NAN_STATUS_INVALID_TX_TYPE = 9;
    public static final int NAN_STATUS_INVALID_MATCH_ALGORITHM = 10;
    public static final int NAN_STATUS_DISABLE_IN_PROGRESS = 11;
    public static final int NAN_STATUS_INVALID_TLV_LEN = 12;
    public static final int NAN_STATUS_INVALID_TLV_TYPE = 13;
    public static final int NAN_STATUS_MISSING_TLV_TYPE = 14;
    public static final int NAN_STATUS_INVALID_TOTAL_TLVS_LEN = 15;
    public static final int NAN_STATUS_INVALID_MATCH_HANDLE = 16;
    public static final int NAN_STATUS_INVALID_TLV_VALUE = 17;
    public static final int NAN_STATUS_INVALID_TX_PRIORITY = 18;
    public static final int NAN_STATUS_INVALID_CONNECTION_MAP = 19;
    public static final int NAN_STATUS_INVALID_TCA_ID = 20;
    public static final int NAN_STATUS_INVALID_STATS_ID = 21;
    public static final int NAN_STATUS_NAN_NOT_ALLOWED = 22;
    public static final int NAN_STATUS_NO_OTA_ACK = 23;
    public static final int NAN_STATUS_TX_FAIL = 24;
    public static final int NAN_STATUS_ALREADY_ENABLED = 25;

    /* NAN Configuration Response codes */
    public static final int NAN_STATUS_INVALID_RSSI_CLOSE_VALUE = 4096;
    public static final int NAN_STATUS_INVALID_RSSI_MIDDLE_VALUE = 4097;
    public static final int NAN_STATUS_INVALID_HOP_COUNT_LIMIT = 4098;
    public static final int NAN_STATUS_INVALID_MASTER_PREFERENCE_VALUE = 4099;
    public static final int NAN_STATUS_INVALID_LOW_CLUSTER_ID_VALUE = 4100;
    public static final int NAN_STATUS_INVALID_HIGH_CLUSTER_ID_VALUE = 4101;
    public static final int NAN_STATUS_INVALID_BACKGROUND_SCAN_PERIOD = 4102;
    public static final int NAN_STATUS_INVALID_RSSI_PROXIMITY_VALUE = 4103;
    public static final int NAN_STATUS_INVALID_SCAN_CHANNEL = 4104;
    public static final int NAN_STATUS_INVALID_POST_NAN_CONNECTIVITY_CAPABILITIES_BITMAP = 4105;
    public static final int NAN_STATUS_INVALID_FA_MAP_NUMCHAN_VALUE = 4106;
    public static final int NAN_STATUS_INVALID_FA_MAP_DURATION_VALUE = 4107;
    public static final int NAN_STATUS_INVALID_FA_MAP_CLASS_VALUE = 4108;
    public static final int NAN_STATUS_INVALID_FA_MAP_CHANNEL_VALUE = 4109;
    public static final int NAN_STATUS_INVALID_FA_MAP_AVAILABILITY_INTERVAL_BITMAP_VALUE = 4110;
    public static final int NAN_STATUS_INVALID_FA_MAP_MAP_ID = 4111;
    public static final int NAN_STATUS_INVALID_POST_NAN_DISCOVERY_CONN_TYPE_VALUE = 4112;
    public static final int NAN_STATUS_INVALID_POST_NAN_DISCOVERY_DEVICE_ROLE_VALUE = 4113;
    public static final int NAN_STATUS_INVALID_POST_NAN_DISCOVERY_DURATION_VALUE = 4114;
    public static final int NAN_STATUS_INVALID_POST_NAN_DISCOVERY_BITMAP_VALUE = 4115;
    public static final int NAN_STATUS_MISSING_FUTHER_AVAILABILITY_MAP = 4116;
    public static final int NAN_STATUS_INVALID_BAND_CONFIG_FLAGS = 4117;
    public static final int NAN_STATUS_INVALID_RANDOM_FACTOR_UPDATE_TIME_VALUE = 4118;
    public static final int NAN_STATUS_INVALID_ONGOING_SCAN_PERIOD = 4119;
    public static final int NAN_STATUS_INVALID_DW_INTERVAL_VALUE = 4120;
    public static final int NAN_STATUS_INVALID_DB_INTERVAL_VALUE = 4121;

    /* publish/subscribe termination reasons */
    public static final int NAN_TERMINATED_REASON_INVALID = 8192;
    public static final int NAN_TERMINATED_REASON_TIMEOUT = 8193;
    public static final int NAN_TERMINATED_REASON_USER_REQUEST = 8194;
    public static final int NAN_TERMINATED_REASON_FAILURE = 8195;
    public static final int NAN_TERMINATED_REASON_COUNT_REACHED = 8196;
    public static final int NAN_TERMINATED_REASON_DE_SHUTDOWN = 8197;
    public static final int NAN_TERMINATED_REASON_DISABLE_IN_PROGRESS = 8198;
    public static final int NAN_TERMINATED_REASON_POST_DISC_ATTR_EXPIRED = 8199;
    public static final int NAN_TERMINATED_REASON_POST_DISC_LEN_EXCEEDED = 8200;
    public static final int NAN_TERMINATED_REASON_FURTHER_AVAIL_MAP_EMPTY = 8201;

    /* 9000-9500 NDP Status type */
    public static final int NAN_STATUS_NDP_UNSUPPORTED_CONCURRENCY = 9000;
    public static final int NAN_STATUS_NDP_NAN_DATA_IFACE_CREATE_FAILED = 9001;
    public static final int NAN_STATUS_NDP_NAN_DATA_IFACE_DELETE_FAILED = 9002;
    public static final int NAN_STATUS_NDP_DATA_INITIATOR_REQUEST_FAILED = 9003;
    public static final int NAN_STATUS_NDP_DATA_RESPONDER_REQUEST_FAILED = 9004;
    public static final int NAN_STATUS_NDP_INVALID_SERVICE_INSTANCE_ID = 9005;
    public static final int NAN_STATUS_NDP_INVALID_NDP_INSTANCE_ID = 9006;
    public static final int NAN_STATUS_NDP_INVALID_RESPONSE_CODE = 9007;
    public static final int NAN_STATUS_NDP_INVALID_APP_INFO_LEN = 9008;

    /* OTA failures and timeouts during negotiation */
    public static final int NAN_STATUS_NDP_MGMT_FRAME_REQUEST_FAILED = 9009;
    public static final int NAN_STATUS_NDP_MGMT_FRAME_RESPONSE_FAILED = 9010;
    public static final int NAN_STATUS_NDP_MGMT_FRAME_CONFIRM_FAILED = 9011;
    public static final int NAN_STATUS_NDP_END_FAILED = 9012;
    public static final int NAN_STATUS_NDP_MGMT_FRAME_END_REQUEST_FAILED = 9013;

    /* 9500 onwards vendor specific error codes */
    public static final int NAN_STATUS_NDP_VENDOR_SPECIFIC_ERROR = 9500;

    private static int translateHalStatusToNanEventCallbackReason(int halStatus) {
        switch (halStatus) {
            case NAN_STATUS_SUCCESS:
                /*
                 * TODO: b/27914592 all of these codes will be cleaned-up/reduced.
                 */
                return WifiNanEventCallback.REASON_OTHER;
            case NAN_STATUS_INVALID_RSSI_CLOSE_VALUE:
            case NAN_STATUS_INVALID_RSSI_MIDDLE_VALUE:
            case NAN_STATUS_INVALID_HOP_COUNT_LIMIT:
            case NAN_STATUS_INVALID_MASTER_PREFERENCE_VALUE:
            case NAN_STATUS_INVALID_LOW_CLUSTER_ID_VALUE:
            case NAN_STATUS_INVALID_HIGH_CLUSTER_ID_VALUE:
            case NAN_STATUS_INVALID_BACKGROUND_SCAN_PERIOD:
            case NAN_STATUS_INVALID_RSSI_PROXIMITY_VALUE:
            case NAN_STATUS_INVALID_SCAN_CHANNEL:
            case NAN_STATUS_INVALID_POST_NAN_CONNECTIVITY_CAPABILITIES_BITMAP:
            case NAN_STATUS_INVALID_FA_MAP_NUMCHAN_VALUE:
            case NAN_STATUS_INVALID_FA_MAP_DURATION_VALUE:
            case NAN_STATUS_INVALID_FA_MAP_CLASS_VALUE:
            case NAN_STATUS_INVALID_FA_MAP_CHANNEL_VALUE:
            case NAN_STATUS_INVALID_FA_MAP_AVAILABILITY_INTERVAL_BITMAP_VALUE:
            case NAN_STATUS_INVALID_FA_MAP_MAP_ID:
            case NAN_STATUS_INVALID_POST_NAN_DISCOVERY_CONN_TYPE_VALUE:
            case NAN_STATUS_INVALID_POST_NAN_DISCOVERY_DEVICE_ROLE_VALUE:
            case NAN_STATUS_INVALID_POST_NAN_DISCOVERY_DURATION_VALUE:
            case NAN_STATUS_INVALID_POST_NAN_DISCOVERY_BITMAP_VALUE:
            case NAN_STATUS_MISSING_FUTHER_AVAILABILITY_MAP:
            case NAN_STATUS_INVALID_BAND_CONFIG_FLAGS:
            case NAN_STATUS_INVALID_RANDOM_FACTOR_UPDATE_TIME_VALUE:
            case NAN_STATUS_INVALID_ONGOING_SCAN_PERIOD:
            case NAN_STATUS_INVALID_DW_INTERVAL_VALUE:
            case NAN_STATUS_INVALID_DB_INTERVAL_VALUE:
                return WifiNanEventCallback.REASON_INVALID_ARGS;
        }

        return WifiNanEventCallback.REASON_OTHER;
    }

    private static int translateHalStatusToNanSessionCallbackTerminate(int halStatus) {
        switch (halStatus) {
            case NAN_TERMINATED_REASON_TIMEOUT:
            case NAN_TERMINATED_REASON_USER_REQUEST:
            case NAN_TERMINATED_REASON_COUNT_REACHED:
                return WifiNanDiscoverySessionCallback.TERMINATE_REASON_DONE;

            case NAN_TERMINATED_REASON_INVALID:
            case NAN_TERMINATED_REASON_FAILURE:
            case NAN_TERMINATED_REASON_DE_SHUTDOWN:
            case NAN_TERMINATED_REASON_DISABLE_IN_PROGRESS:
            case NAN_TERMINATED_REASON_POST_DISC_ATTR_EXPIRED:
            case NAN_TERMINATED_REASON_POST_DISC_LEN_EXCEEDED:
            case NAN_TERMINATED_REASON_FURTHER_AVAIL_MAP_EMPTY:
                return WifiNanDiscoverySessionCallback.TERMINATE_REASON_FAIL;
        }

        return WifiNanDiscoverySessionCallback.TERMINATE_REASON_FAIL;
    }

    private static int translateHalStatusToNanSessionCallbackReason(int halStatus) {
        switch (halStatus) {
            case NAN_STATUS_TIMEOUT:
            case NAN_STATUS_DE_FAILURE:
            case NAN_STATUS_INVALID_MSG_VERSION:
            case NAN_STATUS_INVALID_MSG_LEN:
            case NAN_STATUS_INVALID_MSG_ID:
            case NAN_STATUS_INVALID_HANDLE:
                return WifiNanDiscoverySessionCallback.REASON_OTHER;
            case NAN_STATUS_NO_SPACE_AVAILABLE:
                return WifiNanDiscoverySessionCallback.REASON_NO_RESOURCES;
            case NAN_STATUS_INVALID_PUBLISH_TYPE:
            case NAN_STATUS_INVALID_TX_TYPE:
            case NAN_STATUS_INVALID_MATCH_ALGORITHM:
                return WifiNanDiscoverySessionCallback.REASON_INVALID_ARGS;
            case NAN_STATUS_DISABLE_IN_PROGRESS:
            case NAN_STATUS_INVALID_TLV_LEN:
            case NAN_STATUS_INVALID_TLV_TYPE:
            case NAN_STATUS_MISSING_TLV_TYPE:
            case NAN_STATUS_INVALID_TOTAL_TLVS_LEN:
            case NAN_STATUS_INVALID_MATCH_HANDLE:
            case NAN_STATUS_INVALID_TLV_VALUE:
            case NAN_STATUS_INVALID_TX_PRIORITY:
            case NAN_STATUS_INVALID_CONNECTION_MAP:
            case NAN_STATUS_INVALID_TCA_ID:
            case NAN_STATUS_INVALID_STATS_ID:
            case NAN_STATUS_NAN_NOT_ALLOWED:
                return WifiNanDiscoverySessionCallback.REASON_OTHER;
            case NAN_STATUS_NO_OTA_ACK:
            case NAN_STATUS_TX_FAIL:
                return WifiNanDiscoverySessionCallback.REASON_TX_FAIL;
        }

        return WifiNanDiscoverySessionCallback.REASON_OTHER;
    }

    // callback from native
    private static void onNanNotifyResponse(short transactionId, int responseType, int status,
            int value) {
        if (VDBG) {
            Log.v(TAG,
                    "onNanNotifyResponse: transactionId=" + transactionId + ", responseType="
                    + responseType + ", status=" + status + ", value=" + value);
        }
        WifiNanStateManager stateMgr = WifiNanStateManager.getInstance();

        switch (responseType) {
            case NAN_RESPONSE_ENABLED:
                /* fall through */
            case NAN_RESPONSE_CONFIG:
                if (status == NAN_STATUS_SUCCESS) {
                    stateMgr.onConfigSuccessResponse(transactionId);
                } else {
                    stateMgr.onConfigFailedResponse(transactionId,
                            translateHalStatusToNanEventCallbackReason(status));
                }
                break;
            case NAN_RESPONSE_PUBLISH_CANCEL:
                if (status != NAN_STATUS_SUCCESS) {
                    Log.e(TAG, "onNanNotifyResponse: NAN_RESPONSE_PUBLISH_CANCEL error - status="
                            + status + ", value=" + value);
                }
                break;
            case NAN_RESPONSE_TRANSMIT_FOLLOWUP:
                if (status == NAN_STATUS_SUCCESS) {
                    stateMgr.onMessageSendQueuedSuccessResponse(transactionId);
                } else {
                    stateMgr.onMessageSendQueuedFailResponse(transactionId,
                            translateHalStatusToNanSessionCallbackReason(status));
                }
                break;
            case NAN_RESPONSE_SUBSCRIBE_CANCEL:
                if (status != NAN_STATUS_SUCCESS) {
                    Log.e(TAG, "onNanNotifyResponse: NAN_RESPONSE_PUBLISH_CANCEL error - status="
                            + status + ", value=" + value);
                }
                break;
            case NAN_RESPONSE_DP_INTERFACE_CREATE:
                if (status != NAN_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onNanNotifyResponse: NAN_RESPONSE_DP_INTERFACE_CREATE error - status="
                                    + status + ", value=" + value);
                }
                stateMgr.onCreateDataPathInterfaceResponse(transactionId,
                        status == NAN_STATUS_SUCCESS, status);
                break;
            case NAN_RESPONSE_DP_INTERFACE_DELETE:
                if (status != NAN_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onNanNotifyResponse: NAN_RESPONSE_DP_INTERFACE_DELETE error - status="
                                    + status + ", value=" + value);
                }
                stateMgr.onDeleteDataPathInterfaceResponse(transactionId,
                        status == NAN_STATUS_SUCCESS, status);
                break;
            case NAN_RESPONSE_DP_RESPONDER_RESPONSE:
                if (status != NAN_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onNanNotifyResponse: NAN_RESPONSE_DP_RESPONDER_RESPONSE error - "
                                    + "status=" + status + ", value=" + value);
                }
                stateMgr.onRespondToDataPathSetupRequestResponse(transactionId,
                        status == NAN_STATUS_SUCCESS, status);
                break;
            case NAN_RESPONSE_DP_END:
                if (status != NAN_STATUS_SUCCESS) {
                    Log.e(TAG, "onNanNotifyResponse: NAN_RESPONSE_DP_END error - status=" + status
                            + ", value=" + value);
                }
                stateMgr.onEndDataPathResponse(transactionId, status == NAN_STATUS_SUCCESS,
                        status);
                break;
            default:
                Log.e(TAG, "onNanNotifyResponse: unclassified responseType=" + responseType);
                break;
        }
    }

    private static void onNanNotifyResponsePublishSubscribe(short transactionId, int responseType,
            int status, int value, int pubSubId) {
        if (VDBG) {
            Log.v(TAG,
                    "onNanNotifyResponsePublishSubscribe: transactionId=" + transactionId
                            + ", responseType=" + responseType + ", status=" + status + ", value="
                            + value + ", pubSubId=" + pubSubId);
        }

        switch (responseType) {
            case NAN_RESPONSE_PUBLISH:
                if (status == NAN_STATUS_SUCCESS) {
                    WifiNanStateManager.getInstance().onSessionConfigSuccessResponse(transactionId,
                            true, pubSubId);
                } else {
                    WifiNanStateManager.getInstance().onSessionConfigFailResponse(transactionId,
                            true, translateHalStatusToNanSessionCallbackReason(status));
                }
                break;
            case NAN_RESPONSE_SUBSCRIBE:
                if (status == NAN_STATUS_SUCCESS) {
                    WifiNanStateManager.getInstance().onSessionConfigSuccessResponse(transactionId,
                            false, pubSubId);
                } else {
                    WifiNanStateManager.getInstance().onSessionConfigFailResponse(transactionId,
                            false, translateHalStatusToNanSessionCallbackReason(status));
                }
                break;
            default:
                Log.wtf(TAG, "onNanNotifyResponsePublishSubscribe: unclassified responseType="
                        + responseType);
                break;
        }
    }

    private static void onNanNotifyResponseCapabilities(short transactionId, int status, int value,
            Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "onNanNotifyResponseCapabilities: transactionId=" + transactionId
                    + ", status=" + status + ", value=" + value + ", capabilities=" + capabilities);
        }

        if (status == NAN_STATUS_SUCCESS) {
            WifiNanStateManager.getInstance().onCapabilitiesUpdateResponse(transactionId,
                    capabilities);
        } else {
            Log.e(TAG,
                    "onNanNotifyResponseCapabilities: error status=" + status + ", value=" + value);
        }
    }

    private static void onNanNotifyResponseDataPathInitiate(short transactionId, int status,
            int value, int ndpId) {
        if (VDBG) {
            Log.v(TAG,
                    "onNanNotifyResponseDataPathInitiate: transactionId=" + transactionId
                            + ", status=" + status + ", value=" + value + ", ndpId=" + ndpId);
        }
        if (status == NAN_STATUS_SUCCESS) {
            WifiNanStateManager.getInstance().onInitiateDataPathResponseSuccess(transactionId,
                    ndpId);
        } else {
            WifiNanStateManager.getInstance().onInitiateDataPathResponseFail(transactionId, status);
        }
    }

    public static final int NAN_EVENT_ID_DISC_MAC_ADDR = 0;
    public static final int NAN_EVENT_ID_STARTED_CLUSTER = 1;
    public static final int NAN_EVENT_ID_JOINED_CLUSTER = 2;

    // callback from native
    private static void onDiscoveryEngineEvent(int eventType, byte[] mac) {
        if (VDBG) {
            Log.v(TAG, "onDiscoveryEngineEvent: eventType=" + eventType + ", mac="
                    + String.valueOf(HexEncoding.encode(mac)));
        }

        if (eventType == NAN_EVENT_ID_DISC_MAC_ADDR) {
            WifiNanStateManager.getInstance().onInterfaceAddressChangeNotification(mac);
        } else if (eventType == NAN_EVENT_ID_STARTED_CLUSTER) {
            WifiNanStateManager.getInstance().onClusterChangeNotification(
                    WifiNanClientState.CLUSTER_CHANGE_EVENT_STARTED, mac);
        } else if (eventType == NAN_EVENT_ID_JOINED_CLUSTER) {
            WifiNanStateManager.getInstance().onClusterChangeNotification(
                    WifiNanClientState.CLUSTER_CHANGE_EVENT_JOINED, mac);
        } else {
            Log.w(TAG, "onDiscoveryEngineEvent: invalid eventType=" + eventType);
        }
    }

    // callback from native
    private static void onMatchEvent(int pubSubId, int requestorInstanceId, byte[] mac,
            byte[] serviceSpecificInfo, byte[] matchFilter) {
        if (VDBG) {
            Log.v(TAG, "onMatchEvent: pubSubId=" + pubSubId + ", requestorInstanceId="
                    + requestorInstanceId + ", mac=" + String.valueOf(HexEncoding.encode(mac))
                    + ", serviceSpecificInfo=" + Arrays.toString(serviceSpecificInfo)
                    + ", matchFilter=" + Arrays.toString(matchFilter));
        }

        WifiNanStateManager.getInstance().onMatchNotification(pubSubId, requestorInstanceId, mac,
                serviceSpecificInfo, matchFilter);
    }

    // callback from native
    private static void onPublishTerminated(int publishId, int status) {
        if (VDBG) Log.v(TAG, "onPublishTerminated: publishId=" + publishId + ", status=" + status);

        WifiNanStateManager.getInstance().onSessionTerminatedNotification(publishId,
                translateHalStatusToNanSessionCallbackTerminate(status), true);
    }

    // callback from native
    private static void onSubscribeTerminated(int subscribeId, int status) {
        if (VDBG) {
            Log.v(TAG, "onSubscribeTerminated: subscribeId=" + subscribeId + ", status=" + status);
        }

        WifiNanStateManager.getInstance().onSessionTerminatedNotification(subscribeId,
                translateHalStatusToNanSessionCallbackTerminate(status), false);
    }

    // callback from native
    private static void onFollowupEvent(int pubSubId, int requestorInstanceId, byte[] mac,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onFollowupEvent: pubSubId=" + pubSubId + ", requestorInstanceId="
                    + requestorInstanceId + ", mac=" + String.valueOf(HexEncoding.encode(mac)));
        }

        WifiNanStateManager.getInstance().onMessageReceivedNotification(pubSubId,
                requestorInstanceId, mac, message);
    }

    // callback from native
    private static void onDisabledEvent(int status) {
        if (VDBG) Log.v(TAG, "onDisabledEvent: status=" + status);

        WifiNanStateManager.getInstance()
                .onNanDownNotification(translateHalStatusToNanEventCallbackReason(status));
    }

    // callback from native
    private static void onTransmitFollowupEvent(short transactionId, int reason) {
        if (VDBG) {
            Log.v(TAG, "onTransmitFollowupEvent: transactionId=" + transactionId + ", reason="
                    + reason);
        }

        if (reason == NAN_STATUS_SUCCESS) {
            WifiNanStateManager.getInstance().onMessageSendSuccessNotification(transactionId);
        } else {
            WifiNanStateManager.getInstance().onMessageSendFailNotification(transactionId,
                    translateHalStatusToNanSessionCallbackReason(reason));
        }
    }

    private static void onDataPathRequest(int pubSubId, byte[] mac, int ndpId, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onDataPathRequest: pubSubId=" + pubSubId + ", mac=" + String.valueOf(
                    HexEncoding.encode(mac)) + ", ndpId=" + ndpId);
        }

        WifiNanStateManager.getInstance()
                .onDataPathRequestNotification(pubSubId, mac, ndpId, message);
    }

    private static void onDataPathConfirm(int ndpId, byte[] mac, boolean accept, int reason,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onDataPathConfirm: ndpId=" + ndpId + ", mac=" + String.valueOf(HexEncoding
                    .encode(mac)) + ", accept=" + accept + ", reason=" + reason);
        }

        WifiNanStateManager.getInstance()
                .onDataPathConfirmNotification(ndpId, mac, accept, reason, message);
    }

    private static void onDataPathEnd(int ndpId) {
        if (VDBG) {
            Log.v(TAG, "onDataPathEndNotification: ndpId=" + ndpId);
        }

        WifiNanStateManager.getInstance().onDataPathEndNotification(ndpId);
    }
}
