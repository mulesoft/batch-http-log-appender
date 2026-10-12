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

import java.util.zip.CRC32C;

/**
 * The version-2 per-record frame: a self-synchronizing, integrity-checked envelope that
 * lets {@link SpillReader} skip a record corrupted in the <em>middle</em> of a segment
 * (an improperly-closed file left by a crashed runtime) and resume from the next intact
 * record — rather than abandoning everything after the first damaged byte as the v1
 * length-prefix framing must.
 *
 * <pre>
 *   bytes 0..3   SYNC      0x42 0x48 0x46 0x31  ("BHF1" — Batch-Http Frame v1-of-record)
 *   bytes 4..7   length    payload byte count, big-endian int (&gt; 0)
 *   bytes 8..11  crc32c    CRC-32C of the payload bytes, big-endian int
 *   bytes 12..   payload   the codec-encoded record
 * </pre>
 *
 * <h2>Why both a marker and a checksum</h2>
 * The {@link #SYNC} marker gives the reader a byte pattern to scan forward to after it
 * detects damage, so it can find where the next frame <em>might</em> begin. But a log
 * message can contain arbitrary bytes — an HTTP wire dump, embedded JSON, even raw
 * binary — so the marker can legitimately appear inside a payload. The {@link #crc32c}
 * is what makes resync safe: a candidate boundary is accepted only when the declared
 * length is sane <em>and</em> the payload's checksum matches, so bytes that merely look
 * like the marker are rejected and the scan continues. Marker + CRC together mean no
 * payload content can ever be mistaken for framing.
 *
 * <h2>Cost</h2>
 * 12 bytes of overhead per record (vs 4 in v1) and one {@link CRC32C} pass over the
 * payload. CRC-32C is hardware-accelerated on JDK 9+ (SSE4.2 / ARMv8 CRC), so for a
 * typical sub-kilobyte log line the checksum is sub-microsecond and is dwarfed by the
 * disk write that follows. Crucially this runs only on the spill path — i.e. only while
 * the endpoint is unreachable or the queue has overflowed — so steady-state healthy
 * logging pays nothing.
 */
final class SpillFrameV2 {

    private SpillFrameV2() {}

    /** 4-byte frame-start marker. Distinct from the segment header magic {@code BHSP}. */
    static final byte SYNC0 = 'B', SYNC1 = 'H', SYNC2 = 'F', SYNC3 = '1';

    /** SYNC(4) + length(4) + crc32c(4). */
    static final int HEADER_LEN = 12;

    /** CRC-32C of {@code payload}, as an unsigned value carried in an int. */
    static int crc32c(byte[] payload) {
        return crc32cRange(payload, 0, payload.length);
    }

    /** CRC-32C of {@code len} bytes of {@code buf} starting at {@code off} — lets the reader
     *  checksum a candidate payload in place without copying it first. */
    static int crc32cRange(byte[] buf, int off, int len) {
        CRC32C crc = new CRC32C();
        crc.update(buf, off, len);
        return (int) crc.getValue();
    }

    /** True if the four bytes at {@code off} are the {@link #SYNC} marker. */
    static boolean isSyncAt(byte[] buf, int off) {
        return buf[off] == SYNC0 && buf[off + 1] == SYNC1
                && buf[off + 2] == SYNC2 && buf[off + 3] == SYNC3;
    }

    /** Read a big-endian 4-byte int from {@code buf} starting at {@code off}. */
    static int readIntBE(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24)
                | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8)
                | (buf[off + 3] & 0xFF);
    }

    /** Write {@code value} as a big-endian 4-byte int into {@code buf} starting at {@code off}. */
    static void putIntBE(byte[] buf, int off, int value) {
        buf[off]     = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }
}
