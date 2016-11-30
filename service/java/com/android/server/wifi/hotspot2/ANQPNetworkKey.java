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

package com.android.server.wifi.hotspot2;

import android.text.TextUtils;

/**
 * Unique key for identifying ESS (Extended Service Set).  Each ESS will/should contain the same
 * values for ANQP elements.
 */
public class ANQPNetworkKey {
    private final String mSSID;
    private final long mBSSID;
    private final long mHESSID;

    public ANQPNetworkKey(String ssid, long bssid, long hessid) {
        mSSID = ssid;
        mBSSID = bssid;
        mHESSID = hessid;
    }

    /**
     * Build an ANQP network key suitable for the granularity of the key space as follows:
     *
     * HESSID   domainID    Key content Rationale
     * -------- ----------- ----------- --------------------
     * n/a      zero        SSID/BSSID  Domain ID indicates unique AP info
     * not set  set         SSID        Standard definition of an ESS
     * set      set         HESSID      The ESS is defined by the HESSID
     *
     * @param ssid The SSID of the AP
     * @param bssid The BSSID of the AP
     * @param hessid The HESSID of the AP
     * @param anqpDomainId The ANQP Domain ID of the AP
     * @return {@link ANQPNetworkKey}
     */
    public static ANQPNetworkKey buildKey(String ssid, long bssid, long hessid, int anqpDomainId) {
        if (anqpDomainId == 0) {
            return new ANQPNetworkKey(ssid, bssid, 0);
        } else if (hessid != 0L) {
            return new ANQPNetworkKey(null, 0, hessid);
        }
        return new ANQPNetworkKey(ssid, 0, 0);
    }

    @Override
    public int hashCode() {
        if (mHESSID != 0) {
            return (int)((mHESSID >>> 32) * 31 + mHESSID);
        } else if (mBSSID != 0) {
            return (int)((mSSID.hashCode() * 31 + (mBSSID >>> 32)) * 31 + mBSSID);
        } else {
            return mSSID.hashCode();
        }
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        }
        if (!(thatObject instanceof ANQPNetworkKey)) {
            return false;
        }
        ANQPNetworkKey that = (ANQPNetworkKey) thatObject;
        return TextUtils.equals(that.mSSID, mSSID)&&
                that.mBSSID == mBSSID &&
                that.mHESSID == mHESSID;
    }

    @Override
    public String toString() {
        if (mHESSID != 0L) {
            return "HESSID:" + Utils.macToString(mHESSID);
        } else if (mBSSID != 0L) {
            return Utils.macToString(mBSSID) +
                    ":<" + Utils.toUnicodeEscapedString(mSSID) + ">";
        } else {
            return '<' + Utils.toUnicodeEscapedString(mSSID) + '>';
        }
    }
}
