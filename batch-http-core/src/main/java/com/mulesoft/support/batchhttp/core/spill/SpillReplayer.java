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
import com.mulesoft.support.batchhttp.core.Diagnostics;
import com.mulesoft.support.batchhttp.core.LogRecord;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background machinery that drains spilled segments back to the endpoint when it is
 * healthy, and on startup replays any segments adopted from a previous run/crash.
 *
 * <p>A single driver thread fires one pass every {@code replayInterval}. When segments are
 * already on disk at construction (adopted from a previous run or a crash), the first pass
 * runs immediately rather than one interval later: in a crash loop that restarts the runtime
 * every few seconds, waiting a full interval would mean the inherited backlog never replays
 * while new spill keeps evicting it. Each pass:
 * <ol>
 *   <li>Re-tries the directory lock if a live sibling held it at startup
 *       ({@link SpillStore#tryReacquireOwnership()}).</li>
 *   <li>Seals the active segment so records accumulated during the outage become
 *       shippable (otherwise a never-rolled active segment would never replay).</li>
 *   <li>Builds the oldest-first list of shippable segments into a shared deque.</li>
 *   <li>Hands segments to one or more workers which read them, re-batch into
 *       sub-batches of {@code subBatchRecords}, and ship via the {@link BatchShipper}.
 *       On a full-segment success the segment is deleted; on the first failed
 *       sub-batch the whole pass is marked unhealthy and all workers stop claiming
 *       (natural backpressure — drain resumes next interval).</li>
 * </ol>
 *
 * <p>Damage never stops the drain. A segment whose records are partly corrupt replays its
 * intact records and reports the rest ({@link SpillStore.SpillStats#corruptBytesSkipped}, plus a
 * WARN per segment). A segment that cannot be read at all (an I/O error) is kept for a later
 * pass and logged once, and the workers move on to the segments behind it. A segment evicted
 * between listing and claiming is skipped. A worker claims each segment from the store
 * ({@link SpillStore#beginReplay}) so eviction cannot delete it mid-read.
 *
 * <h2>Backlog order ({@link BatchConfig.SpillBacklogOrder})</h2>
 * <ul>
 *   <li>{@code OLDEST_FIRST} — every worker claims from the old end (FIFO).</li>
 *   <li>{@code NEWEST_FIRST} — every worker claims from the new end (freshest logs first).</li>
 *   <li>{@code DUAL_STREAM} — two parallel streams: one worker drains the newest end
 *       (so recent logs surface immediately) while another drains the oldest end (so
 *       the historical backlog still makes progress); they meet in the middle.</li>
 * </ul>
 *
 * <h2>Rate cap</h2>
 * A shared token bucket charges the on-disk payload bytes of each shipped sub-batch,
 * so a large backlog drain never starves live traffic or saturates the intake. Zero
 * means unlimited.
 *
 * <p>A mid-segment failure re-reads the whole segment next pass, so duplicate delivery
 * is possible; parallel/newest-first widen that window and reorder delivery. The same holds
 * across a crash: a sub-batch delivered just before the process died, in a segment not yet
 * deleted, is delivered again by the next run. That is an accepted trade-off for logs
 * (at-least-once) and avoids a per-segment cursor/checkpoint file.
 */
public final class SpillReplayer implements java.io.Closeable {

    /** Ships one sub-batch. Returns true only on terminal success (segment is then delete-safe). */
    @FunctionalInterface
    public interface BatchShipper {
        boolean ship(List<LogRecord> subBatch);
    }

    private final SpillStore store;
    private final BatchShipper shipper;
    private final long replayIntervalMillis;
    private final int subBatchRecords;
    private final long joinTimeoutMillis;
    private final String sinkName;
    private final BatchConfig.SpillBacklogOrder backlogOrder;
    private final int workerCount;
    private final RateLimiter rateLimiter;
    private final Diagnostics diag;
    /** Segments were already on disk at construction: run the first pass without waiting an interval. */
    private final boolean replayImmediately;
    /** Unreadable segments already reported this run, so a medium that stays broken logs once, not every pass. */
    private final Set<Path> warnedUnreadable = ConcurrentHashMap.newKeySet();

    /** What happened to one segment in a pass. */
    private enum SegmentResult {
        /** Every intact record was shipped (or deliberately abandoned): delete the segment. */
        SHIPPED,
        /** A sub-batch could not be delivered: keep the segment and end the pass (endpoint unhealthy). */
        SHIP_FAILED,
        /** The file could not be read: keep it for a later pass, carry on with the next segment. */
        UNREADABLE,
        /** The file disappeared (evicted or deleted since the pass listed it): nothing to do. */
        GONE
    }

    private final Thread driver;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile List<Thread> passWorkers = List.of();

    /** Backward-compatible constructor: single worker, oldest-first, unlimited rate. */
    public SpillReplayer(SpillStore store, BatchShipper shipper, long replayIntervalMillis,
                         int subBatchRecords, long joinTimeoutMillis, String sinkName) {
        this(store, shipper, replayIntervalMillis, subBatchRecords, joinTimeoutMillis, sinkName,
                BatchConfig.SpillBacklogOrder.OLDEST_FIRST, 1, 0L);
    }

    public SpillReplayer(SpillStore store, BatchShipper shipper, long replayIntervalMillis,
                         int subBatchRecords, long joinTimeoutMillis, String sinkName,
                         BatchConfig.SpillBacklogOrder backlogOrder, int replayThreads,
                         long maxBytesPerSec) {
        this(store, shipper, replayIntervalMillis, subBatchRecords, joinTimeoutMillis, sinkName,
                backlogOrder, replayThreads, maxBytesPerSec, Diagnostics.NOOP);
    }

    /**
     * @param diag where to report recovery outcomes (segments with corrupt bytes, unreadable
     *             segments, a directory lock taken over); {@code null} for none
     */
    public SpillReplayer(SpillStore store, BatchShipper shipper, long replayIntervalMillis,
                         int subBatchRecords, long joinTimeoutMillis, String sinkName,
                         BatchConfig.SpillBacklogOrder backlogOrder, int replayThreads,
                         long maxBytesPerSec, Diagnostics diag) {
        this.diag = diag != null ? diag : Diagnostics.NOOP;
        this.store = store;
        this.shipper = shipper;
        this.replayIntervalMillis = Math.max(1, replayIntervalMillis);
        this.subBatchRecords = Math.max(1, subBatchRecords);
        this.joinTimeoutMillis = Math.max(1, joinTimeoutMillis);
        this.sinkName = sinkName;
        this.backlogOrder = backlogOrder != null ? backlogOrder : BatchConfig.SpillBacklogOrder.OLDEST_FIRST;
        // DUAL_STREAM always uses exactly two workers; otherwise honour the configured count.
        this.workerCount = this.backlogOrder == BatchConfig.SpillBacklogOrder.DUAL_STREAM
                ? 2
                : Math.max(1, replayThreads);
        this.rateLimiter = new RateLimiter(maxBytesPerSec);
        boolean backlog;
        try {
            backlog = !store.shippableSegments().isEmpty();
        } catch (IOException e) {
            backlog = false; // the first regular pass will try again
        }
        this.replayImmediately = backlog;
        this.driver = new Thread(this::run, "batch-http-spill-replay-" + sinkName);
        this.driver.setDaemon(true);
    }

    public void start() {
        driver.start();
    }

    private void run() {
        boolean first = true;
        while (running.get()) {
            // The immediate first pass happens here, on the driver thread, so the constructor
            // (and the appender start that runs it) never blocks on a backlog.
            if (!(first && replayImmediately)) {
                try {
                    Thread.sleep(replayIntervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            first = false;
            try {
                replayOnce();
            } catch (RuntimeException e) {
                // A replay pass must never kill the thread; try again next interval.
            }
        }
    }

    /** One drain pass. Public so shutdown can attempt a final synchronous drain. */
    public void replayOnce() {
        // A live sibling may have held the directory lock at startup; once it lets go, the
        // segments it left behind become ours (per spillRecoveryMode) and replay below.
        if (!store.ownsDirectory()) {
            int before = store.recoveredSegments();
            if (store.tryReacquireOwnership() && diag.isEnabled(Diagnostics.Level.INFO)) {
                diag.log(Diagnostics.Level.INFO, "batch-http[" + sinkName + "] spill directory "
                        + store.directory() + " is no longer locked by another instance — took it over ("
                        + (store.recoveredSegments() - before) + " segment(s) adopted for replay)");
            }
        }
        // Seal the active segment first: records spilled during the outage live there
        // and would otherwise never become shippable.
        store.sealActiveSegment();

        List<Path> segments;
        try {
            segments = store.shippableSegments(); // oldest-first
        } catch (java.io.IOException e) {
            return;
        }
        if (segments.isEmpty()) return;

        // Shared, atomically-claimed work deque (oldest at the front, newest at the back).
        Deque<Path> deque = new ArrayDeque<>(segments);
        AtomicBoolean passHealthy = new AtomicBoolean(true);

        if (workerCount == 1) {
            // Inline (no extra thread): claim from the end the configured order dictates.
            boolean fromBack = backlogOrder == BatchConfig.SpillBacklogOrder.NEWEST_FIRST;
            drain(deque, fromBack, passHealthy);
            return;
        }

        // Parallel workers. For DUAL_STREAM the first worker drains the newest end and
        // the rest drain the oldest end; for NEWEST_FIRST all drain the newest end;
        // for OLDEST_FIRST all drain the oldest end.
        List<Thread> workers = new ArrayList<>(workerCount);
        for (int w = 0; w < workerCount; w++) {
            final boolean fromBack = workerDrainsNewestEnd(w);
            Thread t = new Thread(() -> drain(deque, fromBack, passHealthy),
                    "batch-http-spill-replay-" + sinkName + "-w" + w);
            t.setDaemon(true);
            workers.add(t);
        }
        passWorkers = workers;
        for (Thread t : workers) t.start();
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
                break;
            }
        }
        passWorkers = List.of();
    }

    /** Which end of the deque worker {@code w} claims from. */
    private boolean workerDrainsNewestEnd(int w) {
        if (backlogOrder == BatchConfig.SpillBacklogOrder.DUAL_STREAM) {
            return w == 0;                 // worker 0 = recent stream; others = backlog
        }
        return backlogOrder == BatchConfig.SpillBacklogOrder.NEWEST_FIRST;
    }

    /** Claim and ship segments from the chosen end until the deque is drained or the pass turns unhealthy. */
    private void drain(Deque<Path> deque, boolean fromBack, AtomicBoolean passHealthy) {
        while (running.get() && passHealthy.get()) {
            Path segment = claim(deque, fromBack, passHealthy);
            if (segment == null) return;
            // Evicted since the pass listed it (or otherwise no longer ours to ship): that is
            // not an endpoint failure, so move on rather than ending the pass.
            if (!store.beginReplay(segment)) continue;
            SegmentResult result;
            try {
                result = replaySegment(segment, passHealthy);
            } catch (RuntimeException e) {
                // A bug while re-shipping must not kill a parallel worker; keep the segment and
                // stop this pass, exactly like a failed ship, so it is retried next interval.
                result = SegmentResult.SHIP_FAILED;
            }
            if (result == SegmentResult.SHIPPED) {
                store.deleteSegment(segment); // also releases the claim
                continue;
            }
            store.endReplay(segment);
            if (result == SegmentResult.SHIP_FAILED) {
                passHealthy.set(false); // endpoint unhealthy — stop the pass (backpressure)
                return;
            }
            // UNREADABLE / GONE: keep going with the segments behind it.
        }
    }

    private Path claim(Deque<Path> deque, boolean fromBack, AtomicBoolean passHealthy) {
        synchronized (deque) {
            if (!running.get() || !passHealthy.get()) return null;
            return fromBack ? deque.pollLast() : deque.pollFirst();
        }
    }

    /** Read and ship one segment in sub-batches, stopping at the first failed ship. */
    private SegmentResult replaySegment(Path segment, AtomicBoolean passHealthy) {
        long delivered = 0;
        try (SpillReader reader = new SpillReader(segment, store.maxSegmentBytes())) {
            List<LogRecord> batch = new ArrayList<>(subBatchRecords);
            long batchBytes = 0;
            LogRecord r;
            while ((r = reader.next()) != null) {
                batch.add(r);
                batchBytes += reader.lastPayloadLen();
                if (batch.size() >= subBatchRecords) {
                    if (!shipper.ship(batch)) return SegmentResult.SHIP_FAILED;
                    delivered += batch.size();
                    rateLimiter.charge(batchBytes, running, passHealthy);
                    batch = new ArrayList<>(subBatchRecords);
                    batchBytes = 0;
                }
            }
            if (!batch.isEmpty()) {
                if (!shipper.ship(batch)) return SegmentResult.SHIP_FAILED;
                delivered += batch.size();
                rateLimiter.charge(batchBytes, running, passHealthy);
            }
            // Whole segment shipped; surface any corrupt bytes the reader skipped (a torn tail
            // left by a crash, interior damage recovered around) so the loss is observable.
            long corrupt = reader.corruptBytesSkipped();
            store.addCorruptBytesSkipped(corrupt);
            if (corrupt > 0 && diag.isEnabled(Diagnostics.Level.WARN)) {
                diag.log(Diagnostics.Level.WARN, "batch-http[" + sinkName + "] replayed spill segment "
                        + segment.getFileName() + ": " + delivered + " record(s) delivered, " + corrupt
                        + " corrupt byte(s) skipped (torn tail or damaged record)");
            }
            return SegmentResult.SHIPPED;
        } catch (NoSuchFileException gone) {
            return SegmentResult.GONE;
        } catch (IOException e) {
            // Cannot read the segment at all (a failing block, permissions, not a regular file).
            // Keep it — the medium may recover — but never let it block the segments behind it.
            if (warnedUnreadable.add(segment) && diag.isEnabled(Diagnostics.Level.WARN)) {
                diag.log(Diagnostics.Level.WARN, "batch-http[" + sinkName + "] spill segment "
                        + segment.getFileName() + " could not be read (" + Diagnostics.describe(e)
                        + ") — skipped this pass, will retry");
            }
            return SegmentResult.UNREADABLE;
        }
    }

    @Override
    public void close() {
        running.set(false);
        driver.interrupt();
        for (Thread t : passWorkers) t.interrupt();   // wake any in-flight pass workers
        try {
            driver.join(joinTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The driver joins its own workers, so a driver that returned in time has already reaped
        // them — but if it timed out above, or if a pass started between our interrupt and the
        // read of passWorkers, a worker can still be parked inside a ship() retry. Re-check and
        // reap: these are daemon threads, so the JVM exits regardless, but under Mule they would
        // outlive the appender and pin the deploying application's classloader.
        for (Thread t : passWorkers) {
            if (t.isAlive()) t.interrupt();
        }
        for (Thread t : passWorkers) {
            try {
                t.join(joinTimeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Simple shared token bucket limiting average drain throughput to {@code maxBytesPerSec}
     * (0 = unlimited). Bursts up to one second's worth. Sleeps the calling worker when it
     * runs ahead of the allowance.
     */
    private static final class RateLimiter {
        private final long maxBytesPerSec;
        private double tokens;
        private long lastRefillNanos;

        RateLimiter(long maxBytesPerSec) {
            this.maxBytesPerSec = Math.max(0, maxBytesPerSec);
            this.tokens = this.maxBytesPerSec; // allow an initial burst of up to 1s
            this.lastRefillNanos = System.nanoTime();
        }

        void charge(long bytes, AtomicBoolean running, AtomicBoolean passHealthy) {
            if (maxBytesPerSec <= 0 || bytes <= 0) return;
            synchronized (this) {
                refill();
                while (tokens < bytes && running.get() && passHealthy.get()) {
                    long needBytes = (long) Math.ceil(bytes - tokens);
                    long sleepMs = Math.max(1, needBytes * 1000L / maxBytesPerSec);
                    try {
                        // Bounded so a stopping replayer doesn't hang on the rate gate.
                        wait(Math.min(sleepMs, 200));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    refill();
                }
                tokens -= bytes;
            }
        }

        private void refill() {
            long now = System.nanoTime();
            double add = (now - lastRefillNanos) / 1_000_000_000.0 * maxBytesPerSec;
            if (add > 0) {
                tokens = Math.min((double) maxBytesPerSec, tokens + add);
                lastRefillNanos = now;
            }
        }
    }
}
