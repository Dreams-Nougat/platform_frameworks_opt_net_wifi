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

package com.android.server.wifi.wificond;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * PnoNetwork for wificond
 *
 * @hide
 */
public class PnoNetwork implements Parcelable {
    public byte[] ssid;

    /** public constructor */
    public PnoNetwork() { }

    /** copy constructor */
    public PnoNetwork(PnoNetwork source) {
        ssid = source.ssid.clone();
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** implement Parcelable interface */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(ssid.length);
        out.writeByteArray(ssid);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<PnoNetwork> CREATOR =
            new Parcelable.Creator<PnoNetwork>() {
        @Override
        public PnoNetwork createFromParcel(Parcel in) {
            PnoNetwork result = new PnoNetwork();
            result.ssid = new byte[in.readInt()];
            in.readByteArray(result.ssid);
            return result;
        }

        @Override
        public PnoNetwork[] newArray(int size) {
            return new PnoNetwork[size];
        }
    };
}
