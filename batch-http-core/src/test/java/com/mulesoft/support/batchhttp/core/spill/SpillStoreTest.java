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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SpillStoreTest {

    private static final long BIG = Long.MAX_VALUE / 4;
    private static final long NO_FLOOR = 0L;

    private static LogRecord rec(int i) {
        return LogRecord.builder()
                .timestampMillis(1000 + i).level("INFO").loggerName("logger")
                .threadName("thread").message("payload number " + i).build();
    }

    private static List<LogRecord> recs(int n) {
        List<LogRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(rec(i));
        return out;
    }

    /** A free-space probe that always reports plenty of space. */
    private static SpillStore.FreeSpaceProbe plenty() {
        return d -> BIG;
    }

    private long countSegments(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("spill-")).count();
        }
    }

    @Test
    void spillAfterCloseIsRefusedRatherThanReopeningASegment(@TempDir Path dir) throws Exception {
        // close() seals the active segment, but a flusher that outlived the shutdown drain budget
        // can still call spill(). Writing then would open a brand-new segment with nobody left to
        // close it: a leaked file handle plus a segment left on disk that no reader will drain.
        SpillStore store = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty());
        store.spill(recs(5));
        store.close();
        long segmentsAfterClose = countSegments(dir);

        assertFalse(store.spill(recs(3)), "a spill after close() must be refused, not silently written");
        assertEquals(segmentsAfterClose, countSegments(dir),
                "no new segment file may be created after close()");
        assertEquals(3, store.stats().droppedRecords,
                "records refused after close() must be counted, not lost silently");

        store.close(); // idempotent
    }

    @Test
    void segmentCountIsMaintainedIncrementallyRatherThanScannedPerStatsCall(@TempDir Path dir) throws Exception {
        // stats() is meant to be polled into JMX/a metrics system, so it must not walk the
        // spill directory on every call. The count is therefore bookkeeping, and the risk of
        // bookkeeping is drift: assert it agrees with the filesystem after every operation that
        // creates or removes a segment.
        SpillStore store = new SpillStore(dir, 200, 1000, NO_FLOOR, plenty());
        assertEquals(0, store.stats().segmentCount, "a fresh directory holds no segments");

        store.spill(recs(200));                 // rotation (small segment cap) + eviction (small total cap)
        assertTrue(store.stats().evictedSegments > 0, "precondition: the total cap must have forced eviction");
        assertEquals(countSegments(dir), store.stats().segmentCount, "count must track rotation and eviction");

        store.sealActiveSegment();
        assertEquals(countSegments(dir), store.stats().segmentCount, "sealing keeps the file, so the count is unchanged");

        store.deleteSegment(store.shippableSegments().get(0));   // what the replayer does after a segment drains
        assertEquals(countSegments(dir), store.stats().segmentCount, "a drained segment must leave the count");

        // A file that a directory scan *would* match, planted behind the store's back. Only this
        // store writes under its own prefix, so its own bookkeeping is authoritative — and a
        // stats() call that still saw this file would prove it was re-scanning the directory.
        int before = store.stats().segmentCount;
        Files.createFile(dir.resolve("spill-" + String.format("%020d", 99999) + ".log"));
        assertEquals(before, store.stats().segmentCount, "stats() must not re-derive the count from the directory");
        store.close();

        // Recovery re-seeds the count from disk, so a restart does not start from zero.
        try (SpillStore reopened = new SpillStore(dir, 200, 1000, NO_FLOOR, plenty())) {
            assertEquals(countSegments(dir), reopened.stats().segmentCount,
                    "a restart must adopt the segments already on disk into the count");
        }
    }

    @Test
    void aFailedHeaderWriteClosesTheSegmentStreamInsteadOfStrandingIt(@TempDir Path dir) {
        // Opening a segment writes its header immediately, and the disk being full is exactly
        // the condition the spill buffer exists to survive — so this is a live error path, not a
        // theoretical one. The half-built writer is never returned to anyone, so if the
        // constructor throws without closing, the descriptor is stranded for the JVM's lifetime.
        AtomicBoolean closed = new AtomicBoolean(false);
        OutputStream fullDisk = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("No space left on device");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("No space left on device");
            }
            @Override public void close() { closed.set(true); }
        };

        Path seg = dir.resolve("spill-" + String.format("%020d", 0) + ".log");
        assertThrows(IOException.class, () -> new SpillSegmentWriter(
                        seg, 0, SpillCodec.BINARY.codecId(), SpillSegmentHeader.VERSION_V2, fullDisk),
                "the caller must still see the I/O failure");
        assertTrue(closed.get(), "the stream opened for the segment must be closed before rethrowing");
    }

    @Test
    void rotatesAtSegmentCap(@TempDir Path dir) throws Exception {
        // Tiny segment cap forces frequent rotation.
        try (SpillStore store = new SpillStore(dir, 256, BIG, NO_FLOOR, plenty())) {
            store.spill(recs(50));
            store.sealActiveSegment();
        }
        assertTrue(countSegments(dir) > 1, "small cap must produce multiple segments");
    }

    @Test
    void oversizedRecordStaysWholeInOneSegment(@TempDir Path dir) throws Exception {
        // A single record larger than the segment cap must NOT be split across files — the
        // store is fuzzy size-bounded: it rolls *before* writing, then writes the whole frame
        // into a fresh segment even if that segment ends up larger than the nominal cap.
        long smallCap = 256;
        try (SpillStore store = new SpillStore(dir, smallCap, BIG, NO_FLOOR, plenty())) {
            LogRecord big = LogRecord.builder()
                    .timestampMillis(1).level("INFO").loggerName("l").threadName("t")
                    .message("B".repeat(4096)) // ~4 KiB payload, far over the 256-byte cap
                    .build();
            store.spill(List.of(big));
            store.sealActiveSegment();

            // The whole record must be readable back from exactly one segment.
            int found = 0;
            for (Path seg : store.shippableSegments()) {
                try (SpillReader r = new SpillReader(seg, BIG)) {
                    LogRecord rec;
                    while ((rec = r.next()) != null) {
                        assertEquals(4096, rec.message().length(), "the big message must be intact, not split");
                        found++;
                    }
                }
            }
            assertEquals(1, found, "exactly one whole record, never a torn half");
        }
    }

    @Test
    void dropsOldestWhenOverTotalCap(@TempDir Path dir) throws Exception {
        // Small segment cap so many segments form; small total cap so eviction kicks in.
        SpillStore store = new SpillStore(dir, 200, 1000, NO_FLOOR, plenty());
        store.spill(recs(200));
        store.sealActiveSegment();

        assertTrue(store.stats().evictedSegments > 0, "eviction must have occurred");
        assertTrue(store.totalBytes() <= 1000, "total bytes must stay within the cap, was " + store.totalBytes());
        store.close();
    }

    @Test
    void freeSpaceFloorTriggersEviction(@TempDir Path dir) throws Exception {
        // Probe always reports just under the floor -> every write wants to evict.
        long floor = 100_000;
        SpillStore.FreeSpaceProbe tight = d -> floor - 1;
        SpillStore store = new SpillStore(dir, 200, BIG, floor, tight);

        store.spill(recs(200));
        store.sealActiveSegment();

        // With the floor never satisfied, the store keeps only what cannot be evicted
        // (the active segment) and drops the rest — it must never grow unbounded.
        assertTrue(store.stats().evictedSegments > 0 || store.stats().droppedRecords > 0,
                "tight free space must force eviction and/or drops");
        assertTrue(countSegments(dir) <= 1, "under a never-satisfied floor at most the active segment remains");
        store.close();
    }

    @Test
    void monotonicSeqAcrossReopen(@TempDir Path dir) throws Exception {
        SpillStore first = new SpillStore(dir, 200, BIG, NO_FLOOR, plenty());
        first.spill(recs(30));
        first.close(); // seals active segment, leaves files on disk

        long maxSeqAfterFirst = maxSeq(dir);

        SpillStore second = new SpillStore(dir, 200, BIG, NO_FLOOR, plenty());
        second.spill(recs(30));
        second.sealActiveSegment();

        assertTrue(maxSeq(dir) > maxSeqAfterFirst, "reopened store must allocate higher sequence numbers");
        second.close();
    }

    @Test
    void concurrentSpillProducesNoCorruptFrames(@TempDir Path dir) throws Exception {
        SpillStore store = new SpillStore(dir, 4096, BIG, NO_FLOOR, plenty());
        int threads = 8, perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    store.spill(List.of(rec(base + i)));
                }
            });
            ts.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : ts) th.join();
        store.sealActiveSegment();

        // Read every frame back across all segments; each must decode cleanly.
        AtomicLong decoded = new AtomicLong();
        for (Path seg : store.shippableSegments()) {
            try (SpillReader r = new SpillReader(seg, store.maxSegmentBytes())) {
                LogRecord rec;
                while ((rec = r.next()) != null) {
                    assertNotNull(rec.message());
                    decoded.incrementAndGet();
                }
            }
        }
        assertEquals((long) threads * perThread, decoded.get(),
                "every concurrently-spilled record must be readable back");
        store.close();
    }

    @Test
    void recoversExistingSegmentsOnConstruction(@TempDir Path dir) throws Exception {
        SpillStore first = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty());
        first.spill(recs(10));
        first.close();

        SpillStore second = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty());
        // The pre-existing segment must be visible for replay and counted.
        assertFalse(second.shippableSegments().isEmpty(), "leftover segment must be shippable");
        assertTrue(second.totalBytes() > 0, "leftover bytes must be accounted");
        second.close();
    }

    @Test
    void colocatedInstancesDoNotCollideOrTouchEachOther(@TempDir Path dir) throws Exception {
        // Two apps share ONE spill directory but use distinct instance ids. Each must see,
        // replay, and evict only its own segments — never the sibling's.
        long perStoreCap = BIG;
        try (SpillStore appA = new SpillStore(dir, 200, perStoreCap, NO_FLOOR, plenty(),
                     SpillCodec.BINARY, com.mulesoft.support.batchhttp.core.BatchConfig.SpillRecoveryMode.ADOPT, false, "app-a");
             SpillStore appB = new SpillStore(dir, 200, perStoreCap, NO_FLOOR, plenty(),
                     SpillCodec.BINARY, com.mulesoft.support.batchhttp.core.BatchConfig.SpillRecoveryMode.ADOPT, false, "app-b")) {

            appA.spill(recs(100));
            appA.sealActiveSegment();
            appB.spill(recs(100));
            appB.sealActiveSegment();

            // Each store only counts its own segments.
            int aSegs = appA.stats().segmentCount;
            int bSegs = appB.stats().segmentCount;
            assertTrue(aSegs > 0 && bSegs > 0, "both apps must have produced segments");

            // Every file on disk belongs to exactly one app; the two sets are disjoint.
            long aFiles, bFiles;
            try (var s = Files.list(dir)) {
                aFiles = s.filter(p -> p.getFileName().toString().startsWith("spill-app-a-")).count();
            }
            try (var s = Files.list(dir)) {
                bFiles = s.filter(p -> p.getFileName().toString().startsWith("spill-app-b-")).count();
            }
            assertEquals(aSegs, aFiles, "app-a's segment count must equal its own files on disk");
            assertEquals(bSegs, bFiles, "app-b's segment count must equal its own files on disk");

            // app-a's shippable set must contain none of app-b's files.
            for (Path p : appA.shippableSegments()) {
                assertTrue(p.getFileName().toString().startsWith("spill-app-a-"),
                        "app-a must never see app-b's segment: " + p.getFileName());
            }
        }
    }

    @Test
    void adoptsLegacyIdlessOrphansOnUpgrade(@TempDir Path dir) throws Exception {
        // Pre-upgrade run wrote id-less `spill-<seq>.log` files.
        try (SpillStore legacy = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty())) {
            legacy.spill(recs(40));
        }
        long legacyFiles;
        try (var s = Files.list(dir)) {
            legacyFiles = s.filter(p -> p.getFileName().toString().matches("spill-\\d+\\.log")).count();
        }
        assertTrue(legacyFiles > 0, "precondition: legacy id-less segments exist");

        // Post-upgrade run with an instance id must adopt them into its namespace.
        try (SpillStore upgraded = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty(),
                     SpillCodec.BINARY, com.mulesoft.support.batchhttp.core.BatchConfig.SpillRecoveryMode.ADOPT, false, "app-a")) {
            assertFalse(upgraded.shippableSegments().isEmpty(), "migrated legacy segments must be shippable");
            for (Path p : upgraded.shippableSegments()) {
                assertTrue(p.getFileName().toString().startsWith("spill-app-a-"),
                        "legacy file must be renamed into our namespace: " + p.getFileName());
            }
            // No id-less file should remain.
            try (var s = Files.list(dir)) {
                assertEquals(0, s.filter(p -> p.getFileName().toString().matches("spill-\\d+\\.log")).count(),
                        "no legacy id-less segment should remain after adoption");
            }
            // Records are intact and decodable.
            AtomicLong decoded = new AtomicLong();
            for (Path seg : upgraded.shippableSegments()) {
                try (SpillReader r = new SpillReader(seg, upgraded.maxSegmentBytes())) {
                    while (r.next() != null) decoded.incrementAndGet();
                }
            }
            assertEquals(40, decoded.get(), "all legacy records must survive migration");
        }
    }

    @Test
    void instancesSharingDirBothStayLive(@TempDir Path dir) throws Exception {
        // Distinct ids -> distinct per-instance lock files -> BOTH own their own segments,
        // unlike same-dir same-id which forces one into IGNORE.
        try (SpillStore appA = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty(),
                     SpillCodec.BINARY, com.mulesoft.support.batchhttp.core.BatchConfig.SpillRecoveryMode.ADOPT, false, "app-a");
             SpillStore appB = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty(),
                     SpillCodec.BINARY, com.mulesoft.support.batchhttp.core.BatchConfig.SpillRecoveryMode.ADOPT, false, "app-b")) {
            assertTrue(appA.ownsDirectory(), "app-a must own its own (id-scoped) lock");
            assertTrue(appB.ownsDirectory(), "app-b must own its own (id-scoped) lock");
        }
    }

    /** Records still readable across every shippable segment. */
    private static long onDisk(SpillStore store) throws IOException {
        long n = 0;
        for (Path seg : store.shippableSegments()) {
            try (SpillReader r = new SpillReader(seg, store.maxSegmentBytes())) {
                while (r.next() != null) n++;
            }
        }
        return n;
    }

    private static long fileBytes(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("spill-"))
                    .mapToLong(p -> p.toFile().length()).sum();
        }
    }

    /** A loss listener that records every call. */
    private static final class Losses implements SpillStore.LossListener {
        final List<Object[]> calls = new CopyOnWriteArrayList<>();
        @Override public void onLoss(SpillStore.LossKind kind, int segments, long records, long bytes) {
            calls.add(new Object[]{kind, segments, records, bytes});
        }
        long records(SpillStore.LossKind kind) {
            return calls.stream().filter(c -> c[0] == kind).mapToLong(c -> (Long) c[2]).sum();
        }
        long segments(SpillStore.LossKind kind) {
            return calls.stream().filter(c -> c[0] == kind).mapToLong(c -> (Integer) c[1]).sum();
        }
    }

    private static SpillStore store(Path dir, long seg, long cap, long floor, SpillStore.FreeSpaceProbe probe,
                                    SpillStore.LossListener listener) throws IOException {
        return new SpillStore(dir, seg, cap, floor, probe, SpillCodec.BINARY,
                com.mulesoft.support.batchhttp.core.BatchConfig.SpillRecoveryMode.ADOPT, false, null, listener);
    }

    @Test
    void evictedRecordsAreCountedAsDropped(@TempDir Path dir) throws Exception {
        // Every record handed to spill() must end up on disk or in droppedRecords — including the
        // ones inside a segment deleted to stay under the cap, which used to vanish uncounted.
        Losses losses = new Losses();
        try (SpillStore store = store(dir, 200, 1000, NO_FLOOR, plenty(), losses)) {
            store.spill(recs(200));
            store.sealActiveSegment();
            SpillStore.SpillStats st = store.stats();
            assertTrue(st.evictedSegments > 0, "precondition: the cap forced eviction");
            assertEquals(200, st.spilledRecords, "nothing was refused outright");
            assertEquals(200, onDisk(store) + st.droppedRecords, "conservation across eviction");
            assertEquals(st.evictedRecords, st.droppedRecords, "with no refusals, every drop is an eviction");
            assertEquals(st.evictedRecords, losses.records(SpillStore.LossKind.EVICTED),
                    "the listener hears about every evicted record");
            assertEquals(st.evictedSegments, losses.segments(SpillStore.LossKind.EVICTED));
        }
    }

    @Test
    void freeSpaceFloorEvictionsAndRefusalsAreCounted(@TempDir Path dir) throws Exception {
        long floor = 100_000;
        AtomicInteger probes = new AtomicInteger();
        // Room for ~3 KB above the floor at first (the writes then cross it), and permanently
        // below the floor once eviction forces a re-probe.
        SpillStore.FreeSpaceProbe shrinking = d -> probes.incrementAndGet() == 1 ? floor + 3000 : floor - 1;
        Losses losses = new Losses();
        try (SpillStore store = store(dir, 200, BIG, floor, shrinking, losses)) {
            store.spill(recs(200));
            store.sealActiveSegment();
            SpillStore.SpillStats st = store.stats();
            assertEquals(200, onDisk(store) + st.droppedRecords, "conservation under the free-space floor");
            assertTrue(st.droppedRecords > st.evictedRecords, "precondition: some records were refused outright");
            assertEquals(st.droppedRecords - st.evictedRecords, losses.records(SpillStore.LossKind.REFUSED));
        }
    }

    @Test
    void ioFailureMidBatchCountsOnlyTheUnwrittenRemainderAndStartsAFreshSegment(@TempDir Path dir) throws Exception {
        // The free-space probe is re-run after an eviction; make that one probe fail with an
        // I/O error part-way through the batch, the way a dying disk would.
        AtomicInteger probes = new AtomicInteger();
        SpillStore.FreeSpaceProbe failsOnce = d -> {
            if (probes.incrementAndGet() == 2) throw new IOException("Input/output error");
            return BIG;
        };
        try (SpillStore store = new SpillStore(dir, 200, 1000, 1, failsOnce)) {
            assertFalse(store.spill(recs(200)), "a batch that was not fully written reports false");
            SpillStore.SpillStats st = store.stats();
            assertTrue(st.spilledRecords > 0 && st.spilledRecords < 200, "precondition: the failure hit mid-batch");
            assertEquals(200, st.spilledRecords + st.droppedRecords - st.evictedRecords,
                    "records written before the failure must not also be counted as dropped");
            long maxBefore = maxSeq(dir);

            assertTrue(store.spill(recs(5)), "the store keeps working after the failure");
            assertTrue(maxSeq(dir) > maxBefore, "the next record opens a fresh segment, never appends behind a torn frame");
            store.sealActiveSegment();
            assertEquals(st.spilledRecords + 5 - store.stats().evictedRecords, onDisk(store));
        }
    }

    @Test
    void refusedRecordsAreCountedOnceByTheStore(@TempDir Path dir) throws Exception {
        SpillStore store = new SpillStore(dir, BIG, BIG, NO_FLOOR, plenty());
        store.close();
        assertEquals(0, store.spillRecords(recs(4)), "a closed store writes nothing");
        assertEquals(4, store.stats().droppedRecords, "and counts the refusal itself");
    }

    @Test
    void totalBytesIncludesSegmentHeadersSoTheCapIsTheRealFootprint(@TempDir Path dir) throws Exception {
        try (SpillStore store = new SpillStore(dir, 200, 1000, NO_FLOOR, plenty())) {
            store.spill(recs(200));
            store.sealActiveSegment();
            assertEquals(fileBytes(dir), store.totalBytes(), "bookkeeping must match the bytes on disk");
            assertTrue(fileBytes(dir) <= 1000, "the cap bounds the real on-disk footprint");
        }
    }

    @Test
    void deletingAnAlreadyMissingSegmentDoesNotDecrementTheCount(@TempDir Path dir) throws Exception {
        try (SpillStore store = new SpillStore(dir, 200, BIG, NO_FLOOR, plenty())) {
            store.spill(recs(20));
            store.sealActiveSegment();
            int before = store.stats().segmentCount;
            Path seg = store.shippableSegments().get(0);
            Files.delete(seg);           // gone behind the store's back
            store.deleteSegment(seg);
            assertEquals(before, store.stats().segmentCount, "a segment that was not deleted here is not uncounted here");
        }
    }

    @Test
    void evictionSkipsASegmentClaimedForReplay(@TempDir Path dir) throws Exception {
        try (SpillStore store = new SpillStore(dir, 200, BIG, NO_FLOOR, plenty())) {
            store.spill(recs(20));
            store.sealActiveSegment();
            List<Path> segs = store.shippableSegments();
            assertTrue(segs.size() >= 3, "precondition: several segments");
            assertTrue(store.beginReplay(segs.get(0)));
        }
        // Reopen with a cap that forces eviction on the next write, and claim the oldest first.
        try (SpillStore store = new SpillStore(dir, 200, fileBytes(dir), NO_FLOOR, plenty())) {
            List<Path> segs = store.shippableSegments();
            assertTrue(store.beginReplay(segs.get(0)), "a worker claims the oldest segment");
            store.spill(recs(1));
            assertTrue(Files.exists(segs.get(0)), "a segment being replayed must never be evicted");
            assertFalse(Files.exists(segs.get(1)), "the next-oldest is evicted instead");
            assertFalse(store.beginReplay(segs.get(1)), "an evicted segment can no longer be claimed");
            store.deleteSegment(segs.get(0)); // the worker finished and releases its claim
            assertEquals(countSegments(dir), store.stats().segmentCount, "no double decrement");
        }
    }

    private static long maxSeq(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("spill-") && n.endsWith(".log"))
                    .mapToLong(n -> Long.parseLong(n.substring("spill-".length(), n.length() - ".log".length())))
                    .max().orElse(-1);
        }
    }
}
