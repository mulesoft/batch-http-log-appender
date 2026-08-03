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
import com.mulesoft.support.batchhttp.core.FilenameSafe;
import com.mulesoft.support.batchhttp.core.LogRecord;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The disk-spill buffer: a bounded, free-space-aware, append-only store of
 * {@link LogRecord}s spread across rotating segment files.
 *
 * <h2>Single-writer</h2>
 * All mutation funnels through {@link #spill} / {@link #deleteSegment} /
 * {@link #sealActiveSegment} under {@code writeLock}. Flusher threads and the
 * application thread (overflow path) may call concurrently; the monitor serializes
 * them. Replay workers read closed segments only (never the active one), so reads
 * never race writes on the same file. {@code totalBytesOnDisk} is touched only
 * under the lock.
 *
 * <h2>Rotation</h2>
 * Records are framed into the active segment until it reaches {@code maxSegmentBytes},
 * then a new segment (next sequence number) is started.
 *
 * <h2>Bounding + free-space protection (drop-oldest)</h2>
 * Before writing, if the total spilled bytes would exceed {@code maxTotalBytes} or
 * the filesystem's usable space would drop below {@code minFreeBytes}, the oldest
 * closed segment is deleted to make room. The buffer therefore never exhausts the
 * filesystem; under sustained pressure it sheds the oldest data. When
 * {@code startupReconcile} is set, the same pruning runs once at construction so a
 * run that inherits a large backlog on a nearly-full disk frees space before writing.
 *
 * <h2>Per-instance file naming (co-located apps)</h2>
 * Every segment file carries the owning instance's id in its name —
 * {@code spill-<instanceId>-<seq>.log} — and each store only ever lists, replays,
 * evicts, and counts files bearing <em>its own</em> id. Two apps that share a spill
 * directory (e.g. two same-vendor apps on one host, or an explicitly shared
 * {@code spillDirectory}) therefore never write the same filename or touch each
 * other's segments. When the id is empty the legacy name {@code spill-<seq>.log} is
 * used, so existing single-app deployments are byte-for-byte unchanged. The id must be
 * <em>stable across restarts</em> of the same app (so orphan recovery finds its own
 * files) and <em>distinct per concurrently-running instance</em>.
 *
 * <h2>Crash recovery and directory ownership</h2>
 * On construction the store scans its directory for pre-existing segment files bearing
 * its own id (left by a previous run or crash). A {@link FileLock} on a per-instance
 * hidden lock file ({@code .spill-<instanceId>.lock}) distinguishes the two cases that
 * matter: if the lock is free the previous owner is gone and the files are genuine
 * orphans, handled per {@link BatchConfig.SpillRecoveryMode} ({@code ADOPT} replays
 * them, {@code DELETE} discards them, {@code IGNORE} leaves them); if the lock is held,
 * a sibling instance with the same id is alive and owns the files, so this instance
 * ignores them rather than double-shipping or deleting them out from under it.
 */
public final class SpillStore implements Closeable {

    /** Filesystem free-space probe. A seam so tests can exercise the floor without a real full disk. */
    @FunctionalInterface
    public interface FreeSpaceProbe {
        long usableBytes(Path dir) throws IOException;
    }

    private static final String PREFIX = "spill-";
    private static final String SUFFIX = ".log";
    /** 20 digits holds Long.MAX_VALUE; zero-padding makes lexicographic == numeric order. */
    private static final String SEQ_FORMAT = "%020d";

    private final Path dir;
    /** Sanitized owning-instance id embedded in every segment name; "" for the legacy single-app layout. */
    private final String instanceId;
    /** Filename prefix for this instance's segments: {@code spill-} or {@code spill-<instanceId>-}. */
    private final String segmentPrefix;
    /** Per-instance lock file name, so co-located apps each own their own segment set. */
    private final String lockFileName;
    private final long maxSegmentBytes;
    private final long maxTotalBytes;
    private final long minFreeBytes;
    private final FreeSpaceProbe freeSpaceProbe;
    private final SpillCodec codec;
    /** On-disk bytes each record's frame adds beyond its payload (v1: 4, v2: 12). */
    private final int frameOverhead;
    private final BatchConfig.SpillRecoveryMode recoveryMode;

    private final Object writeLock = new Object();
    private final AtomicLong seqGen = new AtomicLong(0);
    private SpillSegmentWriter active;       // null until first write or after seal
    private long totalBytesOnDisk;
    /**
     * Segment files of ours currently on disk (including the active and any ignored ones),
     * maintained alongside {@link #totalBytesOnDisk} so {@link #stats()} costs no directory
     * scan — it is polled into JMX/metrics systems at whatever cadence the host chooses.
     */
    private int segmentsOnDisk;
    /** Set by {@link #close()}; a late {@link #spill} must not re-open a segment behind it. */
    private boolean closed;

    // Free-space probe throttle: usableBytes() is a filesystem syscall (statvfs), so on the
    // spill path we cache its result and re-probe at most once per FREE_SPACE_PROBE_INTERVAL
    // of bytes written rather than on every record. The cached value is intentionally
    // conservative — between probes we subtract what we have written from the last reading,
    // so the floor can only be crossed early, never late.
    private static final long FREE_SPACE_PROBE_INTERVAL = 4L * 1024 * 1024; // 4 MiB
    private long cachedUsableBytes = Long.MAX_VALUE;     // last probe reading (under writeLock)
    private long bytesSinceFreeProbe = Long.MAX_VALUE;   // force a probe on first use

    // Pre-existing segments we must not replay or evict (IGNORE mode, or a live sibling owns them).
    private final Set<Long> ignoredSeqs = new HashSet<>();

    // Directory ownership lock; null when locking is unsupported or a sibling holds it.
    private FileChannel lockChannel;
    private FileLock dirLock;
    private final boolean ownsDirectory;

    // observability
    private final AtomicLong spilledRecords = new AtomicLong();
    private final AtomicLong spilledBytes = new AtomicLong();
    private final AtomicLong evictedSegments = new AtomicLong();
    private final AtomicLong droppedRecords = new AtomicLong();
    /** Bytes skipped as corrupt while replaying v2 segments (interior damage recovered around). */
    private final AtomicLong corruptBytesSkipped = new AtomicLong();

    /**
     * Backward-compatible constructor: BINARY codec, ADOPT recovery, no startup
     * reconcile, legacy ({@code spill-<seq>.log}) naming. Retained for tests and
     * simple embeddings.
     */
    public SpillStore(Path dir, long maxSegmentBytes, long maxTotalBytes,
                      long minFreeBytes, FreeSpaceProbe probe) throws IOException {
        this(dir, maxSegmentBytes, maxTotalBytes, minFreeBytes, probe,
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);
    }

    /**
     * Backward-compatible constructor without an instance id: uses the legacy
     * {@code spill-<seq>.log} naming and a shared lock file.
     */
    public SpillStore(Path dir, long maxSegmentBytes, long maxTotalBytes, long minFreeBytes,
                      FreeSpaceProbe probe, SpillCodec codec,
                      BatchConfig.SpillRecoveryMode recoveryMode, boolean startupReconcile) throws IOException {
        this(dir, maxSegmentBytes, maxTotalBytes, minFreeBytes, probe, codec,
                recoveryMode, startupReconcile, null);
    }

    /**
     * @param instanceId stable-across-restarts, distinct-per-running-instance id embedded in
     *                   each segment file name so co-located apps sharing {@code dir} never
     *                   collide or touch each other's files. {@code null}/blank selects the
     *                   legacy {@code spill-<seq>.log} layout.
     */
    public SpillStore(Path dir, long maxSegmentBytes, long maxTotalBytes, long minFreeBytes,
                      FreeSpaceProbe probe, SpillCodec codec,
                      BatchConfig.SpillRecoveryMode recoveryMode, boolean startupReconcile,
                      String instanceId) throws IOException {
        this.dir = dir;
        this.maxSegmentBytes = maxSegmentBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.minFreeBytes = minFreeBytes;
        this.freeSpaceProbe = probe != null ? probe : SpillStore::defaultUsableBytes;
        this.codec = codec != null ? codec : SpillCodec.BINARY;
        this.frameOverhead = this.codec.framingVersion() == SpillSegmentHeader.VERSION_V2
                ? SpillFrameV2.HEADER_LEN : 4;
        this.recoveryMode = recoveryMode != null ? recoveryMode : BatchConfig.SpillRecoveryMode.ADOPT;
        this.instanceId = sanitizeId(instanceId);
        this.segmentPrefix = this.instanceId.isEmpty() ? PREFIX : PREFIX + this.instanceId + "-";
        this.lockFileName = this.instanceId.isEmpty() ? ".spill.lock" : ".spill-" + this.instanceId + ".lock";
        Files.createDirectories(dir);
        this.ownsDirectory = acquireDirectoryLock();
        // Upgrade path: a pre-upgrade run wrote id-less `spill-<seq>.log` files. Claim them
        // into our namespace before recovery so they are not stranded by the new naming.
        if (!this.instanceId.isEmpty() && ownsDirectory) {
            migrateLegacyOrphans();
        }
        recoverExistingState();
        if (startupReconcile) {
            reconcileOnStartup();
        }
    }

    /**
     * Adopt segment files written by a pre-upgrade run under the legacy id-less name
     * {@code spill-<seq>.log}, renaming them into this instance's namespace so the
     * post-upgrade store finds and replays them. Gated by the legacy shared lock file
     * ({@code .spill.lock}) so that when several id-scoped apps share one directory only
     * one claims the legacy orphans, and a still-running legacy (id-less) instance — which
     * holds that lock — keeps its live files untouched. Honors {@link #recoveryMode}:
     * {@code DELETE} discards the orphans, {@code IGNORE} leaves them id-less (so they are
     * never replayed or evicted), {@code ADOPT} renames them in.
     */
    private void migrateLegacyOrphans() {
        if (recoveryMode == BatchConfig.SpillRecoveryMode.IGNORE) {
            return; // leave legacy files exactly where they are, untouched
        }
        Path legacyLock = dir.resolve(".spill.lock");
        try (FileChannel ch = FileChannel.open(legacyLock,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lk = null;
            try {
                lk = ch.tryLock();
                if (lk == null) {
                    return; // a live legacy instance or a peer migrator owns them; don't touch
                }
                List<Path> legacy = listLegacySegments();
                legacy.sort((a, b) -> Long.compare(legacySeqOf(a), legacySeqOf(b)));
                long next = 0;
                for (Path p : legacy) {
                    if (recoveryMode == BatchConfig.SpillRecoveryMode.DELETE) {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                            // leave it; it stays id-less and is never replayed by us
                        }
                        continue;
                    }
                    // ADOPT: rename into our namespace with a fresh, collision-free sequence.
                    Path target;
                    do {
                        target = dir.resolve(segmentPrefix + String.format(SEQ_FORMAT, next++) + SUFFIX);
                    } while (Files.exists(target));
                    try {
                        Files.move(p, target);
                    } catch (IOException ignore) {
                        // best effort; an unrenamed legacy file stays id-less and inert for us
                    }
                }
            } finally {
                if (lk != null) {
                    try { lk.release(); } catch (IOException ignore) { /* best effort */ }
                }
            }
        } catch (OverlappingFileLockException | IOException ignore) {
            // Another store in this JVM holds the legacy lock, or locking is unsupported.
            // Skip migration; correctness (no double-adopt) is preserved either way.
        }
    }

    /** Pre-existing id-less segments: {@code spill-<digits>.log} exactly (the legacy layout). */
    private List<Path> listLegacySegments() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = new ArrayList<>();
            s.filter(SpillStore::isLegacySegment).forEach(out::add);
            return out;
        }
    }

    private static boolean isLegacySegment(Path p) {
        String n = p.getFileName().toString();
        if (!n.startsWith(PREFIX) || !n.endsWith(SUFFIX)) return false;
        String body = n.substring(PREFIX.length(), n.length() - SUFFIX.length());
        if (body.isEmpty()) return false;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) < '0' || body.charAt(i) > '9') return false; // an id segment has a '-'
        }
        return true;
    }

    private static long legacySeqOf(Path p) {
        String n = p.getFileName().toString();
        try {
            return Long.parseLong(n.substring(PREFIX.length(), n.length() - SUFFIX.length()));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Reduce an instance id to a filename-safe token. Dashes are kept (the id may itself
     * contain them, e.g. {@code newrelic-batch-http}): matching always strips the exact
     * {@code segmentPrefix} and then requires an all-digit sequence body, so an id-internal
     * dash can never be confused with the id/seq boundary or with a sibling's id. Blank/null
     * yields "", which selects the legacy {@code spill-<seq>.log} naming.
     */
    private static String sanitizeId(String id) {
        if (id == null) return "";
        return FilenameSafe.sanitize(id); // "" stays "" -> legacy spill-<seq>.log naming
    }

    private static long defaultUsableBytes(Path dir) throws IOException {
        FileStore fs = Files.getFileStore(dir);
        return fs.getUsableSpace();
    }

    /**
     * Try to take an exclusive lock on the directory's lock file. Returns true if we
     * acquired it (we own the directory), false if a live sibling holds it. Never
     * throws: if the filesystem does not support locking we assume sole ownership,
     * which is the safe assumption for the common single-process case.
     */
    private boolean acquireDirectoryLock() {
        try {
            lockChannel = FileChannel.open(dir.resolve(lockFileName),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            dirLock = lockChannel.tryLock();
            if (dirLock == null) {
                // A sibling process holds the lock — it owns any existing segments.
                lockChannel.close();
                lockChannel = null;
                return false;
            }
            return true;
        } catch (OverlappingFileLockException e) {
            // Another SpillStore in THIS JVM already locked the same dir.
            closeQuietly(lockChannel);
            lockChannel = null;
            return false;
        } catch (IOException e) {
            closeQuietly(lockChannel);
            lockChannel = null;
            return true; // locking unsupported -> assume sole ownership
        }
    }

    /**
     * Seed the sequence counter from segments already on disk and decide what to do
     * with them. Effective mode is the configured one when we own the directory, but
     * forced to IGNORE when a live sibling owns it (never touch a sibling's files).
     */
    private void recoverExistingState() throws IOException {
        BatchConfig.SpillRecoveryMode mode = ownsDirectory
                ? recoveryMode
                : BatchConfig.SpillRecoveryMode.IGNORE;

        long maxSeq = -1;
        long bytes = 0;
        int segs = 0;
        for (Path p : listSegments()) {
            long seq = seqOf(p);
            if (seq > maxSeq) maxSeq = seq;

            if (mode == BatchConfig.SpillRecoveryMode.DELETE) {
                long size = sizeQuietly(p);
                try {
                    Files.deleteIfExists(p);
                    evictedSegments.incrementAndGet();
                } catch (IOException ignore) {
                    // Could not delete; fall back to ignoring it so we never replay it.
                    ignoredSeqs.add(seq);
                    bytes += size;
                    segs++;
                }
            } else if (mode == BatchConfig.SpillRecoveryMode.IGNORE) {
                ignoredSeqs.add(seq);   // leave on disk, but never replay/evict/count
                segs++;
            } else { // ADOPT
                bytes += sizeQuietly(p);
                segs++;
            }
        }
        seqGen.set(maxSeq + 1);   // always start past the highest existing seq, even when ignoring
        totalBytesOnDisk = bytes;
        segmentsOnDisk = segs;
    }

    /** Prune oldest segments until both the total-bytes cap and the free-space floor hold. */
    private void reconcileOnStartup() {
        synchronized (writeLock) {
            try {
                ensureRoomFor(0);
            } catch (IOException ignore) {
                // best effort; the normal spill path will reconcile again on first write
            }
        }
    }

    /**
     * Persist a batch of records to disk. Thread-safe. Never throws for I/O issues —
     * on failure it records the drop and returns false (logging must never break logging).
     */
    public boolean spill(List<LogRecord> batch) {
        if (batch == null || batch.isEmpty()) return true;
        synchronized (writeLock) {
            if (closed) {
                // The store has been sealed. Writing now would open a fresh segment that nobody
                // will ever close — a leaked file handle, and a segment left half-described on
                // disk. Refuse instead, so the caller counts the records rather than losing them
                // silently. Reachable when a flusher outlives the drain budget and spills late.
                droppedRecords.addAndGet(batch.size());
                return false;
            }
            try {
                for (LogRecord r : batch) {
                    byte[] payload = codec.encode(r);
                    int frame = frameOverhead + payload.length;
                    if (!ensureRoomFor(frame)) {
                        droppedRecords.incrementAndGet();
                        continue; // nothing left to evict and still no room — drop this record
                    }
                    rollIfNeeded(frame);
                    active.append(payload);
                    totalBytesOnDisk += frame;
                    bytesSinceFreeProbe += frame; // drives the throttled free-space re-probe
                    spilledRecords.incrementAndGet();
                    spilledBytes.addAndGet(frame);
                }
                return true;
            } catch (IOException e) {
                droppedRecords.addAndGet(batch.size());
                return false;
            }
        }
    }

    /**
     * Make room for {@code bytes} by evicting the oldest closed segment(s) until both
     * the total-bytes cap and the free-space floor are satisfied. Returns false only
     * when there is nothing left to evict and still no room.
     */
    private boolean ensureRoomFor(long bytes) throws IOException {
        while (overCap(bytes) || underFreeFloor(bytes)) {
            if (!evictOldestClosed()) {
                return false;
            }
        }
        return true;
    }

    private boolean overCap(long incoming) {
        return totalBytesOnDisk + incoming > maxTotalBytes;
    }

    private boolean underFreeFloor(long incoming) throws IOException {
        if (minFreeBytes <= 0) {
            return false; // no floor configured -> never probe the filesystem (zero syscalls)
        }
        // Re-probe only every FREE_SPACE_PROBE_INTERVAL of writes; between probes, decay the
        // cached reading by what we've written so the estimate stays conservative.
        if (bytesSinceFreeProbe >= FREE_SPACE_PROBE_INTERVAL) {
            cachedUsableBytes = freeSpaceProbe.usableBytes(dir);
            bytesSinceFreeProbe = 0;
        }
        long estimatedUsable = cachedUsableBytes - bytesSinceFreeProbe;
        return estimatedUsable - incoming < minFreeBytes;
    }

    /** Delete the oldest segment that is neither active nor ignored. Returns false if none. */
    private boolean evictOldestClosed() throws IOException {
        long activeSeq = active != null ? active.seq() : Long.MAX_VALUE;
        Path oldest = null;
        long oldestSeq = Long.MAX_VALUE;
        for (Path p : listSegments()) {
            long seq = seqOf(p);
            if (seq == activeSeq || ignoredSeqs.contains(seq)) continue;
            if (seq < oldestSeq) { oldestSeq = seq; oldest = p; }
        }
        if (oldest == null) return false;
        long size = sizeQuietly(oldest);
        Files.deleteIfExists(oldest);
        totalBytesOnDisk -= size;
        if (totalBytesOnDisk < 0) totalBytesOnDisk = 0;
        if (segmentsOnDisk > 0) segmentsOnDisk--;
        evictedSegments.incrementAndGet();
        // A delete genuinely frees disk; force the next floor check to re-probe rather than
        // trust the now-stale cached reading (eviction is the rare disk-pressure path, so the
        // extra syscall here is proportionate and keeps the ensureRoomFor loop from over-evicting).
        bytesSinceFreeProbe = FREE_SPACE_PROBE_INTERVAL;
        return true;
    }

    /** Open a new segment if there is none, or roll when the active one would exceed the cap. */
    private void rollIfNeeded(int incomingFrameBytes) throws IOException {
        if (active == null) {
            active = openNewSegment();
            return;
        }
        if (active.bytesWritten() > 0 && active.bytesWritten() + incomingFrameBytes > maxSegmentBytes) {
            active.close();
            active = openNewSegment();
        }
    }

    private SpillSegmentWriter openNewSegment() throws IOException {
        long seq = seqGen.getAndIncrement();
        Path file = dir.resolve(segmentPrefix + String.format(SEQ_FORMAT, seq) + SUFFIX);
        // seqGen always starts past every sequence already on disk, so this file is new.
        SpillSegmentWriter w = new SpillSegmentWriter(file, seq, codec.codecId(), codec.framingVersion());
        segmentsOnDisk++;
        return w;
    }

    /**
     * Closes the active segment so the replayer can pick it up. The next spill opens
     * a fresh segment. Used on shutdown and to let recovered/just-written data drain.
     */
    public void sealActiveSegment() {
        synchronized (writeLock) {
            if (active != null) {
                try {
                    active.close();
                } catch (IOException ignore) {
                    // best effort; bytes were flushed per-append anyway
                }
                active = null;
            }
        }
    }

    /** Segments eligible for replay: every segment on disk except the open active one and ignored ones, oldest-first. */
    public List<Path> shippableSegments() throws IOException {
        synchronized (writeLock) {
            long activeSeq = active != null ? active.seq() : Long.MAX_VALUE;
            List<Path> all = listSegments();
            List<Path> out = new ArrayList<>(all.size());
            for (Path p : all) {
                long seq = seqOf(p);
                if (seq != activeSeq && !ignoredSeqs.contains(seq)) out.add(p);
            }
            out.sort((a, b) -> Long.compare(seqOf(a), seqOf(b)));
            return out;
        }
    }

    /** Delete a fully-shipped segment and update the byte total. */
    public void deleteSegment(Path segment) {
        synchronized (writeLock) {
            long size = sizeQuietly(segment);
            try {
                Files.deleteIfExists(segment);
                totalBytesOnDisk -= size;
                if (totalBytesOnDisk < 0) totalBytesOnDisk = 0;
                if (segmentsOnDisk > 0) segmentsOnDisk--;
            } catch (IOException ignore) {
                // leave accounting alone; a later pass will retry the delete
            }
        }
    }

    public long totalBytes() {
        synchronized (writeLock) {
            return totalBytesOnDisk;
        }
    }

    /**
     * Record bytes the replayer discarded as corrupt while draining a v2 segment (a record
     * damaged by an improperly-closed journal that the reader skipped to resync). Surfaced
     * via {@link #stats()} so corruption recovery is observable rather than silent.
     */
    void addCorruptBytesSkipped(long bytes) {
        if (bytes > 0) corruptBytesSkipped.addAndGet(bytes);
    }

    /** Maximum payload size the readers should trust, used to reject corrupt lengths. */
    public long maxSegmentBytes() {
        return maxSegmentBytes;
    }

    /** True when this instance acquired the directory ownership lock (no live sibling). */
    public boolean ownsDirectory() {
        return ownsDirectory;
    }

    public SpillStats stats() {
        synchronized (writeLock) {
            return new SpillStats(spilledRecords.get(), spilledBytes.get(),
                    evictedSegments.get(), droppedRecords.get(), segmentsOnDisk, totalBytesOnDisk,
                    corruptBytesSkipped.get());
        }
    }

    @Override
    public void close() {
        synchronized (writeLock) {
            closed = true;
        }
        sealActiveSegment();
        synchronized (writeLock) {
            if (dirLock != null) {
                try {
                    dirLock.release();
                } catch (IOException ignore) {
                    // best effort
                }
                dirLock = null;
            }
            closeQuietly(lockChannel);
            lockChannel = null;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Only this instance's own segments — files bearing our {@code segmentPrefix}. */
    private List<Path> listSegments() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = new ArrayList<>();
            s.filter(this::isSegment).forEach(out::add);
            return out;
        }
    }

    /**
     * True only for <em>this instance's</em> segment files. With a non-empty id we must
     * also reject a co-located sibling whose id is a prefix of ours: {@code spill-app-...}
     * would otherwise match the legacy matcher for id "app2" etc. The trailing {@code -}
     * in {@code segmentPrefix} plus an all-digit sequence body make the match exact.
     */
    private boolean isSegment(Path p) {
        String n = p.getFileName().toString();
        if (!n.startsWith(segmentPrefix) || !n.endsWith(SUFFIX)) return false;
        String body = n.substring(segmentPrefix.length(), n.length() - SUFFIX.length());
        if (body.isEmpty()) return false;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) < '0' || body.charAt(i) > '9') return false;
        }
        return true;
    }

    private long seqOf(Path p) {
        String n = p.getFileName().toString();
        String digits = n.substring(segmentPrefix.length(), n.length() - SUFFIX.length());
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE; // unparseable names sort last and are never "oldest"
        }
    }

    private static long sizeQuietly(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignore) {
                // best effort
            }
        }
    }

    /** Immutable diagnostics snapshot. */
    public static final class SpillStats {
        public final long spilledRecords, spilledBytes, evictedSegments, droppedRecords;
        public final int segmentCount;
        public final long totalBytes;
        /** Bytes discarded as corrupt during v2 replay (interior journal damage recovered around). */
        public final long corruptBytesSkipped;

        SpillStats(long spilledRecords, long spilledBytes, long evictedSegments,
                   long droppedRecords, int segmentCount, long totalBytes,
                   long corruptBytesSkipped) {
            this.spilledRecords = spilledRecords;
            this.spilledBytes = spilledBytes;
            this.evictedSegments = evictedSegments;
            this.droppedRecords = droppedRecords;
            this.segmentCount = segmentCount;
            this.totalBytes = totalBytes;
            this.corruptBytesSkipped = corruptBytesSkipped;
        }

        @Override public String toString() {
            return "SpillStats{spilledRecords=" + spilledRecords + ", spilledBytes=" + spilledBytes
                    + ", evictedSegments=" + evictedSegments + ", droppedRecords=" + droppedRecords
                    + ", segmentCount=" + segmentCount + ", totalBytes=" + totalBytes
                    + ", corruptBytesSkipped=" + corruptBytesSkipped + '}';
        }
    }
}
