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
import android.os.UserManager;
import android.provider.Settings;

import com.android.server.wifi.WifiSettingsStore;

import java.util.List;

/**
 * A wifi permissions utility assessing permissions
 * for getting scan results by a package
 */
public class WifiPermissionsUtil {
    private static final String TAG = "WifiPermissionsUtil";
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;
    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final UserManager mUserManager;
    private final WifiSettingsStore mSettingsStore;

    public WifiPermissionsUtil(WifiPermissionsWrapper wifiPermissionsWrapper,
              Context context, WifiSettingsStore settingsStore, UserManager userManager) {
        mWifiPermissionsWrapper = wifiPermissionsWrapper;
        mContext = context;
        mUserManager = userManager;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mSettingsStore = settingsStore;
    }

    /**
     * API to determine if the caller has permissions to get
     * scan results.
     * @param pkgName Packagename of the application requesting access
     * @param uid The uid of the package
     * @param minVersion Minimum app API Version number to enforce location permission
     * @return boolean true or false if permissions is granted
     */
    public boolean canAccessScanResults(String pkgName, int uid,
                int minVersion) throws SecurityException {
        mAppOps.checkPackage(uid, pkgName);
        // If the caller has CAN_READ_PEER_MAC_ADDRESS
        // permission or is an Active Nw scorer
        boolean canCallerAccessLocation = checkCallerHasPeersMacAddressPermission(uid)
                || isCallerActiveNwScorer(uid);
        // LocationAccess by App: For AppVersion older than minVersion,
        // it is sufficient to check if the App is foreground.
        // Othewise, Location Mode must be enabled and caller must have
        // Coarse Location permission to have location Access.
        boolean canSystemUseLocation = isLegacyForeground(pkgName, minVersion)
                || (isLocationModeEnabled(pkgName, minVersion)
                && checkCallersLocationPermission(pkgName, uid, minVersion));
        // Either caller or system must enable location access, else return true
        if (!canCallerAccessLocation && !canSystemUseLocation) {
            return false;
        }
        // Check if Wifi Scan request is allowed for this App
        if (!isScanAllowedbyApps(pkgName, uid)) {
            return false;
        }
        // If the User or profile is current, permission is granted
        // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission
        if (!isCurrentProfile(uid) && !checkInteractAcrossUsersFull(uid)) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if the caller holds PEERS_MAC_ADDRESS permission
     */
    private boolean checkCallerHasPeersMacAddressPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.PEERS_MAC_ADDRESS, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the caller is an Active Network Scorer
     */
    private boolean isCallerActiveNwScorer(int uid) {
        return mWifiPermissionsWrapper.isCallerActiveNwScorer(uid);
    }

    /**
     * Returns true if Wifi scan operation is allowed for this caller
     * and package
     */
    private boolean isScanAllowedbyApps(String pkgName, int uid) {
        return checkAppOpAllowed(AppOpsManager.OP_WIFI_SCAN, pkgName, uid);
    }

    /**
     * Returns true if the caller holds INTERACT_ACROSS_USERS_FULL.
     */
    private boolean checkInteractAcrossUsersFull(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the calling user is the current one or a profile of the
     * current user.
     */
    private boolean isCurrentProfile(int uid) {
        int currentUser = mWifiPermissionsWrapper.getCurrentUser();
        int callingUserId = mWifiPermissionsWrapper.getCallingUserId(uid);
        if (callingUserId == currentUser) {
            return true;
        } else {
            List<UserInfo> userProfiles = mUserManager.getProfiles(currentUser);
            for (UserInfo user: userProfiles) {
                if (user.id == callingUserId) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the App version is older than minVersion
     */
    private boolean isLegacyVersion(String pkgName, int minVersion) {
        try {
            if (mContext.getPackageManager().getApplicationInfo(pkgName, 0)
                    .targetSdkVersion < minVersion) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume known app (more strict checking)
        }
        return false;
    }

    private boolean checkAppOpAllowed(int op, String pkgName, int uid) {
        return mAppOps.noteOp(op, uid, pkgName) == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isLegacyForeground(String pkgName, int version) {
        return isLegacyVersion(pkgName, version) && isForegroundApp(pkgName);
    }

    private boolean isForegroundApp(String pkgName) {
        return pkgName.equals(mWifiPermissionsWrapper.getTopPkgName());
    }

    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_COARSE_LOCATION
     * and a corresponding app op is allowed for this package and uid
     */
    private boolean checkCallersLocationPermission(String pkgName, int uid, int version) {
        // Coarse Permission implies Fine permission
        if ((mWifiPermissionsWrapper.getUidPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION, uid)
                == PackageManager.PERMISSION_GRANTED)
                && checkAppOpAllowed(AppOpsManager.OP_COARSE_LOCATION, pkgName, uid)) {
            return true;
        }
        return false;
    }
    private boolean isLocationModeEnabled(String pkgName, int version) {
        // Location mode check on applications that are later than version, for older
        // versions, foreground apps can skip this check and always return true
        return (mSettingsStore.getLocationModeSetting(mContext)
                 != Settings.Secure.LOCATION_MODE_OFF);
    }
}
