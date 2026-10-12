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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * A segment being replayed is never evicted (see {@link #beginReplay}).
 *
 * <h2>Accounting contract</h2>
 * Every record passed to {@link #spill} ends up in exactly one of {@code spilledRecords}
 * (written) or {@code droppedRecords} (refused: store closed, cap/floor with nothing left
 * to evict, an I/O error, or a record that could not be encoded). The store does that
 * counting itself, so a caller must <em>not</em> add a refusal to its own drop counter.
 * When a segment is evicted, the records it held move into {@code droppedRecords} as well
 * (and into {@code evictedRecords}): the store tracks a record count per segment, counted
 * as they are written for this run's segments and by a decode-free scan for adopted ones.
 * Evictions, cap/floor refusals and {@code DELETE}-recovery discards are also reported to the
 * optional {@link LossListener}, outside the lock. Refusals by a closed store or after an I/O
 * error are reported by the caller, which sees them in the return value.
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
 *
 * <p>The lock lives on the open file descriptor, not on the lock file. The OS drops it when
 * the owning process dies however it dies ({@code kill -9}, OOM kill, a crashed worker), so a
 * {@code .spill-<instanceId>.lock} file left on disk by a dead process is expected and
 * harmless: the next run locks it and adopts the dead process's segments. If the lock is
 * held at startup, {@link #tryReacquireOwnership()} (called on every replay pass) retries it
 * and adopts the segments that were left alone once the holder lets go.
 *
 * <p>Within one JVM, a store never opens a lock file that another live store holds: on POSIX
 * systems closing any descriptor of a file drops every lock the process holds on it, so even
 * a probe would silently free the holder's lock for other processes. A JVM-wide claim (shared
 * across classloaders, so it also covers a store leaked by a previous Mule deployment) is taken
 * before the file is opened and released when the store closes.
 */
public final class SpillStore implements Closeable {

    /** Filesystem free-space probe. A seam so tests can exercise the floor without a real full disk. */
    @FunctionalInterface
    public interface FreeSpaceProbe {
        long usableBytes(Path dir) throws IOException;
    }

    /** Why records left the buffer without being shipped; see {@link LossListener}. */
    public enum LossKind {
        /** Oldest segment(s) evicted for the total cap or the free-space floor while spilling. */
        EVICTED,
        /** New record(s) refused: cap/floor reached and nothing left to evict. */
        REFUSED,
        /** Oldest segment(s) evicted by the startup reconcile (usually a previous run's backlog). */
        STARTUP_EVICTED,
        /**
         * Previous-run segments discarded by {@code spillRecoveryMode=DELETE}. A planned discard of
         * records this run never accepted, so they are not added to {@code droppedRecords}.
         */
        RECOVERY_DISCARDED
    }

    /**
     * Told about every loss the buffer causes, so the host can report it. Invoked outside the
     * store's lock, possibly from the constructor (startup reconcile, {@code DELETE} recovery)
     * and from any thread that spills. Must be fast and must not throw.
     */
    @FunctionalInterface
    public interface LossListener {
        /**
         * @param segments segments removed (0 for {@link LossKind#REFUSED})
         * @param records  records lost; records in a segment that could not be read at all are not included
         * @param bytes    on-disk bytes removed (0 for {@link LossKind#REFUSED})
         */
        void onLoss(LossKind kind, int segments, long records, long bytes);
    }

    private static final LossListener NO_LISTENER = (kind, segments, records, bytes) -> { };

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
    private final LossListener lossListener;

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
    // The subset of ignoredSeqs ignored only because a live sibling held the lock at startup;
    // tryReacquireOwnership() hands them to the configured recovery mode once it lets go.
    private final Set<Long> foreignSeqs = new HashSet<>();
    // Records per segment (under writeLock): counted on append for this run's segments, by a
    // decode-free scan for adopted ones; -1 when a segment could not be read at all. Read on
    // eviction so the records a deleted segment held are counted as dropped, not lost silently.
    private final Map<Long, Long> recordsBySeq = new HashMap<>();
    // Segments a replay worker is reading right now; eviction skips them (see beginReplay).
    private final Set<Long> replayingSeqs = new HashSet<>();

    /**
     * Prefix of the JVM-wide claim a store takes on a lock file before it opens it (see
     * {@link #claimInJvm}). The claims live in {@link System#getProperties()} because that is
     * the one map every classloader in the JVM shares: under Mule a leaked store from the
     * previous deployment and the store of the new one are loaded by different classloaders, so
     * a static field here would not see the other.
     */
    private static final String JVM_LOCK_CLAIM_PREFIX = "com.mulesoft.support.batchhttp.spill.lock:";

    // Directory ownership lock; null when locking is unsupported or a sibling holds it.
    private FileChannel lockChannel;
    private FileLock dirLock;
    /** JVM-wide claim on our lock file held while {@link #dirLock} is (or is assumed) held; null otherwise. */
    private String jvmLockClaim;
    /** Unique value stored under our claims, so a store only ever releases its own. */
    private final String jvmClaimToken = "SpillStore@" + Integer.toHexString(System.identityHashCode(this))
            + "/" + System.nanoTime();
    /** Written under writeLock (constructor, tryReacquireOwnership); volatile for the lock-free getter. */
    private volatile boolean ownsDirectory;

    // Losses not yet reported to the listener (under writeLock); handed over after the lock is released.
    private int pendingEvictedSegs;
    private long pendingEvictedRecs;
    private long pendingEvictedBytes;
    private long pendingRefusedRecs;
    // Previous-run segments discarded by DELETE recovery (constructor / ownership take-over only).
    private int discardedSegs;
    private long discardedRecs;
    private long discardedBytes;
    // Segments adopted from a previous run (ADOPT), for the startup INFO line.
    private int recoveredSegments;
    private long recoveredBytes;

    // observability
    private final AtomicLong spilledRecords = new AtomicLong();
    private final AtomicLong spilledBytes = new AtomicLong();
    private final AtomicLong evictedSegments = new AtomicLong();
    private final AtomicLong droppedRecords = new AtomicLong();
    /** Records that were inside evicted segments (a subset of droppedRecords). */
    private final AtomicLong evictedRecords = new AtomicLong();
    /** Intact records found in segments adopted from a previous run. */
    private final AtomicLong adoptedRecords = new AtomicLong();
    /** Bytes skipped as corrupt while replaying segments (torn tails, interior damage recovered around). */
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
        this(dir, maxSegmentBytes, maxTotalBytes, minFreeBytes, probe, codec,
                recoveryMode, startupReconcile, instanceId, null);
    }

    /**
     * @param lossListener told about every eviction, refusal and recovery discard (see
     *                     {@link LossListener}); {@code null} for none. It can be called before
     *                     this constructor returns.
     */
    public SpillStore(Path dir, long maxSegmentBytes, long maxTotalBytes, long minFreeBytes,
                      FreeSpaceProbe probe, SpillCodec codec,
                      BatchConfig.SpillRecoveryMode recoveryMode, boolean startupReconcile,
                      String instanceId, LossListener lossListener) throws IOException {
        this.lossListener = lossListener != null ? lossListener : NO_LISTENER;
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
        try {
            recoverExistingState();
        } catch (IOException | RuntimeException e) {
            // The caller never gets a store to close, so let go of the lock (and the JVM-wide
            // claim) here; a claim left behind would lock every later store in this JVM out.
            releaseDirectoryLock();
            throw e;
        }
        if (discardedSegs > 0) {
            notifyLoss(LossKind.RECOVERY_DISCARDED, discardedSegs, discardedRecs, discardedBytes);
        }
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
        // A legacy (id-less) store or a peer migrator in this JVM holds .spill.lock: opening and
        // closing a channel on it here would release that lock for other processes (see claimInJvm).
        String claim = claimInJvm(".spill.lock");
        if (claim == null) return;
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
                        long size = sizeQuietly(p);
                        long n = SpillReader.countRecords(p, maxSegmentBytes);
                        try {
                            if (Files.deleteIfExists(p)) {
                                discardedSegs++;
                                discardedRecs += Math.max(n, 0);
                                discardedBytes += size;
                            }
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
            // Something else in this JVM holds the legacy lock, or locking is unsupported.
            // Skip migration; correctness (no double-adopt) is preserved either way.
        } finally {
            unclaimInJvm(claim);
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
     *
     * <p>A lock held by another store in this JVM is detected through the JVM-wide claim,
     * before any channel is opened (see {@link #claimInJvm}).
     */
    private boolean acquireDirectoryLock() {
        String claim = claimInJvm(lockFileName);
        if (claim == null) {
            return false; // another live SpillStore in THIS JVM holds our lock file
        }
        try {
            lockChannel = FileChannel.open(dir.resolve(lockFileName),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            dirLock = lockChannel.tryLock();
            if (dirLock == null) {
                // A sibling process holds the lock — it owns any existing segments. Closing
                // our channel is safe: this JVM holds no lock on the file (we had the claim).
                lockChannel.close();
                lockChannel = null;
                unclaimInJvm(claim);
                return false;
            }
            jvmLockClaim = claim;
            return true;
        } catch (OverlappingFileLockException e) {
            // Something in THIS JVM outside the claim protocol (not a SpillStore of this
            // version) locked the file. Treat it as a live holder.
            closeQuietly(lockChannel);
            lockChannel = null;
            unclaimInJvm(claim);
            return false;
        } catch (IOException e) {
            closeQuietly(lockChannel);
            lockChannel = null;
            jvmLockClaim = claim; // keep the claim: we act as the owner, so no same-JVM store may too
            return true; // locking unsupported -> assume sole ownership
        }
    }

    /**
     * Release {@link #dirLock}, its channel and the JVM-wide claim. Idempotent. The channel is
     * closed before the claim is dropped, so no other store in this JVM can open the lock file
     * while we still hold the OS lock through it.
     */
    private void releaseDirectoryLock() {
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
        if (jvmLockClaim != null) {
            unclaimInJvm(jvmLockClaim);
            jvmLockClaim = null;
        }
    }

    /**
     * Claim the lock file {@code fileName} in our directory for this store, JVM-wide; returns the
     * claim key, or {@code null} when another live store in this JVM already holds it.
     *
     * <p>Why a claim before the {@link FileLock}: on Linux and macOS a {@code FileLock} is a POSIX
     * record lock, which belongs to the process, and closing <em>any</em> descriptor of the file
     * releases every lock the process holds on it. So merely testing a lock file that another store
     * in this JVM holds — open a second channel, get {@link OverlappingFileLockException}, close
     * the channel — silently drops that store's lock for every other process, while the JVM still
     * reports it valid. A second runtime sharing the directory would then adopt and delete a live
     * store's segments. The claim lets a store see the holder without touching the file.
     */
    private String claimInJvm(String fileName) {
        String key = JVM_LOCK_CLAIM_PREFIX + lockKeyPath(fileName);
        try {
            return System.getProperties().putIfAbsent(key, jvmClaimToken) == null ? key : null;
        } catch (SecurityException e) {
            return key; // no access to the shared map: fall back to the plain FileLock behaviour
        }
    }

    private void unclaimInJvm(String key) {
        if (key == null) return;
        try {
            System.getProperties().remove(key, jvmClaimToken); // only ever our own claim
        } catch (SecurityException ignore) {
            // nothing was claimed (see claimInJvm)
        }
    }

    /** Canonical path of a file in our directory, so two spellings of one directory share a claim. */
    private String lockKeyPath(String fileName) {
        Path base;
        try {
            base = dir.toRealPath();
        } catch (IOException e) {
            base = dir.toAbsolutePath().normalize();
        }
        return base.resolve(fileName).toString();
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
                long n = SpillReader.countRecords(p, maxSegmentBytes);
                try {
                    Files.deleteIfExists(p);
                    evictedSegments.incrementAndGet();
                    discardedSegs++;
                    discardedRecs += Math.max(n, 0);
                    discardedBytes += size;
                } catch (IOException ignore) {
                    // Could not delete; fall back to ignoring it so we never replay it. Like any
                    // ignored segment its bytes are not counted toward the cap: it can never be
                    // evicted, so counting it would hold overCap() true for good.
                    ignoredSeqs.add(seq);
                    segs++;
                }
            } else if (mode == BatchConfig.SpillRecoveryMode.IGNORE) {
                ignoredSeqs.add(seq);   // leave on disk, but never replay/evict/count
                if (!ownsDirectory) foreignSeqs.add(seq); // re-examined once the live holder lets go
                segs++;
            } else { // ADOPT
                long size = sizeQuietly(p);
                adopt(seq, p);
                bytes += size;
                segs++;
            }
        }
        seqGen.set(maxSeq + 1);   // always start past the highest existing seq, even when ignoring
        totalBytesOnDisk = bytes;
        segmentsOnDisk = segs;
    }

    /**
     * Take a previous run's segment into the buffer (under writeLock or from the constructor):
     * count its intact records without decoding them, so that evicting it later counts what it
     * held and the startup INFO line can say how much was recovered.
     */
    private void adopt(long seq, Path p) {
        long n = SpillReader.countRecords(p, maxSegmentBytes);
        recordsBySeq.put(seq, n);
        if (n > 0) adoptedRecords.addAndGet(n);
        recoveredSegments++;
        recoveredBytes += sizeQuietly(p);
    }

    /** Prune oldest segments until both the total-bytes cap and the free-space floor hold. */
    private void reconcileOnStartup() {
        int segs;
        long recs, bytes;
        synchronized (writeLock) {
            try {
                ensureRoomFor(0);
            } catch (IOException ignore) {
                // best effort; the normal spill path will reconcile again on first write
            }
            segs = pendingEvictedSegs;
            recs = pendingEvictedRecs;
            bytes = pendingEvictedBytes;
            pendingEvictedSegs = 0;
            pendingEvictedRecs = 0;
            pendingEvictedBytes = 0;
        }
        if (segs > 0) {
            notifyLoss(LossKind.STARTUP_EVICTED, segs, recs, bytes);
        }
    }

    /**
     * Persist a batch of records to disk. Thread-safe. Never throws — logging must never
     * break logging.
     *
     * <p>Returns {@code true} only when every record was written. Any record not written has
     * already been counted in {@code droppedRecords} by the store (see the accounting contract
     * in the class doc), so a caller must not count it again.
     */
    public boolean spill(List<LogRecord> batch) {
        if (batch == null || batch.isEmpty()) return true;
        return spillRecords(batch) == batch.size();
    }

    /**
     * {@link #spill} that reports how many of the batch's records were written. The rest were
     * refused and are already counted in {@code droppedRecords}.
     */
    public int spillRecords(List<LogRecord> batch) {
        if (batch == null || batch.isEmpty()) return 0;
        int written = 0;
        int evictedSegs;
        long evictedRecs, evictedBytes, refused;
        synchronized (writeLock) {
            if (closed) {
                // The store has been sealed. Writing now would open a fresh segment that nobody
                // will ever close — a leaked file handle, and a segment left half-described on
                // disk. Refuse instead, and count the records here (the caller must not count them
                // again). Reachable when a flusher outlives the drain budget and spills late.
                droppedRecords.addAndGet(batch.size());
                return 0;
            }
            int i = 0;
            try {
                for (; i < batch.size(); i++) {
                    byte[] payload;
                    try {
                        payload = codec.encode(batch.get(i));
                    } catch (RuntimeException unencodable) {
                        droppedRecords.incrementAndGet(); // a codec bug on one record must not lose the batch
                        continue;
                    }
                    int frame = frameOverhead + payload.length;
                    // A frame that starts a new segment also pays for that segment's header.
                    long need = opensNewSegment(frame) ? frame + SpillSegmentHeader.LENGTH : frame;
                    if (!ensureRoomFor(need)) {
                        droppedRecords.incrementAndGet();
                        pendingRefusedRecs++;
                        continue; // nothing left to evict and still no room — drop this record
                    }
                    rollIfNeeded(frame);
                    active.append(payload);
                    recordsBySeq.merge(active.seq(), 1L, Long::sum);
                    totalBytesOnDisk += frame;
                    bytesSinceFreeProbe += frame; // drives the throttled free-space re-probe
                    spilledRecords.incrementAndGet();
                    spilledBytes.addAndGet(frame);
                    written++;
                }
            } catch (IOException | RuntimeException e) {
                // (RuntimeException: e.g. an UncheckedIOException from listing the directory while
                // looking for a segment to evict — spill() must never throw.)
                // Record i and everything after it were not written; everything before it was
                // written or already counted. Count only that remainder. Record i may have left
                // part of a frame behind (in the stream buffer or on disk), so stop using this
                // segment: closing it turns the junk into the torn tail of a closed segment, which
                // the reader skips and counts, instead of something later records are appended
                // behind. The next record opens a fresh segment.
                droppedRecords.addAndGet(batch.size() - i);
                abandonActive();
            }
            evictedSegs = pendingEvictedSegs;
            evictedRecs = pendingEvictedRecs;
            evictedBytes = pendingEvictedBytes;
            refused = pendingRefusedRecs;
            pendingEvictedSegs = 0;
            pendingEvictedRecs = 0;
            pendingEvictedBytes = 0;
            pendingRefusedRecs = 0;
        }
        // Outside the lock: the listener logs, and spillers must never queue behind that I/O.
        if (evictedSegs > 0) notifyLoss(LossKind.EVICTED, evictedSegs, evictedRecs, evictedBytes);
        if (refused > 0) notifyLoss(LossKind.REFUSED, 0, refused, 0);
        return written;
    }

    private void notifyLoss(LossKind kind, int segments, long records, long bytes) {
        try {
            lossListener.onLoss(kind, segments, records, bytes);
        } catch (RuntimeException ignore) {
            // a reporting failure must never turn into a spill failure
        }
    }

    /** Close the active segment after a failed write so nothing is appended behind a torn frame. */
    private void abandonActive() {
        if (active != null) {
            try {
                active.close();
            } catch (IOException ignore) {
                // the disk is failing; the reader copes with whatever reached it
            }
            active = null;
        }
    }

    /** True when writing a frame of {@code frameBytes} would open a new segment first (see {@link #rollIfNeeded}). */
    private boolean opensNewSegment(int frameBytes) {
        return active == null
                || (active.bytesWritten() > 0 && active.bytesWritten() + frameBytes > maxSegmentBytes);
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

    /**
     * Delete the oldest segment that is neither active, ignored, nor being replayed, and count
     * the records it held as dropped. Returns false if there is none.
     */
    private boolean evictOldestClosed() throws IOException {
        long activeSeq = active != null ? active.seq() : Long.MAX_VALUE;
        Path oldest = null;
        long oldestSeq = Long.MAX_VALUE;
        for (Path p : listSegments()) {
            long seq = seqOf(p);
            if (seq == activeSeq || ignoredSeqs.contains(seq) || replayingSeqs.contains(seq)) continue;
            if (seq < oldestSeq) { oldestSeq = seq; oldest = p; }
        }
        if (oldest == null) return false;
        long size = sizeQuietly(oldest);
        Long known = recordsBySeq.get(oldestSeq);
        // Every segment we write or adopt has an entry; only a file planted behind our back lacks
        // one, and that is counted the same decode-free way, before it is gone.
        long records = known != null ? known : SpillReader.countRecords(oldest, maxSegmentBytes);
        Files.deleteIfExists(oldest);
        recordsBySeq.remove(oldestSeq);
        totalBytesOnDisk -= size;
        if (totalBytesOnDisk < 0) totalBytesOnDisk = 0;
        if (segmentsOnDisk > 0) segmentsOnDisk--;
        evictedSegments.incrementAndGet();
        if (records > 0) {
            droppedRecords.addAndGet(records);
            evictedRecords.addAndGet(records);
            pendingEvictedRecs += records;
        }
        pendingEvictedSegs++;
        pendingEvictedBytes += size;
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
            SpillSegmentWriter full = active;
            active = null; // never append to a segment whose close (flush + fsync) failed
            full.close();
            active = openNewSegment();
        }
    }

    private SpillSegmentWriter openNewSegment() throws IOException {
        while (true) {
            long seq = seqGen.getAndIncrement();
            Path file = segmentPath(seq);
            // seqGen starts past every sequence on disk at startup, so this file is normally new.
            // Create it exclusively anyway: a second store with our id (a sibling that held the
            // lock, or one sharing the directory by mistake) allocates from the same range, and
            // two writers appending to one file would interleave their frames.
            try {
                Files.createFile(file);
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            SpillSegmentWriter w;
            try {
                w = new SpillSegmentWriter(file, seq, codec.codecId(), codec.framingVersion());
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(file); // don't leave a header-less empty file behind
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
            syncDirectory();
            recordsBySeq.put(seq, 0L);
            totalBytesOnDisk += w.bytesWritten(); // the header: eviction later subtracts the real file size
            segmentsOnDisk++;
            return w;
        }
    }

    /**
     * Best-effort {@code fsync} of the spill directory, once per new segment. A segment's own
     * fsync (on roll/seal) makes its <em>data</em> durable, but on POSIX filesystems the new
     * directory entry is only durable once the directory itself is synced; without this a host
     * crash or power loss could lose a whole rolled segment. Not needed for a process crash.
     * Platforms that cannot open a directory (Windows) or sync it simply skip this.
     */
    private void syncDirectory() {
        if (dir.getFileSystem() != FileSystems.getDefault()) return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException | SecurityException ignore) {
            // best effort; includes ClosedByInterruptException for an interrupted flusher
        }
    }

    private Path segmentPath(long seq) {
        return dir.resolve(segmentPrefix + String.format(SEQ_FORMAT, seq) + SUFFIX);
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

    /** Delete a fully-shipped segment and update the byte total. Also releases a {@link #beginReplay} claim. */
    public void deleteSegment(Path segment) {
        synchronized (writeLock) {
            long seq = seqOf(segment);
            long size = sizeQuietly(segment);
            try {
                // Only a file that was really there leaves the books; one already gone was
                // accounted for by whoever removed it, and decrementing again would drift low.
                if (Files.deleteIfExists(segment)) {
                    totalBytesOnDisk -= size;
                    if (totalBytesOnDisk < 0) totalBytesOnDisk = 0;
                    if (segmentsOnDisk > 0) segmentsOnDisk--;
                }
                recordsBySeq.remove(seq);
            } catch (IOException ignore) {
                // leave accounting alone; a later pass will retry the delete
            } finally {
                replayingSeqs.remove(seq);
            }
        }
    }

    /**
     * Claim a segment for a replay worker so eviction cannot delete it mid-read (its records
     * would then be counted as dropped and as sent). Returns false when the segment is no
     * longer eligible — evicted or deleted since the pass listed it, or ignored — which is not
     * an endpoint failure. Released by {@link #deleteSegment} or {@link #endReplay}.
     */
    boolean beginReplay(Path segment) {
        synchronized (writeLock) {
            long seq = seqOf(segment);
            if (ignoredSeqs.contains(seq) || (active != null && active.seq() == seq) || !Files.exists(segment)) {
                return false;
            }
            replayingSeqs.add(seq);
            return true;
        }
    }

    /** Release a {@link #beginReplay} claim on a segment that is being kept. */
    void endReplay(Path segment) {
        synchronized (writeLock) {
            replayingSeqs.remove(seqOf(segment));
        }
    }

    /**
     * If a live sibling held the directory lock at startup, try to take it now. On success the
     * segments left alone at startup are handled per the configured {@code spillRecoveryMode}:
     * {@code ADOPT} makes them shippable, {@code DELETE} discards them, {@code IGNORE} keeps
     * leaving them alone. Called at the start of every replay pass, so a holder that stops (or
     * a leaked store from a previous Mule deployment that is finally closed) hands over within
     * one interval. Returns whether this store owns the directory.
     */
    public boolean tryReacquireOwnership() {
        if (ownsDirectory) return true;
        int segs = 0;
        long recs = 0, bytes = 0;
        synchronized (writeLock) {
            if (ownsDirectory) return true;
            if (closed || !acquireDirectoryLock()) return false;
            ownsDirectory = true;
            for (Long seq : foreignSeqs) {
                Path p = segmentPath(seq);
                if (!Files.exists(p)) {
                    // The previous holder shipped or evicted it before letting go.
                    ignoredSeqs.remove(seq);
                    if (segmentsOnDisk > 0) segmentsOnDisk--;
                    continue;
                }
                if (recoveryMode == BatchConfig.SpillRecoveryMode.ADOPT) {
                    ignoredSeqs.remove(seq);
                    long size = sizeQuietly(p);
                    adopt(seq, p);
                    totalBytesOnDisk += size; // segmentsOnDisk already counted it at startup
                } else if (recoveryMode == BatchConfig.SpillRecoveryMode.DELETE) {
                    long size = sizeQuietly(p);
                    long n = SpillReader.countRecords(p, maxSegmentBytes);
                    try {
                        if (Files.deleteIfExists(p)) {
                            ignoredSeqs.remove(seq);
                            if (segmentsOnDisk > 0) segmentsOnDisk--;
                            evictedSegments.incrementAndGet();
                            segs++;
                            recs += Math.max(n, 0);
                            bytes += size;
                        }
                    } catch (IOException ignore) {
                        // stays ignored, exactly as at startup
                    }
                }
                // IGNORE: leave them where they are, still never replayed or evicted.
            }
            foreignSeqs.clear();
        }
        if (segs > 0) notifyLoss(LossKind.RECOVERY_DISCARDED, segs, recs, bytes);
        return true;
    }

    public long totalBytes() {
        synchronized (writeLock) {
            return totalBytesOnDisk;
        }
    }

    /**
     * Record bytes the replayer discarded as corrupt while draining a segment (a torn tail, or
     * a record damaged by an improperly-closed journal that the reader skipped). Surfaced
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

    /** The directory this store keeps its segments in. */
    public Path directory() {
        return dir;
    }

    /** Segments left by a previous run that this store adopted for replay ({@code ADOPT}). */
    public int recoveredSegments() {
        synchronized (writeLock) {
            return recoveredSegments;
        }
    }

    /** On-disk bytes of the {@link #recoveredSegments()}. */
    public long recoveredBytes() {
        synchronized (writeLock) {
            return recoveredBytes;
        }
    }

    /** Pre-existing segments left untouched: {@code IGNORE} mode, or a live sibling held the lock. */
    public int ignoredSegments() {
        synchronized (writeLock) {
            return ignoredSeqs.size();
        }
    }

    public SpillStats stats() {
        synchronized (writeLock) {
            return new SpillStats(spilledRecords.get(), spilledBytes.get(),
                    evictedSegments.get(), droppedRecords.get(), segmentsOnDisk, totalBytesOnDisk,
                    corruptBytesSkipped.get(), evictedRecords.get(), adoptedRecords.get());
        }
    }

    @Override
    public void close() {
        synchronized (writeLock) {
            closed = true;
        }
        sealActiveSegment();
        synchronized (writeLock) {
            releaseDirectoryLock();
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
        public final long spilledRecords, spilledBytes, evictedSegments;
        /**
         * Records the buffer lost: refused (store closed, cap/floor with nothing left to evict,
         * I/O error) plus every record inside an evicted segment. Counted only here, never also
         * in the shipper's {@code dropped}.
         */
        public final long droppedRecords;
        public final int segmentCount;
        public final long totalBytes;
        /** Bytes discarded as corrupt during replay (torn tails, interior damage recovered around). */
        public final long corruptBytesSkipped;
        /** Records that were inside evicted segments; part of {@link #droppedRecords}. */
        public final long evictedRecords;
        /**
         * Intact records found in segments adopted from a previous run. They are not in this
         * run's {@code accepted}, but their replay or eviction lands in this run's counters.
         */
        public final long adoptedRecords;

        SpillStats(long spilledRecords, long spilledBytes, long evictedSegments,
                   long droppedRecords, int segmentCount, long totalBytes,
                   long corruptBytesSkipped, long evictedRecords, long adoptedRecords) {
            this.spilledRecords = spilledRecords;
            this.spilledBytes = spilledBytes;
            this.evictedSegments = evictedSegments;
            this.droppedRecords = droppedRecords;
            this.segmentCount = segmentCount;
            this.totalBytes = totalBytes;
            this.corruptBytesSkipped = corruptBytesSkipped;
            this.evictedRecords = evictedRecords;
            this.adoptedRecords = adoptedRecords;
        }

        @Override public String toString() {
            return "SpillStats{spilledRecords=" + spilledRecords + ", spilledBytes=" + spilledBytes
                    + ", evictedSegments=" + evictedSegments + ", droppedRecords=" + droppedRecords
                    + ", segmentCount=" + segmentCount + ", totalBytes=" + totalBytes
                    + ", corruptBytesSkipped=" + corruptBytesSkipped
                    + ", evictedRecords=" + evictedRecords + ", adoptedRecords=" + adoptedRecords + '}';
        }
    }
}
