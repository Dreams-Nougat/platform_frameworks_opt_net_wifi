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
 * SingleScanSettings for wificond
 *
 * @hide
 */
public class SingleScanSettings implements Parcelable {
    public boolean isFullScan;
    public ArrayList<ChannelSettings> channelSettings;
    public ArrayList<HiddenNetwork> hiddenNetworks;

    /** public constructor */
    public SingleScanSettings() { }

    /** copy constructor */
    public SingleScanSettings(SingleScanSettings source) {
        isFullScan = source.isFullScan;
        channelSettings = new ArrayList<>(source.channelSettings);
        hiddenNetworks = new ArrayList<>(source.hiddenNetworks);
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** implement Parcelable interface */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(isFullScan ? 1 : 0);
        if (channelSettings == null) {
            out.writeInt(0);
        } else {
            out.writeInt(channelSettings.size());
            for (ChannelSettings channel : channelSettings) {
                channel.writeToParcel(out, flags);
            }
        }
        if (hiddenNetworks == null) {
            out.writeInt(0);
        } else {
            out.writeInt(hiddenNetworks.size());
            for (HiddenNetwork network : hiddenNetworks) {
                network.writeToParcel(out, flags);
            }
        }
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<SingleScanSettings> CREATOR =
            new Parcelable.Creator<SingleScanSettings>() {
        @Override
        public SingleScanSettings createFromParcel(Parcel in) {
            SingleScanSettings result = new SingleScanSettings();
            result.isFullScan = in.readInt() != 0 ? true : false;
            int numberOfChannelSettings = in.readInt();
            if (numberOfChannelSettings != 0) {
                result.channelSettings = new ArrayList<ChannelSettings>(0);
            }
            for (int i = 0; i < numberOfChannelSettings; i++) {
                ChannelSettings channel = ChannelSettings.CREATOR.createFromParcel(in);
                result.channelSettings.add(channel);
            }

            int numberOfHiddenNetworks = in.readInt();
            if (numberOfHiddenNetworks != 0) {
                result.hiddenNetworks = new ArrayList<HiddenNetwork>(0);
            }
            for (int i = 0; i < numberOfHiddenNetworks; i++) {
                HiddenNetwork network = HiddenNetwork.CREATOR.createFromParcel(in);
                result.hiddenNetworks.add(network);
            }

            return result;
        }

        @Override
        public SingleScanSettings[] newArray(int size) {
            return new SingleScanSettings[size];
        }
    };
}
