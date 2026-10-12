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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * What {@code close()} does with records it could not send in time. Before 0.1.6 the flushers
 * flushed only the batch each was holding, and whatever was still queued simply vanished: it
 * was counted in {@code accepted} and nowhere else. Now the remainder is spilled (and replayed
 * by the next start) or, with no spill buffer, counted as dropped with an ERROR — and the POISON
 * shutdown sentinel is never mistaken for a record.
 *
 * <p>The tests wedge the flusher on a send that never answers, so the queue behind it cannot
 * drain within {@code shutdownDrainTimeout}.
 */
class BatchingLogShipperShutdownDrainTest {

    private HttpServer server;
    private ExecutorService serverPool;
    private int port;
    private volatile CountDownLatch received;
    private final CountDownLatch release = new CountDownLatch(1);
    private final ConcurrentLinkedQueue<String> delivered = new ConcurrentLinkedQueue<>();
    private final CapturingDiagnostics diag = new CapturingDiagnostics();

    @BeforeEach
    void setUp() throws Exception {
        received = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool(); // a wedged handler must not block the others
        server.setExecutor(serverPool);
        port = server.getAddress().getPort();
        server.createContext("/wedge", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                received.countDown();
                release.await();
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/ok", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body.lines().filter(l -> !l.isBlank()).forEach(delivered::add);
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) server.stop(0);
        if (serverPool != null) serverPool.shutdownNow();
    }

    private static final class CapturingDiagnostics implements Diagnostics {
        static final class Entry {
            final Level level;
            final String message;
            Entry(Level level, String message) { this.level = level; this.message = message; }
            @Override public String toString() { return level + " " + message; }
        }
        final List<Entry> entries = new CopyOnWriteArrayList<>();

        @Override public boolean isEnabled(Level level) { return true; }
        @Override public void log(Level level, String message) { entries.add(new Entry(level, message)); }
        @Override public void log(Level level, String message, Throwable thrown) { entries.add(new Entry(level, message)); }

        Entry first(Level level, String substring) {
            return entries.stream().filter(e -> e.level == level && e.message.contains(substring)).findFirst().orElse(null);
        }
    }

    /** A sink with a caller-chosen name, so its engine threads are identifiable. */
    private LogSink sink(String name, String path) {
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

    private BatchConfig.Builder base() {
        return BatchConfig.builder()
                .queueCapacity(100)
                .maxBatchRecords(1)                       // the first record wedges the flusher alone
                .lingerTime(Duration.ofMillis(10))
                .maxRetries(0)
                .requestTimeout(Duration.ofSeconds(60))   // the wedged send never times out by itself
                .shutdownDrainTimeout(Duration.ofMillis(200))
                .gzip(false)
                .diagnostics(diag);
    }

    private BatchConfig.Builder withSpill(Path dir) {
        return base()
                .spillEnabled(true)
                .spillDirectory(dir)
                .spillInstanceId("drain")
                .spillReplayInterval(Duration.ofHours(1))
                .minFreeDiskBytes(0);
    }

    private static LogRecord rec(String msg) {
        return LogRecord.builder().message(msg).level("INFO").loggerName("l").threadName("t").build();
    }

    private static long segments(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("spill-")).count();
        }
    }

    /** Replay whatever the previous engine left in {@code dir} into /ok and return the delivered lines. */
    private List<String> replayOnNextStart(Path dir, String name, int expected) throws Exception {
        release.countDown();
        BatchConfig next = withSpill(dir).spillReplayInterval(Duration.ofMillis(100)).build();
        try (BatchingLogShipper shipper = new BatchingLogShipper(sink(name, "/ok"), next)) {
            long deadline = System.currentTimeMillis() + 10_000;
            while (delivered.size() < expected && System.currentTimeMillis() < deadline) Thread.sleep(25);
            Thread.sleep(200); // room for a duplicate to show up
        }
        return new ArrayList<>(delivered);
    }

    private static Set<String> messagesIn(List<String> lines) {
        Set<String> out = new HashSet<>();
        for (String l : lines) {
            int i = l.indexOf("\"message\":\"");
            if (i < 0) continue;
            int start = i + "\"message\":\"".length();
            out.add(l.substring(start, l.indexOf('"', start)));
        }
        return out;
    }

    @Test
    void queuedRecordsAreSpilledAtShutdownAndReplayedOnTheNextStart(@TempDir Path dir) throws Exception {
        BatchingLogShipper shipper = new BatchingLogShipper(sink("DrainSpill", "/wedge"), withSpill(dir).build());
        shipper.offer(rec("held"));
        assertTrue(received.await(5, TimeUnit.SECONDS), "the flusher must reach the wedged send");
        for (int i = 0; i < 20; i++) shipper.offer(rec("q" + i));
        assertEquals(20, shipper.stats().queueDepth, "precondition: 20 records wait behind the wedged send");

        long t0 = System.nanoTime();
        shipper.close();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        BatchingLogShipper.Stats s = shipper.stats();
        SpillStore.SpillStats sp = shipper.spillStats();
        assertTrue(elapsedMs < 5_000, "close() must stay bounded, took " + elapsedMs + " ms");
        assertEquals(0, s.queueDepth, "nothing — not even a POISON sentinel — is left in the queue");
        assertEquals(21, s.accepted);
        assertEquals(0, s.dropped);
        assertEquals(21, sp.spilledRecords, "the interrupted in-flight record plus all 20 queued ones");
        assertEquals(0, sp.droppedRecords);
        assertEquals(s.accepted, s.sentRecords + s.dropped + sp.droppedRecords + sp.spilledRecords);
        CapturingDiagnostics.Entry warn = diag.first(Diagnostics.Level.WARN, "still queued");
        assertNotNull(warn, "the shutdown spill must be reported: " + diag.entries);
        assertTrue(warn.message.contains("spilled 20 of 20 record(s)"), warn.message);
        assertTrue(warn.message.contains("replayed on the next start"), warn.message);

        List<String> lines = replayOnNextStart(dir, "DrainSpill", 21);
        assertEquals(21, lines.size(), "every record is delivered once on the next start, none twice");
        Set<String> want = new HashSet<>();
        want.add("held");
        for (int i = 0; i < 20; i++) want.add("q" + i);
        assertEquals(want, messagesIn(lines));
        assertTrue(lines.stream().noneMatch(l -> l.contains("__BATCH_HTTP_POISON__")),
                "the POISON sentinel must never be spilled or shipped");
        assertEquals(0, segments(dir), "the replayed segments are deleted");
    }

    @Test
    void queuedRecordsAreCountedAsDroppedWithAnErrorWhenSpillIsDisabled() throws Exception {
        BatchingLogShipper shipper = new BatchingLogShipper(sink("DrainNoSpill", "/wedge"),
                base().spillEnabled(false).build());
        shipper.offer(rec("held"));
        assertTrue(received.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 20; i++) shipper.offer(rec("q" + i));

        shipper.close();

        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(0, s.queueDepth);
        assertEquals(21, s.accepted);
        assertEquals(21, s.dropped, "the interrupted in-flight record plus the 20 still queued");
        assertEquals(s.accepted, s.sentRecords + s.dropped);
        CapturingDiagnostics.Entry error = diag.first(Diagnostics.Level.ERROR, "still queued at shutdown");
        assertNotNull(error, "a shutdown loss must be reported: " + diag.entries);
        assertTrue(error.message.contains("dropped 20 record(s)"), error.message);
        assertTrue(error.message.contains("spill is disabled"), error.message);
    }

    @Test
    void drainWithSeveralFlushersNeverSpillsThePoison(@TempDir Path dir) throws Exception {
        received = new CountDownLatch(3);
        String name = "DrainThreeFlushers";
        BatchingLogShipper shipper = new BatchingLogShipper(sink(name, "/wedge"),
                withSpill(dir).flusherThreads(3).build());
        for (int i = 0; i < 3; i++) shipper.offer(rec("h" + i));
        assertTrue(received.await(5, TimeUnit.SECONDS), "each flusher must wedge on one record");
        for (int i = 0; i < 30; i++) shipper.offer(rec("q" + i));

        shipper.close();

        assertEquals(0, shipper.stats().queueDepth);
        assertEquals(33, shipper.stats().accepted);
        assertEquals(33, shipper.spillStats().spilledRecords);
        assertEquals(List.of(), liveThreads("batch-http-flusher-" + name), "every flusher has stopped");

        List<String> lines = replayOnNextStart(dir, name, 33);
        assertEquals(33, lines.size());
        assertEquals(33, messagesIn(lines).size(), "33 distinct records, none twice");
        assertTrue(lines.stream().noneMatch(l -> l.contains("__BATCH_HTTP_POISON__")));
    }

    @Test
    void aSpillRefusalAtShutdownIsCountedOnceNotTwice(@TempDir Path dir) throws Exception {
        BatchingLogShipper shipper = new BatchingLogShipper(sink("DrainRefused", "/wedge"), withSpill(dir).build());
        try {
            // The store exists and holds its lock; from now on it cannot create a segment.
            assumeFalse(!dir.toFile().setWritable(false) || Files.isWritable(dir),
                    "needs a filesystem that enforces permissions for this user (not root, not Windows)");
            shipper.offer(rec("held"));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) shipper.offer(rec("q" + i));

            shipper.close();

            BatchingLogShipper.Stats s = shipper.stats();
            SpillStore.SpillStats sp = shipper.spillStats();
            assertEquals(0, s.dropped, "a refusing spill counts in spillStats().droppedRecords only");
            assertEquals(21, sp.droppedRecords);
            assertEquals(0, sp.spilledRecords);
            assertEquals(s.accepted, s.sentRecords + s.dropped + sp.droppedRecords,
                    "each refused record is counted exactly once");
            // The flusher's held record logs its own "refused" ERROR first; check the drain's line.
            CapturingDiagnostics.Entry error = diag.first(Diagnostics.Level.ERROR, "still queued at shutdown");
            assertNotNull(error, diag.entries.toString());
            assertTrue(error.message.contains("dropped 20 record(s)"), error.message);
            assertTrue(error.message.contains("the spill buffer refused them"), error.message);
        } finally {
            dir.toFile().setWritable(true);
            shipper.close();
        }
    }

    @Test
    void withDeleteRecoveryTheQueuedRemainderIsDroppedNotHiddenOnDisk(@TempDir Path dir) throws Exception {
        // DELETE discards whatever is on disk at the next start, so spilling the remainder there
        // would only move the loss out of sight: it must be counted as dropped, with an ERROR.
        BatchingLogShipper shipper = new BatchingLogShipper(sink("DrainDelete", "/wedge"),
                withSpill(dir).spillRecoveryMode(BatchConfig.SpillRecoveryMode.DELETE).build());
        shipper.offer(rec("held"));
        assertTrue(received.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 20; i++) shipper.offer(rec("q" + i));

        shipper.close();

        BatchingLogShipper.Stats s = shipper.stats();
        SpillStore.SpillStats sp = shipper.spillStats();
        assertEquals(0, s.queueDepth);
        assertEquals(21, s.accepted);
        assertEquals(20, s.dropped, "the 20 still queued are dropped, not spilled");
        assertEquals(1, sp.spilledRecords, "only the in-flight record, whose one send failed, went to disk");
        assertEquals(s.accepted, s.sentRecords + s.dropped + sp.droppedRecords + sp.spilledRecords);
        CapturingDiagnostics.Entry error = diag.first(Diagnostics.Level.ERROR, "still queued at shutdown");
        assertNotNull(error, diag.entries.toString());
        assertTrue(error.message.contains("dropped 20 record(s)"), error.message);
        assertTrue(error.message.contains("spillRecoveryMode=DELETE would not replay them"), error.message);
        assertNull(diag.first(Diagnostics.Level.WARN, "replayed on the next start"),
                "nothing may claim a replay DELETE will not do: " + diag.entries);

        // The next start discards what is on disk and says so (INFO, from the shipper).
        diag.entries.clear();
        release.countDown();
        try (BatchingLogShipper next = new BatchingLogShipper(sink("DrainDelete", "/ok"),
                withSpill(dir).spillRecoveryMode(BatchConfig.SpillRecoveryMode.DELETE).build())) {
            CapturingDiagnostics.Entry info = diag.first(Diagnostics.Level.INFO, "spillRecoveryMode=DELETE discarded");
            assertNotNull(info, diag.entries.toString());
            assertTrue(info.message.contains("1 segment(s) (1 record(s)"), info.message);
            assertEquals(0, segments(dir));
            assertEquals(0, next.spillStats().droppedRecords, "a planned discard is not a spill drop");
        }
        assertTrue(delivered.isEmpty(), "DELETE never replays: " + delivered);
    }

    @RepeatedTest(5)
    void offersRacingCloseAreNeitherStrandedNorUncounted() throws Exception {
        BatchingLogShipper shipper = new BatchingLogShipper(sink("DrainRace", "/ok"), base()
                .spillEnabled(false)
                .queueCapacity(1_000)
                .maxBatchRecords(200)
                .shutdownDrainTimeout(Duration.ofMillis(100))
                .build());
        int producers = 8;
        List<Thread> threads = new ArrayList<>();
        final boolean[] stop = {false};
        for (int p = 0; p < producers; p++) {
            final int id = p;
            Thread t = new Thread(() -> {
                int i = 0;
                while (!stopped(stop)) shipper.offer(rec("p" + id + "-" + i++));
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        Thread.sleep(30);
        shipper.close();
        synchronized (stop) { stop[0] = true; }
        for (Thread t : threads) t.join(2_000);

        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(0, s.queueDepth, "a record accepted while close() ran must not be stranded in the queue");
        assertEquals(s.accepted, s.sentRecords + s.dropped, "every accepted record is sent or counted: " + s);
    }

    private static boolean stopped(boolean[] stop) {
        synchronized (stop) { return stop[0]; }
    }

    @Test
    void closePreservesTheCallersInterruptAndStillDrains(@TempDir Path dir) throws Exception {
        BatchingLogShipper shipper = new BatchingLogShipper(sink("DrainInterrupted", "/wedge"), withSpill(dir).build());
        shipper.offer(rec("held"));
        assertTrue(received.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 20; i++) shipper.offer(rec("q" + i));

        Thread.currentThread().interrupt();
        shipper.close();

        assertTrue(Thread.interrupted(), "close() must restore the caller's interrupt flag");
        assertEquals(0, shipper.stats().queueDepth);
        assertEquals(21, shipper.spillStats().spilledRecords,
                "an interrupted caller must not cut the joins short and seal the store early");
    }

    private static List<Thread> liveThreads(String marker) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        List<Thread> alive;
        do {
            alive = new ArrayList<>();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().contains(marker)) alive.add(t);
            }
            if (alive.isEmpty()) return alive;
            Thread.sleep(25);
        } while (System.currentTimeMillis() < deadline);
        return alive;
    }
}
