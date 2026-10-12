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
package com.mulesoft.support.batchhttp.core;

import com.mulesoft.support.batchhttp.core.sink.LogSink;
import com.mulesoft.support.batchhttp.core.spill.SpillCodec;
import com.mulesoft.support.batchhttp.core.spill.SpillReplayer;
import com.mulesoft.support.batchhttp.core.spill.SpillStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The batching engine. Application threads call {@link #offer(LogRecord)} which
 * is non-blocking by default (configurable overflow policy). One or more flusher
 * threads pull records, accumulate them until a flush trigger fires
 * (record count, byte estimate, or linger time), serialize via the {@link LogSink},
 * and send through {@link HttpTransport} with retry + backoff.
 *
 * <p>This is intentionally framework-agnostic: a Log4j2/Logback appender simply
 * adapts its native event into a {@link LogRecord} and calls {@code offer}.
 */
public final class BatchingLogShipper implements AutoCloseable {

    /** Sentinel pushed to the queue to wake a flusher for shutdown. */
    private static final LogRecord POISON = LogRecord.builder().message("__BATCH_HTTP_POISON__").build();

    /** How long {@link #close()} waits on a flusher after interrupting it, before giving up. */
    private static final long FLUSHER_INTERRUPT_GRACE_MILLIS = 2_000L;

    /** How long a {@code BLOCK}-policy offer waits per attempt before re-checking {@link #running}. */
    private static final long BLOCK_POLL_MILLIS = 100L;

    /** Upper bound close() waits for offer() calls already past their running check (BLOCK ones notice within one poll). */
    private static final long OFFER_QUIESCE_MILLIS = 2 * BLOCK_POLL_MILLIS;

    /** Floor for the time close() spends writing still-queued records to the spill buffer. */
    private static final long MIN_SHUTDOWN_SPILL_MILLIS = 1_000L;

    /**
     * offer() calls between their running check and their enqueue. close() waits (briefly) for
     * zero before the final drain, so in the common case every accepted record is in the queue
     * when it is drained. The wait is bounded, so a call that is still stuck (a DROP_OLDEST offer
     * waiting on the spill lock) is covered by {@link #finalDrainStarted} instead.
     */
    private final AtomicInteger offersInFlight = new AtomicInteger();

    /**
     * Set by close() just before its last drain of the queue. An offer() that enqueues and then
     * sees it takes its record back out (if the drain has not already taken it) and settles it
     * itself, so a record can never sit in a queue nobody will read again.
     */
    private volatile boolean finalDrainStarted;

    private final LogSink sink;
    private final BatchConfig config;
    private final HttpTransport transport;
    private final BlockingQueue<LogRecord> queue;
    private final List<Thread> flushers = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** The engine's own health log (drops, fatal rejections, overflow, spill failures, heartbeat). Never null. */
    private final Diagnostics diag;
    /** Optional periodic stats() heartbeat thread; null when {@code statsInterval <= 0}. */
    private final Thread statsThread;

    // Lightweight observability — read these from JMX/metrics if desired.
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sentBatches = new AtomicLong();
    private final AtomicLong sentRecords = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();

    // Queue-overflow logging throttle: under sustained overflow every application thread
    // would otherwise emit a line per dropped record. We coalesce into at most one WARN per
    // interval, reporting how many were dropped/diverted since the last one.
    private static final long OVERFLOW_LOG_INTERVAL_NANOS = 5_000_000_000L; // 5s
    private final AtomicLong overflowDroppedSinceLog = new AtomicLong();
    private final AtomicLong overflowDivertedSinceLog = new AtomicLong();
    private final AtomicLong lastOverflowLogNanos = new AtomicLong(0L);

    // Spill-loss logging throttle: same idea for records the spill buffer evicts (oldest segment
    // deleted for the cap/floor) or refuses (nothing left to evict). One WARN per interval at
    // most, carrying the counts since the previous one; the first loss is reported at once.
    private static final long SPILL_LOSS_LOG_INTERVAL_NANOS = 5_000_000_000L; // 5s
    private final AtomicLong spillEvictedSegsSinceLog = new AtomicLong();
    private final AtomicLong spillEvictedRecsSinceLog = new AtomicLong();
    private final AtomicLong spillRefusedSinceLog = new AtomicLong();
    private final AtomicLong lastSpillLossLogNanos =
            new AtomicLong(System.nanoTime() - SPILL_LOSS_LOG_INTERVAL_NANOS);

    private final int effectiveMaxRecords;
    private final int effectiveMaxBytes;

    // Disk-spill safety net (null when spill is disabled or could not be initialised).
    private final SpillStore spillStore;
    private final SpillReplayer spillReplayer;

    public BatchingLogShipper(LogSink sink, BatchConfig config) {
        this.sink = sink;
        this.config = config;
        this.diag = config.diagnostics != null ? config.diagnostics : Diagnostics.NOOP;
        this.transport = new HttpTransport(config, sink.name());
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity);
        // Respect both engine config and the vendor's own ceilings.
        this.effectiveMaxRecords = Math.min(config.maxBatchRecords, sink.limits().maxRecordsPerBatch);
        this.effectiveMaxBytes = Math.min(config.maxBatchBytes, sink.limits().maxUncompressedBytes);

        SpillStore store = null;
        SpillReplayer replayer = null;
        if (config.spillEnabled) {
            try {
                Path dir = config.spillDirectory != null
                        ? config.spillDirectory
                        : defaultSpillDir(sink.name());
                // Embed an instance id in segment filenames so co-located apps that share a
                // spill directory never overwrite each other's logs. Prefer the configured id,
                // fall back to the sink name (which also names the default per-sink directory).
                String instanceId = (config.spillInstanceId != null && !config.spillInstanceId.isBlank())
                        ? config.spillInstanceId
                        : sink.name();
                // The loss listener can fire from inside this constructor (startup reconcile,
                // DELETE recovery), so it must not touch spillStore, which is not assigned yet.
                store = new SpillStore(dir, config.maxSpillFileBytes, config.maxTotalSpillBytes,
                        config.minFreeDiskBytes, null,
                        SpillCodec.forFormat(config.spillFormat),
                        config.spillRecoveryMode, config.spillStartupReconcile, instanceId,
                        this::onSpillLoss);
                reportRecoveredState(store, instanceId);
                // The replayer starts before the flushers. If segments were adopted from a previous
                // run (or a crash), its first pass runs at once on its own thread, not one
                // spillReplayInterval later.
                replayer = new SpillReplayer(store, this::shipForReplay,
                        config.spillReplayInterval.toMillis(), effectiveMaxRecords,
                        config.shutdownDrainTimeout.toMillis(), sink.name(),
                        config.spillBacklogOrder, config.spillReplayThreads,
                        config.spillReplayMaxBytesPerSec, diag);
                replayer.start();
            } catch (Exception e) {
                // A bad spill directory must never stop live shipping; run without spill.
                store = null;
                replayer = null;
                diag.log(Diagnostics.Level.ERROR,
                        "batch-http[" + sink.name() + "] spill disabled — could not initialise spill buffer: "
                                + Diagnostics.describe(e) + " (live shipping continues; failed batches will be dropped, not buffered)",
                        e);
            }
        }
        this.spillStore = store;
        this.spillReplayer = replayer;

        for (int i = 0; i < Math.max(1, config.flusherThreads); i++) {
            Thread t = new Thread(this::runFlusher, "batch-http-flusher-" + sink.name() + "-" + i);
            t.setDaemon(true);
            flushers.add(t);
            t.start();
        }

        // Optional periodic stats heartbeat: a single daemon thread that logs a snapshot so
        // an operator can see delivery is healthy without inspecting the spill directory.
        long statsMs = config.statsInterval != null ? config.statsInterval.toMillis() : 0L;
        if (statsMs > 0) {
            Thread st = new Thread(() -> runStatsHeartbeat(statsMs), "batch-http-stats-" + sink.name());
            st.setDaemon(true);
            st.start();
            this.statsThread = st;
        } else {
            this.statsThread = null;
        }
    }

    /**
     * Report at startup what the spill store found on disk: segments adopted from a previous run
     * (which replay now), and segments left alone because another live instance holds the lock.
     */
    private void reportRecoveredState(SpillStore store, String instanceId) {
        String prefix = "batch-http[" + sink.name() + "] ";
        if (!store.ownsDirectory() && diag.isEnabled(Diagnostics.Level.WARN)) {
            diag.log(Diagnostics.Level.WARN, prefix + "spill directory " + store.directory()
                    + " is locked by another live instance with spillInstanceId=" + instanceId + "; "
                    + store.ignoredSegments() + " existing segment(s) left untouched (ownership is retried"
                    + " every spillReplayInterval) — give each running instance a distinct spillInstanceId");
        }
        if (store.recoveredSegments() > 0 && diag.isEnabled(Diagnostics.Level.INFO)) {
            diag.log(Diagnostics.Level.INFO, prefix + "recovered " + store.recoveredSegments()
                    + " spill segment(s) (" + store.recoveredBytes() + " bytes, "
                    + store.stats().adoptedRecords + " record(s)) left by a previous run"
                    + " (spillRecoveryMode=ADOPT) — replaying now");
        }
    }

    /**
     * {@link SpillStore.LossListener}: turn the store's losses into diagnostics. Startup losses
     * are one-shot and logged at once; runtime evictions/refusals are coalesced (see
     * {@link #noteSpillLoss}). The counting itself is the store's: this only reports. Called
     * outside the store's lock, possibly before the constructor has assigned {@code spillStore}.
     */
    private void onSpillLoss(SpillStore.LossKind kind, int segments, long records, long bytes) {
        String prefix = "batch-http[" + sink.name() + "] ";
        if (kind == SpillStore.LossKind.STARTUP_EVICTED) {
            if (diag.isEnabled(Diagnostics.Level.WARN)) {
                diag.log(Diagnostics.Level.WARN, prefix + "startup reconcile evicted " + segments
                        + " oldest spill segment(s) holding " + records + " record(s) (" + bytes
                        + " bytes) left by a previous run to satisfy maxTotalSpillBytes="
                        + config.maxTotalSpillBytes + "/minFreeDiskBytes=" + config.minFreeDiskBytes
                        + " — those logs are lost");
            }
        } else if (kind == SpillStore.LossKind.RECOVERY_DISCARDED) {
            if (diag.isEnabled(Diagnostics.Level.INFO)) {
                diag.log(Diagnostics.Level.INFO, prefix + "spillRecoveryMode=DELETE discarded " + segments
                        + " segment(s) (" + records + " record(s), " + bytes + " bytes) left by a previous run");
            }
        } else {
            if (kind == SpillStore.LossKind.EVICTED) {
                spillEvictedSegsSinceLog.addAndGet(segments);
                spillEvictedRecsSinceLog.addAndGet(records);
            } else {
                spillRefusedSinceLog.addAndGet(records);
            }
            noteSpillLoss(false);
        }
    }

    /**
     * At most once per {@link #SPILL_LOSS_LOG_INTERVAL_NANOS} (or now, when {@code force}), WARN
     * with the evictions and refusals accumulated since the last line. {@code force} is used by
     * close() so the final window is never stranded.
     */
    private void noteSpillLoss(boolean force) {
        if (!diag.isEnabled(Diagnostics.Level.WARN)) return;
        long now = System.nanoTime();
        long last = lastSpillLossLogNanos.get();
        if (!force && now - last < SPILL_LOSS_LOG_INTERVAL_NANOS) return;
        if (!lastSpillLossLogNanos.compareAndSet(last, now)) return; // another thread is logging this window
        long segs = spillEvictedSegsSinceLog.getAndSet(0);
        long recs = spillEvictedRecsSinceLog.getAndSet(0);
        long refused = spillRefusedSinceLog.getAndSet(0);
        if (segs == 0 && refused == 0) return;
        diag.log(Diagnostics.Level.WARN,
                "batch-http[" + sink.name() + "] spill buffer full (maxTotalSpillBytes=" + config.maxTotalSpillBytes
                        + ", minFreeDiskBytes=" + config.minFreeDiskBytes + "): evicted " + segs
                        + " oldest segment(s) holding " + recs + " record(s), refused " + refused
                        + " new record(s) since the last report — those logs are lost");
    }

    /**
     * Periodic heartbeat: emit a one-line stats snapshot every {@code intervalMs} until the
     * shipper is closed. Logged at INFO so it is on by default once an interval is set; the
     * cadence is the operator's throttle. Errors here never propagate.
     */
    private void runStatsHeartbeat(long intervalMs) {
        while (running.get()) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) return;
            try {
                if (diag.isEnabled(Diagnostics.Level.INFO)) {
                    diag.log(Diagnostics.Level.INFO, statsLine());
                }
            } catch (RuntimeException ignore) {
                // never let the heartbeat die or interfere with shipping
            }
        }
    }

    /** One-line, log-friendly rendering of the current stats (plus spill stats when enabled). */
    private String statsLine() {
        Stats s = stats();
        StringBuilder sb = new StringBuilder(160);
        sb.append("batch-http[").append(sink.name()).append("] stats")
                .append(" accepted=").append(s.accepted)
                .append(" sentRecords=").append(s.sentRecords)
                .append(" sentBatches=").append(s.sentBatches)
                .append(" dropped=").append(s.dropped)
                .append(" failedBatches=").append(s.failedBatches)
                .append(" queueDepth=").append(s.queueDepth);
        SpillStore.SpillStats sp = spillStats();
        if (sp != null) {
            sb.append(" spill{onDiskBytes=").append(sp.totalBytes)
                    .append(" segments=").append(sp.segmentCount)
                    .append(" spilledRecords=").append(sp.spilledRecords)
                    .append(" droppedRecords=").append(sp.droppedRecords)
                    .append(" evictedSegments=").append(sp.evictedSegments)
                    .append(" evictedRecords=").append(sp.evictedRecords)
                    .append(" adoptedRecords=").append(sp.adoptedRecords)
                    .append(" corruptBytesSkipped=").append(sp.corruptBytesSkipped)
                    .append('}');
        }
        return sb.toString();
    }

    /**
     * Default spill location when none is configured: a per-sink folder under the OS
     * temp directory — {@code /tmp/...} on Unix-like systems, {@code %TEMP%\...} on
     * Windows. Resolved via {@code java.io.tmpdir} so it is correct on both, falling
     * back to {@code /tmp} only if the JVM somehow reports no temp dir.
     *
     * <p>Note: the OS temp dir is convenient and zero-config but may be cleared on host
     * reboot (tmpfs / systemd-tmpfiles) or pod reschedule. Point {@code spillDirectory}
     * at persistent storage when durability across host restarts matters.
     */
    private static Path defaultSpillDir(String sinkName) {
        String tmp = System.getProperty("java.io.tmpdir");
        Path base = (tmp != null && !tmp.isBlank()) ? Path.of(tmp) : Path.of("/tmp");
        return base.resolve("batch-http-spill").resolve(sanitize(sinkName));
    }

    /** Make a sink name safe to use as a directory component (e.g. "Generic(host)" -> "Generic_host_"). */
    private static String sanitize(String name) {
        String safe = FilenameSafe.sanitize(name);
        return safe.isEmpty() ? "sink" : safe;
    }

    /**
     * Enqueue a record for shipping. Behaviour when the queue is full is governed
     * by {@link BatchConfig.OverflowPolicy}. Returns true if accepted.
     */
    public boolean offer(LogRecord record) {
        // Announce the call before checking `running`, so close() can wait for calls that got
        // past the check and are about to enqueue (see offersInFlight).
        offersInFlight.incrementAndGet();
        try {
            return offerWhileRunning(record);
        } finally {
            offersInFlight.decrementAndGet();
        }
    }

    private boolean offerWhileRunning(LogRecord record) {
        if (!running.get()) return false;
        record = truncateMessage(record);
        accepted.incrementAndGet();
        // NOTE: deliberately an if/else chain rather than `switch (enum)`. A switch
        // on an enum makes javac emit a synthetic $SwitchMap helper class
        // (BatchingLogShipper$1) that is resolved lazily, on first execution. When
        // this engine is driven by a Log4j2 appender under a container that hosts
        // log4j-core on a *different* classloader than this jar (e.g. Mule 4, where
        // log4j lives in lib/boot but the app bundles this library), that lazy load
        // happens on a logging thread whose classloader cannot see the synthetic
        // class -> NoClassDefFoundError. An if/else compiles to plain comparisons
        // with no extra class, so it works regardless of classloader topology.
        BatchConfig.OverflowPolicy policy = config.overflowPolicy;
        // A spill that refuses a record has already counted it in spillStats().droppedRecords, so
        // `dropped` is incremented below only when no spill was attempted at all.
        boolean canSpill = spillStore != null && config.spillOnOverflow;
        if (policy == BatchConfig.OverflowPolicy.BLOCK) {
            try {
                // Bounded waits rather than an unbounded put(): once close() has stopped the
                // flushers nothing will ever drain the queue again, and a put() parked on a
                // full queue would wedge the application thread that called log() for good.
                // Re-checking `running` turns that permanent hang into a counted drop.
                while (running.get()) {
                    if (queue.offer(record, BLOCK_POLL_MILLIS, TimeUnit.MILLISECONDS)) return settleIfStranded(record);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dropped.incrementAndGet();
                return false;
            }
            dropped.incrementAndGet();
            return false;
        } else if (policy == BatchConfig.OverflowPolicy.DROP_OLDEST) {
            while (!queue.offer(record)) {
                LogRecord evicted = queue.poll(); // evict oldest, then retry
                // A POISON sentinel at the head (close() racing this offer) is not a record:
                // never spill it (it would be replayed as a log line) or count it.
                if (evicted != null && evicted != POISON) {
                    if (canSpill && spillStore.spill(List.of(evicted))) {
                        noteOverflow(false);
                    } else {
                        if (!canSpill) dropped.incrementAndGet();
                        noteOverflow(true);
                    }
                }
            }
            return settleIfStranded(record);
        } else { // DROP_NEWEST (default)
            if (queue.offer(record)) return settleIfStranded(record);
            if (canSpill && spillStore.spill(List.of(record))) { // diverted to disk instead of dropped
                noteOverflow(false);
                return true;
            }
            if (!canSpill) dropped.incrementAndGet();
            noteOverflow(true);
            return false;
        }
    }

    /**
     * Called after {@code record} was enqueued. Normally a no-op returning true. If close() has
     * already started its last drain, nothing may ever read the queue again: take the record
     * back and settle it here, the way the drain would have (spill it for the next start, or
     * count it as dropped). {@code remove} and the drain both hold the queue's lock, so exactly
     * one of them gets the record. Returns whether the record was kept.
     */
    private boolean settleIfStranded(LogRecord record) {
        if (!finalDrainStarted || !queue.remove(record)) return true; // LogRecord equality is identity
        if (shutdownSpillReplays()) {
            // A refusal (the store may already be closed) is counted by the store itself.
            return spillStore.spill(List.of(record));
        }
        dropped.incrementAndGet();
        diag.log(Diagnostics.Level.ERROR, "batch-http[" + sink.name() + "] dropped 1 record(s) offered while"
                + " the appender was stopping — accepted after the final shutdown drain");
        return false;
    }

    /**
     * True when records spilled at shutdown will be replayed by the next start: a spill buffer
     * exists and {@code spillRecoveryMode=ADOPT}. With {@code DELETE} the next start discards
     * them and with {@code IGNORE} it never reads them, so spilling them would only hide a loss.
     */
    private boolean shutdownSpillReplays() {
        return spillStore != null && config.spillRecoveryMode == BatchConfig.SpillRecoveryMode.ADOPT;
    }

    /**
     * Record one queue-overflow event (a record dropped, or diverted to spill) and, at most
     * once per {@link #OVERFLOW_LOG_INTERVAL_NANOS}, emit a coalesced WARN. Without coalescing,
     * sustained overflow would have every application thread log on every record — turning a
     * capacity problem into a logging storm. The line reports counts since the previous one so
     * nothing is hidden, only batched.
     */
    private void noteOverflow(boolean droppedRecord) {
        if (droppedRecord) {
            overflowDroppedSinceLog.incrementAndGet();
        } else {
            overflowDivertedSinceLog.incrementAndGet();
        }
        if (!diag.isEnabled(Diagnostics.Level.WARN)) return;
        long now = System.nanoTime();
        long last = lastOverflowLogNanos.get();
        if (now - last < OVERFLOW_LOG_INTERVAL_NANOS) return;
        if (!lastOverflowLogNanos.compareAndSet(last, now)) return; // another thread is logging this window
        long dropped = overflowDroppedSinceLog.getAndSet(0);
        long diverted = overflowDivertedSinceLog.getAndSet(0);
        if (dropped == 0 && diverted == 0) return;
        diag.log(Diagnostics.Level.WARN,
                "batch-http[" + sink.name() + "] queue full (capacity=" + config.queueCapacity
                        + ", policy=" + config.overflowPolicy + "): " + dropped + " record(s) dropped, "
                        + diverted + " diverted to spill in the last "
                        + (OVERFLOW_LOG_INTERVAL_NANOS / 1_000_000_000L) + "s — the endpoint is not keeping up");
    }

    /**
     * Cap an oversized message at {@link BatchConfig#maxMessageBytes} before it enters the
     * queue, so a single huge event can't bloat memory, the HTTP request, or a spill frame.
     * Returns the original record untouched when truncation is disabled or the message fits
     * (the common case — {@link MessageTruncator#truncateUtf8} returns the same reference),
     * and rebuilds the record preserving every other field only when the message was cut.
     */
    private LogRecord truncateMessage(LogRecord record) {
        int max = config.maxMessageBytes;
        if (max <= 0) return record; // disabled
        String original = record.message();
        String capped = MessageTruncator.truncateUtf8(original, max);
        if (capped == original) return record; // identity => nothing was cut, no allocation
        LogRecord.Builder b = LogRecord.builder()
                .timestampMillis(record.timestampMillis())
                .level(record.level())
                .loggerName(record.loggerName())
                .threadName(record.threadName())
                .message(capped)
                .throwable(record.throwable());
        record.attributes().forEach(b::attribute);
        return b.build();
    }

    private void runFlusher() {
        List<LogRecord> batch = new ArrayList<>(Math.min(effectiveMaxRecords, 1024));
        int estimatedBytes = 0;
        long deadline = 0L; // meaningful only while a batch is accumulating (set below)

        while (true) {
            try {
                // No batch in flight => no linger window to honour, so block until the
                // next record (or the POISON shutdown sentinel) arrives. The earlier
                // poll(waitMs) here degenerated to poll(0) once the linger deadline had
                // lapsed on an empty queue: a non-blocking peek that never reset the
                // deadline, so the thread hot-spun at ~100% CPU after the backlog drained.
                // Once a batch IS accumulating, wait only for the remainder of its linger
                // window so a partial batch still flushes on time.
                LogRecord r;
                if (batch.isEmpty()) {
                    r = queue.take();
                } else {
                    long waitMs = Math.max(0, deadline - System.nanoTime() / 1_000_000L);
                    r = queue.poll(waitMs, TimeUnit.MILLISECONDS);
                }

                if (r == POISON) {
                    flush(batch);
                    return;
                }

                if (r != null) {
                    int recordBytes = estimateBytes(r);
                    // Close the batch BEFORE adding a record that would push it past the byte
                    // ceiling, not after. The ceiling is min(config, vendor hard limit), and a
                    // vendor answers an oversized body with a terminal 4xx (Datadog 413) that
                    // the engine will not retry — so crossing it by "just one more record"
                    // loses the whole batch.
                    if (!batch.isEmpty() && estimatedBytes + recordBytes > effectiveMaxBytes) {
                        flush(batch);
                        estimatedBytes = 0;
                    }
                    if (batch.isEmpty()) deadline = monotonicDeadline(); // start linger window
                    batch.add(r);
                    estimatedBytes += recordBytes;
                }

                boolean sizeTrigger = batch.size() >= effectiveMaxRecords || estimatedBytes >= effectiveMaxBytes;
                boolean timeTrigger = !batch.isEmpty() && System.nanoTime() / 1_000_000L >= deadline;

                if (sizeTrigger || timeTrigger) {
                    flush(batch);
                    estimatedBytes = 0;
                    // deadline is re-armed when the next record starts a fresh batch.
                }
            } catch (InterruptedException e) {
                // Only close() interrupts a flusher, and only once the drain budget is spent. With a
                // spill buffer the next start replays (ADOPT), re-assert the flag so send() fails at
                // once (an interrupted HTTP send and backoff sleep both return immediately) and the
                // held batch goes straight to disk while the store is still open, instead of running
                // a full retry schedule past the grace window and being refused by a closed store.
                // Otherwise keep the real delivery attempt: failing fast there would only lose the
                // batch (with DELETE/IGNORE recovery, disk is not a way back either).
                if (shutdownSpillReplays()) Thread.currentThread().interrupt();
                flush(batch);
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A flusher thread must never die silently; reset and keep going. The records it
                // was holding are gone, so account for them — an uncounted discard here would
                // break the accepted == sentRecords + dropped + spillDropped invariant and hide
                // a serialization bug behind logs that simply never arrive.
                if (!batch.isEmpty()) {
                    dropped.addAndGet(batch.size());
                    diag.log(Diagnostics.Level.ERROR,
                            "batch-http[" + sink.name() + "] dropped " + batch.size()
                                    + " record(s) — unexpected error while batching: "
                                    + Diagnostics.describe(e), e);
                    batch.clear();
                }
                estimatedBytes = 0;
            }
        }
    }

    private long monotonicDeadline() {
        return System.nanoTime() / 1_000_000L + config.lingerTime.toMillis();
    }

    /**
     * Size estimate used only to decide when to flush (avoids serializing twice). Counts UTF-8
     * <em>bytes</em>, not chars: the vendor ceilings this feeds are byte limits, and a char count
     * under-reports a non-Latin payload by up to 3× — enough for a "1 MB" batch of CJK or emoji
     * text to arrive as a 3 MB body and be rejected outright. Runs on the flusher thread, never
     * on the application's {@code offer} path.
     */
    private static int estimateBytes(LogRecord r) {
        int n = 80; // framing + fixed fields
        n += utf8Length(r.message());
        n += utf8Length(r.throwable());
        n += utf8Length(r.loggerName()) + utf8Length(r.threadName());
        for (var e : r.attributes().entrySet()) n += utf8Length(e.getKey()) + utf8Length(e.getValue()) + 8;
        return n;
    }

    /**
     * UTF-8 byte length of {@code s} without encoding it. Surrogate pairs are counted as the
     * 4 bytes they encode to (2 chars × 2), so the sum is exact rather than an upper bound.
     */
    private static int utf8Length(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isSurrogate(c)) n += 2; // half of a 4-byte code point
            else n += 3;
        }
        return n;
    }

    private void flush(List<LogRecord> batch) {
        if (batch.isEmpty()) return;
        List<LogRecord> toSend = new ArrayList<>(batch);
        batch.clear();

        SendResult outcome = send(toSend);
        if (outcome.outcome == SendOutcome.SUCCESS) {
            return;
        }
        if (outcome.outcome == SendOutcome.ERROR) {
            // A sink/serializer bug. The batch was already moved out of the caller's list, so the
            // flusher's own catch would see nothing to count: account here for exactly the records
            // the intake did not take (an earlier partial acceptance already counted the rest as sent).
            dropped.addAndGet(outcome.unsent.size());
            diag.log(Diagnostics.Level.ERROR,
                    "batch-http[" + sink.name() + "] dropped " + outcome.unsent.size()
                            + " record(s) — unexpected error while batching: " + outcome.detail, outcome.error);
            return;
        }
        // Only what the intake did not store: a per-record sink may have taken part of the batch.
        List<LogRecord> unsent = outcome.unsent;
        failedBatches.incrementAndGet();
        // Last-resort safety net: spill to disk so the background replayer can
        // re-ship once the endpoint recovers. We do NOT spill FATAL (4xx) batches —
        // the body is bad and would only churn the buffer forever.
        if (outcome.outcome == SendOutcome.RETRYABLE_EXHAUSTED && spillStore != null) {
            // The spill can itself refuse some or all of the batch (buffer at its cap, disk below
            // the free-space floor, write error). The store counts what it refuses in
            // spillStats().droppedRecords, so it is reported here but not counted again in `dropped`.
            int written = spillStore.spillRecords(unsent);
            if (written > 0) {
                diag.log(Diagnostics.Level.WARN,
                        "batch-http[" + sink.name() + "] send failed after " + config.maxRetries
                                + " retries (" + outcome.detail + ") — spilled " + written
                                + " record(s) to disk for retry");
            }
            if (written < unsent.size()) {
                diag.log(Diagnostics.Level.ERROR,
                        "batch-http[" + sink.name() + "] dropped " + (unsent.size() - written)
                                + " record(s) — send failed after " + config.maxRetries + " retries ("
                                + outcome.detail + ") and the spill buffer refused them"
                                + " (counted in spillStats().droppedRecords)");
            }
        } else {
            dropped.addAndGet(unsent.size());
            if (outcome.outcome == SendOutcome.FATAL) {
                // 4xx: misconfiguration (bad API key, wrong endpoint, oversized) — won't fix itself.
                diag.log(Diagnostics.Level.ERROR,
                        "batch-http[" + sink.name() + "] dropped " + unsent.size()
                                + " record(s) — endpoint rejected the batch (" + outcome.detail
                                + "); check credentials/endpoint, retrying will not help");
            } else {
                // Retryable but exhausted with no spill buffer to catch it — genuine data loss.
                diag.log(Diagnostics.Level.ERROR,
                        "batch-http[" + sink.name() + "] dropped " + unsent.size()
                                + " record(s) — send failed after " + config.maxRetries + " retries ("
                                + outcome.detail + ") and spill is disabled");
            }
        }
    }

    /**
     * Outcome of attempting to send a batch through the transport with retries. {@code ERROR} is
     * an unexpected exception from the sink or the transport (e.g. a serializer bug), never retried.
     */
    private enum SendOutcome { SUCCESS, RETRYABLE_EXHAUSTED, FATAL, ERROR }

    /**
     * Send outcome plus the diagnostic detail (last HTTP status / error) for logging, and the
     * records still unaccounted for. {@code unsent} is the whole batch unless the intake took part
     * of it (a per-record sink), in which case it is only the remainder — so the caller spills or
     * drops exactly what was not delivered, never a record the intake already stored.
     */
    private static final class SendResult {
        final SendOutcome outcome;
        final String detail;
        final List<LogRecord> unsent;
        /** The exception behind {@link SendOutcome#ERROR}; null otherwise. */
        final Throwable error;
        SendResult(SendOutcome outcome, String detail, List<LogRecord> unsent) {
            this(outcome, detail, unsent, null);
        }
        SendResult(SendOutcome outcome, String detail, List<LogRecord> unsent, Throwable error) {
            this.outcome = outcome;
            this.detail = detail;
            this.unsent = unsent;
            this.error = error;
        }
    }

    /**
     * Serialize and send a batch with retry + backoff. Shared by live flushing and
     * spill replay so both honour identical transport semantics. Never throws: an unexpected
     * exception comes back as {@link SendOutcome#ERROR} carrying only the records still owed,
     * so a caller never counts as dropped a record an earlier partial acceptance counted as sent.
     */
    private SendResult send(List<LogRecord> toSend) {
        // Serialize and (if enabled) gzip the body exactly once; the retry loop reuses it so a
        // batch retried against a failing endpoint is not re-compressed on every attempt. The one
        // exception is a partial acceptance, which re-serializes just the records still owed.
        // `pending` always holds the records not yet accounted for, including in the catch below.
        List<LogRecord> pending = toSend;
        try {
            HttpTransport.Payload payload = transport.prepare(sink.serialize(pending));
            long backoff = config.initialRetryBackoff.toMillis();
            String lastDetail = "no attempt";
            for (int attempt = 0; attempt <= config.maxRetries; attempt++) {
                HttpTransport.Outcome result = transport.send(sink, payload);
                lastDetail = result.detail;
                if (result.result == HttpTransport.Result.SUCCESS) {
                    List<LogRecord> retry = settleAccepted(pending, result.body);
                    if (retry.isEmpty()) {
                        sentBatches.incrementAndGet(); // once per batch, not per partial acceptance
                        return new SendResult(SendOutcome.SUCCESS, result.detail, retry);
                    }
                    // The intake stored the rest and asked for these again (throttled or briefly
                    // unavailable): back off like any retryable failure and re-send only them.
                    pending = retry;
                    payload = transport.prepare(sink.serialize(pending));
                    lastDetail = "HTTP " + result.statusCode + " with " + retry.size()
                            + " record(s) refused transiently";
                } else if (result.result == HttpTransport.Result.FATAL) {
                    return new SendResult(SendOutcome.FATAL, result.detail, pending); // retrying a 4xx won't help
                }
                if (attempt < config.maxRetries) {
                    if (diag.isEnabled(Diagnostics.Level.DEBUG)) {
                        diag.log(Diagnostics.Level.DEBUG,
                                "batch-http[" + sink.name() + "] send attempt " + (attempt + 1) + "/"
                                        + (config.maxRetries + 1) + " failed (" + lastDetail + "), retrying");
                    }
                    // A Retry-After from the endpoint beats our own guess — a rate-limited intake
                    // knows when its window reopens, and retrying earlier just earns another 429.
                    // Still clamped by maxRetryBackoff so a hostile/absurd value can't park a flusher.
                    long wait = result.retryAfterMillis >= 0
                            ? Math.min(result.retryAfterMillis, config.maxRetryBackoff.toMillis())
                            : jitter(backoff);
                    sleep(wait);
                    backoff = Math.min(backoff * 2, config.maxRetryBackoff.toMillis());
                }
            }
            return new SendResult(SendOutcome.RETRYABLE_EXHAUSTED, lastDetail, pending);
        } catch (RuntimeException e) {
            return new SendResult(SendOutcome.ERROR, Diagnostics.describe(e), pending, e);
        }
    }

    /**
     * Book an accepted request and return the records the intake asked to have re-sent. For a
     * sink that answers per record, the response says which records it stored, which it refused
     * for good (counted as dropped here, with the reason), and which it refused transiently.
     * Every other sink's 2xx means the whole batch was stored.
     */
    private List<LogRecord> settleAccepted(List<LogRecord> sent, byte[] body) {
        LogSink.RecordResults r = sink.reportsPerRecordResults() && body != null
                ? sink.inspectResponse(body, sent.size())
                : LogSink.RecordResults.ALL_ACCEPTED;
        if (r.allAccepted()) {
            sentRecords.addAndGet(sent.size());
            return java.util.Collections.emptyList();
        }
        List<LogRecord> retry = new ArrayList<>(r.retryable.length);
        for (int i : r.retryable) {
            if (i >= 0 && i < sent.size()) retry.add(sent.get(i));
        }
        int rejected = Math.min(r.rejected, sent.size() - retry.size());
        sentRecords.addAndGet(sent.size() - retry.size() - rejected);
        if (rejected > 0) {
            dropped.addAndGet(rejected);
            diag.log(Diagnostics.Level.ERROR,
                    "batch-http[" + sink.name() + "] dropped " + rejected + " of " + sent.size()
                            + " record(s) — the intake accepted the request but refused them ("
                            + r.rejectedDetail + "); retrying will not help");
        }
        return retry;
    }

    /**
     * Ship a sub-batch read back from a spill segment. Returns true only when the
     * segment record may be deleted: on SUCCESS, and also on FATAL (a malformed body
     * will never succeed, so we drop it rather than loop forever on a poison segment).
     * RETRYABLE-exhausted returns false so the segment stays on disk for the next pass — even
     * when the intake stored part of the sub-batch. Re-spilling just the remainder would put
     * those records on disk twice (the kept segment still holds them), so a later failure in
     * the same segment would replay them twice; the stored part is instead re-sent with the
     * rest of the segment, the same at-least-once re-send as any mid-segment failure.
     */
    private boolean shipForReplay(List<LogRecord> subBatch) {
        if (subBatch.isEmpty()) return true;
        SendResult result = send(subBatch);
        if (result.outcome == SendOutcome.ERROR) {
            // A sink or serializer bug on some replayed record. Keeping the segment would re-send
            // the sub-batches before this one on every pass, forever; abandon this sub-batch like a
            // FATAL rejection instead, and count what the intake did not already take.
            failedBatches.incrementAndGet();
            dropped.addAndGet(result.unsent.size());
            diag.log(Diagnostics.Level.ERROR,
                    "batch-http[" + sink.name() + "] dropped " + result.unsent.size()
                            + " replayed record(s) — unexpected error while re-shipping: "
                            + result.detail, result.error);
            return true;
        }
        if (result.outcome == SendOutcome.RETRYABLE_EXHAUSTED) return false;
        if (result.outcome == SendOutcome.FATAL) {
            // Abandoning a poison segment is the right call, but it is still a loss: count it,
            // or the records vanish from the books entirely (they were never sent and never
            // dropped) and accepted == sentRecords + dropped + spillDropped stops holding.
            failedBatches.incrementAndGet();
            dropped.addAndGet(result.unsent.size());
            diag.log(Diagnostics.Level.ERROR,
                    "batch-http[" + sink.name() + "] dropped " + result.unsent.size()
                            + " replayed record(s) — endpoint rejected the batch (" + result.detail
                            + "); check credentials/endpoint, retrying will not help");
        }
        return true;
    }

    private static long jitter(long base) {
        // Deterministic-ish jitter without Math.random: spread by current nanos. Scaled to the
        // backoff rather than a flat 0..63 ms — at a 10 s ceiling a fixed 63 ms band is no
        // spread at all, and every flusher that failed on the same outage would march back to
        // the endpoint together. Capped so the added delay stays bounded.
        long span = Math.min(Math.max(base / 4, 1L), 250L);
        return base + Math.floorMod(System.nanoTime(), span);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Disk-spill diagnostics, or {@code null} when spill is disabled. Exposed for tests/metrics. */
    public SpillStore.SpillStats spillStats() {
        return spillStore != null ? spillStore.stats() : null;
    }

    /** Stats snapshot for diagnostics/metrics. */
    public Stats stats() {
        return new Stats(accepted.get(), dropped.get(), sentBatches.get(),
                sentRecords.get(), failedBatches.get(), queue.size());
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        // Joins below must not be cut short by an interrupt the caller arrived with (an
        // interrupted closing thread would otherwise skip every join and seal the store under
        // flushers that are still writing). Clear it now, restore it on the way out.
        boolean callerInterrupted = Thread.interrupted();
        try {
            callerInterrupted |= shutDown();
        } finally {
            if (callerInterrupted) Thread.currentThread().interrupt();
        }
    }

    /** The body of {@link #close()}. Returns true if the closing thread was interrupted while waiting. */
    private boolean shutDown() {
        boolean interrupted = false;
        // Wake the heartbeat thread so it observes !running and exits promptly.
        if (statsThread != null) {
            statsThread.interrupt();
        }
        // Signal each flusher to drain and exit. offer() is deliberately non-blocking, so a
        // queue that is at capacity right now simply refuses the sentinel — see the interrupt
        // fallback below, which is what actually guarantees termination in that case.
        for (int i = 0; i < flushers.size(); i++) {
            queue.offer(POISON);
        }
        long deadline = System.nanoTime() + config.shutdownDrainTimeout.toNanos();
        interrupted |= joinAll(deadline);
        // Anything still running has either outlived the drain budget or never received a
        // POISON (the queue was full when we offered it — precisely the overload case in which
        // shutdown matters). Interrupt it: the flusher's InterruptedException path hands what it
        // holds to the spill buffer (or, when the next start would not replay a spill, makes one
        // last send) and returns. Without this a flusher parks in queue.take() forever — a daemon thread, so the JVM still exits,
        // but under Mule it survives the appender that created it and pins the application
        // classloader across every subsequent redeploy.
        for (Thread t : flushers) {
            if (t.isAlive()) t.interrupt();
        }
        // One grace budget shared by all flushers, not one per flusher.
        interrupted |= joinAll(System.nanoTime() + FLUSHER_INTERRUPT_GRACE_MILLIS * 1_000_000L);
        // Stop the background replayer first, so no replay pass seals or ships the segment the
        // drain below is about to write. Then account for whatever the flushers left in the
        // queue, while the store is still open, and only then close the store: that seals (and
        // fsyncs) the active segment so the next run can adopt it.
        if (spillReplayer != null) {
            spillReplayer.close();
        }
        try {
            drainRemainderOnShutdown();
        } finally {
            if (spillStore != null) {
                noteSpillLoss(true); // report the final window of evictions/refusals, if any
                spillStore.close();
            }
            // Release the HTTP client's thread pool last: nothing is sending any more, and an
            // executor left running keeps a live thread (and the app classloader it was born
            // with) for the lifetime of the JVM.
            transport.close();
        }
        // A final snapshot on shutdown closes the books for whoever is reading the log.
        if (config.statsInterval != null && config.statsInterval.toMillis() > 0
                && diag.isEnabled(Diagnostics.Level.INFO)) {
            diag.log(Diagnostics.Level.INFO, statsLine());
        }
        return interrupted;
    }

    /**
     * Join every flusher against one monotonic deadline. An interrupt of the closing thread is
     * remembered (and returned) but does not end the wait early; the deadline still bounds it.
     */
    private boolean joinAll(long deadlineNanos) {
        boolean interrupted = false;
        for (Thread t : flushers) {
            while (t.isAlive()) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) return interrupted;
                try {
                    t.join(remainingMs);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        return interrupted;
    }

    /**
     * Account for records still queued once the flushers have stopped: they missed the drain
     * budget, or arrived behind the POISON sentinels. Spill them so the next start replays them
     * (regardless of {@code spillOnOverflow}: they never had a send attempt); with no spill
     * buffer, or a {@code spillRecoveryMode} that will not replay them, count them as dropped.
     * Never sends over HTTP, and the spill writing is bounded, so close() stays bounded.
     */
    private void drainRemainderOnShutdown() {
        // Let offer() calls that got past their running check finish enqueueing first.
        long quiesce = System.nanoTime() + OFFER_QUIESCE_MILLIS * 1_000_000L;
        while (offersInFlight.get() > 0 && System.nanoTime() < quiesce) {
            LockSupport.parkNanos(1_000_000L);
        }
        // From here on a late offer() settles its own record (see settleIfStranded).
        finalDrainStarted = true;
        List<LogRecord> rest = new ArrayList<>(queue.size());
        queue.drainTo(rest);
        int n = 0;
        for (LogRecord r : rest) {
            if (r != POISON) n++; // sentinels were never accepted records
        }
        if (n == 0) return;
        String prefix = "batch-http[" + sink.name() + "] ";
        long drainMs = config.shutdownDrainTimeout.toMillis();
        if (!shutdownSpillReplays()) {
            dropped.addAndGet(n);
            diag.log(Diagnostics.Level.ERROR, prefix + "dropped " + n + " record(s) still queued at shutdown"
                    + " — not sent within shutdownDrainTimeout=" + drainMs + "ms and "
                    + (spillStore == null ? "spill is disabled"
                                          : "spillRecoveryMode=" + config.spillRecoveryMode
                                                  + " would not replay them on the next start"));
            return;
        }
        long budgetEnd = System.nanoTime() + Math.max(drainMs, MIN_SHUTDOWN_SPILL_MILLIS) * 1_000_000L;
        int spilled = 0, refused = 0, unspilled = 0, i = 0;
        boolean pastBudget = false;
        RuntimeException firstError = null;
        for (LogRecord r : rest) {
            if (r == POISON) continue;
            if (!pastBudget && (i++ & 255) == 0 && System.nanoTime() > budgetEnd) pastBudget = true;
            if (pastBudget) {
                unspilled++;
                continue;
            }
            try {
                // One record at a time: a refusal is then exactly one record, already counted by
                // the store in spillStats().droppedRecords.
                if (spillStore.spill(List.of(r))) spilled++; else refused++;
            } catch (RuntimeException e) {
                unspilled++; // spill() does not throw by contract; never let a surprise abort close()
                if (firstError == null) firstError = e;
            }
        }
        if (unspilled > 0) dropped.addAndGet(unspilled);
        if (spilled > 0 && diag.isEnabled(Diagnostics.Level.WARN)) {
            diag.log(Diagnostics.Level.WARN, prefix + "shutdown: spilled " + spilled + " of " + n
                    + " record(s) still queued after shutdownDrainTimeout=" + drainMs + "ms to disk"
                    + " — they will be replayed on the next start");
        }
        if (refused > 0) {
            diag.log(Diagnostics.Level.ERROR, prefix + "dropped " + refused + " record(s) still queued at shutdown"
                    + " — the spill buffer refused them (counted in spillStats().droppedRecords)");
        }
        if (unspilled > 0) {
            String msg = prefix + "dropped " + unspilled + " record(s) still queued at shutdown"
                    + " — could not be spilled within the shutdown budget";
            if (firstError != null) {
                diag.log(Diagnostics.Level.ERROR, msg + " (" + Diagnostics.describe(firstError) + ")", firstError);
            } else {
                diag.log(Diagnostics.Level.ERROR, msg);
            }
        }
    }

    public static final class Stats {
        public final long accepted, dropped, sentBatches, sentRecords, failedBatches, queueDepth;
        Stats(long accepted, long dropped, long sentBatches, long sentRecords, long failedBatches, long queueDepth) {
            this.accepted = accepted;
            this.dropped = dropped;
            this.sentBatches = sentBatches;
            this.sentRecords = sentRecords;
            this.failedBatches = failedBatches;
            this.queueDepth = queueDepth;
        }
        @Override public String toString() {
            return "Stats{accepted=" + accepted + ", dropped=" + dropped
                    + ", sentBatches=" + sentBatches + ", sentRecords=" + sentRecords
                    + ", failedBatches=" + failedBatches + ", queueDepth=" + queueDepth + '}';
        }
    }
}
