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
package com.mulesoft.support.batchhttp.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MessageTruncatorTest {

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void returnsSameReferenceWhenWellUnderBudget() {
        String s = "a normal log line";
        assertSame(s, MessageTruncator.truncateUtf8(s, 32 * 1024),
                "the common case must not allocate — same reference back");
    }

    @Test
    void disabledWhenMaxIsZeroOrNegative() {
        String s = "x".repeat(10_000);
        assertSame(s, MessageTruncator.truncateUtf8(s, 0));
        assertSame(s, MessageTruncator.truncateUtf8(s, -1));
    }

    @Test
    void nullIsPassedThrough() {
        assertNull(MessageTruncator.truncateUtf8(null, 100));
    }

    @Test
    void truncatesAsciiAndAppendsMarker() {
        String s = "x".repeat(1000);
        String out = MessageTruncator.truncateUtf8(s, 100);
        assertNotSame(s, out);
        assertTrue(out.startsWith("x".repeat(100)), "keeps a 100-byte prefix of ASCII");
        assertTrue(out.contains("[truncated 900 chars]"), "marker reports the dropped count, was: " + out);
    }

    @Test
    void keptPrefixNeverExceedsByteBudget() {
        // The kept prefix (before the marker) must be <= maxBytes even for multi-byte chars.
        String s = "é".repeat(1000); // each 'é' is 2 UTF-8 bytes
        int max = 101;               // odd budget: a 2-byte char can't land exactly on it
        String out = MessageTruncator.truncateUtf8(s, max);
        String prefix = out.substring(0, out.indexOf("…"));
        assertTrue(utf8Len(prefix) <= max,
                "kept prefix " + utf8Len(prefix) + " bytes must not exceed budget " + max);
        assertEquals(100, utf8Len(prefix), "should pack 50 two-byte chars = 100 bytes, stopping before 102");
    }

    @Test
    void neverSplitsAMultiByteCharacter() {
        // A 4-byte emoji with a budget that lands mid-character must drop the whole char,
        // never emit a half-encoded code point.
        String s = "🚀".repeat(10); // 4 UTF-8 bytes each
        String out = MessageTruncator.truncateUtf8(s, 10); // 2 full rockets = 8 bytes, 3rd won't fit
        String prefix = out.substring(0, out.indexOf("…"));
        assertEquals("🚀🚀", prefix, "exactly two whole rockets fit in 10 bytes");
        assertEquals(8, utf8Len(prefix));
        // Re-decoding must be lossless (no replacement chars from a split sequence).
        assertEquals(prefix, new String(prefix.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void messageExactlyAtBudgetIsUntouched() {
        String s = "x".repeat(100);
        assertSame(s, MessageTruncator.truncateUtf8(s, 100), "exactly at budget => no truncation");
    }

    @Test
    void mostlyAsciiJustOverGuardButFittingIsReturnedWhole() {
        // 3*len exceeds the guard so it walks, but the actual bytes still fit -> returned whole.
        String s = "x".repeat(40); // 40 bytes, guard 3*40=120 > 100 forces the walk
        String out = MessageTruncator.truncateUtf8(s, 100);
        assertSame(s, out, "walk must confirm it fits and return the same reference");
    }

    @Test
    void handlesHugeMessageWithoutMaterializingFullUtf8() {
        // A ~50 MB message must truncate to a tiny result; the point is it must not OOM or
        // allocate a second 50 MB byte[] just to measure it. (Bounded work ~= maxBytes.)
        String huge = "A".repeat(50 * 1024 * 1024);
        String out = MessageTruncator.truncateUtf8(huge, 1024);
        assertTrue(out.startsWith("A".repeat(1024)));
        assertTrue(out.contains("[truncated "));
        assertTrue(utf8Len(out.substring(0, out.indexOf("…"))) <= 1024);
    }
}
