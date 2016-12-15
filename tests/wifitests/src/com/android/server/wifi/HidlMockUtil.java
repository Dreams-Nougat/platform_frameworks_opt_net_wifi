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

import android.app.test.MockAnswerUtil;
import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiIface;
import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;

import java.util.ArrayList;

/**
 * Utilities to mock HIDL Wi-Fi objects.
 */
public class HidlMockUtil {
    public static WifiStatus statusOk;
    public static WifiStatus statusFail;

    static {
        statusOk = new WifiStatus();
        statusOk.code = WifiStatusCode.SUCCESS;

        statusFail = new WifiStatus();
        statusFail.code = WifiStatusCode.ERROR_UNKNOWN;
        statusFail.description = "mock fail status object";
    }

    public static class GetChipIdsAnswer extends MockAnswerUtil.AnswerWithArguments {
        private boolean mSucceed = true;
        private boolean mThrowExceptionOnce = false;
        private ArrayList<Integer> mChipIds;

        public GetChipIdsAnswer(boolean succeed, boolean throwExceptionOnce,
                ArrayList<Integer> chipIds) {
            mSucceed = succeed;
            mThrowExceptionOnce = throwExceptionOnce;
            mChipIds = chipIds;
        }

        public void answer(IWifi.getChipIdsCallback cb) {
            if (mThrowExceptionOnce) {
                mThrowExceptionOnce = false;
                throw new RuntimeException("CreateNanIfaceAnswer - failure");
            }

            if (mSucceed) {
                cb.onValues(statusOk, mChipIds);
            } else {
                cb.onValues(statusFail, null);
            }
        }
    }

    public static class GetChipAnswer extends MockAnswerUtil.AnswerWithArguments {
        private boolean mSucceed = true;
        private boolean mThrowExceptionOnce = false;
        private IWifiChip mWifiChip;

        public GetChipAnswer(boolean succeed, boolean throwExceptionOnce, IWifiChip wifiChip) {
            mSucceed = succeed;
            mThrowExceptionOnce = throwExceptionOnce;
            mWifiChip = wifiChip;
        }

        public void answer(int chipId, IWifi.getChipCallback cb) {
            if (mThrowExceptionOnce) {
                mThrowExceptionOnce = false;
                throw new RuntimeException("CreateNanIfaceAnswer - failure");
            }

            int localChipId = chipId; // strange: if not here then method signature not valid!?
            if (mSucceed) {
                cb.onValues(statusOk, mWifiChip);
            } else {
                cb.onValues(statusFail, null);
            }
        }
    }

    public static class CreateNanIfaceAnswer extends MockAnswerUtil.AnswerWithArguments {
        private boolean mSucceed = true;
        private boolean mThrowExceptionOnce = false;
        private IWifiNanIface mWifiNanIface;

        public CreateNanIfaceAnswer(boolean succeed, boolean throwExceptionOnce,
                IWifiNanIface wifiNanIface) {
            mSucceed = succeed;
            mThrowExceptionOnce = throwExceptionOnce;
            mWifiNanIface = wifiNanIface;
        }

        public void answer(IWifiChip.createNanIfaceCallback cb) {
            if (mThrowExceptionOnce) {
                mThrowExceptionOnce = false;
                throw new RuntimeException("CreateNanIfaceAnswer - failure");
            }

            if (mSucceed) {
                cb.onValues(statusOk, mWifiNanIface);
            } else {
                cb.onValues(statusFail, null);
            }
        }
    }

    public static class GetNameAnswer extends MockAnswerUtil.AnswerWithArguments {
        private boolean mSucceed = true;
        private boolean mThrowExceptionOnce = false;
        private String mName;

        public GetNameAnswer(boolean succeed, boolean throwExceptionOnce, String name) {
            mSucceed = succeed;
            mThrowExceptionOnce = throwExceptionOnce;
            mName = name;
        }

        public void answer(IWifiIface.getNameCallback cb) {
            if (mThrowExceptionOnce) {
                mThrowExceptionOnce = false;
                throw new RuntimeException("CreateNanIfaceAnswer - failure");
            }

            if (mSucceed) {
                cb.onValues(statusOk, mName);
            } else {
                cb.onValues(statusFail, null);
            }
        }
    }
}
