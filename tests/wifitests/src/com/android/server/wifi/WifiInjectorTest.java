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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link WifiInjector}. */
@SmallTest
public class WifiInjectorTest {

    @Mock private Context mContext;
    private WifiInjector mInjector;

    /**
     * Method to initialize mocks for tests.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }


    /**
     * Test that a WifiInjector cannot be created with a null Context.
     */
    @Test(expected = IllegalStateException.class)
    public void testShouldNotCreateWifiInjectorWithNullContext() {
        mInjector = WifiInjector.getInstance(null);
    }

    /**
     * Test that attempting to call the WifiInjector a second time returns the same instance
     */
    @Test(expected = NullPointerException.class)
    public void testShouldNotBeAbleToCreateMoreThanOneWifiInjector() {
        try {
            mInjector = WifiInjector.getInstance(mContext);
        } catch (NullPointerException e) {
        }
        assertEquals("Different instances of WifiInjector", mInjector.hashCode(),
                WifiInjector.getInstance(mContext).hashCode());
    }
}
