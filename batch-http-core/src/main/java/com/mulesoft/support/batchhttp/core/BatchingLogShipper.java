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
import java.util.concurrent.atomic.AtomicLong;

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
                store = new SpillStore(dir, config.maxSpillFileBytes, config.maxTotalSpillBytes,
                        config.minFreeDiskBytes, null,
                        SpillCodec.forFormat(config.spillFormat),
                        config.spillRecoveryMode, config.spillStartupReconcile, instanceId);
                replayer = new SpillReplayer(store, this::shipForReplay,
                        config.spillReplayInterval.toMillis(), effectiveMaxRecords,
                        config.shutdownDrainTimeout.toMillis(), sink.name(),
                        config.spillBacklogOrder, config.spillReplayThreads,
                        config.spillReplayMaxBytesPerSec);
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
        if (policy == BatchConfig.OverflowPolicy.BLOCK) {
            try {
                // Bounded waits rather than an unbounded put(): once close() has stopped the
                // flushers nothing will ever drain the queue again, and a put() parked on a
                // full queue would wedge the application thread that called log() for good.
                // Re-checking `running` turns that permanent hang into a counted drop.
                while (running.get()) {
                    if (queue.offer(record, BLOCK_POLL_MILLIS, TimeUnit.MILLISECONDS)) return true;
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
                if (evicted != null) {
                    if (spillOverflow(evicted)) {
                        noteOverflow(false);
                    } else {
                        dropped.incrementAndGet();
                        noteOverflow(true);
                    }
                }
            }
            return true;
        } else { // DROP_NEWEST (default)
            if (queue.offer(record)) return true;
            if (spillOverflow(record)) { // diverted to disk instead of dropped
                noteOverflow(false);
                return true;
            }
            dropped.incrementAndGet();
            noteOverflow(true);
            return false;
        }
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

    /** Divert a queue-overflow record to the spill buffer when enabled. Returns true if it was spilled. */
    private boolean spillOverflow(LogRecord record) {
        if (spillStore != null && config.spillOnOverflow) {
            return spillStore.spill(List.of(record));
        }
        return false;
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
        failedBatches.incrementAndGet();
        // Last-resort safety net: spill to disk so the background replayer can
        // re-ship once the endpoint recovers. We do NOT spill FATAL (4xx) batches —
        // the body is bad and would only churn the buffer forever.
        if (outcome.outcome == SendOutcome.RETRYABLE_EXHAUSTED && spillStore != null) {
            // The spill can itself refuse the batch (buffer at its cap, disk below the free-space
            // floor, write error). Ignoring the answer silently loses those records and breaks the
            // accepted == sentRecords + dropped + spillDropped invariant, so count and report it.
            if (spillStore.spill(toSend)) {
                diag.log(Diagnostics.Level.WARN,
                        "batch-http[" + sink.name() + "] send failed after " + config.maxRetries
                                + " retries (" + outcome.detail + ") — spilled " + toSend.size()
                                + " record(s) to disk for retry");
            } else {
                dropped.addAndGet(toSend.size());
                diag.log(Diagnostics.Level.ERROR,
                        "batch-http[" + sink.name() + "] dropped " + toSend.size()
                                + " record(s) — send failed after " + config.maxRetries + " retries ("
                                + outcome.detail + ") and the spill buffer refused them");
            }
        } else {
            dropped.addAndGet(toSend.size());
            if (outcome.outcome == SendOutcome.FATAL) {
                // 4xx: misconfiguration (bad API key, wrong endpoint, oversized) — won't fix itself.
                diag.log(Diagnostics.Level.ERROR,
                        "batch-http[" + sink.name() + "] dropped " + toSend.size()
                                + " record(s) — endpoint rejected the batch (" + outcome.detail
                                + "); check credentials/endpoint, retrying will not help");
            } else {
                // Retryable but exhausted with no spill buffer to catch it — genuine data loss.
                diag.log(Diagnostics.Level.ERROR,
                        "batch-http[" + sink.name() + "] dropped " + toSend.size()
                                + " record(s) — send failed after " + config.maxRetries + " retries ("
                                + outcome.detail + ") and spill is disabled");
            }
        }
    }

    /** Outcome of attempting to send a batch through the transport with retries. */
    private enum SendOutcome { SUCCESS, RETRYABLE_EXHAUSTED, FATAL }

    /** Send outcome plus the diagnostic detail (last HTTP status / error) for logging. */
    private static final class SendResult {
        final SendOutcome outcome;
        final String detail;
        SendResult(SendOutcome outcome, String detail) {
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    /**
     * Serialize and send a batch with retry + backoff. Shared by live flushing and
     * spill replay so both honour identical transport semantics.
     */
    private SendResult send(List<LogRecord> toSend) {
        // Serialize and (if enabled) gzip the body exactly once; the retry loop reuses it so a
        // batch retried against a failing endpoint is not re-compressed on every attempt.
        HttpTransport.Payload payload = transport.prepare(sink.serialize(toSend));
        long backoff = config.initialRetryBackoff.toMillis();
        String lastDetail = "no attempt";
        for (int attempt = 0; attempt <= config.maxRetries; attempt++) {
            HttpTransport.Outcome result = transport.send(sink, payload);
            lastDetail = result.detail;
            if (result.result == HttpTransport.Result.SUCCESS) {
                sentBatches.incrementAndGet();
                sentRecords.addAndGet(toSend.size());
                return new SendResult(SendOutcome.SUCCESS, result.detail);
            }
            if (result.result == HttpTransport.Result.FATAL) {
                return new SendResult(SendOutcome.FATAL, result.detail); // retrying a 4xx won't help
            }
            if (attempt < config.maxRetries) {
                if (diag.isEnabled(Diagnostics.Level.DEBUG)) {
                    diag.log(Diagnostics.Level.DEBUG,
                            "batch-http[" + sink.name() + "] send attempt " + (attempt + 1) + "/"
                                    + (config.maxRetries + 1) + " failed (" + result.detail + "), retrying");
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
        return new SendResult(SendOutcome.RETRYABLE_EXHAUSTED, lastDetail);
    }

    /**
     * Ship a sub-batch read back from a spill segment. Returns true only when the
     * segment record may be deleted: on SUCCESS, and also on FATAL (a malformed body
     * will never succeed, so we drop it rather than loop forever on a poison segment).
     * RETRYABLE-exhausted returns false so the segment stays on disk for the next pass.
     */
    private boolean shipForReplay(List<LogRecord> subBatch) {
        if (subBatch.isEmpty()) return true;
        SendResult result = send(subBatch);
        if (result.outcome == SendOutcome.RETRYABLE_EXHAUSTED) return false;
        if (result.outcome == SendOutcome.FATAL) {
            // Abandoning a poison segment is the right call, but it is still a loss: count it,
            // or the records vanish from the books entirely (they were never sent and never
            // dropped) and accepted == sentRecords + dropped + spillDropped stops holding.
            failedBatches.incrementAndGet();
            dropped.addAndGet(subBatch.size());
            diag.log(Diagnostics.Level.ERROR,
                    "batch-http[" + sink.name() + "] dropped " + subBatch.size()
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
        long deadline = System.currentTimeMillis() + config.shutdownDrainTimeout.toMillis();
        for (Thread t : flushers) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                t.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Anything still running has either outlived the drain budget or never received a
        // POISON (the queue was full when we offered it — precisely the overload case in which
        // shutdown matters). Interrupt it: the flusher's InterruptedException path flushes what
        // it holds and returns. Without this a flusher parks in queue.take() forever — a daemon
        // thread, so the JVM still exits, but under Mule it survives the appender that created
        // it and pins the application classloader across every subsequent redeploy.
        for (Thread t : flushers) {
            if (t.isAlive()) t.interrupt();
        }
        for (Thread t : flushers) {
            try {
                t.join(FLUSHER_INTERRUPT_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Stop the background replayer, then close the store so the active segment is
        // flushed to disk and recoverable on the next run. The flushers above may have
        // just spilled a final failed batch, so this ordering preserves it.
        if (spillReplayer != null) {
            spillReplayer.close();
        }
        if (spillStore != null) {
            spillStore.close();
        }
        // Release the HTTP client's thread pool last: nothing is sending any more, and an
        // executor left running keeps a live thread (and the app classloader it was born
        // with) for the lifetime of the JVM.
        transport.close();
        // A final snapshot on shutdown closes the books for whoever is reading the log.
        if (config.statsInterval != null && config.statsInterval.toMillis() > 0
                && diag.isEnabled(Diagnostics.Level.INFO)) {
            diag.log(Diagnostics.Level.INFO, statsLine());
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
