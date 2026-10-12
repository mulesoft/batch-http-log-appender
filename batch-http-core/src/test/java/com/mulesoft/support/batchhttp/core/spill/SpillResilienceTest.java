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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // ---- crash recovery, ownership, eviction accounting ----

    /** Loss listener that records every call. */
    private static final class Losses implements SpillStore.LossListener {
        final List<Object[]> calls = new CopyOnWriteArrayList<>();
        @Override public void onLoss(SpillStore.LossKind kind, int segments, long records, long bytes) {
            calls.add(new Object[]{kind, segments, records, bytes});
        }
        List<Object[]> of(SpillStore.LossKind kind) {
            List<Object[]> out = new ArrayList<>();
            for (Object[] c : calls) if (c[0] == kind) out.add(c);
            return out;
        }
    }

    private static final class CapturingDiagnostics implements Diagnostics {
        final List<String> lines = new CopyOnWriteArrayList<>();
        @Override public boolean isEnabled(Level level) { return true; }
        @Override public void log(Level level, String message) { lines.add(level + " " + message); }
        long count(String substring) { return lines.stream().filter(l -> l.contains(substring)).count(); }
    }

    private static List<Path> segmentFiles(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            List<Path> out = new ArrayList<>();
            s.filter(p -> p.getFileName().toString().startsWith("spill-")).sorted().forEach(out::add);
            return out;
        }
    }

    private static Set<String> messages(List<LogRecord> recs) {
        Set<String> out = new HashSet<>();
        for (LogRecord r : recs) out.add(r.message());
        return out;
    }

    private static List<LogRecord> read(Path seg) throws IOException {
        List<LogRecord> out = new ArrayList<>();
        try (SpillReader r = new SpillReader(seg, BIG)) {
            LogRecord rec;
            while ((rec = r.next()) != null) out.add(rec);
        }
        return out;
    }

    @Test
    void unreadableSegmentDoesNotBlockTheSegmentsBehindIt(@TempDir Path dir) throws Exception {
        try (SpillStore producer = new SpillStore(dir, 150, BIG, 0L, plenty())) {
            producer.spill(recs(12));
        }
        List<Path> files = segmentFiles(dir);
        assertTrue(files.size() >= 3, "precondition: at least three segments");
        Path middle = files.get(1);
        Set<String> expected = new HashSet<>();
        for (Path f : files) if (!f.equals(middle)) expected.addAll(messages(read(f)));
        // An I/O error on read: the file is replaced by something that cannot be read as one.
        Files.delete(middle);
        Files.createDirectory(middle);

        SpillStore store = new SpillStore(dir, 150, BIG, 0L, plenty());
        List<LogRecord> shipped = new CopyOnWriteArrayList<>();
        CapturingDiagnostics diag = new CapturingDiagnostics();
        SpillReplayer replayer = new SpillReplayer(store, b -> { shipped.addAll(b); return true; },
                60_000, 25, 2000, "test", BatchConfig.SpillBacklogOrder.OLDEST_FIRST, 1, 0L, diag);
        replayer.replayOnce();
        replayer.replayOnce();

        assertEquals(expected, messages(shipped), "every readable segment drains, before and after the bad one");
        assertEquals(expected.size(), shipped.size(), "and exactly once");
        assertTrue(Files.isDirectory(middle), "the unreadable segment is kept for a later pass, not deleted");
        assertEquals(List.of(middle), segmentFiles(dir), "everything else is drained and deleted");
        assertEquals(1, diag.count("could not be read"), "reported once per run, not every pass: " + diag.lines);
        replayer.close();
        store.close();
    }

    @Test
    void staleLockFileFromAPreviousOwnerDoesNotBlockAdoption(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 150, BIG, 0L, plenty())) {
            previous.spill(recs(12));
        }
        assertTrue(Files.exists(dir.resolve(".spill.lock")), "the lock file outlives its owner");
        int files = segmentFiles(dir).size();

        try (SpillStore next = new SpillStore(dir, 150, BIG, 0L, plenty())) {
            assertTrue(next.ownsDirectory(), "the lock is on the descriptor, not the file, so it is free");
            assertEquals(files, next.shippableSegments().size());
            assertEquals(files, next.recoveredSegments());
            assertEquals(12, next.stats().adoptedRecords, "adoption counts the records it found");
        }
    }

    @Test
    void startupReplayDoesNotWaitForTheInterval(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 150, BIG, 0L, plenty())) {
            previous.spill(recs(12));
        }
        SpillStore store = new SpillStore(dir, 150, BIG, 0L, plenty());
        List<LogRecord> shipped = new CopyOnWriteArrayList<>();
        SpillReplayer replayer = new SpillReplayer(store, b -> { shipped.addAll(b); return true; },
                60_000, 25, 2000, "test");
        replayer.start();
        long deadline = System.currentTimeMillis() + 2000;
        while (shipped.size() < 12 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(12, shipped.size(), "an adopted backlog replays at once, not one 60 s interval later");
        replayer.close();
        store.close();
    }

    @Test
    void ownershipIsTakenOverOnceTheHolderReleasesTheLock(@TempDir Path dir) throws Exception {
        SpillStore owner = new SpillStore(dir, 150, BIG, 0L, plenty());
        owner.spill(recs(12));
        owner.sealActiveSegment();
        List<Path> ownerFiles = segmentFiles(dir);

        SpillStore sibling = new SpillStore(dir, 150, BIG, 0L, plenty());
        assertFalse(sibling.ownsDirectory());
        assertEquals(ownerFiles.size(), sibling.ignoredSegments());
        assertFalse(sibling.tryReacquireOwnership(), "the holder is still alive");
        assertTrue(sibling.shippableSegments().isEmpty());

        owner.close();
        assertTrue(sibling.tryReacquireOwnership(), "a released lock is taken over");
        assertTrue(sibling.ownsDirectory());
        assertEquals(ownerFiles, sibling.shippableSegments(), "the segments left alone at startup are adopted");
        assertEquals(12, sibling.stats().adoptedRecords);
        assertEquals(ownerFiles.size(), sibling.stats().segmentCount);
        assertTrue(sibling.totalBytes() > 0, "adopted bytes now count toward the cap");
        sibling.close();
    }

    @Test
    void replayPassTakesOverAReleasedLockAndDrainsItsSegments(@TempDir Path dir) throws Exception {
        SpillStore owner = new SpillStore(dir, 150, BIG, 0L, plenty());
        owner.spill(recs(12));
        owner.sealActiveSegment();
        SpillStore sibling = new SpillStore(dir, 150, BIG, 0L, plenty());
        owner.close();

        List<LogRecord> shipped = new CopyOnWriteArrayList<>();
        CapturingDiagnostics diag = new CapturingDiagnostics();
        SpillReplayer replayer = new SpillReplayer(sibling, b -> { shipped.addAll(b); return true; },
                60_000, 25, 2000, "test", BatchConfig.SpillBacklogOrder.OLDEST_FIRST, 1, 0L, diag);
        replayer.replayOnce();
        assertEquals(12, shipped.size());
        assertEquals(1, diag.count("no longer locked by another instance"), diag.lines.toString());
        replayer.close();
        sibling.close();
    }

    @Test
    void startupReconcileCountsTheAdoptedRecordsItEvicts(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 200, BIG, 0L, plenty())) {
            previous.spill(recs(200));
        }
        Map<Path, Long> counts = new HashMap<>();
        for (Path f : segmentFiles(dir)) counts.put(f, (long) read(f).size());

        Losses losses = new Losses();
        SpillStore reconciled = new SpillStore(dir, 200, 1000, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, true, null, losses);
        long evicted = 0;
        for (Map.Entry<Path, Long> e : counts.entrySet()) if (!Files.exists(e.getKey())) evicted += e.getValue();
        SpillStore.SpillStats st = reconciled.stats();
        assertTrue(evicted > 0, "precondition: reconcile evicted something");
        assertEquals(evicted, st.evictedRecords, "the records of every evicted segment are counted");
        assertEquals(st.evictedRecords, st.droppedRecords);
        assertEquals(200, st.adoptedRecords);
        List<Object[]> startup = losses.of(SpillStore.LossKind.STARTUP_EVICTED);
        assertEquals(1, startup.size(), "one startup notice, not one per segment");
        assertEquals(st.evictedSegments, (long) (Integer) startup.get(0)[1]);
        assertEquals(st.evictedRecords, (long) (Long) startup.get(0)[2]);
        reconciled.close();
    }

    @Test
    void runtimeEvictionOfAnAdoptedSegmentCountsItsRecords(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 200, BIG, 0L, plenty())) {
            previous.spill(recs(60));
        }
        Map<Path, Long> counts = new HashMap<>();
        long backlog = 0;
        for (Path f : segmentFiles(dir)) {
            counts.put(f, (long) read(f).size());
            backlog += Files.size(f);
        }
        SpillStore store = new SpillStore(dir, 200, backlog - 1, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);
        store.spill(recs(1));
        long evicted = 0;
        for (Map.Entry<Path, Long> e : counts.entrySet()) if (!Files.exists(e.getKey())) evicted += e.getValue();
        assertTrue(evicted > 0);
        assertEquals(evicted, store.stats().evictedRecords);
        store.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BINARY", "JSON"})
    void evictingADamagedAdoptedSegmentCountsOnlyItsIntactRecords(String format, @TempDir Path dir) throws Exception {
        SpillCodec codec = SpillCodec.forFormat(BatchConfig.SpillFormat.valueOf(format));
        try (SpillStore previous = new SpillStore(dir, 400, BIG, 0L, plenty(),
                codec, BatchConfig.SpillRecoveryMode.ADOPT, false)) {
            previous.spill(recs(20));
        }
        List<Path> files = segmentFiles(dir);
        Path oldest = files.get(0);
        long before = read(oldest).size();
        assertTrue(before >= 2, "precondition: the oldest segment holds several records");
        // Corrupt the middle of its first record and tear its tail.
        try (FileChannel ch = FileChannel.open(oldest, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[8]), SpillSegmentHeader.LENGTH + SpillFrameV2.HEADER_LEN + 4);
            ch.truncate(ch.size() - 5);
        }
        long intact = SpillReader.countRecords(oldest, BIG);
        assertTrue(intact < before && intact > 0, "precondition: damage cost some records, not all");

        long total = 0;
        for (Path f : files) total += Files.size(f);
        SpillStore store = new SpillStore(dir, 400, total - 1, 0L, plenty(),
                codec, BatchConfig.SpillRecoveryMode.ADOPT, true);
        assertFalse(Files.exists(oldest), "precondition: reconcile evicted exactly the damaged segment");
        assertTrue(Files.exists(files.get(1)));
        assertEquals(intact, store.stats().evictedRecords, "only records that could have been delivered are counted");
        store.close();
    }

    @Test
    void deleteRecoveryIsReportedButNotCountedAsALossOfThisRun(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 200, BIG, 0L, plenty())) {
            previous.spill(recs(30));
        }
        int prevSegments = segmentFiles(dir).size();
        assertTrue(prevSegments > 1);

        Losses losses = new Losses();
        SpillStore deleting = new SpillStore(dir, 200, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.DELETE, false, null, losses);
        SpillStore.SpillStats st = deleting.stats();
        assertEquals(0, st.droppedRecords, "a planned discard of a previous run's records is not this run's drop");
        assertEquals(0, st.evictedRecords);
        assertEquals(prevSegments, st.evictedSegments, "unchanged: DELETE still counts in evictedSegments");
        List<Object[]> discarded = losses.of(SpillStore.LossKind.RECOVERY_DISCARDED);
        assertEquals(1, discarded.size());
        assertEquals(prevSegments, (int) (Integer) discarded.get(0)[1]);
        assertEquals(30L, (long) (Long) discarded.get(0)[2]);
        deleting.close();
    }

    @Test
    void ignoredSegmentsAreNeverEvictedOrCounted(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 200, BIG, 0L, plenty())) {
            previous.spill(recs(30));
        }
        List<Path> before = segmentFiles(dir);
        SpillStore ignoring = new SpillStore(dir, 200, 1, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.IGNORE, true);
        ignoring.spill(recs(3));
        for (Path f : before) assertTrue(Files.exists(f), "an ignored segment is never evicted: " + f);
        assertEquals(0, ignoring.stats().evictedRecords);
        assertEquals(3, ignoring.stats().droppedRecords, "the cap refuses the new records instead");
        ignoring.close();
    }

    @Test
    void aSiblingsSegmentsAreNeverEvictedByTheNonOwner(@TempDir Path dir) throws Exception {
        SpillStore owner = new SpillStore(dir, 200, BIG, 0L, plenty());
        owner.spill(recs(30));
        owner.sealActiveSegment();
        List<Path> ownerFiles = segmentFiles(dir);
        SpillStore sibling = new SpillStore(dir, 200, 1, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, true);
        sibling.spill(recs(2));
        for (Path f : ownerFiles) assertTrue(Files.exists(f), "the live owner's segment must survive: " + f);
        assertEquals(0, sibling.stats().evictedRecords);
        sibling.close();
        owner.close();
    }

    @Test
    void aSegmentEvictedMidPassIsSkippedAndTheClaimedOneIsProtected(@TempDir Path dir) throws Exception {
        try (SpillStore producer = new SpillStore(dir, 150, BIG, 0L, plenty())) {
            producer.spill(recs(12));
        }
        List<Path> files = segmentFiles(dir);
        assertTrue(files.size() >= 3, "precondition: at least three segments");
        long total = 0;
        for (Path f : files) total += Files.size(f);
        long secondRecords = read(files.get(1)).size();
        Set<String> expected = new HashSet<>();
        for (Path f : files) if (!f.equals(files.get(1))) expected.addAll(messages(read(f)));

        // The cap is exactly what is on disk, so any new write must evict.
        SpillStore store = new SpillStore(dir, 150, total, 0L, plenty());
        List<LogRecord> shipped = new CopyOnWriteArrayList<>();
        AtomicBoolean first = new AtomicBoolean(true);
        SpillReplayer.BatchShipper shipper = batch -> {
            if (first.getAndSet(false)) {
                // A flusher spills while the first segment is being shipped: the oldest segment
                // is claimed by this worker, so eviction has to take the second one instead.
                store.spill(List.of(LogRecord.builder().timestampMillis(1).level("INFO")
                        .loggerName("l").threadName("t").message("late").build()));
            }
            shipped.addAll(batch);
            return true;
        };
        SpillReplayer replayer = new SpillReplayer(store, shipper, 60_000, 25, 2000, "test");
        replayer.replayOnce();

        assertEquals(expected, messages(shipped), "the claimed segment ships; the pass carries on past the evicted one");
        assertEquals(expected.size(), shipped.size());
        assertEquals(secondRecords, store.stats().evictedRecords);
        assertEquals(secondRecords, store.stats().droppedRecords);
        assertEquals(segmentFiles(dir).size(), store.stats().segmentCount, "bookkeeping matches the disk");
        replayer.close();
        store.close();
    }

    @Test
    void legacyV1JsonSegmentWithATornTailReplaysItsIntactRecords(@TempDir Path dir) throws Exception {
        // A JSON segment written by 0.1.5 (v1 framing) and torn by a crash.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(SpillSegmentHeader.of(SpillCodec.JSON.codecId(), SpillSegmentHeader.VERSION_V1));
        for (LogRecord r : recs(3)) {
            byte[] p = SpillCodec.JSON.encode(r);
            byte[] len = new byte[4];
            SpillFrameV2.putIntBE(len, 0, p.length);
            bos.write(len);
            bos.write(p);
        }
        bos.write(new byte[]{0, 0, 0, 50, '{', '"'}); // a frame torn after 2 payload bytes
        Path seg = dir.resolve("spill-" + String.format("%020d", 0) + ".log");
        Files.write(seg, bos.toByteArray());

        SpillStore store = new SpillStore(dir, 150, BIG, 0L, plenty());
        assertEquals(3, store.stats().adoptedRecords);
        List<LogRecord> shipped = new CopyOnWriteArrayList<>();
        SpillReplayer replayer = new SpillReplayer(store, b -> { shipped.addAll(b); return true; },
                60_000, 25, 2000, "test");
        replayer.replayOnce();
        assertEquals(3, shipped.size());
        assertEquals(6, store.stats().corruptBytesSkipped, "the torn v1 tail is counted");
        assertFalse(Files.exists(seg));
        replayer.close();
        store.close();
    }
}
