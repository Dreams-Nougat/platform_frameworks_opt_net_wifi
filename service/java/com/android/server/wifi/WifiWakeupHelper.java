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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.provider.Settings;

import com.android.internal.R;

/**
 * Helper class for building and showing notifications for {@link WifiWakeupController}.
 */
public class WifiWakeupHelper {
    private final Context mContext;

    public WifiWakeupHelper(Context context) {
        mContext = context;
    }

    /**
     * Show a notification that Wi-Fi has been enabled by Wi-Fi Wakeup.
     *
     * @param wifiConfiguration the {@link WifiConfiguration} that triggered Wi-Fi to wakeup
     */
    public void showWifiEnabledNotification(WifiConfiguration wifiConfiguration) {
        String title = mContext.getResources().getString(
                R.string.wifi_wakeup_enabled_notification_title);
        String summary = mContext.getResources().getString(
                R.string.wifi_wakeup_enabled_notification_context, wifiConfiguration.SSID);
        PendingIntent savedNetworkSettingsPendingIntent = PendingIntent.getActivity(mContext, 0,
                new Intent(Settings.ACTION_WIFI_SAVED_NETWORK_SETTINGS),
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action savedNetworkSettingsAction =
                new Notification.Action.Builder(
                        null,
                        mContext.getResources().getString(
                                R.string.wifi_wakeup_enabled_notification_open_settings),
                        savedNetworkSettingsPendingIntent)
                .build();
        Notification notification = new Notification.Builder(mContext)
                .setContentTitle(title)
                .setSmallIcon(R.drawable.stat_notify_wifi_in_range)
                .setStyle(new Notification.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(savedNetworkSettingsAction)
                .build();
        mContext.getSystemService(NotificationManager.class)
            .notify(R.string.wifi_wakeup_enabled_notification_title, notification);
    }
}
