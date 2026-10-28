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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
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
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The diagnostics logger and its loop guard do not depend on the vendor: with one appender per
 * vendor on the root logger, each one's drop and heartbeat reach the root logger's other
 * appenders, and no appender ships a diagnostic, its own or another vendor's.
 */
class DiagnosticsLoggerVendorsTest {

    /** Appender name → vendor, as in {@code log4j2-batchhttp-diaglogger-vendors.xml}. */
    private static final Map<String, String> APPENDERS = new LinkedHashMap<>();
    static {
        APPENDERS.put("dd", "datadog");
        APPENDERS.put("splunk", "splunk");
        APPENDERS.put("nr", "newrelic");
        APPENDERS.put("dt", "dynatrace");
        APPENDERS.put("es", "elasticsearch");
        APPENDERS.put("gen", "generic");
    }

    /** Stands in for a platform appender attached to root after deployment. */
    private static final class Capturing extends AbstractAppender {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        Capturing() { super("capture", null, null, true, Property.EMPTY_ARRAY); }
        @Override public void append(LogEvent event) { events.add(event.toImmutable()); }

        boolean any(String logger, Level level, String substring) {
            return events.stream().anyMatch(e -> logger.equals(e.getLoggerName()) && e.getLevel() == level
                    && e.getMessage().getFormattedMessage().contains(substring));
        }
    }

    /** A self-signed HTTPS intake that rejects every batch with 400 (dropped, reported at ERROR). */
    private static final class Intake {
        final HttpsServer server;
        final AtomicInteger requests = new AtomicInteger();
        final List<String> bodies = new CopyOnWriteArrayList<>();

        Intake(SSLContext tls) throws Exception {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            server.createContext("/", exchange -> {
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

        String hostPort() { return "127.0.0.1:" + server.getAddress().getPort(); }
    }

    private final Map<String, Intake> intakes = new LinkedHashMap<>();
    private LoggerContext ctx;
    private Capturing capture;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        SSLContext tls = selfSignedServerContext(tmp);
        assumeTrue(tls != null, "keytool unavailable — cannot generate a self-signed cert");
        for (Map.Entry<String, String> a : APPENDERS.entrySet()) {
            Intake intake = new Intake(tls);
            intakes.put(a.getKey(), intake);
            String vendor = a.getValue();
            // datadog/newrelic take a host and build https://<host>/... themselves.
            boolean hostOnly = vendor.equals("datadog") || vendor.equals("newrelic");
            System.setProperty("batchhttp.vt." + vendor,
                    hostOnly ? intake.hostPort() : "https://" + intake.hostPort() + "/intake");
        }
        URL cfg = getClass().getResource("/log4j2-batchhttp-diaglogger-vendors.xml");
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
        for (String vendor : APPENDERS.values()) System.clearProperty("batchhttp.vt." + vendor);
        for (Intake intake : intakes.values()) intake.server.stop(0);
    }

    private boolean allReported() {
        for (String name : APPENDERS.keySet()) {
            String diag = LoggerDiagnostics.PREFIX + "." + name;
            if (!capture.any(diag, Level.ERROR, "dropped") || !capture.any(diag, Level.INFO, "stats")) return false;
        }
        return true;
    }

    @Test
    void everyVendorLogsItsDiagnosticsAndShipsNone() throws Exception {
        Logger log = ctx.getLogger("com.example.app");
        for (int i = 0; i < 20; i++) log.info("app event {}", i);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !allReported()) Thread.sleep(50);
        Thread.sleep(300); // a few more heartbeats from six appenders: none of them may be shipped
        for (String name : APPENDERS.keySet()) {
            ctx.getConfiguration().<BatchHttpAppender>getAppender(name).stop();
        }

        for (Map.Entry<String, String> a : APPENDERS.entrySet()) {
            String name = a.getKey();
            String vendor = a.getValue();
            String diag = LoggerDiagnostics.PREFIX + "." + name;
            BatchHttpAppender appender = ctx.getConfiguration().getAppender(name);
            Intake intake = intakes.get(name);

            assertTrue(capture.any(diag, Level.ERROR, "dropped"), vendor + ": the drop must be logged through " + diag);
            assertTrue(capture.any(diag, Level.INFO, "stats"), vendor + ": the heartbeat must be logged through " + diag);
            assertEquals(20, appender.stats().accepted, vendor + ": only the 20 application events may enter the queue");
            for (String body : intake.bodies) {
                assertFalse(body.contains("batch-http["), vendor + ": a diagnostic was shipped: " + body);
            }
            assertTrue(intake.requests.get() >= 4 && intake.requests.get() <= 5,
                    vendor + ": 20 events at 5/batch, no diagnostics: expected 4 requests, got " + intake.requests.get());
        }
    }

    /** Same approach as {@code HttpTransportTlsTest}: a throwaway PKCS12 key pair for 127.0.0.1. */
    private static SSLContext selfSignedServerContext(Path tmp) throws Exception {
        Path ksPath = tmp.resolve("server.p12");
        char[] pass = "changeit".toCharArray();
        Process p = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", ksPath.toString(),
                "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-validity", "1")
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0 || !Files.exists(ksPath)) return null;

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ksPath)) {
            ks.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new java.security.SecureRandom());
        return ctx;
    }
}
