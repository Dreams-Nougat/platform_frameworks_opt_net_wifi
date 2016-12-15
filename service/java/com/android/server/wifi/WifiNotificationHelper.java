/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;

/**
 * Helper class that creates notifications for {@link WifiNotificationController}.
 */
public class WifiNotificationHelper {
    Context mContext;

    WifiNotificationHelper(Context context) {
        mContext = context;
    }

    /**
     * Creates the main open networks notification with two actions. "Options" link to the
     * Wi-Fi picker activity, and "Connect" prompts {@link WifiNotificationController}
     * to connect to the recommended network.
     */
    public Notification createMainNotification(String ssid) {
        PendingIntent optionsIntent = PendingIntent.getActivity(
                mContext, 0, new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK), FLAG_UPDATE_CURRENT);
        Action optionsAction = new Action.Builder(
                null /* icon */,
                mContext.getText(com.android.internal.R.string.wifi_available_options),
                optionsIntent)
                .build();
        PendingIntent connectIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                new Intent(WifiNotificationController.ACTION_CONNECT_TO_RECOMMENDED_NETWORK),
                FLAG_UPDATE_CURRENT);
        Action connectAction = new Action.Builder(
                null /* icon */,
                mContext.getText(com.android.internal.R.string.wifi_available_connect),
                connectIntent)
                .build();
        return createNotificationBuilder(ssid)
                .addAction(optionsAction)
                .addAction(connectAction)
                .build();
    }

    /**
     * Creates the notification that indicates the controller is attempting to connect
     * to the recommended network.
     */
    public Notification createConnectingNotification(String ssid) {
        Action connecting = new Action.Builder(
                null /* icon */,
                mContext.getText(com.android.internal.R.string.wifi_available_connecting),
                null /* pendingIntent */)
                .build();
        return createNotificationBuilder(ssid)
                .addAction(connecting)
                .build();
    }

    /**
     * Creates the notification that indicates the controller successfully connected
     * to the recommended network.
     */
    public Notification createConnectedNotification(String ssid) {
        Action connected = new Action.Builder(
                null /* icon */,
                mContext.getText(com.android.internal.R.string.wifi_available_connected),
                null /* pendingIntent */)
                .build();
        return createNotificationBuilder(ssid)
                .addAction(connected)
                .build();
    }

    Notification.Builder createNotificationBuilder(String ssid) {
        CharSequence title = mContext.getText(com.android.internal.R.string.wifi_available);
        return new Notification.Builder(mContext)
                .setWhen(0)
                .setSmallIcon(com.android.internal.R.drawable.stat_notify_wifi_in_range)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(ssid);
    }
}
