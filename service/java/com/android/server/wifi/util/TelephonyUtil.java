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

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

/**
 * Utilities for the Wifi Service to interact with telephony.
 */
public class TelephonyUtil {
    public static final String TAG = "TelephonyUtil";

    /**
     * Get the identity for the current SIM or null if the sim is not available
     */
    public static String getSimIdentity(TelephonyManager tm, int eapMethod) {
        if (tm == null) {
            Log.e(TAG, "No valid TelephonyManager");
            return null;
        }
        String imsi = tm.getSubscriberId();
        String mccMnc = "";

        if (tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
            mccMnc = tm.getSimOperator();
        }

        return buildIdentity(eapMethod, imsi, mccMnc);
    }

    /**
     * create Permanent Identity base on IMSI,
     *
     * rfc4186 & rfc4187:
     * identity = usernam@realm
     * with username = prefix | IMSI
     * and realm is derived MMC/MNC tuple according 3GGP spec(TS23.003)
     */
    private static String buildIdentity(int eapMethod, String imsi, String mccMnc) {
        if (imsi == null || imsi.isEmpty()) {
            return null;
        }

        String prefix;
        if (eapMethod == WifiEnterpriseConfig.Eap.SIM) {
            prefix = "1";
        } else if (eapMethod == WifiEnterpriseConfig.Eap.AKA) {
            prefix = "0";
        } else if (eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME) {
            prefix = "6";
        } else {  // not a valide EapMethod
            return null;
        }

        /* extract mcc & mnc from mccMnc */
        String mcc;
        String mnc;
        if (mccMnc != null && !mccMnc.isEmpty()) {
            mcc = mccMnc.substring(0, 3);
            mnc = mccMnc.substring(3);
            if (mnc.length() == 2) {
                mnc = "0" + mnc;
            }
        } else {
            // extract mcc & mnc from IMSI, assume mnc size is 3
            mcc = imsi.substring(0, 3);
            mnc = imsi.substring(3, 6);
        }

        return prefix + imsi + "@wlan.mnc" + mnc + ".mcc" + mcc + ".3gppnetwork.org";
    }

    /**
     * Checks if the network is a sim config.
     *
     * @param config Config corresponding to the network.
     * @return true if it is a sim config, false otherwise.
     */
    public static boolean isSimConfig(WifiConfiguration config) {
        if (config == null || config.enterpriseConfig == null) {
            return false;
        }

        return isSimEapMethod(config.enterpriseConfig.getEapMethod());
    }

    /**
     * Checks if the network is a sim config.
     *
     * @param method
     * @return true if it is a sim config, false otherwise.
     */
    public static boolean isSimEapMethod(int eapMethod) {
        return eapMethod == WifiEnterpriseConfig.Eap.SIM
                || eapMethod == WifiEnterpriseConfig.Eap.AKA
                || eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME;
    }

    // TODO replace some of this code with Byte.parseByte
    private static int parseHex(char ch) {
        if ('0' <= ch && ch <= '9') {
            return ch - '0';
        } else if ('a' <= ch && ch <= 'f') {
            return ch - 'a' + 10;
        } else if ('A' <= ch && ch <= 'F') {
            return ch - 'A' + 10;
        } else {
            throw new NumberFormatException("" + ch + " is not a valid hex digit");
        }
    }

    private static byte[] parseHex(String hex) {
        return parseHex(hex, true);
    }

    private static byte[] parseHex(String hex, boolean needLength) {
        /* This only works for good input; don't throw bad data at it */
        if (hex == null) {
            return new byte[0];
        }

        if (hex.length() % 2 != 0) {
            throw new NumberFormatException(hex + " is not a valid hex string");
        }

        byte[] result = null;
        if (needLength) {
            result = new byte[(hex.length()) / 2 + 1];
            result[0] = (byte) ((hex.length()) / 2);
        } else {
            result = new byte[(hex.length()) / 2];
        }

        for (int i = 0, j = 1; i < hex.length(); i += 2, j++) {
            int val = parseHex(hex.charAt(i)) * 16 + parseHex(hex.charAt(i + 1));
            byte b = (byte) (val & 0xFF);
            if (needLength) {
                result[j] = b;
            } else {
                result[j - 1] = b;
            }
        }

        return result;
    }

    private static String makeHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String makeHex(byte[] bytes, int from, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[from + i]));
        }
        return sb.toString();
    }

    private static byte[] concatHex(byte[] array1, byte[] array2) {

        int len = array1.length + array2.length;

        byte[] result = new byte[len];

        int index = 0;
        if (array1.length != 0) {
            for (byte b : array1) {
                result[index] = b;
                index++;
            }
        }

        if (array2.length != 0) {
            for (byte b : array2) {
                result[index] = b;
                index++;
            }
        }

        return result;
    }

    public static String getGsmSimAuthResponse(String[] requestData, TelephonyManager tm) {
        if (tm == null) {
            Log.e(TAG, "No valid TelephonyManager");
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String challenge : requestData) {
            if (challenge == null || challenge.isEmpty()) {
                continue;
            }
            Log.d(TAG, "RAND = " + challenge);

            byte[] rand = null;
            try {
                rand = parseHex(challenge);
            } catch (NumberFormatException e) {
                Log.e(TAG, "malformed challenge");
                continue;
            }

            String base64Challenge = Base64.encodeToString(rand, Base64.NO_WRAP);

            // Try USIM first for authentication.
            String tmResponse = tm.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                    TelephonyManager.AUTHTYPE_EAP_SIM, base64Challenge);
            boolean isLengthRequired = true;
            if (tmResponse == null) {
                // Then, in case of failure, issue may be due to sim type, retry as a simple sim
                tmResponse = tm.getIccAuthentication(TelephonyManager.APPTYPE_SIM,
                        TelephonyManager.AUTHTYPE_EAP_SIM, base64Challenge);

                if (tmResponse == null) {
                    /*
                     * add a new app type to handle 2G autehtnication @ 3GPP TS 11.11:
                     * Standard       Cellular_auth     Type Command
                     *
                     * 3GPP TS 31.102 3G_authentication [Length][RAND][Length][AUTN]
                     *                         [Length][RES][Length][CK][Length][IK] and more
                     *                2G_authentication [Length][RAND]
                     *                         [Length][SRES][Length][Cipher Key Kc]
                     * 3GPP TS 11.11  2G_authentication [RAND]
                     *                         [SRES][Cipher Key Kc]
                     */
                    try {
                        rand = parseHex(challenge, false);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "malformed challenge");
                        continue;
                    }
                    base64Challenge = Base64.encodeToString(
                            rand, Base64.NO_WRAP);
                    /* SIM, appType passed in should be APPTYPE_SIM */
                    tmResponse = tm.getIccAuthentication(tm.APPTYPE_SIM,
                            tm.AUTHTYPE_EAP_SIM, base64Challenge);
                    isLengthRequired = false;
                }
            }
            Log.v(TAG, "Raw Response - " + tmResponse);

            if (tmResponse == null || tmResponse.length() <= 4) {
                Log.e(TAG, "bad response - " + tmResponse);
                return null;
            }

            byte[] result = Base64.decode(tmResponse, Base64.DEFAULT);
            Log.v(TAG, "Hex Response -" + makeHex(result));
            if (!isLengthRequired) {
                String sres = makeHex(result, 0, 4);
                String kc = makeHex(result, 4, 8);
                sb.append(":" + kc + ":" + sres);
                Log.v(TAG, "kc:" + kc + " sres:" + sres);
            } else {
                int sresLen = result[0];
                if (sresLen >= result.length) {
                    Log.e(TAG, "malfomed response - " + tmResponse);
                    return null;
                }
                String sres = makeHex(result, 1, sresLen);
                int kcOffset = 1 + sresLen;
                if (kcOffset >= result.length) {
                    Log.e(TAG, "malfomed response - " + tmResponse);
                    return null;
                }
                int kcLen = result[kcOffset];
                if (kcOffset + kcLen > result.length) {
                    Log.e(TAG, "malfomed response - " + tmResponse);
                    return null;
                }
                String kc = makeHex(result, 1 + kcOffset, kcLen);
                sb.append(":" + kc + ":" + sres);
                Log.v(TAG, "kc:" + kc + " sres:" + sres);
            }
        }

        return sb.toString();
    }

    /**
     * Data supplied when making a SIM Auth Request
     */
    public static class SimAuthRequestData {
        public SimAuthRequestData() {}
        public SimAuthRequestData(int networkId, int protocol, String ssid, String[] data) {
            this.networkId = networkId;
            this.protocol = protocol;
            this.ssid = ssid;
            this.data = data;
        }

        public int networkId;
        public int protocol;
        public String ssid;
        // EAP-SIM: data[] contains the 3 rand, one for each of the 3 challenges
        // EAP-AKA/AKA': data[] contains rand & authn couple for the single challenge
        public String[] data;
    }

    /**
     * The response to a SIM Auth request if successful
     */
    public static class SimAuthResponseData {
        public SimAuthResponseData(String type, String response) {
            this.type = type;
            this.response = response;
        }

        public String type;
        public String response;
    }

    public static SimAuthResponseData get3GAuthResponse(SimAuthRequestData requestData,
            TelephonyManager tm) {
        StringBuilder sb = new StringBuilder();
        byte[] rand = null;
        byte[] authn = null;
        String resType = "UMTS-AUTH";

        if (requestData.data.length == 2) {
            try {
                rand = parseHex(requestData.data[0]);
                authn = parseHex(requestData.data[1]);
            } catch (NumberFormatException e) {
                Log.e(TAG, "malformed challenge");
            }
        } else {
            Log.e(TAG, "malformed challenge");
        }

        String tmResponse = "";
        if (rand != null && authn != null) {
            String base64Challenge = Base64.encodeToString(concatHex(rand, authn), Base64.NO_WRAP);
            if (tm != null) {
                tmResponse = tm.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                        TelephonyManager.AUTHTYPE_EAP_AKA, base64Challenge);
                Log.v(TAG, "Raw Response - " + tmResponse);
            } else {
                Log.e(TAG, "No valid TelephonyManager");
            }
        }

        boolean goodReponse = false;
        if (tmResponse != null && tmResponse.length() > 4) {
            byte[] result = Base64.decode(tmResponse, Base64.DEFAULT);
            Log.e(TAG, "Hex Response - " + makeHex(result));
            byte tag = result[0];
            if (tag == (byte) 0xdb) {
                Log.v(TAG, "successful 3G authentication ");
                int resLen = result[1];
                String res = makeHex(result, 2, resLen);
                int ckLen = result[resLen + 2];
                String ck = makeHex(result, resLen + 3, ckLen);
                int ikLen = result[resLen + ckLen + 3];
                String ik = makeHex(result, resLen + ckLen + 4, ikLen);
                sb.append(":" + ik + ":" + ck + ":" + res);
                Log.v(TAG, "ik:" + ik + "ck:" + ck + " res:" + res);
                goodReponse = true;
            } else if (tag == (byte) 0xdc) {
                Log.e(TAG, "synchronisation failure");
                int autsLen = result[1];
                String auts = makeHex(result, 2, autsLen);
                resType = "UMTS-AUTS";
                sb.append(":" + auts);
                Log.v(TAG, "auts:" + auts);
                goodReponse = true;
            } else {
                Log.e(TAG, "bad response - unknown tag = " + tag);
            }
        } else {
            Log.e(TAG, "bad response - " + tmResponse);
        }

        if (goodReponse) {
            String response = sb.toString();
            Log.v(TAG, "Supplicant Response -" + response);
            return new SimAuthResponseData(resType, response);
        } else {
            return null;
        }
    }
}
