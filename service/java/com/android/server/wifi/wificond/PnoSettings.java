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

import java.util.ArrayList;

/**
 * PnoSettings for wificond
 *
 * @hide
 */
public class PnoSettings implements Parcelable {
    int intervalMs;
    int min2gRssi;
    int min5gRssi;
    public ArrayList<PnoNetwork> pnoNetworks;

    /** public constructor */
    public PnoSettings() { }

    /** copy constructor */
    public PnoSettings(PnoSettings source) {
        pnoNetworks = new ArrayList<>(source.pnoNetworks);
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** implement Parcelable interface */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(intervalMs);
        out.writeInt(min2gRssi);
        out.writeInt(min5gRssi);
        if (pnoNetworks == null) {
            out.writeInt(0);
        } else {
            out.writeInt(pnoNetworks.size());
            for (PnoNetwork network : pnoNetworks) {
                network.writeToParcel(out, flags);
            }
        }
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<PnoSettings> CREATOR =
            new Parcelable.Creator<PnoSettings>() {
        @Override
        public PnoSettings createFromParcel(Parcel in) {
            PnoSettings result = new PnoSettings();
            intervalMs = in.readInt();
            min2gRssi = in.readInt();
            min5gRssi = in.readInt();

            int numberOfPnoNetworks = in.readInt();
            if (numberOfPnoNetworks != 0) {
                result.pnoNetworks = new ArrayList<PnoNetwork>(0);
            }
            for (int i = 0; i < numberOfPnoNetworks; i++) {
                PnoNetwork network = PnoNetwork.CREATOR.createFromParcel(in);
                result.pnoNetworks.add(network);
            }

            return result;
        }

        @Override
        public PnoSettings[] newArray(int size) {
            return new PnoSettings[size];
        }
    };
}
