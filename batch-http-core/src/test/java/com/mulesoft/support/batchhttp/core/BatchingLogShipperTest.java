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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spins up an in-JVM HTTP server and verifies the engine batches events into
 * far fewer requests than the number of log records (the whole point vs. the
 * stock one-request-per-event appender).
 */
class BatchingLogShipperTest {

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger recordCount = new AtomicInteger();
    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
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
                String text = new String(body);
                // NDJSON: one record per non-empty line.
                int lines = (int) text.lines().filter(l -> !l.isBlank()).count();
                recordCount.addAndGet(lines);
                batchSizes.add(lines);
                requestCount.incrementAndGet();
                exchange.sendResponseHeaders(responseStatus, -1);
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

    private LogSink sink() {
        return GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + "/intake")
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .maxRecords(100)
                .build();
    }

    @Test
    void batchesManyEventsIntoFewRequests() throws Exception {
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(100)
                .lingerTime(Duration.ofMillis(200))
                .gzip(true)
                // Spill is on by default; off throughout this class so a failure path can never
                // quietly write log payloads into the temp directory during a unit test.
                .spillEnabled(false)
                .build();

        int total = 1000;
        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(), config)) {
            for (int i = 0; i < total; i++) {
                shipper.offer(LogRecord.builder()
                        .timestampMillis(1700000000000L + i)
                        .level("INFO")
                        .loggerName("test")
                        .threadName("main")
                        .message("event " + i)
                        .build());
            }
            // close() drains remaining events on shutdown
        }

        assertEquals(total, recordCount.get(), "all records should be delivered");
        // 1000 records at 100/batch => ~10 requests, NOT 1000.
        assertTrue(requestCount.get() <= 15,
                "expected far fewer requests than records, got " + requestCount.get());
        assertTrue(requestCount.get() >= 10,
                "expected at least 10 full batches, got " + requestCount.get());
    }

    @Test
    void flushesOnLingerTimeoutWhenBatchNotFull() throws Exception {
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(1000)          // far above what we send
                .lingerTime(Duration.ofMillis(150))
                .gzip(false)
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(), config)) {
            for (int i = 0; i < 5; i++) {
                shipper.offer(LogRecord.builder().message("x" + i).loggerName("t").threadName("m").build());
            }
            // Wait past the linger window so the partial batch is flushed by time.
            Thread.sleep(500);
            assertEquals(5, recordCount.get(), "linger timeout should flush the partial batch");
            assertEquals(1, requestCount.get(), "the 5 events should arrive in a single batch");
        }
    }

    @Test
    void truncatesOversizedMessagesBeforeShipping() throws Exception {
        // Capture the raw body so we can measure the delivered message length.
        List<String> bodies = new CopyOnWriteArrayList<>();
        server.createContext("/capture", exchange -> {
            try {
                byte[] body = readBody(exchange.getRequestBody(),
                        "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")));
                bodies.add(new String(body));
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        LogSink captureSink = GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + "/capture")
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .build();

        BatchConfig config = BatchConfig.builder()
                .maxMessageBytes(1024)              // small cap for the test
                .lingerTime(Duration.ofMillis(150))
                .gzip(false)
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(captureSink, config)) {
            shipper.offer(LogRecord.builder()
                    .message("M".repeat(1_000_000)) // 1 MB message, way over the 1 KiB cap
                    .level("ERROR").loggerName("big").threadName("main").build());
            Thread.sleep(500);
        }

        assertEquals(1, bodies.size(), "the one (truncated) record should ship");
        String body = bodies.get(0);
        assertTrue(body.contains("[truncated "), "delivered message must carry the truncation marker");
        // The shipped body is far smaller than the 1 MB original (cap + JSON overhead + marker).
        assertTrue(body.length() < 4096, "truncated body must be tiny, was " + body.length());
        // Other fields survive truncation.
        assertTrue(body.contains("\"level\":\"ERROR\"") || body.contains("ERROR"), "level preserved");
    }

    @Test
    void flusherDoesNotBusySpinWhenIdle() throws Exception {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadCpuTimeSupported(), "per-thread CPU timing not supported on this JVM");
        threads.setThreadCpuTimeEnabled(true);

        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(100)
                .lingerTime(Duration.ofMillis(100)) // short window so it lapses quickly while idle
                .flusherThreads(1)
                .gzip(false)
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(), config)) {
            // Send a few events so the flusher runs once, flushes, then goes idle:
            // exactly the state in which the old poll(0) loop began hot-spinning.
            for (int i = 0; i < 5; i++) {
                shipper.offer(LogRecord.builder().message("warmup " + i)
                        .level("INFO").loggerName("t").threadName("m").build());
            }
            Thread.sleep(300); // let the partial batch flush and the linger window lapse

            long flusherId = findFlusherThreadId();
            assertTrue(flusherId > 0, "flusher thread should be running");

            long cpuBefore = threads.getThreadCpuTime(flusherId);
            long idleMillis = 1000;
            Thread.sleep(idleMillis); // observe the thread while the queue stays empty
            long cpuAfter = threads.getThreadCpuTime(flusherId);

            double cpuMillisUsed = (cpuAfter - cpuBefore) / 1_000_000.0;
            // A blocked take() consumes ~0 CPU. The old busy-spin pinned a core (~1000ms
            // of CPU per 1000ms idle). Allow generous headroom for a shared CI box.
            assertTrue(cpuMillisUsed < 100,
                    "idle flusher must not busy-spin: used " + cpuMillisUsed
                            + "ms CPU over " + idleMillis + "ms idle");
        }
    }

    /** Locate the live "batch-http-flusher-*" thread by name so we can sample its CPU time. */
    private static long findFlusherThreadId() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("batch-http-flusher-")) return t.getId();
        }
        return -1;
    }

    @Test
    void dropNewestNeverBlocksWhenQueueFull() {
        BatchConfig config = BatchConfig.builder()
                .queueCapacity(10)
                .overflowPolicy(BatchConfig.OverflowPolicy.DROP_NEWEST)
                .lingerTime(Duration.ofHours(1)) // ensure flusher won't drain during the test
                .maxBatchRecords(100000)
                .spillEnabled(false)             // overflow must be dropped here, not diverted to disk
                .build();

        // Point at a dead port so nothing drains; queue fills and overflow kicks in.
        LogSink deadSink = GenericNdjsonSink.builder().url("http://127.0.0.1:1/x").build();
        try (BatchingLogShipper shipper = new BatchingLogShipper(deadSink, config)) {
            int accepted = 0;
            for (int i = 0; i < 1000; i++) {
                if (shipper.offer(LogRecord.builder().message("m").loggerName("l").threadName("t").build())) {
                    accepted++;
                }
            }
            // Can't assert exact numbers (a flusher may pull a few), but most must be dropped.
            assertTrue(shipper.stats().dropped > 0, "some records must be dropped under overflow");
        }
    }
}
