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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
 * {@code [length(4)] [payload]} per record (JSON segments written by 0.1.5 and earlier).
 * {@link #next()} returns {@code null} both at a clean end of file and at a truncated or
 * corrupt frame: a partial length prefix, a non-positive or oversized length, fewer payload
 * bytes than the prefix claims, or a payload that does not decode. This tolerates a torn
 * tail from a crash (the intact records before it survive) but cannot recover records
 * that follow a frame corrupted in the <em>middle</em>: there is no delimiter to find
 * the next boundary, so the reader stops. Everything from the damaged frame to the end of
 * the file is reported in {@link #corruptBytesSkipped()}, exactly once.
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
 * <h2>Damaged header — salvage</h2>
 * A non-empty file whose 6-byte header is missing, torn or unrecognised (a crash between
 * creating the file and writing the header, or damage to its first bytes) is not treated as
 * empty. The reader scans the <em>whole</em> file for v2 frames, as if the header were
 * garbage in front of the first frame, and decodes each CRC-valid payload with whichever
 * codec accepts it. The CRC rules out false frames, so the intact records behind a smashed
 * header still replay, and every byte not covered by an intact frame is counted as corrupt.
 * A v1 segment with a damaged header cannot be salvaged (it has no sync markers), so its
 * bytes are all counted. A zero-byte file yields nothing and counts nothing.
 *
 * <h2>Payload bound</h2>
 * The {@link Path} constructor trusts payload lengths up to the larger of the caller's bound
 * and the file's own size. A frame can never be longer than the file holding it, so this
 * keeps a record larger than the current {@code maxSpillFileBytes} (written whole into an
 * oversized segment, or written by a run with a larger setting) readable, while still
 * capping the allocation a corrupt v1 length can cause.
 */
final class SpillReader implements Closeable {

    private final DataInputStream in;
    private final long maxPayloadLen;
    /** Codec named by the header; null in salvage mode, where each frame is tried against every codec. */
    private final SpillCodec codec;
    private final byte version;       // framing version from the header (v1 tail-stop, v2 resync)
    /** The header was missing/damaged on a non-empty file: scan the whole file for v2 frames. */
    private final boolean salvage;
    /** Bytes consumed while probing the header; salvage mode scans them as part of the file. */
    private final byte[] headBytes;
    /** Zero-byte file (crash between create and header write): nothing to read, nothing lost. */
    private final boolean empty;
    private int lastPayloadLen;       // payload byte count of the most recent next() that returned a record

    // v2 scan state: the post-header bytes are loaded once and scanned by index so a
    // corrupt frame can be skipped and the next sync marker found.
    private byte[] v2buf;
    private int v2pos;                // current scan offset within v2buf
    private long v2expectedPos;       // where the next frame was expected to begin (end of last good frame)
    private long corruptBytesSkipped; // bytes discarded as damaged across the whole segment
    private boolean v1Settled;        // v1: the damaged remainder has been counted; read no further

    SpillReader(Path segment, long maxPayloadLen) throws IOException {
        this(Math.max(maxPayloadLen, Files.size(segment)), segment);
    }

    private SpillReader(long payloadBound, Path segment) throws IOException {
        this(new BufferedInputStream(Files.newInputStream(segment)), payloadBound);
    }

    /** Constructor seam for tests that want to read from an arbitrary stream. */
    SpillReader(InputStream stream, long maxPayloadLen) throws IOException {
        this.in = new DataInputStream(stream instanceof BufferedInputStream
                ? stream : new BufferedInputStream(stream));
        this.maxPayloadLen = maxPayloadLen;
        byte[] h = new byte[SpillSegmentHeader.LENGTH];
        int n;
        try {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            in.close(); // the caller never receives the reader, so nobody else would close it
            throw e;
        }
        SpillCodec named = n == h.length && SpillSegmentHeader.isValid(h)
                ? SpillCodec.forId(SpillSegmentHeader.codecId(h)) : null;
        if (named != null) {
            this.codec = named;
            this.version = SpillSegmentHeader.version(h);
            this.salvage = false;
            this.headBytes = null;
            this.empty = false;
        } else {
            this.codec = null;
            this.version = SpillSegmentHeader.VERSION_V2;
            this.salvage = n > 0;
            this.headBytes = Arrays.copyOf(h, n);
            this.empty = n == 0;
        }
    }

    /**
     * Returns the next decoded record, or {@code null} at clean EOF / on a truncated or
     * corrupt frame (v1) / once the remaining bytes hold no further intact frame
     * (v2 and salvage). For v2, frames corrupted in the middle of the segment are skipped
     * rather than ending the read.
     */
    LogRecord next() throws IOException {
        if (empty) {
            return null;
        }
        return version == SpillSegmentHeader.VERSION_V2 ? nextV2() : nextV1();
    }

    /**
     * Number of intact frames {@link #next()} would visit, without decoding them: v2 checks
     * sync, length and CRC; v1 checks length and that the payload is present. Consumes the
     * reader. v1 can over-count a frame whose payload then fails to decode, which takes a
     * codec bug rather than disk damage.
     */
    long countRecords() throws IOException {
        if (empty) {
            return 0;
        }
        long n = 0;
        if (version == SpillSegmentHeader.VERSION_V2) {
            while (nextFrameV2() >= 0) n++;
            return n;
        }
        byte[] lenBuf = new byte[4];
        while (in.readNBytes(lenBuf, 0, 4) == 4) {
            int len = SpillFrameV2.readIntBE(lenBuf, 0);
            if (len <= 0 || len > maxPayloadLen) {
                return n;
            }
            try {
                in.skipNBytes(len);
            } catch (EOFException torn) {
                return n;
            }
            n++;
        }
        return n;
    }

    /** {@link #countRecords()} for a segment file, or {@code -1} when it cannot be read at all. */
    static long countRecords(Path segment, long maxPayloadLen) {
        try (SpillReader r = new SpillReader(segment, maxPayloadLen)) {
            return r.countRecords();
        } catch (IOException e) {
            return -1;
        }
    }

    /** v1 length-prefixed framing: stop at the first corrupt/truncated frame (torn tail), counting what is discarded. */
    private LogRecord nextV1() throws IOException {
        if (v1Settled) {
            return null;
        }
        byte[] lenBuf = new byte[4];
        int n = in.readNBytes(lenBuf, 0, 4);
        if (n == 0) {
            return null; // clean end of file
        }
        if (n < 4) {
            return settleV1(n); // 1-3 bytes of a length prefix survived a crash
        }
        int len = SpillFrameV2.readIntBE(lenBuf, 0);
        if (len <= 0 || len > maxPayloadLen) {
            return settleV1(4); // corrupt length — stop, do not try to resync
        }
        byte[] payload = new byte[len];
        int got = in.readNBytes(payload, 0, len);
        if (got < len) {
            return settleV1(4L + got); // the prefix promised more bytes than the file holds — torn tail
        }
        // A decode failure means the framing desynced (or a genuinely corrupt record);
        // be conservative and stop the segment rather than risk reading garbage lengths
        // from misaligned bytes.
        LogRecord rec = codec.decode(payload);
        if (rec == null) {
            return settleV1(4L + len);
        }
        lastPayloadLen = len;
        return rec;
    }

    /**
     * End a v1 read at damage: count the bytes of the damaged frame already consumed plus
     * everything after it, once. v1 has no way to find the next frame boundary, so all of
     * it is lost — but it is reported, not mistaken for a clean end of file.
     */
    private LogRecord settleV1(long consumedOfThisFrame) throws IOException {
        if (!v1Settled) {
            corruptBytesSkipped += consumedOfThisFrame + in.transferTo(OutputStream.nullOutputStream());
            v1Settled = true;
        }
        return null;
    }

    /**
     * v2 sync-marker + CRC framing (also used in salvage mode): validate each frame and
     * decode it, skipping — and counting — any frame that validates but does not decode.
     */
    private LogRecord nextV2() throws IOException {
        int start;
        while ((start = nextFrameV2()) >= 0) {
            int len = lastPayloadLen;
            LogRecord rec = decode(Arrays.copyOfRange(v2buf, start, start + len));
            if (rec != null) {
                return rec;
            }
            // CRC matched yet the bytes don't decode — a codec bug, or a salvaged frame no
            // codec we know can read. Skip it rather than stop, and count it: these are
            // records that will not be delivered.
            corruptBytesSkipped += SpillFrameV2.HEADER_LEN + len;
        }
        return null;
    }

    private LogRecord decode(byte[] payload) {
        if (codec != null) {
            return codec.decode(payload);
        }
        // Salvage: the header that named the codec is gone. JSON first — a binary payload
        // starts with the timestamp's high byte, never '{', so JSON rejects it cleanly,
        // whereas the binary decoder is not strict enough to reject arbitrary JSON text.
        LogRecord r = SpillCodec.JSON.decode(payload);
        return r != null ? r : SpillCodec.BINARY.decode(payload);
    }

    /**
     * Advance to the next frame that validates (sync, sane length, fully present payload, CRC)
     * and return its payload offset in {@code v2buf}, or {@code -1} when no further intact
     * frame exists. Bytes skipped to get there are counted as corrupt; at the end, the bytes
     * after the last good frame are counted once.
     */
    private int nextFrameV2() throws IOException {
        if (v2buf == null) {
            // Segment bytes are size-bounded; load once for index scanning. In salvage mode the
            // header probe already consumed the first bytes of the file, so scan them as well.
            byte[] rest = in.readAllBytes();
            if (salvage) {
                v2buf = new byte[headBytes.length + rest.length];
                System.arraycopy(headBytes, 0, v2buf, 0, headBytes.length);
                System.arraycopy(rest, 0, v2buf, headBytes.length, rest.length);
            } else {
                v2buf = rest;
            }
        }
        while (v2pos + SpillFrameV2.HEADER_LEN <= v2buf.length) {
            if (!SpillFrameV2.isSyncAt(v2buf, v2pos)) {
                v2pos++; // not a frame start — keep scanning for the next sync marker
                continue;
            }
            int len = SpillFrameV2.readIntBE(v2buf, v2pos + 4);
            int crc = SpillFrameV2.readIntBE(v2buf, v2pos + 8);
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
            v2pos = payloadStart + len;
            v2expectedPos = v2pos;
            lastPayloadLen = len;
            return payloadStart;
        }
        // The scan ran out of bytes without finding another intact frame, so everything after
        // the last good frame was discarded as damage — a torn tail, or a segment with nothing
        // readable in it at all. Those are the two shapes a crash leaves behind most often, and
        // counting them only when a *later* frame validates would report zero for exactly the
        // cases an operator is inspecting corruptBytesSkipped to understand. Settle the balance
        // once; repeat calls then add nothing.
        corruptBytesSkipped += v2buf.length - v2expectedPos;
        v2expectedPos = v2buf.length;
        return -1;
    }

    /** Payload byte count of the record most recently returned by {@link #next()}. */
    int lastPayloadLen() {
        return lastPayloadLen;
    }

    /** Total bytes discarded as damaged while reading this segment (v1 tail-stop, v2 resync, salvage). */
    long corruptBytesSkipped() {
        return corruptBytesSkipped;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
