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

package com.android.server.wifi.util;

import android.os.Handler;
import android.os.Message;
import android.os.Messenger;

import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiLog;

/**
 * This class subclasses AsyncChannel and adds logging
 * to the sendMessage() API
 */
public class WifiAsyncChannel extends AsyncChannel {
    private static final String LOG_TAG = "Wifi Async Channel";

    private WifiLog mLog;
    private String mTag;
    private WifiInjector mWifiInjector;

    /**
     * AsyncChannelWithLogging constructor
     */
    public WifiAsyncChannel(String serviceTag) {
        super();
        mTag = LOG_TAG + " for " + serviceTag;
    }

    private void logMessage(Message msg) {
        if (mLog == null) {
            if (mWifiInjector == null) {
                mWifiInjector = WifiInjector.getInstance();
            }
            mLog = mWifiInjector.makeLog(mTag);
        }
        if (mLog != null) {
            mLog.trace(" Message = % from uid = %")
                .c(msg.what)
                .c(msg.sendingUid)
                .flush();
        } else {
            Log.e(LOG_TAG, "Error, Unable to create WifiLog");
            Log.i(mTag, "Message " + msg.what + " from uid = " + msg.sendingUid);
        }
    }

    /**
     * Send a message to the destination handler.
     *
     * @param msg
     */
    @Override
    public void sendMessage(Message msg) {
        logMessage(msg);
        super.sendMessage(msg);
    }

    /**
     * Reply to srcMsg
     *
     * @param srcMsg
     * @param dstMsg
     */
    @Override
    public void replyToMessage(Message srcMsg, Message dstMsg) {
        logMessage(dstMsg);
        super.replyToMessage(srcMsg, dstMsg);
    }

    /**
     * Send the Message synchronously.
     *
     * @param msg to send
     * @return reply message or null if an error.
     */
    @Override
    public Message sendMessageSynchronously(Message msg) {
        logMessage(msg);
        Message replyMessage = super.sendMessageSynchronously(msg);
        logMessage(replyMessage);
        return (replyMessage);
    }

    /**
     * Reply to the src handler that we're half connected.
     * see: CMD_CHANNEL_HALF_CONNECTED for message contents
     *
     * @param status to be stored in msg.arg1
     */
    @Override
    protected void replyHalfConnected(int status) {
        Handler srcHandler = super.mSrcHandler;
        Messenger dstMessenger = super.mDstMessenger;
        Message msg = mSrcHandler.obtainMessage(CMD_CHANNEL_HALF_CONNECTED);
        msg.arg1 = status;
        msg.obj = this;
        msg.replyTo = dstMessenger;
        if (!super.linkToDeathMonitor()) {
            // Override status to indicate failure
            msg.arg1 = STATUS_BINDING_UNSUCCESSFUL;
        }

        mSrcHandler.sendMessage(msg);
    }

    /**
     * Reply to the src handler that we are disconnected
     * see: CMD_CHANNEL_DISCONNECTED for message contents
     *
     * @param status to be stored in msg.arg1
     */
    @Override
    protected void replyDisconnected(int status) {
        Handler srcHandler = super.mSrcHandler;
        Messenger dstMessenger = super.mDstMessenger;
        // Can't reply if already disconnected. Avoid NullPointerException.
        if (srcHandler == null) return;
        Message msg = mSrcHandler.obtainMessage(CMD_CHANNEL_DISCONNECTED);
        msg.arg1 = status;
        msg.obj = this;
        msg.replyTo = dstMessenger;
        mSrcHandler.sendMessage(msg);
    }
}
