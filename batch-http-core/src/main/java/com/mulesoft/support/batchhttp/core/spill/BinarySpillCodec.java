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

import java.nio.charset.StandardCharsets;

/**
 * Binary spill codec — the default. Encodes each record as length-prefixed TLV with
 * raw UTF-8 string bytes and no escaping whatsoever:
 *
 * <pre>
 *   int64  timestampMillis              (8 bytes, big-endian)
 *   str    level                        ([uvarint byteLen][UTF-8 bytes])
 *   str    loggerName
 *   str    threadName
 *   str    message
 *   byte   hasThrowable                 (0 | 1)
 *   str    throwable                    (only when hasThrowable == 1)
 *   uvarint attributeCount
 *   { str key, str value } * attributeCount
 * </pre>
 *
 * <p>Because every string is written as a byte length followed by its verbatim UTF-8
 * bytes, all character sets round-trip losslessly and there are no escape edge cases
 * to get wrong. Compared with the JSON codec this avoids building an intermediate
 * {@code String}, the char-by-char escape scan on write, and the per-field
 * {@code StringBuilder}/un-escape work on read — fewer allocations and less branching
 * on a path that, during an outage, runs for every record.
 */
public final class BinarySpillCodec implements SpillCodec {

    @Override
    public byte codecId() { return ID_BINARY; }

    /**
     * Binary segments use the {@code VERSION_V2} self-synchronizing, CRC-checked framing
     * so a record corrupted in the middle of a segment can be skipped and replay resumed
     * from the next intact record — safely, for any payload bytes. See {@link SpillFrameV2}.
     */
    @Override
    public byte framingVersion() { return SpillSegmentHeader.VERSION_V2; }

    @Override
    public byte[] encode(LogRecord r) {
        Buf b = new Buf(128);
        b.writeLong(r.timestampMillis());
        b.writeStr(r.level());
        b.writeStr(r.loggerName());
        b.writeStr(r.threadName());
        b.writeStr(r.message());
        String exc = r.throwable();
        if (exc != null) {
            b.writeByte(1);
            b.writeStr(exc);
        } else {
            b.writeByte(0);
        }
        var attrs = r.attributes();
        b.writeVarint(attrs.size());
        for (var e : attrs.entrySet()) {
            b.writeStr(e.getKey());
            b.writeStr(e.getValue());
        }
        return b.toBytes();
    }

    @Override
    public LogRecord decode(byte[] payload) {
        try {
            Cursor c = new Cursor(payload);
            LogRecord.Builder rb = LogRecord.builder();
            rb.timestampMillis(c.readLong());
            rb.level(c.readStr());
            rb.loggerName(c.readStr());
            rb.threadName(c.readStr());
            rb.message(c.readStr());
            int hasExc = c.readByte();
            if (hasExc == 1) {
                rb.throwable(c.readStr());
            } else if (hasExc != 0) {
                return null; // not a flag we wrote — framing desynced
            }
            int attrCount = c.readVarint();
            if (attrCount < 0) return null;
            for (int i = 0; i < attrCount; i++) {
                String k = c.readStr();
                String v = c.readStr();
                rb.attribute(k, v);
            }
            if (!c.atEnd()) return null; // trailing garbage — treat as corrupt
            return rb.build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Minimal growable write buffer (avoids the synchronized ByteArrayOutputStream).
    // ------------------------------------------------------------------
    private static final class Buf {
        private byte[] a;
        private int n;

        Buf(int cap) { a = new byte[Math.max(16, cap)]; }

        private void ensure(int more) {
            if (n + more > a.length) {
                int cap = a.length;
                while (cap < n + more) cap <<= 1;
                byte[] na = new byte[cap];
                System.arraycopy(a, 0, na, 0, n);
                a = na;
            }
        }

        void writeByte(int v) {
            ensure(1);
            a[n++] = (byte) v;
        }

        void writeLong(long v) {
            ensure(8);
            a[n++] = (byte) (v >>> 56);
            a[n++] = (byte) (v >>> 48);
            a[n++] = (byte) (v >>> 40);
            a[n++] = (byte) (v >>> 32);
            a[n++] = (byte) (v >>> 24);
            a[n++] = (byte) (v >>> 16);
            a[n++] = (byte) (v >>> 8);
            a[n++] = (byte) v;
        }

        /** Unsigned LEB128. */
        void writeVarint(int value) {
            int v = value;
            ensure(5);
            while ((v & ~0x7F) != 0) {
                a[n++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            a[n++] = (byte) v;
        }

        /** A null string is written as length 0 (decodes to ""); the four mandatory fields are never null. */
        void writeStr(String s) {
            if (s == null || s.isEmpty()) {
                writeVarint(0);
                return;
            }
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVarint(bytes.length);
            ensure(bytes.length);
            System.arraycopy(bytes, 0, a, n, bytes.length);
            n += bytes.length;
        }

        byte[] toBytes() {
            byte[] out = new byte[n];
            System.arraycopy(a, 0, out, 0, n);
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Read cursor over the payload. Any out-of-bounds read throws and surfaces as a
    // null decode (the reader then stops the segment — see SpillReader).
    // ------------------------------------------------------------------
    private static final class Cursor {
        private final byte[] a;
        private int i;

        Cursor(byte[] a) { this.a = a; }

        boolean atEnd() { return i == a.length; }

        int readByte() {
            if (i >= a.length) throw new IndexOutOfBoundsException();
            return a[i++] & 0xFF;
        }

        long readLong() {
            if (i + 8 > a.length) throw new IndexOutOfBoundsException();
            long v = 0;
            for (int k = 0; k < 8; k++) {
                v = (v << 8) | (a[i++] & 0xFFL);
            }
            return v;
        }

        /** Unsigned LEB128, max 5 bytes (32-bit). */
        int readVarint() {
            int v = 0, shift = 0;
            while (true) {
                if (i >= a.length || shift > 28) throw new IndexOutOfBoundsException();
                int b = a[i++] & 0xFF;
                v |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return v;
        }

        String readStr() {
            int len = readVarint();
            if (len < 0 || i + len > a.length) throw new IndexOutOfBoundsException();
            if (len == 0) return "";
            String s = new String(a, i, len, StandardCharsets.UTF_8);
            i += len;
            return s;
        }
    }
}
