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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the disk-spill safety net end-to-end: failed batches land on disk, the
 * background replayer drains them once the endpoint recovers, and spill files left
 * by a previous run are recovered on startup.
 */
class BatchingLogShipperSpillTest {

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger recordCount = new AtomicInteger();
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

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
    }
}
