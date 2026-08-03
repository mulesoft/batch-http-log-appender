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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpillReaderTruncationTest {

    private static final long MAX = 16L * 1024 * 1024;
    private static final SpillCodec CODEC = SpillCodec.BINARY;

    private static LogRecord rec(int i) {
        return LogRecord.builder()
                .timestampMillis(1000 + i).level("INFO").loggerName("l").threadName("t")
                .message("record " + i).build();
    }

    /** Header bytes for the codec under test. */
    private static byte[] header() {
        return SpillSegmentHeader.of(CODEC.codecId());
    }

    /** Build a frame: 4-byte big-endian length + payload. */
    private static byte[] frame(byte[] payload) {
        int len = payload.length;
        byte[] out = new byte[4 + len];
        out[0] = (byte) (len >>> 24);
        out[1] = (byte) (len >>> 16);
        out[2] = (byte) (len >>> 8);
        out[3] = (byte) len;
        System.arraycopy(payload, 0, out, 4, len);
        return out;
    }

    private List<LogRecord> readAll(Path segment) throws IOException {
        List<LogRecord> out = new ArrayList<>();
        try (SpillReader r = new SpillReader(segment, MAX)) {
            LogRecord rec;
            while ((rec = r.next()) != null) out.add(rec);
        }
        return out;
    }

    @Test
    void readsCleanSegment(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(header());
        for (int i = 0; i < 3; i++) bos.write(frame(CODEC.encode(rec(i))));
        Path seg = dir.resolve("clean.log");
        Files.write(seg, bos.toByteArray());

        List<LogRecord> got = readAll(seg);
        assertEquals(3, got.size());
        assertEquals("record 0", got.get(0).message());
        assertEquals("record 2", got.get(2).message());
    }

    @Test
    void stopsAtTruncatedFinalFrame(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(header());
        for (int i = 0; i < 3; i++) bos.write(frame(CODEC.encode(rec(i))));
        // A 4th frame whose header claims 100 bytes but only 10 follow (crash mid-write).
        bos.write(new byte[]{0, 0, 0, 100});
        bos.write(new byte[10]);
        Path seg = dir.resolve("truncated.log");
        Files.write(seg, bos.toByteArray());

        List<LogRecord> got = readAll(seg);
        assertEquals(3, got.size(), "the 3 intact records survive; the torn tail is skipped");
    }

    @Test
    void stopsAtTruncatedLengthPrefix(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(header());
        bos.write(frame(CODEC.encode(rec(0))));
        bos.write(new byte[]{0, 0}); // only 2 of 4 length bytes survived
        Path seg = dir.resolve("partial-len.log");
        Files.write(seg, bos.toByteArray());

        List<LogRecord> got = readAll(seg);
        assertEquals(1, got.size());
    }

    @Test
    void stopsOnCorruptNegativeLength(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(header());
        bos.write(frame(CODEC.encode(rec(0))));
        bos.write(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}); // -1
        bos.write(CODEC.encode(rec(1)));
        Path seg = dir.resolve("neg-len.log");
        Files.write(seg, bos.toByteArray());

        List<LogRecord> got = readAll(seg);
        assertEquals(1, got.size(), "reader stops at the corrupt length rather than resyncing");
    }

    @Test
    void stopsOnOversizedLength(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(header());
        bos.write(frame(CODEC.encode(rec(0))));
        bos.write(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}); // ~2GB, well over the cap
        Path seg = dir.resolve("huge-len.log");
        Files.write(seg, bos.toByteArray());

        List<LogRecord> got = readAll(seg);
        assertEquals(1, got.size());
    }

    @Test
    void emptyOrHeaderlessSegmentYieldsNoRecords(@TempDir Path dir) throws Exception {
        // A file too short to even hold a header (e.g. crash before the header flushed)
        // must yield zero records rather than throw.
        Path tooShort = dir.resolve("short.log");
        Files.write(tooShort, new byte[]{1, 2, 3});
        assertEquals(0, readAll(tooShort).size());

        // A valid header with no frames after it.
        Path headerOnly = dir.resolve("header-only.log");
        Files.write(headerOnly, header());
        assertEquals(0, readAll(headerOnly).size());
    }

    @Test
    void readsSegmentWrittenByOtherCodec(@TempDir Path dir) throws Exception {
        // Header advertises JSON; reader must honour it regardless of the default codec.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(SpillSegmentHeader.of(SpillCodec.JSON.codecId()));
        for (int i = 0; i < 2; i++) bos.write(frame(SpillCodec.JSON.encode(rec(i))));
        Path seg = dir.resolve("json.log");
        Files.write(seg, bos.toByteArray());

        List<LogRecord> got = readAll(seg);
        assertEquals(2, got.size());
        assertEquals("record 1", got.get(1).message());
    }
}
