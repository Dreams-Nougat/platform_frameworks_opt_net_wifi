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
import static org.junit.Assert.assertTrue;

import android.test.suitebuilder.annotation.SmallTest;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Unit tests for {@link com.android.server.wifi.ByteBufferReader}.
 */
@SmallTest
public class ByteBufferReaderTest {
    /**
     * Verify the expectations when reading an integer that results in a buffer underflow.
     *
     * @throws Exception
     */
    @Test(expected = BufferUnderflowException.class)
    public void readIntegerWithBufferUnderflow() throws Exception {
        byte[] data = new byte[1];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        ByteBufferReader.readInteger(buffer, buffer.order(), 2);
    }

    /**
     * Verify the expectations when reading an integer that exceeds the maximum integer size.
     *
     * @throws Exception
     */
    @Test(expected = IllegalArgumentException.class)
    public void readIntegerExceedingMaximumLength() throws Exception {
        int length = ByteBufferReader.MAXIMUM_INTEGER_SIZE + 1;
        byte[] data = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        ByteBufferReader.readInteger(buffer, buffer.order(), length);
    }

    /**
     * Verify the expectations when reading an integer with size less than the minimum.
     *
     * @throws Exception
     */
    @Test(expected = IllegalArgumentException.class)
    public void readIntegerLessThanMinimumLength() throws Exception {
        int length = ByteBufferReader.MINIMUM_INTEGER_SIZE - 1;
        byte[] data = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        ByteBufferReader.readInteger(buffer, buffer.order(), length);
    }

    /**
     * Verify the expectations when reading a minimum size integer.
     *
     * @throws Exception
     */
    @Test
    public void readIntegerWithMinimumSize() throws Exception {
        byte[] data = new byte[] {0x1};
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, ByteBufferReader.readInteger(buffer, buffer.order(), 1));
    }

    /**
     * Verify the expectations when reading a maximum size integer.
     *
     * @throws Exception
     */
    @Test
    public void readIntegerWithMaximumSize() throws Exception {
        byte[] data = new byte[] {0x1f, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11};

        // Little Endian parsing.
        ByteBuffer leBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long leValue = 0x110000000000001fL;
        assertEquals(leValue, ByteBufferReader.readInteger(leBuffer, leBuffer.order(),
                ByteBufferReader.MAXIMUM_INTEGER_SIZE));

        // Big Endian parsing.
        ByteBuffer beBuffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        long beValue = 0x1f00000000000011L;
        assertEquals(beValue, ByteBufferReader.readInteger(beBuffer, beBuffer.order(),
                ByteBufferReader.MAXIMUM_INTEGER_SIZE));
    }

    /**
     * Verify the expectations when reading a string with negative length.
     *
     * @throws Exception
     */
    @Test(expected = NegativeArraySizeException.class)
    public void readStringWithNegativeLength() throws Exception {
        ByteBufferReader.readString(ByteBuffer.wrap(new byte[10]), -1, StandardCharsets.US_ASCII);
    }

    /**
     * Verify the expectations when reading a string with zero length.
     *
     * @throws Exception
     */
    @Test
    public void readStringWithZeroLength() throws Exception {
        String str = ByteBufferReader.readString(
                ByteBuffer.wrap(new byte[10]), 0, StandardCharsets.US_ASCII);
        assertTrue(str.isEmpty());
    }

    /**
     * Verify the expectations for reading a valid string.
     *
     * @throws Exception
     */
    @Test
    public void readString() throws Exception {
        String expectedValue = "Hello World";
        ByteBuffer buffer = ByteBuffer.wrap(expectedValue.getBytes(StandardCharsets.US_ASCII));
        String actualValue = ByteBufferReader.readString(
                buffer, buffer.remaining(), StandardCharsets.US_ASCII);
        assertEquals(expectedValue, actualValue);
    }

    /**
     * Verify the expectations for reading a buffer that contained string length and value.
     *
     * @throws Exception
     */
    @Test
    public void readByteLengthAndString() throws Exception {
        String expectedValue = "Hello World";
        ByteBuffer buffer = ByteBuffer.allocate(expectedValue.length() + 1);
        buffer.put((byte) expectedValue.length());
        buffer.put(expectedValue.getBytes(StandardCharsets.US_ASCII));
        // Rewind the buffer's position to the beginning for reading.
        buffer.position(0);
        String actualValue =
                ByteBufferReader.readByteLengthAndString(buffer, StandardCharsets.US_ASCII);
        assertEquals(expectedValue, actualValue);
    }
}
