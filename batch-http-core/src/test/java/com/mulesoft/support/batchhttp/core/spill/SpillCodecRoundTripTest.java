/*
 * Copyright (c) 2026, Salesforce, Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mulesoft.support.batchhttp.core.spill;

import com.mulesoft.support.batchhttp.core.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SpillCodecRoundTripTest {

    /** Run every round-trip against both codecs. */
    static Stream<SpillCodec> codecs() {
        return Stream.of(SpillCodec.BINARY, SpillCodec.JSON);
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void roundTripsAllFields(SpillCodec codec) {
        LogRecord original = LogRecord.builder()
                .timestampMillis(1700000000123L)
                .level("ERROR")
                .loggerName("com.example.Svc")
                .threadName("worker-1")
                .message("boom \"quoted\" \n newline \t tab")
                .throwable("java.lang.RuntimeException: x\n\tat A.b(A.java:1)")
                .attribute("requestId", "abc-123")
                .attribute("weird\"key", "val\\ue\n")
                .build();

        LogRecord decoded = codec.decode(codec.encode(original));

        assertNotNull(decoded);
        assertEquals(original.timestampMillis(), decoded.timestampMillis());
        assertEquals(original.level(), decoded.level());
        assertEquals(original.loggerName(), decoded.loggerName());
        assertEquals(original.threadName(), decoded.threadName());
        assertEquals(original.message(), decoded.message());
        assertEquals(original.throwable(), decoded.throwable());
        assertEquals(original.attributes(), decoded.attributes());
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void roundTripsNullThrowableAndEmptyAttributes(SpillCodec codec) {
        LogRecord original = LogRecord.builder()
                .timestampMillis(42L)
                .level("INFO")
                .loggerName("l")
                .threadName("t")
                .message("plain")
                .build();

        LogRecord decoded = codec.decode(codec.encode(original));

        assertNotNull(decoded);
        assertNull(decoded.throwable());
        assertTrue(decoded.attributes().isEmpty());
        assertEquals("plain", decoded.message());
        assertEquals(42L, decoded.timestampMillis());
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void roundTripsControlCharactersInMessage(SpillCodec codec) {
        LogRecord original = LogRecord.builder()
                .timestampMillis(1L)
                .level("DEBUG")
                .loggerName("l")
                .threadName("t")
                .message("ctrl  end")
                .build();

        LogRecord decoded = codec.decode(codec.encode(original));

        assertNotNull(decoded);
        assertEquals(original.message(), decoded.message());
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void roundTripsNonLatinAndEmojiAcrossCharsets(SpillCodec codec) {
        LogRecord original = LogRecord.builder()
                .timestampMillis(7L)
                .level("INFO")
                .loggerName("l")
                .threadName("t")
                .message("日本語 éèê Ω 🚀 mixed")
                .attribute("ключ", "значение")
                .build();

        LogRecord decoded = codec.decode(codec.encode(original));

        assertNotNull(decoded);
        assertEquals(original.message(), decoded.message());
        assertEquals(original.attributes(), decoded.attributes());
    }

    @Test
    void jsonDecodeReturnsNullOnGarbage() {
        assertNull(SpillCodec.JSON.decode("not json at all".getBytes()));
        assertNull(SpillCodec.JSON.decode("{".getBytes()));
        assertNull(SpillCodec.JSON.decode("{\"ts\":}".getBytes()));
    }

    @Test
    void binaryDecodeReturnsNullOnGarbage() {
        assertNull(SpillCodec.BINARY.decode(new byte[]{0x01, 0x02, 0x03}));
        assertNull(SpillCodec.BINARY.decode(new byte[0]));
    }

    @Test
    void codecsAdvertiseDistinctIds() {
        assertEquals(SpillCodec.ID_BINARY, SpillCodec.BINARY.codecId());
        assertEquals(SpillCodec.ID_JSON, SpillCodec.JSON.codecId());
        assertSame(SpillCodec.BINARY, SpillCodec.forId(SpillCodec.ID_BINARY));
        assertSame(SpillCodec.JSON, SpillCodec.forId(SpillCodec.ID_JSON));
        assertNull(SpillCodec.forId((byte) 99));
    }
}
