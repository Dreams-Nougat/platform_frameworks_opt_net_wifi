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

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.android.server.wifi.WifiInjector;

import java.util.List;
/**
 * A wifi permissions utility assessing permissions
 * for getting scan result by a package
 * */
public class WifiPermissionsUtil {
    private static final String TAG = "WifipermissionsUtil";
    private static final boolean VERBOSE_LOG = false;
    private WifiPermissionsWrapper mWifiPermissionsWrapper;
    private Context mContext;
    private int mUserId;
    private int mCurrentUser;
    private int mUid;
    private AppOpsManager mAppOps;
    private UserManager mUserManager;
    public WifiPermissionsUtil(WifiInjector wifiInjector) {
        mWifiPermissionsWrapper = wifiInjector.getWifiPermissionsWrapper();
        mContext = mWifiPermissionsWrapper.getContext();
        mCurrentUser = mWifiPermissionsWrapper.getCurrentUser();
        mUserManager = mWifiPermissionsWrapper.getUserManager();
        mUserId = mWifiPermissionsWrapper.getCallingUserId();
        mUid = mWifiPermissionsWrapper.getUid();
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
    }
    /**
     * API to determine if the caller has permissions to get
     * scan results.
     */
    public boolean canAccessScanResults(String pkgName) {
        if (VERBOSE_LOG) {
            Log.e(TAG, "Invoking API");
        }
        boolean stat = false;
        if (checkPeersMacAddress()
                || isActiveNwScorer()
                || (isLocationModeEnabled(pkgName)
                && checkCallersLocationPermission(pkgName))) {
            if (isScanAllowedbyApps(pkgName)
                    && isCurrentProfile()
                    && checkInteractAcrossUsersFull()) {
                stat = true;
            }
        }
        if (VERBOSE_LOG) {
            Log.e(TAG, "canAccessScanResults " + stat);
        }
        return stat;
    }
    /**
     * Returns true if the caller holds PEERS_MAC_ADDRESS.
     */
    private boolean checkPeersMacAddress() {
        int perm = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.PEERS_MAC_ADDRESS);
        boolean stat = (perm
                == PackageManager.PERMISSION_GRANTED);
        if (VERBOSE_LOG) {
            Log.e(TAG, "Perm " + perm + " checkPeersMacAddress: " + stat);
        }
        return stat;
    }
    /**
     * Returns true if the caller is an Active Network Scorer
     */
    private boolean isActiveNwScorer() {
        boolean stat = mWifiPermissionsWrapper.isCallerActiveNwScorer();
        if (VERBOSE_LOG) {
            Log.e(TAG, "isActiveNwScorer " + stat);
        }
        return stat;
    }
    /**
     * Returns true if Wifi scan is allowed in App
     */
    private boolean isScanAllowedbyApps(String pkgName) {
        boolean stat = checkAppOpAllowed(AppOpsManager.OP_WIFI_SCAN, pkgName);
        if (VERBOSE_LOG) {
            Log.e(TAG, "Scan allowed by Apps " + stat);
        }
        return stat;
    }

    /**
     * Returns true if the caller holds INTERACT_ACROSS_USERS_FULL.
     */
    private boolean checkInteractAcrossUsersFull() {
        boolean stat = (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                == PackageManager.PERMISSION_GRANTED);
        if (VERBOSE_LOG) {
            Log.e(TAG, "checkInteractAcrossUsersFull: " + stat);
        }
        return stat;
    }
    /**
     * Returns true if the calling user is the current one or a profile of the
     * current user..
     */
    private boolean isCurrentProfile() {
        int mCurrentUser = mWifiPermissionsWrapper.getCurrentUser();
        boolean stat = false;
        if (VERBOSE_LOG) {
            Log.e(TAG, "mUserId = " + mUserId + " mCurrentUser = " + mCurrentUser);
        }
        if (mUserId == mCurrentUser) {
            stat = true;
        } else {
            List<UserInfo> userProfiles = mUserManager.getProfiles(mCurrentUser);
            for (UserInfo user: userProfiles) {
                if (user.id == mUserId) {
                    stat = true;
                }
            }
        }
        if (VERBOSE_LOG) {
            Log.e(TAG, "isCurrentProfile = " + stat);
        }
        return stat;
    }
    private boolean isMapp(String pkgName) {
        boolean status = false;
        try {
            status = mContext.getPackageManager().getApplicationInfo(pkgName, 0)
                    .targetSdkVersion >= Build.VERSION_CODES.M;
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume M app (more strict checking)
        }
        if (VERBOSE_LOG) {
            Log.e(TAG, "isMapp : " + status);
        }
        return status;
    }
    private boolean checkAppOpAllowed(int op, String pkgName) {
        if (mAppOps.noteOp(op, mUid, pkgName) == AppOpsManager.MODE_ALLOWED) {
            return true;
        }
        return false;
    }
    private boolean isLegacyForeground(String pkgName) {
        boolean stat = (!isMapp(pkgName) && isForegroundApp(pkgName));
        if (VERBOSE_LOG) {
            Log.e(TAG, "isLegacyForeground " + stat);
        }
        return stat;
    }
    private boolean isForegroundApp(String pkgName) {
        boolean stat = false;
        if (pkgName.equals(mWifiPermissionsWrapper.getTopPkgName())) {
            stat = true;
        }
        if (VERBOSE_LOG) {
            Log.e(TAG, "isForegroundApp " + stat);
        }
        return stat;
    }
    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_COARSE_LOCATION or
     * android.Manifest.permission.ACCESS_FINE_LOCATION and a corresponding app op is allowed
     */
    private boolean checkCallersLocationPermission(String pkgName) {
        boolean stat = false;
        if ((mWifiPermissionsWrapper.getUidPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED)
                && checkAppOpAllowed(AppOpsManager.OP_FINE_LOCATION, pkgName)) {
            stat = true;
        }
        if ((mWifiPermissionsWrapper.getUidPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                 == PackageManager.PERMISSION_GRANTED)
                 && checkAppOpAllowed(AppOpsManager.OP_COARSE_LOCATION, pkgName)) {
            stat = true;
        }
        if (isLegacyForeground(pkgName)) {
            stat = true;
        }
        if (VERBOSE_LOG) {
            Log.e(TAG, "checkCallersLocationPermission " + stat);
        }
        if (!stat) {
            Log.e(TAG, "Permission denial: Need ACCESS_COARSE_LOCATION or "
                    + "ACCESS_FINE_LOCATION permission to get scan results");
        }
        return stat;
    }
    private boolean isLocationModeEnabled(String pkgName) {
        boolean stat = (isLegacyForeground(pkgName)
                 || (mWifiPermissionsWrapper.getLocationModeSetting()
                 != Settings.Secure.LOCATION_MODE_OFF));
        if (VERBOSE_LOG) {
            Log.e(TAG, "isLocationModeEnabled" + stat);
        }
        return stat;
    }
}
