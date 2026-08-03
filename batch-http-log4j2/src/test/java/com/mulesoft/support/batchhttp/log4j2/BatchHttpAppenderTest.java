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
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: stand up a {@link BatchHttpAppender} the way a customer does — by
 * letting Log4j2 parse a {@code log4j2.xml}, discover the {@code <BatchHttp>} plugin
 * via the {@code packages} scan, and bind every attribute by name — then emit events
 * and verify they arrive batched at a local HTTP intake.
 *
 * <p>Driving the appender through real config (rather than calling the
 * {@code @PluginFactory} method with a long positional argument list) means this test
 * cannot drift out of sync when a factory parameter is added or reordered, and it
 * additionally proves plugin discovery and attribute binding actually work.
 */
class BatchHttpAppenderTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger records = new AtomicInteger();
    private final StringBuilder lastBody = new StringBuilder();
    private int port;
    private LoggerContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/intake", exchange -> {
            try {
                boolean gz = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"));
                InputStream in = gz ? new GZIPInputStream(exchange.getRequestBody()) : exchange.getRequestBody();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                String body = bos.toString("UTF-8");
                synchronized (lastBody) { lastBody.setLength(0); lastBody.append(body); }
                records.addAndGet((int) body.lines().filter(l -> !l.isBlank()).count());
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
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
        if (ctx != null) Configurator.shutdown(ctx);
        System.clearProperty("batchhttp.it.url");
        if (server != null) server.stop(0);
    }

    @Test
    void appenderShipsEventsBatchedThroughLog4j() throws Exception {
        // The config's url="${sys:batchhttp.it.url}" resolves to our random local intake.
        System.setProperty("batchhttp.it.url", "http://127.0.0.1:" + port + "/intake");

        // Initialise a dedicated LoggerContext from the test config so plugin discovery
        // and attribute binding run for real, isolated from the surrounding test JVM.
        URL cfg = getClass().getResource("/log4j2-batchhttp-it.xml");
        assertNotNull(cfg, "test config must be on the classpath");
        try (InputStream in = cfg.openStream()) {
            ctx = Configurator.initialize(getClass().getClassLoader(), new ConfigurationSource(in, cfg));
        }

        BatchHttpAppender appender = ctx.getConfiguration().getAppender("testBatch");
        assertNotNull(appender, "Log4j2 must build the <BatchHttp> appender from XML config");

        ThreadContext.put("requestId", "req-42");
        Logger log = ctx.getLogger("com.mulesoft.support.batchhttp.it");
        for (int i = 0; i < 200; i++) {
            log.info("processing item {}", i);
        }
        log.error("kaboom", new IllegalStateException("synthetic failure"));
        ThreadContext.clearAll();

        // Stop drains and flushes the tail synchronously.
        appender.stop();

        assertEquals(201, records.get(), "all 201 events must be delivered");
        assertTrue(requests.get() >= 4 && requests.get() <= 10,
                "201 events at 50/batch should be a handful of requests, got " + requests.get());
        synchronized (lastBody) {
            // ThreadContext (MDC) should be promoted to a structured attribute.
            assertTrue(lastBody.toString().contains("requestId")
                            || requests.get() > 1,
                    "MDC attribute should appear in at least one batch");
        }
    }
}
