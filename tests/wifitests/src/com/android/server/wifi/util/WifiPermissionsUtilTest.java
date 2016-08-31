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

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.server.wifi.BinderUtil;
import com.android.server.wifi.WifiSettingsStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

/** Unit tests for {@link WifiPermissionsUtil}. */
@RunWith(JUnit4.class)
public class WifiPermissionsUtilTest {
    public static final String TAG = "WifiPermissionsUtilTest";

    @Mock private WifiPermissionsWrapper mMockPermissionsWrapper;
    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPkgMgr;
    @Mock private ApplicationInfo mMockApplInfo;
    @Mock private AppOpsManager mMockAppOps;
    @Mock private UserInfo mMockUserInfo;
    @Mock private UserManager mMockUserManager;
    @Mock private WifiSettingsStore mMockWifiSettingsStore;
    @Mock private ContentResolver mMockContentResolver;

    private WifiPermissionsUtil mCodeUnderTest = spy(WifiPermissionsUtil.class);
    private static final String sPkgName = "com.google.somePackage";
    private static String[] sPermissions;
    private static int[] sTestVector;
    private static final int MANAGED_PROFILE_UID = 1100000;
    private static final int OTHER_USER_UID = 1200000;
    private static final int CAN_READ_PEER_MAC_ADDRESS = 0;
    private static final int HAS_INTERACT_USERS_FULL = 1;
    private static final int IS_USER_CURRENT = 2;
    private static final int IS_PROFILE_CURRENT = 3;
    private static final int IS_LEGACY_VERSION = 4;
    private static final int IS_FOREGROUND_APP = 5;
    private static final int WIFI_SCAN_ALLOWED_APPS = 6;
    private static final int IS_ACTIVE_NW_SCORER = 7;
    private static final int FINE_LOCATION_ACCESS = 8;
    private static final int COARSE_LOCATION_ACCESS = 9;
    private static final int IS_LOCATION_ENABLED = 10;
    private static final int IS_PACKAGE_PRESENT = 11;
    /*Add new Permissions here and increment MAX_PERMISSIONS*/
    private static final int MAX_PERMISSIONS = 12;
    private static final int NUM_TEST_CASES = 14;
    private final int mPid = 0;
    private int mPeerMacAddressPermission;
    private int mInteractAcrossUsersFullPermission;
    private int mWifiScanAllowApps;
    private int mUid;
    private int mFineLocationPermission;
    private int mCoarseLocationPermission;
    private int mAllowFineLocationApps;
    private int mAllowCoraseLocationApps;
    private String mPkgNameOfTopActivity;
    private final int mCallingUser = UserHandle.USER_CURRENT_OR_SELF;
    private int mCurrentUser;
    private final String mManifestStringCoarse =
            Manifest.permission.ACCESS_COARSE_LOCATION;
    private final String mManifestStringFine =
            Manifest.permission.ACCESS_FINE_LOCATION;
    private int mLocationModeSetting;
    private boolean mThrowSecurityException;
    private int mTargetVersion;

    /**
    * Set up test vectors for each test case
    */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setupPermissionStrings();
        setupTestCases();
    }

    private void setupPermissionStrings() {
        sPermissions = new String[MAX_PERMISSIONS];
        sPermissions[CAN_READ_PEER_MAC_ADDRESS] = "canReadPeerMacAddress";
        sPermissions[HAS_INTERACT_USERS_FULL] = "hasInteractUsersFull";
        sPermissions[IS_USER_CURRENT] = "isUserCurrent";
        sPermissions[IS_PROFILE_CURRENT] = "isProfileCurrent";
        sPermissions[IS_LEGACY_VERSION] = "notLegacyVersion";
        sPermissions[IS_FOREGROUND_APP] = "isForegroundApp";
        sPermissions[WIFI_SCAN_ALLOWED_APPS] = "isWifiScanAllowedApps";
        sPermissions[FINE_LOCATION_ACCESS] = "FineLocation";
        sPermissions[COARSE_LOCATION_ACCESS] = "CoarseLocation";
        sPermissions[IS_ACTIVE_NW_SCORER] = "isActiveNwScorer";
        sPermissions[IS_LOCATION_ENABLED] = "isLocationModeEnabled";
        sPermissions[IS_PACKAGE_PRESENT] = "isPackagePresent";
    }

    private void setupTestCases() {
        sTestVector = new int[NUM_TEST_CASES];
        int index = 0;
        sTestVector[index++] =  ((0x1 << CAN_READ_PEER_MAC_ADDRESS)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << IS_FOREGROUND_APP)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_USER_CURRENT)
            | (0x1 << HAS_INTERACT_USERS_FULL));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << CAN_READ_PEER_MAC_ADDRESS)
            | (0x1 << IS_FOREGROUND_APP)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_USER_CURRENT)
            | (0x1 << HAS_INTERACT_USERS_FULL));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << IS_ACTIVE_NW_SCORER)
            | (0x1 << IS_FOREGROUND_APP)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_USER_CURRENT));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << IS_FOREGROUND_APP)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_USER_CURRENT));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << IS_FOREGROUND_APP)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_PROFILE_CURRENT)
            | (0x1 << HAS_INTERACT_USERS_FULL));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << IS_FOREGROUND_APP)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_PROFILE_CURRENT));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << IS_FOREGROUND_APP)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LEGACY_VERSION)
            | (0x1 << IS_FOREGROUND_APP));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_FOREGROUND_APP));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LOCATION_ENABLED)
            | (0x1 << FINE_LOCATION_ACCESS));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LOCATION_ENABLED)
            | (0x1 << COARSE_LOCATION_ACCESS));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LOCATION_ENABLED)
            | (0x1 << COARSE_LOCATION_ACCESS)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_USER_CURRENT)
            | (0x1 << HAS_INTERACT_USERS_FULL));
        sTestVector[index++] =  ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LOCATION_ENABLED)
            | (0x1 << COARSE_LOCATION_ACCESS)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS)
            | (0x1 << IS_PROFILE_CURRENT));
        sTestVector[index++] = ((0x1 << IS_PACKAGE_PRESENT)
            | (0x1 << IS_LOCATION_ENABLED)
            | (0x1 << COARSE_LOCATION_ACCESS)
            | (0x1 << WIFI_SCAN_ALLOWED_APPS));
        /* Add new test case here and update NUM_TEST_CASES */
        if (index > NUM_TEST_CASES) {
            throw new IllegalStateException(
            "Test cases limits exceeded.");
        }
    }

    /**
     * This function tests all possible code paths
     * in WifiPermissionsUtil by means of test
     * vectors that create various scenarios
     */
    @Test (expected = SecurityException.class)
    public void test() throws Exception {
        boolean output = true;
        for (int i = 0; i < NUM_TEST_CASES; i++) {
            setupTestVars(sTestVector[i]);
            setupMocks();
            setupMockInterface(sTestVector[i]);
            mCodeUnderTest = new WifiPermissionsUtil(mMockPermissionsWrapper,
                    mMockContext, mMockWifiSettingsStore, mMockUserManager);
            try {
                output = mCodeUnderTest.canAccessScanResults(sPkgName, mUid, mPid, mTargetVersion);
            } catch (SecurityException e) {
                throw e;
            }
            /**
            * Validate code under test by comparing its
            * output to expected output for each use case
            */
            validateResult(sTestVector[i], output);
        }
    }

    private void setupMocks() throws Exception {
        when(mMockContext.checkPermission(
            eq(android.Manifest.permission.PEERS_MAC_ADDRESS), anyInt(), anyInt()))
            .thenReturn(mPeerMacAddressPermission);
        when(mMockContext.checkPermission(
            eq(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL), anyInt(), anyInt()))
            .thenReturn(mInteractAcrossUsersFullPermission);
        when(mMockPkgMgr.getApplicationInfo(sPkgName, 0))
            .thenReturn(mMockApplInfo);
        when(mMockContext.getPackageManager()).thenReturn(mMockPkgMgr);
        when(mMockAppOps.noteOp(AppOpsManager.OP_WIFI_SCAN, mUid, sPkgName))
            .thenReturn(mWifiScanAllowApps);
        when(mMockAppOps.noteOp(AppOpsManager.OP_FINE_LOCATION, mUid, sPkgName))
            .thenReturn(mAllowFineLocationApps);
        when(mMockAppOps.noteOp(AppOpsManager.OP_COARSE_LOCATION, mUid, sPkgName))
            .thenReturn(mAllowCoraseLocationApps);
        if (mThrowSecurityException) {
            doThrow(new SecurityException("Package " + sPkgName + " doesn't belong"
                    + " to application bound to user " + mUid))
                    .when(mMockAppOps).checkPackage(mUid, sPkgName);
        }
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE))
            .thenReturn(mMockAppOps);
        when(mMockUserManager.getProfiles(mCurrentUser))
            .thenReturn(Arrays.asList(mMockUserInfo));
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.getSystemService(Context.USER_SERVICE))
            .thenReturn(mMockUserManager);
    }

    private void setupTestVars(int testCase) {
        if (getValAtOffset(testCase, IS_PACKAGE_PRESENT)) {
            mThrowSecurityException = false;
        } else {
            mThrowSecurityException = true;
        }
        if (getValAtOffset(testCase, IS_PROFILE_CURRENT)) {
            mMockUserInfo.id = mCallingUser;
        } else {
            mMockUserInfo.id = mCallingUser + 1;
        }
        if (getValAtOffset(testCase, IS_LEGACY_VERSION)) {
            mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.GINGERBREAD;
            mTargetVersion = Build.VERSION_CODES.M;
        } else {
            mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.M;
            mTargetVersion = Build.VERSION_CODES.M;
        }
        if (getValAtOffset(testCase, CAN_READ_PEER_MAC_ADDRESS)) {
            mPeerMacAddressPermission = PackageManager.PERMISSION_GRANTED;
        } else {
            mPeerMacAddressPermission = PackageManager.PERMISSION_DENIED;
        }
        if (getValAtOffset(testCase, IS_FOREGROUND_APP)) {
            mPkgNameOfTopActivity = sPkgName;
        } else {
            mPkgNameOfTopActivity = "T" + sPkgName;
        }
        if (getValAtOffset(testCase, HAS_INTERACT_USERS_FULL)) {
            mInteractAcrossUsersFullPermission = PackageManager.PERMISSION_GRANTED;
        } else {
            mInteractAcrossUsersFullPermission = PackageManager.PERMISSION_DENIED;
        }
        if (getValAtOffset(testCase, IS_LOCATION_ENABLED)) {
            mLocationModeSetting = Settings.Secure.LOCATION_MODE_OFF + 1;
        } else {
            mLocationModeSetting = Settings.Secure.LOCATION_MODE_OFF;
        }
        if (getValAtOffset(testCase, IS_USER_CURRENT)) {
            mCurrentUser = UserHandle.USER_CURRENT_OR_SELF;
        } else {
            mCurrentUser = UserHandle.USER_SYSTEM;
        }
        if (getValAtOffset(testCase, WIFI_SCAN_ALLOWED_APPS)) {
            mWifiScanAllowApps = AppOpsManager.MODE_ALLOWED;
            mUid = MANAGED_PROFILE_UID;
        } else {
            mWifiScanAllowApps = AppOpsManager.MODE_ERRORED;
            mUid = OTHER_USER_UID;
        }
        if (getValAtOffset(testCase, FINE_LOCATION_ACCESS)) {
            mAllowFineLocationApps = AppOpsManager.MODE_ALLOWED;
            mFineLocationPermission = PackageManager.PERMISSION_GRANTED;
        } else {
            mAllowFineLocationApps = AppOpsManager.MODE_ERRORED;
            mFineLocationPermission = PackageManager.PERMISSION_DENIED;
        }
        if (getValAtOffset(testCase, COARSE_LOCATION_ACCESS)) {
            mCoarseLocationPermission = PackageManager.PERMISSION_GRANTED;
            mAllowCoraseLocationApps = AppOpsManager.MODE_ALLOWED;
        } else {
            mCoarseLocationPermission = PackageManager.PERMISSION_DENIED;
            mAllowCoraseLocationApps = AppOpsManager.MODE_ERRORED;
        }
    }

    private void setupMockInterface(int testCase) {
        BinderUtil.setUid(mUid);
        when(mMockPermissionsWrapper.getCallingUserId(mUid)).thenReturn(mCallingUser);
        when(mMockPermissionsWrapper.getCurrentUser()).thenReturn(mCurrentUser);
        when(mMockPermissionsWrapper.isCallerActiveNwScorer(mUid))
            .thenReturn(getValAtOffset(testCase, IS_ACTIVE_NW_SCORER));
        when(mMockPermissionsWrapper.getUidPermission(mManifestStringFine, mUid))
            .thenReturn(mFineLocationPermission);
        when(mMockPermissionsWrapper.getUidPermission(mManifestStringCoarse, mUid))
            .thenReturn(mCoarseLocationPermission);
        when(mMockWifiSettingsStore.getLocationModeSetting(mMockContext))
            .thenReturn(mLocationModeSetting);
        when(mMockPermissionsWrapper.getTopPkgName()).thenReturn(mPkgNameOfTopActivity);
    }

    private static boolean computeResult(int input) {
        boolean isLegacyForeground = (getValAtOffset(input, IS_LEGACY_VERSION)
                && getValAtOffset(input, IS_FOREGROUND_APP));
        if ((getValAtOffset(input, CAN_READ_PEER_MAC_ADDRESS)
                || getValAtOffset(input, IS_ACTIVE_NW_SCORER))
                || (isLegacyForeground
                    || (getValAtOffset(input, IS_LOCATION_ENABLED)
                    && (getValAtOffset(input, FINE_LOCATION_ACCESS)
                    || getValAtOffset(input, COARSE_LOCATION_ACCESS))))) {
            if (getValAtOffset(input, WIFI_SCAN_ALLOWED_APPS)
                    && (getValAtOffset(input, IS_USER_CURRENT)
                    || getValAtOffset(input, IS_PROFILE_CURRENT))
                    && getValAtOffset(input, HAS_INTERACT_USERS_FULL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean getValAtOffset(int input, int offset) {
        if (offset >= MAX_PERMISSIONS) {
            throw new IllegalStateException(
               "Unknown Permissions check");
        }
        return ((input & (0x1 << offset)) != 0);
    }

    private static String getFailureMessage(int input) {
        StringBuilder newString = new StringBuilder();
        newString.append("Test input :" + input + "\n");
        for (int i = 0; i < MAX_PERMISSIONS; i++)  {
            if (getValAtOffset(input, i)) {
                newString.append(sPermissions[i] + ":\tTrue ");
            } else {
                newString.append(sPermissions[i] + ":\tFalse ");
            }
            newString.append("\n");
        }
        return newString.toString();
    }

    private static void validateResult(int input, boolean output) {
        assertEquals(getFailureMessage(input), computeResult(input), output);
    }
}
