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

import com.mulesoft.support.batchhttp.core.BatchConfig;
import com.mulesoft.support.batchhttp.core.LogRecord;

/**
 * Converts a {@link LogRecord} to and from its on-disk spill payload (the bytes that
 * live inside one frame). The framing that wraps each payload lives in
 * {@link SpillSegmentWriter}/{@link SpillReader}, and which framing a segment uses is
 * chosen by its codec via {@link #framingVersion()} (stamped into the segment header):
 * <ul>
 *   <li>{@code VERSION_V1} — a 4-byte big-endian length prefix per record (legacy: JSON
 *       segments written by 0.1.5 and earlier).</li>
 *   <li>{@code VERSION_V2} — a self-synchronizing {@code [SYNC(4)][length(4)][crc32c(4)]}
 *       envelope (both built-in codecs; see {@link SpillFrameV2}) that lets the reader skip a
 *       record damaged mid-segment and resync on the next intact one.</li>
 * </ul>
 * Framing is therefore codec-dependent; {@link SpillSegmentHeader} records both the codec
 * id and the framing version so a segment stays self-describing across runs.
 *
 * <p>Two encodings are available:
 * <ul>
 *   <li>{@link JsonSpillCodec} — UTF-8 JSON. Human-inspectable; useful for support.</li>
 *   <li>{@link BinarySpillCodec} — length-prefixed binary TLV (the default). Faster,
 *       far fewer allocations, and free of any escaping logic: each string is written
 *       as {@code [varint byteLength][raw UTF-8 bytes]}, so every character set is
 *       handled losslessly with no escape edge cases.</li>
 * </ul>
 *
 * <p>Each codec has a stable one-byte {@link #codecId()} written into the segment
 * header (see {@link SpillSegmentHeader}), so a segment is self-describing: a run
 * configured for BINARY can still read a JSON segment left by a previous run and
 * vice versa. {@link #forId(byte)} resolves the codec a segment was written with.
 */
public interface SpillCodec {

    /** Codec id for {@link BinarySpillCodec}. */
    byte ID_BINARY = 1;
    /** Codec id for {@link JsonSpillCodec}. */
    byte ID_JSON = 2;

    BinarySpillCodec BINARY = new BinarySpillCodec();
    JsonSpillCodec JSON = new JsonSpillCodec();

    /** Encode a record to its payload bytes (no length prefix). */
    byte[] encode(LogRecord record);

    /** Decode a payload back into a record, or {@code null} if the bytes are not well-formed. */
    LogRecord decode(byte[] payload);

    /** Stable identifier persisted in the segment header. */
    byte codecId();

    /**
     * Which per-record framing this codec's segments use (see {@link SpillSegmentHeader}).
     * Both built-in codecs opt into the crash-resilient, self-synchronizing
     * {@code VERSION_V2} framing (JSON since 0.1.6). Default is v1 so any future codec is safe
     * until it explicitly upgrades.
     */
    default byte framingVersion() {
        return SpillSegmentHeader.VERSION_V1;
    }

    /** Resolve the codec selected by configuration. if/else (never switch on the enum). */
    static SpillCodec forFormat(BatchConfig.SpillFormat format) {
        if (format == BatchConfig.SpillFormat.JSON) {
            return JSON;
        }
        return BINARY; // default
    }

    /** Resolve the codec a segment was written with, from its header id. if/else (never switch). */
    static SpillCodec forId(byte id) {
        if (id == ID_JSON) {
            return JSON;
        }
        if (id == ID_BINARY) {
            return BINARY;
        }
        return null; // unknown codec — caller treats the segment as unreadable
    }
}
