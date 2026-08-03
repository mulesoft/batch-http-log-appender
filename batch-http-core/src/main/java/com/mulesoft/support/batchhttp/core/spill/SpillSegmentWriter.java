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

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Owns a single, currently-active spill segment file and appends length-prefixed
 * frames to it.
 *
 * <p>File layout: a fixed {@link SpillSegmentHeader} (magic + framing version + codec id)
 * written once when the file is created, then a sequence of frames. The header's framing
 * version selects how each record is wrapped:
 * <ul>
 *   <li>{@code VERSION_V1} — {@code [length(4)] [payload]}. The length prefix lets the
 *       reader detect a truncated trailing frame after a crash in O(1).</li>
 *   <li>{@code VERSION_V2} — {@code [SYNC(4)] [length(4)] [crc32c(4)] [payload]} (see
 *       {@link SpillFrameV2}). The marker + CRC additionally let the reader skip a record
 *       corrupted in the <em>middle</em> of a segment and resync on the next intact one,
 *       safely for arbitrary payload bytes.</li>
 * </ul>
 * The header makes the file self-describing so a later run can decode it even if its
 * configured codec or framing differs.
 *
 * <p>Each {@link #append} flushes the buffer to the OS so an abrupt process death
 * loses at most the single in-flight frame. We do not {@code fsync} per frame —
 * that would defeat the "near-zero overhead" goal and the spill path is already
 * the abnormal case.
 *
 * <p>Not thread-safe: callers serialize access (see {@link SpillStore}).
 */
final class SpillSegmentWriter implements Closeable {

    private final Path file;
    private final long seq;
    private final byte framingVersion;
    private final OutputStream out;
    private long bytesWritten;

    /** Legacy constructor (v1 framing) retained for callers/tests that don't specify a version. */
    SpillSegmentWriter(Path file, long seq, byte codecId) throws IOException {
        this(file, seq, codecId, SpillSegmentHeader.VERSION_V1);
    }

    SpillSegmentWriter(Path file, long seq, byte codecId, byte framingVersion) throws IOException {
        this(file, seq, codecId, framingVersion, null);
    }

    /**
     * @param stream constructor seam for tests that need to drive header stamping over an
     *               arbitrary stream; {@code null} opens {@code file} itself.
     */
    SpillSegmentWriter(Path file, long seq, byte codecId, byte framingVersion, OutputStream stream)
            throws IOException {
        this.file = file;
        this.seq = seq;
        this.framingVersion = framingVersion;
        long existing = Files.exists(file) ? Files.size(file) : 0L;
        // Append mode so a re-opened (recovered) segment is never truncated.
        OutputStream opened = stream != null ? stream : new BufferedOutputStream(Files.newOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
        this.bytesWritten = existing;
        // Stamp the self-describing header exactly once, when the file is brand new.
        if (existing == 0L) {
            try {
                byte[] header = SpillSegmentHeader.of(codecId, framingVersion);
                opened.write(header);
                opened.flush();
                bytesWritten += header.length;
            } catch (IOException e) {
                // A full disk is precisely the condition this buffer exists to survive, and it
                // surfaces here first. Nothing else references the handle we just opened, so
                // failing without closing it strands a file descriptor for the life of the JVM.
                try {
                    opened.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        }
        this.out = opened;
    }

    Path file() { return file; }

    long seq() { return seq; }

    long bytesWritten() { return bytesWritten; }

    /** Append one framed record. Returns the number of bytes the frame occupies on disk. */
    int append(byte[] payload) throws IOException {
        int len = payload.length;
        int frame;
        if (framingVersion == SpillSegmentHeader.VERSION_V2) {
            byte[] header = new byte[SpillFrameV2.HEADER_LEN];
            header[0] = SpillFrameV2.SYNC0;
            header[1] = SpillFrameV2.SYNC1;
            header[2] = SpillFrameV2.SYNC2;
            header[3] = SpillFrameV2.SYNC3;
            SpillFrameV2.putIntBE(header, 4, len);
            SpillFrameV2.putIntBE(header, 8, SpillFrameV2.crc32c(payload));
            out.write(header);
            out.write(payload);
            frame = SpillFrameV2.HEADER_LEN + len;
        } else {
            byte[] header = new byte[4];
            SpillFrameV2.putIntBE(header, 0, len);
            out.write(header);
            out.write(payload);
            frame = 4 + len;
        }
        out.flush(); // hand the bytes to the OS; a crash loses only an in-flight frame
        bytesWritten += frame;
        return frame;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
