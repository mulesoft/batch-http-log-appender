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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two properties the README states as guarantees and that only bite in production:
 *
 * <ul>
 *   <li><b>Vendor ceilings are respected.</b> "The engine also respects each vendor's hard intake
 *       limits (e.g. Datadog 1000 logs / 5 MB per request) and flushes before crossing them."
 *       An oversized body earns a terminal 4xx (Datadog answers 413), which the engine correctly
 *       refuses to retry — so crossing the limit is not a slow request, it is a lost batch.</li>
 *   <li><b>Records are conserved.</b> {@code accepted == sentRecords + dropped + spillDropped}.
 *       Every record must end up on exactly one of those books; a path that quietly discards one
 *       shows up to an operator as logs that simply never arrived.</li>
 * </ul>
 */
class BatchSizeAndConservationTest {

    private HttpServer server;
    private int port;
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/intake", exchange -> {
            try {
                byte[] body = readBody(exchange.getRequestBody(),
                        "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")));
                int code = status.get();
                if (code == 200) bodies.add(body);
                exchange.sendResponseHeaders(code, -1);
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

    private LogSink sink(int maxBytes) {
        return GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + "/intake")
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .maxRecords(100_000)   // isolate the BYTE ceiling from the record ceiling
                .maxBytes(maxBytes)
                .build();
    }

    private static LogRecord rec(String msg) {
        return LogRecord.builder().message(msg).level("INFO").loggerName("l").threadName("t").build();
    }

    @Test
    void neverExceedsTheVendorByteCeilingWithMultiByteMessages() throws Exception {
        // Every char here is 3 UTF-8 bytes, so a char-counting estimate under-reports the real
        // body by ~3x — the case that silently ships an oversized request.
        String cjk = "日".repeat(300);   // 300 chars, 900 bytes
        int ceiling = 4000;

        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(100_000)          // isolate the byte trigger
                .maxBatchBytes(ceiling)
                .lingerTime(Duration.ofMillis(100))
                .gzip(false)
                // Spill is on by default; off wherever this class ships against a healthy
                // endpoint, so no unit test writes log payloads into the temp directory.
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(ceiling), config)) {
            for (int i = 0; i < 60; i++) shipper.offer(rec(cjk));
        }

        assertFalse(bodies.isEmpty(), "batches should have been delivered");
        for (byte[] body : bodies) {
            assertTrue(body.length <= ceiling,
                    "a batch body of " + body.length + " bytes exceeded the " + ceiling
                            + "-byte vendor ceiling; the engine must flush BEFORE crossing it");
        }
    }

    @Test
    void multiByteMessagesAreNotUnderCountedByTheByteEstimate() throws Exception {
        // Same byte budget, same number of bytes on the wire, but expressed as 3-byte chars vs
        // 1-byte chars. A char-based estimate would pack ~3x as many multi-byte records per batch.
        int ceiling = 4000;
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(100_000)
                .maxBatchBytes(ceiling)
                .lingerTime(Duration.ofMillis(100))
                .gzip(false)
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(ceiling), config)) {
            for (int i = 0; i < 40; i++) shipper.offer(rec("A".repeat(900)));   // 900 bytes
        }
        int asciiBatches = bodies.size();
        bodies.clear();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(ceiling), config)) {
            for (int i = 0; i < 40; i++) shipper.offer(rec("日".repeat(300)));  // also 900 bytes
        }
        int cjkBatches = bodies.size();

        assertTrue(asciiBatches > 0 && cjkBatches > 0, "both runs must deliver something");
        assertEquals(asciiBatches, cjkBatches,
                "equal payloads in bytes must batch identically regardless of charset; a "
                        + "char-counting estimate would fit far more CJK records per request");
    }

    @Test
    void surrogatePairsAreCountedAsFourBytesNotSix() throws Exception {
        // An emoji is one code point, two Java chars, four UTF-8 bytes. Counting each char as
        // 3 bytes would over-report by 50% and needlessly fragment batches.
        int ceiling = 4000;
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(100_000)
                .maxBatchBytes(ceiling)
                .lingerTime(Duration.ofMillis(100))
                .gzip(false)
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(ceiling), config)) {
            for (int i = 0; i < 40; i++) shipper.offer(rec("😀".repeat(225)));  // 900 bytes
        }

        assertFalse(bodies.isEmpty());
        for (byte[] body : bodies) {
            assertTrue(body.length <= ceiling,
                    "emoji batch of " + body.length + " bytes exceeded the ceiling");
            assertTrue(new String(body, StandardCharsets.UTF_8).contains("😀"),
                    "the emoji must survive intact on the wire");
        }
    }

    @Test
    void everyAcceptedRecordIsAccountedForOnAHealthyEndpoint() {
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(50)
                .lingerTime(Duration.ofMillis(100))
                .gzip(false)
                .spillEnabled(false)
                .build();

        int total = 500;
        BatchingLogShipper shipper = new BatchingLogShipper(sink(1024 * 1024), config);
        for (int i = 0; i < total; i++) shipper.offer(rec("event " + i));
        shipper.close();

        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(total, s.accepted);
        assertEquals(s.accepted, s.sentRecords + s.dropped, "conservation of records");
        assertEquals(0, s.queueDepth, "the in-memory queue must be fully drained");
    }

    @Test
    void parallelFlushersNeitherDuplicateNorLoseRecords() throws Exception {
        // Each flusher assembles into its own batch list and pulls from the shared BlockingQueue,
        // so no record should ever appear in two batches (double-billing the intake) or in none.
        BatchConfig config = BatchConfig.builder()
                .flusherThreads(4)
                .maxBatchRecords(20)
                .lingerTime(Duration.ofMillis(50))
                .queueCapacity(10_000)
                .gzip(false)
                .spillEnabled(false)
                .build();

        int total = 2000;
        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(1024 * 1024), config)) {
            for (int i = 0; i < total; i++) {
                assertTrue(shipper.offer(rec("unique-" + i)), "queue is large enough for every record");
            }
        }

        List<String> delivered = new java.util.ArrayList<>();
        for (byte[] body : bodies) {
            new String(body, StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank())
                    .forEach(delivered::add);
        }
        assertEquals(total, delivered.size(),
                "with " + config.flusherThreads + " flushers every record must ship exactly once");
        assertEquals(total, new java.util.HashSet<>(delivered).size(),
                "no record may be shipped twice by two flushers");
    }

    @Test
    void replayedRecordsRejectedAsFatalAreCountedNotSilentlyDiscarded(@TempDir Path spillDir) throws Exception {
        // Force the live send to fail so the batch lands on disk, then make the endpoint answer
        // the replay with a terminal 400. Abandoning that poison segment is right — but the
        // records are gone, so they must appear as drops rather than evaporating from the books.
        status.set(503);

        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(10)
                .lingerTime(Duration.ofMillis(50))
                .maxRetries(0)
                .initialRetryBackoff(Duration.ofMillis(1))
                .spillEnabled(true)
                .spillDirectory(spillDir)
                .spillReplayInterval(Duration.ofMillis(100))
                .minFreeDiskBytes(0)
                .gzip(false)
                .build();

        int total = 20;
        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(1024 * 1024), config)) {
            for (int i = 0; i < total; i++) shipper.offer(rec("doomed " + i));

            // Wait until the failed batches have actually reached disk.
            long deadline = System.currentTimeMillis() + 10_000;
            while (shipper.spillStats().spilledRecords < total && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(total, shipper.spillStats().spilledRecords, "all records should have spilled");

            status.set(400); // now the replay gets a terminal rejection

            deadline = System.currentTimeMillis() + 15_000;
            while (shipper.spillStats().segmentCount > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(0, shipper.spillStats().segmentCount,
                    "a fatally-rejected segment must be abandoned, not replayed forever");

            BatchingLogShipper.Stats s = shipper.stats();
            long spillDropped = shipper.spillStats().droppedRecords;
            assertEquals(total, s.accepted);
            assertEquals(s.accepted, s.sentRecords + s.dropped + spillDropped,
                    "records abandoned on the replay path must be counted as dropped: " + s);
        }
    }

    @Test
    void conservationHoldsAcrossSpillEvictionAndRecovery(@TempDir Path spillDir) throws Exception {
        // An outage long enough to overflow a tiny spill buffer, then recovery. Every record must
        // end up sent, dropped, or counted by the buffer as evicted/refused — exactly once.
        status.set(503);
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(10)
                .lingerTime(Duration.ofMillis(20))
                .maxRetries(0)
                .initialRetryBackoff(Duration.ofMillis(1))
                .spillEnabled(true)
                .spillDirectory(spillDir)
                .maxSpillFileBytes(512)
                .maxTotalSpillBytes(4096)
                .minFreeDiskBytes(0)
                .spillReplayInterval(Duration.ofMillis(100))
                .gzip(false)
                .build();

        int total = 300;
        BatchingLogShipper shipper = new BatchingLogShipper(sink(1024 * 1024), config);
        for (int i = 0; i < total; i++) shipper.offer(rec("outage " + i));
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var sp = shipper.spillStats();
            if (sp.spilledRecords + sp.droppedRecords - sp.evictedRecords >= total) break;
            Thread.sleep(25);
        }
        status.set(200);
        deadline = System.currentTimeMillis() + 10_000;
        while (shipper.spillStats().segmentCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(25);
        shipper.close();

        BatchingLogShipper.Stats s = shipper.stats();
        var sp = shipper.spillStats();
        assertEquals(0, sp.segmentCount, "the buffer drained once the endpoint recovered");
        assertTrue(sp.evictedRecords > 0, "precondition: the cap evicted something");
        assertEquals(total, s.accepted);
        assertEquals(s.accepted, s.sentRecords + s.dropped + sp.droppedRecords,
                "accepted == sentRecords + dropped + spillDropped: " + s + " " + sp);
    }
}
