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

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shutdown must actually let go of everything it started. This library's usual home is a Mule /
 * CloudHub runtime that redeploys applications into a <em>single long-lived JVM</em>, so a thread
 * outliving the appender that created it is not merely untidy: threads inherit the context
 * classloader of whoever created them, which here is the deploying application's classloader, and
 * a surviving thread pins that classloader — and everything it loaded — for the life of the JVM.
 * Repeat over a few dozen redeploys and the runtime dies of metaspace exhaustion.
 *
 * <p>Every thread the engine starts is named after its sink, so each test uses a unique sink name
 * and can assert precisely on <em>its own</em> threads without tripping over another test class's.
 */
class ShipperLifecycleLeakTest {

    private HttpServer server;
    private int port;
    private volatile CountDownLatch release; // frees any deliberately wedged handler at teardown
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ok", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (release != null) release.countDown();
        if (server != null) server.stop(0);
    }

    /** A sink with a caller-chosen {@link LogSink#name()}, so its engine threads are identifiable. */
    private LogSink named(String name, String path) {
        LogSink delegate = GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + path)
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .build();
        return new LogSink() {
            @Override public URI endpoint() { return delegate.endpoint(); }
            @Override public Map<String, String> headers() { return delegate.headers(); }
            @Override public byte[] serialize(List<LogRecord> batch) { return delegate.serialize(batch); }
            @Override public Limits limits() { return delegate.limits(); }
            @Override public String name() { return name; }
        };
    }

    private static LogRecord rec(String msg) {
        return LogRecord.builder().message(msg).level("INFO").loggerName("l").threadName("t").build();
    }

    private static List<Thread> liveThreadsMatching(String marker) {
        List<Thread> found = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().contains(marker)) found.add(t);
        }
        return found;
    }

    /** Threads die asynchronously; give them a bounded moment before declaring a leak. */
    private static List<Thread> awaitNoThreads(String marker, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<Thread> alive = liveThreadsMatching(marker);
        while (!alive.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            alive = liveThreadsMatching(marker);
        }
        return alive;
    }

    @Test
    void closeReleasesTheTransportThreadPool() throws Exception {
        String marker = "LeakProbeTransport";
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(1)                 // send immediately, so the pool thread is created
                .lingerTime(Duration.ofMillis(50))
                .gzip(false)
                // Spill is on by default; off here so the only threads under test are the
                // flusher and the transport pool, and no test writes to the temp directory.
                .spillEnabled(false)
                .build();

        BatchingLogShipper shipper = new BatchingLogShipper(named(marker, "/ok"), config);
        shipper.offer(rec("materialise-the-http-client"));
        long deadline = System.currentTimeMillis() + 5000;
        while (requests.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, requests.get(), "the record should have been delivered");
        assertFalse(liveThreadsMatching("batch-http-transport-" + marker).isEmpty(),
                "the HttpClient's executor thread should exist while the shipper is running");

        shipper.close();

        assertEquals(List.of(), awaitNoThreads("batch-http-transport-" + marker, 5000),
                "close() must shut down the HttpClient executor; a surviving pool thread pins "
                        + "the deploying application's classloader across Mule redeploys");
    }

    @Test
    void closeTerminatesFlushersEvenWhenTheQueueIsFullAtShutdown() throws Exception {
        String marker = "LeakProbeFullQueue";
        CountDownLatch received = new CountDownLatch(1);
        release = new CountDownLatch(1);
        server.createContext("/stall", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                received.countDown();  // the flusher's send has arrived...
                release.await();       // ...and now hangs, so the flusher stops consuming
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        // A full queue is exactly when shutdown matters, and exactly when the non-blocking
        // offer() of the POISON sentinel fails — so the sentinel never reaches the flusher and
        // nothing else would ever tell it to stop.
        BatchConfig config = BatchConfig.builder()
                .queueCapacity(2)
                .maxBatchRecords(1)                        // flush (and stall) on the first record
                .lingerTime(Duration.ofMillis(10))
                .maxRetries(0)
                .requestTimeout(Duration.ofSeconds(60))    // don't let the send time out mid-test
                .shutdownDrainTimeout(Duration.ofMillis(200))
                .gzip(false)
                .spillEnabled(false)   // a full queue must stay full, not drain into a disk buffer
                .build();

        BatchingLogShipper shipper = new BatchingLogShipper(named(marker, "/stall"), config);
        shipper.offer(rec("stalls-the-flusher"));
        assertTrue(received.await(5, TimeUnit.SECONDS), "flusher should have reached the stalled send");
        // With the only consumer stalled mid-send, these fill the queue to capacity and stay there.
        for (int i = 0; i < 50; i++) shipper.offer(rec("m" + i));
        assertEquals(config.queueCapacity, shipper.stats().queueDepth, "the queue must be full");
        assertFalse(liveThreadsMatching("batch-http-flusher-" + marker).isEmpty(),
                "the flusher should be running before close()");

        shipper.close();

        assertEquals(List.of(), awaitNoThreads("batch-http-flusher-" + marker, 10_000),
                "close() must terminate every flusher even when a full queue refused the POISON "
                        + "sentinel; a parked flusher survives the appender and pins its classloader");
        assertEquals(List.of(), awaitNoThreads("batch-http-transport-" + marker, 5000),
                "the transport pool must be released too");

        // ...and the records behind the refused POISON are accounted for, not stranded.
        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(0, s.queueDepth, "queued records and any POISON must be drained at shutdown");
        assertEquals(51, s.accepted);
        assertEquals(51, s.dropped, "48 refused by DROP_NEWEST + 1 interrupted in-flight + 2 drained (spill off)");
        assertEquals(s.accepted, s.sentRecords + s.dropped);
    }

    @Test
    void closeIsIdempotentAndSafeToCallConcurrently() throws Exception {
        String marker = "LeakProbeIdempotent";
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(10)
                .lingerTime(Duration.ofMillis(50))
                .gzip(false)
                .spillEnabled(false)
                .build();

        BatchingLogShipper shipper = new BatchingLogShipper(named(marker, "/ok"), config);
        for (int i = 0; i < 20; i++) shipper.offer(rec("m" + i));

        int closers = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(closers);
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < closers; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    shipper.close();
                } catch (Throwable e) {
                    synchronized (failures) { failures.add(e); }
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "concurrent close() calls must all return");
        assertEquals(List.of(), failures, "close() must not throw when called concurrently");
        shipper.close(); // and again, afterwards

        assertEquals(List.of(), awaitNoThreads("batch-http-flusher-" + marker, 5000));
        assertEquals(List.of(), awaitNoThreads("batch-http-transport-" + marker, 5000));
    }

    @Test
    void blockingOfferDoesNotWedgeTheApplicationThreadAcrossClose() throws Exception {
        String marker = "LeakProbeBlock";
        CountDownLatch received = new CountDownLatch(1);
        release = new CountDownLatch(1);
        server.createContext("/wedge", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                received.countDown();  // the flusher's send has arrived...
                release.await();       // ...and now hangs, so the queue can never drain
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
                .maxBatchRecords(1)                        // flush (and wedge) on the first record
                .lingerTime(Duration.ofMillis(10))
                .requestTimeout(Duration.ofSeconds(60))    // don't let the send time out mid-test
                .shutdownDrainTimeout(Duration.ofMillis(200))
                .gzip(false)
                .spillEnabled(false)   // BLOCK must park the caller, not divert to a disk buffer
                .build();

        BatchingLogShipper shipper = new BatchingLogShipper(named(marker, "/wedge"), config);
        assertTrue(shipper.offer(rec("wedges-the-flusher")));
        assertTrue(received.await(5, TimeUnit.SECONDS), "flusher should have reached the wedged send");
        assertTrue(shipper.offer(rec("fills-the-queue")));

        // This offer must park: the queue is full and the only consumer is wedged in send().
        final boolean[] accepted = {true};
        Thread producer = new Thread(() -> accepted[0] = shipper.offer(rec("parks-on-a-full-queue")));
        producer.setDaemon(true);
        producer.start();
        Thread.sleep(200);
        assertTrue(producer.isAlive(), "BLOCK should still be waiting for room at this point");

        shipper.close();

        // An unbounded put() would leave this application thread parked forever, because after
        // close() nothing will ever drain the queue again.
        producer.join(10_000);
        assertFalse(producer.isAlive(), "a BLOCK offer must not stay parked once the engine is closed");
        assertFalse(accepted[0], "an offer that could not be enqueued must report failure");
        assertTrue(shipper.stats().dropped > 0, "and must be counted as a drop, not lost silently");
        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(0, s.queueDepth);
        assertEquals(3, s.accepted);
        assertEquals(s.accepted, s.sentRecords + s.dropped,
                "the wedged in-flight record, the drained one and the parked BLOCK offer are all counted");
    }
}
