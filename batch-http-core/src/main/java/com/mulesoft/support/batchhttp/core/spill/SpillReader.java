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

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a spill segment: a {@link SpillSegmentHeader} followed by framed records, one
 * at a time, for replay.
 *
 * <p>The header tells us both which {@link SpillCodec} the segment was written with and
 * which per-record framing version it uses, so a run configured one way can still drain
 * a segment left by a previous run that used another (essential for orphan recovery
 * across a format or framing change).
 *
 * <h2>v1 framing — tail-stop</h2>
 * {@code [length(4)] [payload]} per record. {@link #next()} returns {@code null} both at
 * a clean end of file and at a truncated or corrupt trailing frame: a non-positive or
 * oversized length, or fewer payload bytes than the header claims. This tolerates a torn
 * tail from a crash (the intact records before it survive) but cannot recover records
 * that follow a frame corrupted in the <em>middle</em> — there is no delimiter to find
 * the next boundary, so the reader stops.
 *
 * <h2>v2 framing — skip-and-resync</h2>
 * {@code [SYNC(4)] [length(4)] [crc32c(4)] [payload]} per record (see {@link SpillFrameV2}).
 * When a frame fails to validate — bad sync, insane length, truncated payload, or a CRC
 * mismatch — the reader scans forward byte by byte to the next {@code SYNC} marker and
 * retries, so a record corrupted in the middle of a segment (e.g. a journal left
 * improperly closed by a crashed runtime, with stale or zero-filled blocks) is skipped
 * and replay resumes from the next intact record. The per-frame CRC is what makes that
 * resync safe for arbitrary payloads — an HTTP wire dump, embedded JSON, or raw binary
 * that happens to contain the marker bytes is rejected by the checksum rather than
 * mistaken for a frame boundary. {@link #corruptBytesSkipped()} reports how many bytes
 * were discarded as damaged so the outcome is observable.
 *
 * <p>A missing/invalid segment header yields zero records rather than an exception.
 */
final class SpillReader implements Closeable {

    private final DataInputStream in;
    private final long maxPayloadLen;
    private final SpillCodec codec;   // null when the header was missing/unknown -> no records
    private final byte version;       // framing version from the header (v1 tail-stop, v2 resync)
    private int lastPayloadLen;       // payload byte count of the most recent next() that returned a record

    // v2 scan state: the post-header bytes are loaded once and scanned by index so a
    // corrupt frame can be skipped and the next sync marker found.
    private byte[] v2buf;
    private int v2pos;                // current scan offset within v2buf
    private long v2expectedPos;       // where the next frame was expected to begin (end of last good frame)
    private long corruptBytesSkipped; // bytes discarded as damaged across the whole segment

    SpillReader(Path segment, long maxPayloadLen) throws IOException {
        this(new BufferedInputStream(Files.newInputStream(segment)), maxPayloadLen);
    }

    /** Constructor seam for tests that want to read from an arbitrary stream. */
    SpillReader(InputStream stream, long maxPayloadLen) {
        this.in = new DataInputStream(stream instanceof BufferedInputStream
                ? stream : new BufferedInputStream(stream));
        this.maxPayloadLen = maxPayloadLen;
        byte[] h = readHeader();
        if (h == null) {
            this.codec = null;
            this.version = SpillSegmentHeader.VERSION_V1;
        } else {
            this.codec = SpillCodec.forId(SpillSegmentHeader.codecId(h));
            this.version = SpillSegmentHeader.version(h);
        }
    }

    /** Read and validate the segment header, returning its bytes (or null if missing/invalid). */
    private byte[] readHeader() {
        byte[] h = new byte[SpillSegmentHeader.LENGTH];
        try {
            in.readFully(h);
        } catch (IOException e) {
            return null; // file shorter than a header — nothing to replay
        }
        return SpillSegmentHeader.isValid(h) ? h : null;
    }

    /**
     * Returns the next decoded record, or {@code null} at clean EOF / on a truncated or
     * corrupt trailing frame (v1) / once the remaining bytes hold no further intact frame
     * (v2). For v2, frames corrupted in the middle of the segment are skipped rather than
     * ending the read.
     */
    LogRecord next() throws IOException {
        if (codec == null) {
            return null;
        }
        return version == SpillSegmentHeader.VERSION_V2 ? nextV2() : nextV1();
    }

    /** v1 length-prefixed framing: stop at the first corrupt/truncated frame (torn tail). */
    private LogRecord nextV1() throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException eof) {
            return null; // clean end, or fewer than 4 length bytes survived a crash
        }
        if (len <= 0 || len > maxPayloadLen) {
            return null; // corrupt length — stop, do not try to resync
        }
        byte[] payload = new byte[len];
        try {
            in.readFully(payload);
        } catch (EOFException eof) {
            return null; // header promised more bytes than the file holds — truncated tail
        }
        lastPayloadLen = len;
        // A decode failure means the framing desynced (or a genuinely corrupt record);
        // be conservative and stop the segment rather than risk reading garbage lengths
        // from misaligned bytes.
        return codec.decode(payload);
    }

    /**
     * v2 sync-marker + CRC framing: validate each frame and, on any failure, scan forward
     * to the next sync marker and retry — skipping exactly the damaged bytes and resuming
     * from the next intact record.
     */
    private LogRecord nextV2() throws IOException {
        if (v2buf == null) {
            v2buf = in.readAllBytes(); // segment bytes are size-bounded; load once for index scanning
        }
        while (v2pos + SpillFrameV2.HEADER_LEN <= v2buf.length) {
            if (!SpillFrameV2.isSyncAt(v2buf, v2pos)) {
                v2pos++; // not a frame start — keep scanning for the next sync marker
                continue;
            }
            int len = readIntAt(v2pos + 4);
            int crc = readIntAt(v2pos + 8);
            // A sane length and a fully-present payload are prerequisites; otherwise this
            // is a false marker inside payload bytes or a torn tail — step past and rescan.
            if (len <= 0 || len > maxPayloadLen
                    || (long) v2pos + SpillFrameV2.HEADER_LEN + len > v2buf.length) {
                v2pos++;
                continue;
            }
            int payloadStart = v2pos + SpillFrameV2.HEADER_LEN;
            if (SpillFrameV2.crc32cRange(v2buf, payloadStart, len) != crc) {
                v2pos++; // checksum says these bytes are not an intact frame — keep scanning
                continue;
            }
            // Validated frame. Account for any bytes we skipped to get here as corruption.
            corruptBytesSkipped += v2pos - v2expectedPos;
            byte[] payload = new byte[len];
            System.arraycopy(v2buf, payloadStart, payload, 0, len);
            v2pos = payloadStart + len;
            v2expectedPos = v2pos;
            lastPayloadLen = len;
            LogRecord rec = codec.decode(payload);
            if (rec == null) {
                // CRC matched yet the bytes don't decode — vanishingly unlikely (it would
                // mean a codec bug, not disk damage). Skip it too rather than stop.
                continue;
            }
            return rec;
        }
        // The scan ran out of bytes without finding another intact frame, so everything after
        // the last good frame was discarded as damage — a torn tail, or a segment with nothing
        // readable in it at all. Those are the two shapes a crash leaves behind most often, and
        // counting them only when a *later* frame validates would report zero for exactly the
        // cases an operator is inspecting corruptBytesSkipped to understand. Settle the balance
        // once; repeat calls then add nothing.
        corruptBytesSkipped += v2buf.length - v2expectedPos;
        v2expectedPos = v2buf.length;
        return null;
    }

    private int readIntAt(int off) {
        return ((v2buf[off] & 0xFF) << 24)
                | ((v2buf[off + 1] & 0xFF) << 16)
                | ((v2buf[off + 2] & 0xFF) << 8)
                | (v2buf[off + 3] & 0xFF);
    }

    /** Payload byte count of the record most recently returned by {@link #next()}. */
    int lastPayloadLen() {
        return lastPayloadLen;
    }

    /** Total bytes discarded as damaged while reading this segment (v2 resync). */
    long corruptBytesSkipped() {
        return corruptBytesSkipped;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
