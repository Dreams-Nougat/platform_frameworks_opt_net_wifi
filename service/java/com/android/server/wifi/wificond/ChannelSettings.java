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
 * ChannelSettings for wificond
 *
 * @hide
 */
public class ChannelSettings implements Parcelable {
    public int frequency;

    /** public constructor */
    public ChannelSettings() { }

    /** copy constructor */
    public ChannelSettings(ChannelSettings source) {
        frequency = source.frequency;
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** implement Parcelable interface */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(frequency);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<ChannelSettings> CREATOR =
            new Parcelable.Creator<ChannelSettings>() {
        @Override
        public ChannelSettings createFromParcel(Parcel in) {
            ChannelSettings result = new ChannelSettings();
            result.frequency = in.readInt();
            return result;
        }

        @Override
        public ChannelSettings[] newArray(int size) {
            return new ChannelSettings[size];
        }
    };
}
