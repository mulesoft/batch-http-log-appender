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

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for the two decisions the engine makes under stress that the happy-path
 * batching test doesn't reach: the {@link BatchConfig.OverflowPolicy overflow policy} when the
 * queue is full, and the retry/backoff loop when the endpoint is failing.
 *
 * <ul>
 *   <li><b>BLOCK</b> — offer() blocks instead of dropping, and returns {@code false} (counting a
 *       drop) only when the waiting thread is interrupted.</li>
 *   <li><b>DROP_OLDEST</b> — evicts the head to admit the newest, and (when spill-on-overflow is
 *       on) diverts the evicted record to disk instead of dropping it.</li>
 *   <li><b>retry/backoff</b> — a retryable status (503) is retried until it succeeds, while a
 *       fatal status (400) is dropped on the first attempt with no retry.</li>
 * </ul>
 *
 * <p>Determinism trick for the stress paths: a handler that blocks inside the request (never
 * responds) wedges the single flusher mid-send so the queue provably stays full, and a long
 * linger + POISON-driven flush on {@code close()} makes the retry sequence a single, exactly-once
 * batch send.
 */
class BatchingLogShipperOverflowRetryTest {

    private HttpServer server;
    private int port;
    private volatile CountDownLatch release; // released in teardown to free any wedged handler

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (release != null) release.countDown(); // unwedge the blocking handler, if any
        if (server != null) server.stop(0);
    }

    private LogSink sink(String path) {
        return GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + path)
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .build();
    }

    private static LogRecord rec(String msg) {
        return LogRecord.builder().message(msg).level("INFO").loggerName("l").threadName("t").build();
    }

    // ---- 2.1 BLOCK ----

    @Test
    void blockPolicyDeliversEveryRecordWithoutDropping() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        server.createContext("/ok", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                delivered.addAndGet((int) body.lines().filter(l -> !l.isBlank()).count());
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });

        BatchConfig config = BatchConfig.builder()
                .overflowPolicy(BatchConfig.OverflowPolicy.BLOCK)
                .queueCapacity(16)             // small, so offers actually have to wait for drain
                .maxBatchRecords(50)
                .lingerTime(Duration.ofMillis(50))
                .gzip(false)
                // Spill is on by default; off wherever this class exercises the overflow policy
                // itself, which spill-on-overflow would otherwise short-circuit.
                .spillEnabled(false)
                .build();

        int total = 500;
        try (BatchingLogShipper shipper = new BatchingLogShipper(sink("/ok"), config)) {
            for (int i = 0; i < total; i++) {
                assertTrue(shipper.offer(rec("event " + i)), "BLOCK must never refuse a record it can eventually enqueue");
            }
        }
        assertEquals(total, delivered.get(), "BLOCK back-pressures the producer; nothing is dropped");
    }

    @Test
    void blockPolicyReturnsFalseAndCountsDropWhenInterrupted() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        release = new CountDownLatch(1);
        server.createContext("/wedge", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                received.countDown();   // the flusher's send has reached the server...
                release.await();        // ...and now hangs here, so the flusher stops draining
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        BatchConfig config = BatchConfig.builder()
                .overflowPolicy(BatchConfig.OverflowPolicy.BLOCK)
                .queueCapacity(1)
                .maxBatchRecords(1)                       // flush (and wedge) on the very first record
                .lingerTime(Duration.ofMillis(10))
                .requestTimeout(Duration.ofSeconds(60))   // don't let the send time out mid-test
                .shutdownDrainTimeout(Duration.ofMillis(200)) // don't wait on the (intentionally) wedged flusher
                .gzip(false)
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink("/wedge"), config)) {
            assertTrue(shipper.offer(rec("wedges-the-flusher")));
            assertTrue(received.await(5, TimeUnit.SECONDS), "flusher should have reached the wedged send");

            // Flusher is now stuck in send(); fill the (capacity-1) queue so the next offer must block.
            assertTrue(shipper.offer(rec("fills-the-queue")));

            long droppedBefore = shipper.stats().dropped;
            final boolean[] result = {true};
            Thread producer = new Thread(() -> result[0] = shipper.offer(rec("must-block-then-fail")));
            producer.start();

            // The queue can never drain (flusher wedged), so put() stays blocked until interrupted.
            Thread.sleep(200);
            producer.interrupt();
            producer.join(5000);

            assertFalse(producer.isAlive(), "interrupted producer must return, not hang");
            assertFalse(result[0], "an interrupted BLOCK offer must return false");
            assertTrue(shipper.stats().dropped > droppedBefore,
                    "an interrupted BLOCK offer must count as a drop");
        }
    }

    // ---- 2.2 DROP_OLDEST ----

    @Test
    void dropOldestEvictsHeadAndAlwaysAcceptsTheNewest() {
        BatchConfig config = BatchConfig.builder()
                .overflowPolicy(BatchConfig.OverflowPolicy.DROP_OLDEST)
                .queueCapacity(10)
                .lingerTime(Duration.ofHours(1))   // flusher parks holding one record; never drains
                .maxBatchRecords(100_000)
                .spillEnabled(false)
                .build();

        // Dead port: the parked flusher never actually sends, so the queue provably stays full.
        LogSink deadSink = GenericNdjsonSink.builder().url("http://127.0.0.1:1/x").build();
        try (BatchingLogShipper shipper = new BatchingLogShipper(deadSink, config)) {
            for (int i = 0; i < 1000; i++) {
                assertTrue(shipper.offer(rec("m" + i)), "DROP_OLDEST must always admit the newest record");
            }
            assertTrue(shipper.stats().dropped > 0, "evicting the head under overflow must count as drops");
        }
    }

    @Test
    void dropOldestDivertsEvictedRecordsToSpillInsteadOfDropping(@TempDir Path spillDir) {
        BatchConfig config = BatchConfig.builder()
                .overflowPolicy(BatchConfig.OverflowPolicy.DROP_OLDEST)
                .queueCapacity(10)
                .lingerTime(Duration.ofHours(1))
                .maxBatchRecords(100_000)
                .spillEnabled(true)
                .spillOnOverflow(true)
                .spillDirectory(spillDir)
                .spillReplayInterval(Duration.ofHours(1)) // keep the replayer out of the way
                .minFreeDiskBytes(0)                       // never let a free-space floor fail the spill
                .maxTotalSpillBytes(64L * 1024 * 1024)
                .build();

        LogSink deadSink = GenericNdjsonSink.builder().url("http://127.0.0.1:1/x").build();
        try (BatchingLogShipper shipper = new BatchingLogShipper(deadSink, config)) {
            for (int i = 0; i < 1000; i++) {
                shipper.offer(rec("m" + i));
            }
            assertTrue(shipper.spillStats().spilledRecords > 0,
                    "with spill-on-overflow, evicted records are written to disk");
            assertEquals(0, shipper.stats().dropped,
                    "records diverted to spill must not be counted as dropped");
        }
    }

    // ---- 2.3 retry / backoff ----

    @Test
    void retryableStatusIsRetriedUntilItSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/flaky", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                int n = attempts.incrementAndGet();
                exchange.sendResponseHeaders(n <= 2 ? 503 : 200, -1); // fail twice, then succeed
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });

        BatchConfig config = BatchConfig.builder()
                .maxRetries(3)
                .initialRetryBackoff(Duration.ofMillis(10))
                .maxRetryBackoff(Duration.ofMillis(50))
                .lingerTime(Duration.ofSeconds(30)) // no time-flush; close()'s POISON drives the single flush
                .maxBatchRecords(100)
                .gzip(false)
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink("/flaky"), config)) {
            shipper.offer(rec("deliver-me"));
        } // close() flushes the one record and the retry loop runs to completion

        assertEquals(3, attempts.get(), "should be 503, 503, then a successful 200");
        // No assertion on wall-clock backoff (jitter makes it fuzzy); the attempt count proves retries ran.
    }

    @Test
    void fatalStatusIsDroppedWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/bad", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                attempts.incrementAndGet();
                exchange.sendResponseHeaders(400, -1); // Bad Request — retrying the same body is pointless
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });

        BatchConfig config = BatchConfig.builder()
                .maxRetries(3)
                .initialRetryBackoff(Duration.ofMillis(10))
                .lingerTime(Duration.ofSeconds(30))
                .maxBatchRecords(100)
                .gzip(false)
                .spillEnabled(false)   // the 4xx record must be counted dropped, not buffered
                .build();

        BatchingLogShipper shipper = new BatchingLogShipper(sink("/bad"), config);
        shipper.offer(rec("doomed"));
        shipper.close();

        assertEquals(1, attempts.get(), "a 4xx must NOT be retried");
        BatchingLogShipper.Stats stats = shipper.stats();
        assertEquals(1, stats.dropped, "the fatally-rejected record is dropped");
        assertEquals(1, stats.failedBatches, "the batch is counted as failed");
        assertEquals(0, stats.sentRecords, "nothing was delivered");
    }
}
