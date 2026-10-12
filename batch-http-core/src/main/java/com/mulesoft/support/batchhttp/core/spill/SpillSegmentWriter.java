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
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
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
 * <h2>Durability</h2>
 * Each {@link #append} flushes the buffer to the OS, so once it returns the frame lives in
 * the kernel page cache and survives an abrupt death of the <em>process</em> ({@code kill -9},
 * OOM kill, {@code Runtime.halt}, a crashed worker): the next run reads it back. At most the
 * single in-flight frame is torn, and the reader skips and counts that. {@link #close()}
 * additionally {@code fsync}s the file, and the store closes a segment whenever it rolls,
 * whenever the replayer seals it (once per replay pass) and on shutdown; the store also
 * {@code fsync}s the directory when it creates a segment, so the file's name is durable too
 * (best effort: skipped where a directory cannot be synced, e.g. Windows). So a <em>host</em>
 * crash or power loss should lose at most what was spilled since the last roll/seal, about one
 * replay interval or one segment; this is best effort and depends on the filesystem honouring
 * {@code fsync}. A segment deleted just before a power loss can also reappear and be replayed
 * again (a duplicate, never a loss). We deliberately do not {@code fsync} per frame: that would
 * defeat the "near-zero overhead" goal, and the spill path is already the abnormal case.
 *
 * <p>The file is written through a {@link FileOutputStream} and synced through its
 * {@link FileDescriptor}, not through a {@code FileChannel}. A {@code FileChannel} is
 * interruptible: a write or {@code force()} from a thread whose interrupt flag is set closes
 * the channel with {@code ClosedByInterruptException}. That is exactly the thread that must
 * still be able to spill, because a flusher interrupted at shutdown hands its batch to the
 * spill buffer.
 *
 * <p>Not thread-safe: callers serialize access (see {@link SpillStore}).
 */
final class SpillSegmentWriter implements Closeable {

    private final Path file;
    private final long seq;
    private final byte framingVersion;
    private final OutputStream out;
    /** Descriptor to {@code fsync} on close; null for the test-stream seam or a non-default filesystem. */
    private final FileDescriptor fd;
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
        OutputStream opened;
        FileDescriptor descriptor = null;
        if (stream != null) {
            opened = stream;
        } else if (file.getFileSystem() == FileSystems.getDefault()) {
            FileOutputStream fos = new FileOutputStream(file.toFile(), true);
            descriptor = fos.getFD();
            opened = new BufferedOutputStream(fos);
        } else {
            // A non-default (e.g. in-memory) filesystem has no descriptor to sync.
            opened = new BufferedOutputStream(Files.newOutputStream(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
        }
        this.fd = descriptor;
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

    /**
     * Flush, {@code fsync} and close. Called when a segment rolls, is sealed for replay, or the
     * store closes, so the host-crash exposure window ends at each of those points. A sync
     * failure is still reported, but the stream is closed either way.
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            out.flush();
            if (fd != null) fd.sync();
        } catch (IOException e) {
            failure = e;
        }
        try {
            out.close();
        } catch (IOException e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
