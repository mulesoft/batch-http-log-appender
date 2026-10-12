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

import com.mulesoft.support.batchhttp.core.sink.GenericNdjsonSink;
import com.mulesoft.support.batchhttp.core.sink.LogSink;
import com.mulesoft.support.batchhttp.core.spill.SpillStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Verifies the disk-spill safety net end-to-end: failed batches land on disk, the
 * background replayer drains them once the endpoint recovers, and spill files left
 * by a previous run are recovered on startup.
 */
class BatchingLogShipperSpillTest {

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger recordCount = new AtomicInteger();
    private final Map<String, AtomicInteger> deliveredByMessage = new ConcurrentHashMap<>();
    private static final Pattern EVENT = Pattern.compile("event \\d+|poison");
    private volatile int responseStatus = 200;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/intake", exchange -> {
            try {
                byte[] body = readBody(exchange.getRequestBody(),
                        "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")));
                int status = responseStatus;
                if (status >= 200 && status < 300) {
                    int lines = (int) new String(body).lines().filter(l -> !l.isBlank()).count();
                    recordCount.addAndGet(lines);
                    Matcher m = EVENT.matcher(new String(body, StandardCharsets.UTF_8));
                    while (m.find()) {
                        deliveredByMessage.computeIfAbsent(m.group(), k -> new AtomicInteger()).incrementAndGet();
                    }
                }
                requestCount.incrementAndGet();
                exchange.sendResponseHeaders(status, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static byte[] readBody(InputStream in, boolean gzipped) throws Exception {
        InputStream src = gzipped ? new GZIPInputStream(in) : in;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = src.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private LogSink liveSink() {
        return GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + "/intake")
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .maxRecords(100)
                .build();
    }

    private BatchConfig spillConfig(Path dir) {
        return BatchConfig.builder()
                .maxBatchRecords(50)
                .lingerTime(Duration.ofMillis(100))
                .maxRetries(0)                      // fail fast so the test isn't slow
                .initialRetryBackoff(Duration.ofMillis(1))
                .gzip(false)
                .spillEnabled(true)
                .spillDirectory(dir)
                .spillReplayInterval(Duration.ofMillis(150))
                .build();
    }

    private static List<LogRecord> records(int n) {
        List<LogRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(LogRecord.builder()
                    .timestampMillis(1700000000000L + i)
                    .level("INFO").loggerName("test").threadName("main")
                    .message("event " + i).build());
        }
        return out;
    }

    private static long segmentCount(Path dir) throws Exception {
        if (!Files.exists(dir)) return 0;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("spill-")).count();
        }
    }

    @Test
    void failedBatchesSpillThenDrainOnRecovery(@TempDir Path dir) throws Exception {
        responseStatus = 500; // endpoint down — every send fails after retries

        try (BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), spillConfig(dir))) {
            for (LogRecord r : records(120)) shipper.offer(r);

            // Wait for flushes to fail and spill to disk.
            waitFor(() -> {
                try { return segmentCount(dir) > 0 && shipper.spillStats().spilledRecords >= 120; }
                catch (Exception e) { return false; }
            }, 5000);

            assertTrue(shipper.spillStats().spilledRecords >= 120, "failed batches must be spilled to disk");
            assertTrue(segmentCount(dir) > 0, "spill files must exist on disk");

            // Endpoint recovers — the background replayer should drain and delete segments.
            recordCount.set(0);
            responseStatus = 200;
            waitFor(() -> recordCount.get() >= 120, 8000);

            assertTrue(recordCount.get() >= 120, "recovered records must reach the endpoint, got " + recordCount.get());
            waitFor(() -> {
                try { return segmentCount(dir) == 0; } catch (Exception e) { return false; }
            }, 5000);
            assertEquals(0, segmentCount(dir), "drained segments must be deleted");
        }
    }

    @Test
    void recoversLeftoverSegmentsFromPreviousRunOnStartup(@TempDir Path dir) throws Exception {
        // Simulate a previous run/crash: write spill segments directly via a standalone store.
        try (SpillStore previous = new SpillStore(dir, 16L * 1024 * 1024, Long.MAX_VALUE / 4, 0, d -> Long.MAX_VALUE / 4)) {
            previous.spill(records(75));
        } // close() seals the active segment, leaving files on disk
        assertTrue(segmentCount(dir) > 0, "precondition: leftover segments exist");

        responseStatus = 200; // endpoint healthy on this run
        try (BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), spillConfig(dir))) {
            waitFor(() -> recordCount.get() >= 75, 8000);
            assertTrue(recordCount.get() >= 75, "leftover records must be replayed on startup, got " + recordCount.get());
            waitFor(() -> {
                try { return segmentCount(dir) == 0; } catch (Exception e) { return false; }
            }, 5000);
            assertEquals(0, segmentCount(dir), "replayed leftover segments must be deleted");
        }
    }

    @Test
    void queueOverflowSpillsInsteadOfDropping(@TempDir Path dir) throws Exception {
        BatchConfig config = BatchConfig.builder()
                .queueCapacity(10)
                .overflowPolicy(BatchConfig.OverflowPolicy.DROP_NEWEST)
                .lingerTime(Duration.ofHours(1))   // flusher won't drain during the test
                .maxBatchRecords(100000)
                .spillEnabled(true)
                .spillDirectory(dir)
                .spillOnOverflow(true)
                .spillReplayInterval(Duration.ofHours(1)) // don't replay during the test
                .build();

        // Dead endpoint so the flusher can't drain the queue.
        LogSink dead = GenericNdjsonSink.builder().url("http://127.0.0.1:1/x").build();
        try (BatchingLogShipper shipper = new BatchingLogShipper(dead, config)) {
            for (LogRecord r : records(1000)) shipper.offer(r);

            waitFor(() -> shipper.spillStats() != null && shipper.spillStats().spilledRecords > 0, 3000);
            assertTrue(shipper.spillStats().spilledRecords > 0, "overflow records must be spilled, not dropped");
            assertEquals(0, shipper.stats().dropped, "with spillOnOverflow, nothing should be counted dropped");
        }
    }

    /** Captures every diagnostic emission. */
    private static final class CapturingDiagnostics implements Diagnostics {
        final List<String> warn = new CopyOnWriteArrayList<>();
        final List<String> all = new CopyOnWriteArrayList<>();
        @Override public boolean isEnabled(Level level) { return true; }
        @Override public void log(Level level, String message) {
            all.add(level + " " + message);
            if (level == Level.WARN) warn.add(message);
        }
        boolean any(Level level, String substring) {
            return all.stream().anyMatch(l -> l.startsWith(level + " ") && l.contains(substring));
        }
    }

    private static long sumOf(List<String> lines, String regex) {
        Pattern p = Pattern.compile(regex);
        long sum = 0;
        for (String l : lines) {
            Matcher m = p.matcher(l);
            if (m.find()) sum += Long.parseLong(m.group(1));
        }
        return sum;
    }

    @Test
    void spillEvictionIsCountedAndReportedInACoalescedWarn(@TempDir Path dir) throws Exception {
        responseStatus = 503; // nothing gets through, so everything spills into a tiny buffer
        CapturingDiagnostics diag = new CapturingDiagnostics();
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(20)
                .lingerTime(Duration.ofMillis(20))
                .maxRetries(0)
                .initialRetryBackoff(Duration.ofMillis(1))
                .gzip(false)
                .spillEnabled(true)
                .spillDirectory(dir)
                .maxSpillFileBytes(512)
                .maxTotalSpillBytes(2048)
                .minFreeDiskBytes(0)
                .spillReplayInterval(Duration.ofHours(1))
                .diagnostics(diag)
                .build();

        int total = 500;
        BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), config);
        for (LogRecord r : records(total)) shipper.offer(r);
        waitFor(() -> {
            SpillStore.SpillStats sp = shipper.spillStats();
            return sp.spilledRecords + (sp.droppedRecords - sp.evictedRecords) >= total;
        }, 10_000);
        shipper.close();

        BatchingLogShipper.Stats s = shipper.stats();
        SpillStore.SpillStats sp = shipper.spillStats();
        assertTrue(sp.evictedRecords > 0, "precondition: the 2 KiB cap forced eviction");
        assertEquals(0, s.dropped, "spill losses are counted by the store, not again by the shipper");
        long onDisk = sp.spilledRecords - sp.evictedRecords;
        assertEquals(s.accepted, s.sentRecords + s.dropped + sp.droppedRecords + onDisk,
                "accepted == sentRecords + dropped + spillDropped + (records still on disk)");

        List<String> lines = new ArrayList<>();
        for (String w : diag.warn) if (w.contains("spill buffer full")) lines.add(w);
        assertTrue(lines.size() >= 1 && lines.size() <= 3, "coalesced, not one line per eviction: " + lines);
        assertEquals(sp.evictedRecords, sumOf(lines, "holding (\\d+) record"),
                "the WARN lines account for every evicted record");
        assertEquals(sp.droppedRecords, sp.evictedRecords + sumOf(lines, "refused (\\d+) new record"),
                "and for every refused one");
    }

    @Test
    void aRefusedLiveSpillIsCountedOnceNotTwice(@TempDir Path dir) throws Exception {
        responseStatus = 503;
        BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), spillConfig(dir));
        try {
            assumeFalse(!dir.toFile().setWritable(false) || Files.isWritable(dir),
                    "needs a filesystem that enforces permissions for this user");
            for (LogRecord r : records(5)) shipper.offer(r);
            waitFor(() -> shipper.spillStats().droppedRecords >= 5, 5_000);
            BatchingLogShipper.Stats s = shipper.stats();
            SpillStore.SpillStats sp = shipper.spillStats();
            assertEquals(5, s.accepted);
            assertEquals(5, sp.droppedRecords);
            assertEquals(0, s.dropped, "the spill's refusal must not be counted a second time in dropped");
            assertEquals(s.accepted, s.sentRecords + s.dropped + sp.droppedRecords);
        } finally {
            dir.toFile().setWritable(true);
            shipper.close();
        }
    }

    @Test
    void aReplaySubBatchThatThrowsIsDroppedInsteadOfReplayedForever(@TempDir Path dir) throws Exception {
        LogSink real = liveSink();
        LogSink throwsOnPoison = new LogSink() {
            @Override public URI endpoint() { return real.endpoint(); }
            @Override public Map<String, String> headers() { return real.headers(); }
            @Override public Limits limits() { return real.limits(); }
            @Override public String name() { return "PoisonSink"; }
            @Override public byte[] serialize(List<LogRecord> batch) {
                for (LogRecord r : batch) {
                    if ("poison".equals(r.message())) throw new IllegalStateException("cannot serialize");
                }
                return real.serialize(batch);
            }
        };
        List<LogRecord> seeded = new ArrayList<>(records(5));
        seeded.add(LogRecord.builder().timestampMillis(1).level("INFO").loggerName("test")
                .threadName("main").message("poison").build());
        for (LogRecord r : records(10).subList(5, 10)) seeded.add(r);
        try (SpillStore previous = new SpillStore(dir, 1 << 20, 1 << 24, 0, d -> Long.MAX_VALUE / 4,
                com.mulesoft.support.batchhttp.core.spill.SpillCodec.BINARY,
                BatchConfig.SpillRecoveryMode.ADOPT, false, "poison")) {
            previous.spill(seeded);
        }

        responseStatus = 200;
        CapturingDiagnostics diag = new CapturingDiagnostics();
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(5)                     // sub-batches: [e0..e4] [poison,e5..e8] [e9]
                .lingerTime(Duration.ofMillis(50))
                .maxRetries(0)
                .gzip(false)
                .spillDirectory(dir)
                .spillInstanceId("poison")
                .spillReplayInterval(Duration.ofMillis(100))
                .minFreeDiskBytes(0)
                .diagnostics(diag)
                .build();
        try (BatchingLogShipper shipper = new BatchingLogShipper(throwsOnPoison, config)) {
            waitFor(() -> { try { return segmentCount(dir) == 0; } catch (Exception e) { return false; } }, 3_000);
            assertEquals(0, segmentCount(dir), "the segment must be abandoned, not kept and re-sent forever");
            Thread.sleep(400); // a few more replay intervals: nothing may be sent again
            deliveredByMessage.forEach((m, c) -> assertEquals(1, c.get(), m + " was delivered more than once"));
            assertEquals(6, shipper.stats().sentRecords);
            assertEquals(5, shipper.stats().dropped, "the failing sub-batch is counted as dropped");
            assertTrue(diag.any(Diagnostics.Level.ERROR, "unexpected error while re-shipping"), diag.all.toString());
        }
    }

    @Test
    void startupReportsAdoptedSegmentsAndALockHeldByAnotherInstance(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 1 << 20, 1 << 24, 0, d -> Long.MAX_VALUE / 4,
                com.mulesoft.support.batchhttp.core.spill.SpillCodec.BINARY,
                BatchConfig.SpillRecoveryMode.ADOPT, false, "diag")) {
            previous.spill(records(7));
        }
        responseStatus = 503; // keep the adopted segment on disk while the second engine starts
        CapturingDiagnostics first = new CapturingDiagnostics();
        CapturingDiagnostics second = new CapturingDiagnostics();
        BatchConfig.Builder cfg = BatchConfig.builder()
                .maxRetries(0).gzip(false).spillDirectory(dir).spillInstanceId("diag")
                .spillReplayInterval(Duration.ofHours(1)).minFreeDiskBytes(0);
        try (BatchingLogShipper owner = new BatchingLogShipper(liveSink(), cfg.diagnostics(first).build());
             BatchingLogShipper sibling = new BatchingLogShipper(liveSink(), cfg.diagnostics(second).build())) {
            assertTrue(first.any(Diagnostics.Level.INFO, "recovered 1 spill segment(s)"), first.all.toString());
            assertTrue(first.any(Diagnostics.Level.INFO, "7 record(s)) left by a previous run"), first.all.toString());
            assertTrue(second.any(Diagnostics.Level.WARN, "is locked by another live instance with spillInstanceId=diag"),
                    second.all.toString());
        }
    }

    @Test
    void startupReconcileEvictionIsReportedAsALoss(@TempDir Path dir) throws Exception {
        try (SpillStore previous = new SpillStore(dir, 512, 1 << 24, 0, d -> Long.MAX_VALUE / 4,
                com.mulesoft.support.batchhttp.core.spill.SpillCodec.BINARY,
                BatchConfig.SpillRecoveryMode.ADOPT, false, "reconcile")) {
            previous.spill(records(100));
        }
        responseStatus = 503;
        CapturingDiagnostics diag = new CapturingDiagnostics();
        BatchConfig config = BatchConfig.builder()
                .maxRetries(0).gzip(false).spillDirectory(dir).spillInstanceId("reconcile")
                .maxSpillFileBytes(512).maxTotalSpillBytes(2048).minFreeDiskBytes(0)
                .spillReplayInterval(Duration.ofHours(1)).diagnostics(diag).build();
        try (BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), config)) {
            SpillStore.SpillStats sp = shipper.spillStats();
            assertTrue(sp.evictedRecords > 0);
            assertEquals(sp.evictedRecords, sp.droppedRecords);
            assertTrue(diag.any(Diagnostics.Level.WARN, "startup reconcile evicted " + sp.evictedSegments
                    + " oldest spill segment(s) holding " + sp.evictedRecords + " record(s)"), diag.all.toString());
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
    }
}
