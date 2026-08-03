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
import com.mulesoft.support.batchhttp.core.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the crash-resilience hardening: startup free-space reconcile, the
 * {@link BatchConfig.SpillRecoveryMode} options, directory-ownership locking, and the
 * dual-stream / parallel backlog replay paths.
 */
class SpillResilienceTest {

    private static final long BIG = Long.MAX_VALUE / 4;

    private static List<LogRecord> recs(int n) {
        List<LogRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(LogRecord.builder()
                    .timestampMillis(1000 + i).level("INFO").loggerName("l")
                    .threadName("t").message("payload number " + i).build());
        }
        return out;
    }

    private static long countSegments(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("spill-")).count();
        }
    }

    private static SpillStore.FreeSpaceProbe plenty() {
        return d -> BIG;
    }

    // ---- Point 3: startup reconcile ----

    @Test
    void startupReconcilePrunesOldSegmentsOverCap(@TempDir Path dir) throws Exception {
        // Leave a backlog on disk from a "previous run".
        try (SpillStore previous = new SpillStore(dir, 200, BIG, 0L, plenty())) {
            previous.spill(recs(200));
        }
        long before = countSegments(dir);
        assertTrue(before > 2, "precondition: a multi-segment backlog exists");

        // New run with a small total cap + startup reconcile ON must prune immediately,
        // before any new write.
        SpillStore reconciled = new SpillStore(dir, 200, 1000, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, /*startupReconcile*/ true);
        assertTrue(reconciled.totalBytes() <= 1000,
                "startup reconcile must bring total within the cap, was " + reconciled.totalBytes());
        assertTrue(countSegments(dir) < before, "reconcile must have deleted oldest segments");
        assertTrue(reconciled.stats().evictedSegments > 0, "eviction must be recorded");
        reconciled.close();
    }

    @Test
    void startupReconcileHonoursFreeSpaceFloor(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 200, BIG, 0L, plenty())) {
            previous.spill(recs(200));
        }
        assertTrue(countSegments(dir) > 0);

        // Free space always under the floor -> reconcile sheds everything it can.
        long floor = 100_000;
        SpillStore.FreeSpaceProbe tight = d -> floor - 1;
        SpillStore reconciled = new SpillStore(dir, 200, BIG, floor, tight,
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, true);
        assertEquals(0, countSegments(dir),
                "with no active segment and an unsatisfiable floor, all closed segments are evicted");
        reconciled.close();
    }

    // ---- Point 1: recovery modes ----

    @Test
    void deleteModeDiscardsOrphansOnStartup(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, BIG, BIG, 0L, plenty())) {
            previous.spill(recs(20));
        }
        assertTrue(countSegments(dir) > 0);

        SpillStore deleting = new SpillStore(dir, BIG, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.DELETE, false);
        assertEquals(0, countSegments(dir), "DELETE mode must discard leftover segments");
        assertTrue(deleting.shippableSegments().isEmpty());
        assertEquals(0, deleting.totalBytes());
        deleting.close();
    }

    @Test
    void ignoreModeLeavesOrphansButNeverReplaysThem(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, BIG, BIG, 0L, plenty())) {
            previous.spill(recs(20));
        }
        long left = countSegments(dir);
        assertTrue(left > 0);

        SpillStore ignoring = new SpillStore(dir, BIG, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.IGNORE, false);
        assertEquals(left, countSegments(dir), "IGNORE must leave the files on disk");
        assertTrue(ignoring.shippableSegments().isEmpty(), "IGNORE must not expose them for replay");
        assertEquals(0, ignoring.totalBytes(), "ignored bytes are not counted toward the buffer");

        // New seq numbers must still be allocated past the highest existing one.
        ignoring.spill(recs(5));
        ignoring.sealActiveSegment();
        assertFalse(ignoring.shippableSegments().isEmpty(), "freshly spilled records ARE shippable");
        ignoring.close();
    }

    @Test
    void adoptModeRecoversOrphansForReplay(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, BIG, BIG, 0L, plenty())) {
            previous.spill(recs(20));
        }
        SpillStore adopting = new SpillStore(dir, BIG, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);
        assertFalse(adopting.shippableSegments().isEmpty(), "ADOPT must expose orphans for replay");
        assertTrue(adopting.totalBytes() > 0, "ADOPT counts orphan bytes");
        adopting.close();
    }

    // ---- Point 1: directory ownership lock ----

    @Test
    void secondStoreOnSameDirDoesNotOwnItAndIgnoresSiblingSegments(@TempDir Path dir) throws Exception {
        SpillStore owner = new SpillStore(dir, BIG, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);
        owner.spill(recs(20));
        owner.sealActiveSegment();
        assertTrue(owner.ownsDirectory(), "first store acquires the directory lock");

        // A second store opened while the first is alive must NOT take ownership and
        // must not adopt the live owner's segments (no double-shipping/eviction).
        SpillStore sibling = new SpillStore(dir, BIG, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);
        assertFalse(sibling.ownsDirectory(), "second store must not own the directory");
        assertTrue(sibling.shippableSegments().isEmpty(),
                "a non-owner must not replay a live sibling's segments");

        sibling.close();
        owner.close();
    }

    // ---- Point 2: dual-stream / parallel replay ----

    @Test
    void dualStreamReplayDrainsAllSegments(@TempDir Path dir) throws Exception {
        // Many small segments so there is real work for two streams to split.
        try (SpillStore producer = new SpillStore(dir, 150, BIG, 0L, plenty())) {
            producer.spill(recs(300));
        }
        long segs = countSegments(dir);
        assertTrue(segs >= 4, "precondition: several segments to drain, had " + segs);

        SpillStore store = new SpillStore(dir, 150, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);

        List<LogRecord> shipped = new CopyOnWriteArrayList<>();
        SpillReplayer.BatchShipper ok = batch -> { shipped.addAll(batch); return true; };

        SpillReplayer replayer = new SpillReplayer(store, ok, 50, 25, 2000, "test",
                BatchConfig.SpillBacklogOrder.DUAL_STREAM, 2, 0L);
        replayer.replayOnce(); // synchronous single pass with two parallel workers

        assertEquals(300, shipped.size(), "dual-stream replay must deliver every record exactly once");
        assertEquals(0, countSegments(dir), "all drained segments must be deleted");
        replayer.close();
        store.close();
    }

    @Test
    void failedShipLeavesSegmentsForNextPass(@TempDir Path dir) throws Exception {
        try (SpillStore producer = new SpillStore(dir, 150, BIG, 0L, plenty())) {
            producer.spill(recs(120));
        }
        long segs = countSegments(dir);
        assertTrue(segs > 0);

        SpillStore store = new SpillStore(dir, 150, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);

        // Shipper that always fails: nothing should be deleted (backpressure).
        AtomicInteger attempts = new AtomicInteger();
        SpillReplayer.BatchShipper failing = batch -> { attempts.incrementAndGet(); return false; };

        SpillReplayer replayer = new SpillReplayer(store, failing, 50, 25, 2000, "test",
                BatchConfig.SpillBacklogOrder.OLDEST_FIRST, 1, 0L);
        replayer.replayOnce();

        assertTrue(attempts.get() > 0, "at least one ship attempt must have been made");
        assertTrue(countSegments(dir) >= segs, "failed ships must leave segments on disk for the next pass");
        replayer.close();
        store.close();
    }
}
