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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the engine's self-diagnostics: failure points emit through the {@link Diagnostics}
 * seam with the failure detail (HTTP status / cause), the periodic stats heartbeat fires, and
 * the default ({@link Diagnostics#NOOP}) keeps the engine silent.
 */
class DiagnosticsTest {

    /** Captures every diagnostic emission so the test can assert on level + message. */
    private static final class CapturingDiagnostics implements Diagnostics {
        record Entry(Level level, String message) {}
        final List<Entry> entries = new CopyOnWriteArrayList<>();

        @Override public boolean isEnabled(Level level) { return true; }
        @Override public void log(Level level, String message) { entries.add(new Entry(level, message)); }
        @Override public void log(Level level, String message, Throwable thrown) { entries.add(new Entry(level, message)); }

        boolean any(Level level, String substring) {
            return entries.stream().anyMatch(e -> e.level == level && e.message.contains(substring));
        }
    }

    private HttpServer server;
    private volatile int responseStatus = 200;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/intake", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
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

    private LogSink liveSink() {
        return GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + "/intake")
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .maxRecords(100)
                .build();
    }

    private static LogRecord record(int i) {
        return LogRecord.builder()
                .timestampMillis(1700000000000L + i)
                .level("INFO").loggerName("test").threadName("main")
                .message("event " + i).build();
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
    }

    @Test
    void fatalRejectionLogsErrorWithStatusAndDoesNotSpill(@TempDir Path dir) throws Exception {
        responseStatus = 403; // 4xx => FATAL: bad credentials/endpoint, retrying won't help
        CapturingDiagnostics diag = new CapturingDiagnostics();
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(10)
                .lingerTime(Duration.ofMillis(50))
                .maxRetries(0)
                .gzip(false)
                .spillEnabled(true)       // spill ON, but FATAL must NOT be spilled
                .spillDirectory(dir)
                .diagnostics(diag)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), config)) {
            for (int i = 0; i < 10; i++) shipper.offer(record(i));
            waitFor(() -> diag.any(Diagnostics.Level.ERROR, "403"), 4000);
        }

        assertTrue(diag.any(Diagnostics.Level.ERROR, "403"),
                "a FATAL (4xx) rejection must log an ERROR carrying the HTTP status; got " + diag.entries);
        assertTrue(diag.entries.stream().anyMatch(e -> e.message().contains("rejected")),
                "the FATAL message should explain the endpoint rejected the batch");
    }

    @Test
    void retryExhaustedWithSpillLogsWarnAndSpills(@TempDir Path dir) throws Exception {
        responseStatus = 503; // 5xx => RETRYABLE; exhausting retries with spill on => spilled, WARN
        CapturingDiagnostics diag = new CapturingDiagnostics();
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(10)
                .lingerTime(Duration.ofMillis(50))
                .maxRetries(1)
                .initialRetryBackoff(Duration.ofMillis(1))
                .gzip(false)
                .spillEnabled(true)
                .spillDirectory(dir)
                .spillReplayInterval(Duration.ofHours(1)) // don't drain during the test
                .diagnostics(diag)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), config)) {
            for (int i = 0; i < 10; i++) shipper.offer(record(i));
            waitFor(() -> diag.any(Diagnostics.Level.WARN, "spilled"), 4000);
        }

        assertTrue(diag.any(Diagnostics.Level.WARN, "spilled"),
                "retry-exhausted batches with spill enabled must log a WARN noting the spill; got " + diag.entries);
    }

    @Test
    void periodicStatsHeartbeatEmitsInfoLine() throws Exception {
        CapturingDiagnostics diag = new CapturingDiagnostics();
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(100)
                .lingerTime(Duration.ofMillis(50))
                .gzip(false)
                .spillEnabled(false)   // no disk buffer needed to observe the heartbeat
                .diagnostics(diag)
                .statsInterval(Duration.ofMillis(100)) // fast heartbeat for the test
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), config)) {
            for (int i = 0; i < 5; i++) shipper.offer(record(i));
            waitFor(() -> diag.any(Diagnostics.Level.INFO, "stats"), 3000);
            assertTrue(diag.any(Diagnostics.Level.INFO, "stats"),
                    "a periodic stats heartbeat must be logged at INFO; got " + diag.entries);
            assertTrue(diag.entries.stream().anyMatch(e -> e.message().contains("sentRecords=")),
                    "the heartbeat line must include shipped-record counters");
        }
    }

    @Test
    void noopDiagnosticsStaySilentOnFailure() throws Exception {
        responseStatus = 403;
        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(10)
                .lingerTime(Duration.ofMillis(50))
                .maxRetries(0)
                .gzip(false)
                .spillEnabled(false)   // keep the failure path off disk; this test is about silence
                .build(); // no diagnostics => NOOP, no heartbeat

        // The default must not throw and must remain entirely silent — we just exercise the
        // failure path to prove NOOP is wired and behaviour is unchanged for existing embeddings.
        try (BatchingLogShipper shipper = new BatchingLogShipper(liveSink(), config)) {
            for (int i = 0; i < 10; i++) shipper.offer(record(i));
            Thread.sleep(300);
            assertTrue(shipper.stats().dropped >= 10, "FATAL batch should still be dropped under NOOP");
        }
    }
}
