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

package com.android.server.wifi.hotspot2.anqp;

import static org.junit.Assert.assertEquals;

import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.anqp.I18Name}.
 */
@SmallTest
public class I18NameTest {
    private static final String TEST_LANGUAGE = "en";
    private static final Locale TEST_LOCALE = Locale.forLanguageTag(TEST_LANGUAGE);
    private static final String TEST_TEXT = "Hello World";

    /**
     * Helper function for generating test data. The test data include the language code and
     * text field.
     *
     * @return byte[] of data
     * @throws IOException
     */
    private byte[] getTestData() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(TEST_LANGUAGE.getBytes(StandardCharsets.US_ASCII));
        stream.write(new byte[]{(byte) 0x0});  // Padding for language code.
        stream.write(TEST_TEXT.getBytes(StandardCharsets.UTF_8));
        return stream.toByteArray();
    }

    /**
     * Verify that BufferUnderflowException will be thrown when parsing from an empty buffer.
     *
     * @throws Exception
     */
    @Test(expected = BufferUnderflowException.class)
    public void parseEmptyBuffer() throws Exception {
        I18Name.parse(ByteBuffer.allocate(0));
    }

    /**
     * Verify that BufferUnderflowException will be thrown when the length field is set to more
     * than the actual buffer size.
     *
     * @throws Exception
     */
    @Test(expected = BufferUnderflowException.class)
    public void parseBufferWithLengthMoreThanBufferSize() throws Exception {
        byte[] testData = getTestData();
        // Allocate extra byte for storing the length field.
        ByteBuffer buffer = ByteBuffer.allocate(testData.length + 1);
        // Adding one extra byte to the length field to cause buffer underflow.
        buffer.put((byte) (testData.length + 1));
        buffer.put(testData);
        // Rewind buffer for reading.
        buffer.position(0);
        I18Name.parse(buffer);
    }

    /**
     * Verify that ProtocolException will be thrown when the length field is set to less than
     * the minimum.
     *
     * @throws Exception
     */
    @Test(expected = ProtocolException.class)
    public void parseBufferWithLengthLessThanMinimum() throws Exception {
        byte[] testData = getTestData();
        ByteBuffer buffer = ByteBuffer.allocate(testData.length + 1);
        buffer.put((byte) (I18Name.MINIMUM_LENGTH - 1));
        buffer.put(testData);
        // Rewind buffer for reading.
        buffer.position(0);
        I18Name.parse(buffer);
    }

    /**
     * Verify that the expected I18Name will be returned when parsing a buffer contained the
     * predefined test data.
     *
     * @throws Exception
     */
    @Test
    public void parseBufferWithTestData() throws Exception {
        byte[] testData = getTestData();
        ByteBuffer buffer = ByteBuffer.allocate(testData.length + 1);
        buffer.put((byte) (testData.length));
        buffer.put(testData);
        // Rewind buffer for reading.
        buffer.position(0);
        I18Name actualName = I18Name.parse(buffer);
        I18Name expectedName = new I18Name(TEST_LANGUAGE, TEST_LOCALE, TEST_TEXT);
        assertEquals(expectedName, actualName);
    }

    /**
     * Verify that the expected I18Name will be returned when parsing a buffer contained
     * a non-English (French) language.
     *
     * @throws Exception
     */
    @Test
    public void parseBufferWithFrenchData() throws Exception {
        // Test data for French.
        String language = "fr";
        String text = "Hello World";

        byte[] languageBytes = language.getBytes(StandardCharsets.US_ASCII);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        int dataLength = I18Name.LANGUAGE_CODE_LENGTH + textBytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(dataLength + 1);
        buffer.put((byte) dataLength);
        buffer.put(languageBytes);
        buffer.put((byte) 0);    // Padding for language code.
        buffer.put(textBytes);

        // Rewind buffer for reading.
        buffer.position(0);
        I18Name actualName = I18Name.parse(buffer);
        I18Name expectedName = new I18Name(language, Locale.forLanguageTag(language), text);
        assertEquals(expectedName, actualName);
    }

    /**
     * Verify that an I18Name with an empty text will be returned when parsing a buffer contained
     * an empty text field.
     *
     * @throws Exception
     */
    @Test
    public void parseBufferWithEmptyText() throws Exception {
        byte[] language = TEST_LANGUAGE.getBytes(StandardCharsets.US_ASCII);
        int dataLength = I18Name.LANGUAGE_CODE_LENGTH;
        ByteBuffer buffer = ByteBuffer.allocate(dataLength + 1);
        buffer.put((byte) dataLength);
        buffer.put(language);
        buffer.put((byte) 0);    // Padding for language code.

        // Rewind buffer for reading.
        buffer.position(0);
        I18Name actualName = I18Name.parse(buffer);
        I18Name expectedName = new I18Name(TEST_LANGUAGE, TEST_LOCALE, "");
        assertEquals(expectedName, actualName);
    }
}
