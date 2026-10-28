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
package com.mulesoft.support.batchhttp.log4j2;

import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine's health messages are logged through {@code com.mulesoft.support.batchhttp.diagnostics.<name>},
 * so the root logger's other appenders (a platform's log view) receive them, their level switches
 * them on and off, and no {@code <BatchHttp>} appender on the same root logger ever ships them —
 * neither its own nor another appender's — even when every batch is rejected and each rejection
 * produces another diagnostic.
 */
class DiagnosticsLoggerTest {

    private static final String DIAG = LoggerDiagnostics.PREFIX;
    private static final String TEST_DIAG = DIAG + ".testBatch";
    private static final String OTHER_DIAG = DIAG + ".otherBatch";

    /** Stands in for a platform appender attached to root after deployment. */
    private static final class Capturing extends AbstractAppender {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        Capturing() { super("capture", null, null, true, Property.EMPTY_ARRAY); }
        @Override public void append(LogEvent event) { events.add(event.toImmutable()); }

        boolean any(String logger, Level level, String substring) {
            return events.stream().anyMatch(e -> logger.equals(e.getLoggerName()) && e.getLevel() == level
                    && e.getMessage().getFormattedMessage().contains(substring));
        }

        long count(String logger) {
            return events.stream().filter(e -> logger.equals(e.getLoggerName())).count();
        }
    }

    /** An intake that rejects every batch with 400 (not retryable: dropped and reported at ERROR). */
    private static final class Intake {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final List<String> bodies = new CopyOnWriteArrayList<>();

        Intake() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/intake", exchange -> {
                try {
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(400, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/intake"; }
    }

    private Intake intake;
    private Intake other;
    private LoggerContext ctx;
    private Capturing capture;

    @BeforeEach
    void setUp() throws Exception {
        intake = new Intake();
        other = new Intake();
        System.setProperty("batchhttp.it.url", intake.url());
        System.setProperty("batchhttp.it.url2", other.url());
        URL cfg = getClass().getResource("/log4j2-batchhttp-diaglogger.xml");
        assertNotNull(cfg, "test config must be on the classpath");
        try (InputStream in = cfg.openStream()) {
            ctx = Configurator.initialize(getClass().getClassLoader(), new ConfigurationSource(in, cfg));
        }
        capture = new Capturing();
        capture.start();
        ctx.getConfiguration().getRootLogger().addAppender(capture, Level.ALL, null);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) Configurator.shutdown(ctx);
        System.clearProperty("batchhttp.it.url");
        System.clearProperty("batchhttp.it.url2");
        if (intake != null) intake.server.stop(0);
        if (other != null) other.server.stop(0);
    }

    private void logAndWaitForDiagnostics() throws InterruptedException {
        Logger log = ctx.getLogger("com.example.app");
        for (int i = 0; i < 20; i++) log.info("app event {}", i);
        // Let the flushers hit the 400s and the stats threads tick a few times.
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && !(capture.any(TEST_DIAG, Level.ERROR, "dropped") && capture.any(TEST_DIAG, Level.INFO, "stats")
                     && capture.any(OTHER_DIAG, Level.ERROR, "dropped") && capture.any(OTHER_DIAG, Level.INFO, "stats"))) {
            Thread.sleep(50);
        }
        Thread.sleep(300); // a few more heartbeats: none of them may be shipped
    }

    private static void assertOnlyAppEventsShipped(BatchHttpAppender appender, Intake intake) {
        assertEquals(20, appender.stats().accepted, "only the 20 application events may enter the queue");
        for (String body : intake.bodies) {
            assertFalse(body.contains("batch-http["), "a diagnostic was shipped: " + body);
        }
        assertTrue(intake.requests.get() >= 4 && intake.requests.get() <= 5,
                "20 events at 5/batch, no diagnostics: expected 4 requests, got " + intake.requests.get());
    }

    @Test
    void diagnosticsReachRootAppendersButAreNeverShipped() throws Exception {
        BatchHttpAppender test = ctx.getConfiguration().getAppender("testBatch");
        BatchHttpAppender otherAppender = ctx.getConfiguration().getAppender("otherBatch");

        logAndWaitForDiagnostics();
        test.stop();
        otherAppender.stop();

        // No diagnosticsLevel on otherBatch (StatusLogger copy at WARN): its INFO heartbeat comes
        // from the logger level alone, inherited from root.
        for (String diag : new String[] {TEST_DIAG, OTHER_DIAG}) {
            assertTrue(capture.any(diag, Level.ERROR, "dropped"), "the drop must be logged through " + diag);
            assertTrue(capture.any(diag, Level.INFO, "stats"), "the stats heartbeat must be logged through " + diag);
        }
        assertOnlyAppEventsShipped(test, intake);
        assertOnlyAppEventsShipped(otherAppender, other);
    }

    @Test
    void loggerLevelSwitchesTheCopyOffAndOnWhileRunning() throws Exception {
        BatchHttpAppender test = ctx.getConfiguration().getAppender("testBatch");

        Configurator.setLevel(DIAG, Level.OFF);          // every appender
        Configurator.setLevel(OTHER_DIAG, Level.WARN);   // one appender, overriding the parent
        logAndWaitForDiagnostics();
        assertEquals(0, capture.count(TEST_DIAG), "level OFF must silence the logger copy");
        assertTrue(capture.any(OTHER_DIAG, Level.ERROR, "dropped"), "the child level must win over the parent");
        assertFalse(capture.any(OTHER_DIAG, Level.INFO, "stats"), "WARN must hide the INFO heartbeat");

        Configurator.setLevel(DIAG, Level.INFO);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !capture.any(TEST_DIAG, Level.INFO, "stats")) {
            Thread.sleep(50);
        }
        assertTrue(capture.any(TEST_DIAG, Level.INFO, "stats"), "raising the level must switch the copy back on");
        test.stop();
    }
}
