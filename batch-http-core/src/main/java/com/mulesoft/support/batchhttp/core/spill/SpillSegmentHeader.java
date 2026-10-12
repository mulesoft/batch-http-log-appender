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

/**
 * Fixed 6-byte header written once at the start of every spill segment, making each
 * file self-describing:
 *
 * <pre>
 *   bytes 0..3  magic   {'B','H','S','P'}   (Batch-Http SPill)
 *   byte  4     version 1 or 2 (the per-record framing, see below)
 *   byte  5     codecId (see {@link SpillCodec#codecId()})
 * </pre>
 *
 * <p>The codec id lets a run decode segments written by a previous run that used a
 * different {@link com.mulesoft.support.batchhttp.core.BatchConfig.SpillFormat} — the
 * key to recovering orphaned files across a format change. The magic guards against
 * mistaking an unrelated file for a segment.
 *
 * <h2>Framing version</h2>
 * The version byte selects how the records <em>after</em> the header are framed, and is
 * what lets the reader stay backward-compatible while the framing evolves:
 * <ul>
 *   <li>{@link #VERSION_V1} — {@code [length(4)] [payload]} per record. A crash can only
 *       ever tear the trailing frame; the reader stops at the first damaged length and
 *       keeps everything before it (the reader counts the discarded bytes). Used by
 *       JSON segments written by 0.1.5 and earlier, and by every pre-upgrade segment.</li>
 *   <li>{@link #VERSION_V2} — {@code [SYNC(4)] [length(4)] [crc32c(4)] [payload]} per
 *       record (both codecs; JSON since 0.2.0). The sync marker plus per-frame CRC let the reader skip a
 *       corrupt record in the <em>middle</em> of a segment and resync on the next intact
 *       one — and make that resync safe for arbitrary payload bytes (HTTP wire dumps,
 *       embedded JSON, raw binary), since content that merely looks like the marker is
 *       rejected by the checksum. See {@link SpillFrameV2}.</li>
 * </ul>
 * A reader picks the path from this byte, so a v2-configured run still drains a v1
 * orphan left by a previous version and vice versa.
 */
final class SpillSegmentHeader {

    private SpillSegmentHeader() {}

    static final int LENGTH = 6;
    /** Length-prefixed framing (tail-stop on corruption). */
    static final byte VERSION_V1 = 1;
    /** Sync-marker + CRC framing (skip-and-resync on interior corruption). */
    static final byte VERSION_V2 = 2;
    private static final byte M0 = 'B', M1 = 'H', M2 = 'S', M3 = 'P';

    /** Build a legacy {@link #VERSION_V1} header for a segment written with {@code codecId}. */
    static byte[] of(byte codecId) {
        return of(codecId, VERSION_V1);
    }

    /** Build the header bytes for a segment written with {@code codecId} and {@code version} framing. */
    static byte[] of(byte codecId, byte version) {
        return new byte[]{M0, M1, M2, M3, version, codecId};
    }

    /** True if the 6 bytes are a valid header naming a framing version we can read. */
    static boolean isValid(byte[] h) {
        return h != null && h.length == LENGTH
                && h[0] == M0 && h[1] == M1 && h[2] == M2 && h[3] == M3
                && (h[4] == VERSION_V1 || h[4] == VERSION_V2);
    }

    /** Framing version from a header known to be {@link #isValid valid}. */
    static byte version(byte[] h) {
        return h[4];
    }

    /** Codec id from a header known to be {@link #isValid valid}. */
    static byte codecId(byte[] h) {
        return h[5];
    }
}
