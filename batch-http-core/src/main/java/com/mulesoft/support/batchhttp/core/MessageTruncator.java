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

/**
 * Caps a log message at a configurable UTF-8 byte budget so a single oversized event —
 * a developer dumping a multi-megabyte payload, an entire HTTP body, or a stringified
 * blob — cannot blow up the in-memory queue, the HTTP request, or a spill segment.
 *
 * <h2>Why bytes, and why it's cheap even for a 200 MB message</h2>
 * The limit is in <em>bytes</em> because that is what bounds the queue footprint, the
 * wire payload, and the on-disk frame. The naive way to measure a string's UTF-8 length
 * is {@code s.getBytes(UTF_8)} — but for a pathological 200 MB message that allocates a
 * second 200 MB array just to measure it, which is exactly the blow-up we are trying to
 * prevent. Instead this walks code points, summing their encoded byte sizes, and stops
 * the moment the budget is reached: at most {@code maxBytes}-worth of iterations
 * (~32 K for the default) and a single ~{@code maxBytes} substring copy, regardless of
 * how large the original is. The common case — a normal log line well under the budget —
 * is rejected in O(1) by a cheap upper-bound guard before any walking.
 *
 * <p>Truncation happens on a UTF-8 code-point boundary, so a multi-byte character is
 * never split into invalid bytes, and a short marker recording how many characters were
 * dropped is appended so the truncation is visible downstream rather than silent.
 */
public final class MessageTruncator {

    private MessageTruncator() {}

    /**
     * Return {@code s} unchanged when truncation is disabled ({@code maxBytes <= 0}), when
     * {@code s} is null, or when its UTF-8 encoding already fits within {@code maxBytes};
     * otherwise return a prefix whose UTF-8 length is &le; {@code maxBytes} with a
     * {@code …[truncated N chars]} marker appended. The returned reference is identical to
     * the input when nothing was cut, so callers can skip rebuilding on the hot path.
     */
    public static String truncateUtf8(String s, int maxBytes) {
        if (maxBytes <= 0 || s == null) {
            return s; // disabled, or nothing to do
        }
        final int len = s.length();
        // Cheap O(1) guard: a Java char encodes to at most 3 UTF-8 bytes (a surrogate pair
        // is 2 chars -> 4 bytes = 2 bytes/char), so if 3*chars fits, the bytes certainly do.
        // Normal log lines are tiny, so this returns immediately without touching the bytes.
        if ((long) len * 3 <= maxBytes) {
            return s;
        }
        // Walk code points, summing UTF-8 byte sizes, stopping at the budget. Bounded by the
        // budget (not the string length), so a giant message costs ~maxBytes work, not O(n).
        int byteCount = 0;
        int i = 0;
        while (i < len) {
            int cp = s.codePointAt(i);
            int cpBytes = utf8Length(cp);
            if (byteCount + cpBytes > maxBytes) {
                break;
            }
            byteCount += cpBytes;
            i += Character.charCount(cp);
        }
        if (i >= len) {
            return s; // every code point fit after all (e.g. mostly ASCII just over the guard)
        }
        return s.substring(0, i) + "…[truncated " + (len - i) + " chars]";
    }

    /** UTF-8 byte length of a single Unicode code point. */
    private static int utf8Length(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }
}
