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
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Startup-diagnostics and layout-decoding behavior of {@link BatchHttpAppender}, driven
 * through real Log4j2 configuration parsing.
 *
 * <p>Each test synthesizes its own {@code log4j2.xml} into a temp file rather than adding a
 * checked-in resource, because the point under test is precisely what a <em>misconfigured</em>
 * file does — one variant per vendor mistake.
 */
class BatchHttpAppenderConfigTest {

    /** Records every StatusLogger event: the appender's own diagnostics never flow through a Logger. */
    private static final class CapturingListener implements StatusListener {
        final List<StatusData> events = new CopyOnWriteArrayList<>();
        @Override public void log(StatusData data) { events.add(data); }
        @Override public Level getStatusLevel() { return Level.ALL; }
        @Override public void close() { }

        /** The formatted text of every ERROR the appender emitted, joined for assertion. */
        String errors() {
            StringBuilder sb = new StringBuilder();
            for (StatusData d : events) {
                if (d.getLevel() == Level.ERROR) sb.append(d.getMessage().getFormattedMessage()).append('\n');
            }
            return sb.toString();
        }
    }

    private CapturingListener listener;
    private LoggerContext ctx;
    private HttpServer server;
    private final StringBuilder body = new StringBuilder();

    @BeforeEach
    void setUp() {
        listener = new CapturingListener();
        StatusLogger.getLogger().registerListener(listener);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) Configurator.shutdown(ctx);
        StatusLogger.getLogger().removeListener(listener);
        System.clearProperty("batchhttp.cfg.url");
        if (server != null) server.stop(0);
    }

    /** Parse {@code xml} as a Log4j2 configuration, exactly as the runtime would parse a customer's file. */
    private LoggerContext configure(Path dir, String xml) throws Exception {
        Path file = dir.resolve("log4j2-generated.xml");
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        URL url = file.toUri().toURL();
        try (InputStream in = Files.newInputStream(file)) {
            ctx = Configurator.initialize(getClass().getClassLoader(), new ConfigurationSource(in, url));
        }
        return ctx;
    }

    private static String config(String appenderXml) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Configuration status=\"OFF\" packages=\"com.mulesoft.support.batchhttp.log4j2\">\n"
                + "  <Appenders>\n" + appenderXml + "  </Appenders>\n"
                + "  <Loggers>\n"
                + "    <Logger name=\"com.mulesoft.support.batchhttp.cfg\" level=\"INFO\" additivity=\"false\">\n"
                + "      <AppenderRef ref=\"underTest\"/>\n"
                + "    </Logger>\n"
                + "    <Root level=\"OFF\"/>\n"
                + "  </Loggers>\n"
                + "</Configuration>\n";
    }

    // ------------------------------------------------------------------
    // Missing required credentials must fail loudly, and say what is missing.
    // ------------------------------------------------------------------

    @Test
    void datadogWithoutAnApiKeyFailsToBuildAndNamesTheMissingCredential(@TempDir Path dir) throws Exception {
        LoggerContext c = configure(dir, config(
                "    <BatchHttp name=\"underTest\" vendor=\"datadog\" service=\"orders\">\n"
                        + "      <PatternLayout pattern=\"%m\"/>\n"
                        + "    </BatchHttp>\n"));

        assertNull(c.getConfiguration().getAppender("underTest"),
                "an appender with no apiKey must not be built");

        String errors = listener.errors();
        assertTrue(errors.contains("apiKey"),
                "the diagnostic must name the missing credential, got:\n" + errors);
        assertTrue(errors.contains("datadog"),
                "the diagnostic must name the vendor, got:\n" + errors);
        assertTrue(errors.contains("underTest"),
                "the diagnostic must name the appender so the right element is found in log4j2.xml, got:\n" + errors);
    }

    @Test
    void splunkWithoutUrlOrTokenFailsToBuildAndNamesBothRequiredAttributes(@TempDir Path dir) throws Exception {
        LoggerContext c = configure(dir, config(
                "    <BatchHttp name=\"underTest\" vendor=\"splunk\" index=\"main\">\n"
                        + "      <PatternLayout pattern=\"%m\"/>\n"
                        + "    </BatchHttp>\n"));

        assertNull(c.getConfiguration().getAppender("underTest"),
                "an appender with no url and no token must not be built");

        String errors = listener.errors();
        // Named as they appear in log4j2.xml — the builder's own vocabulary ("baseUrl") is no
        // help to someone searching their config file for the attribute to add.
        assertTrue(errors.contains("url") && errors.contains("token"),
                "the diagnostic must name both required attributes, got:\n" + errors);
        assertTrue(errors.contains("splunk"),
                "the diagnostic must name the vendor, got:\n" + errors);
    }

    @Test
    void elasticsearchWithoutUrlOrApiKeyFailsToBuildAndNamesBothRequiredAttributes(@TempDir Path dir) throws Exception {
        LoggerContext c = configure(dir, config(
                "    <BatchHttp name=\"underTest\" vendor=\"elasticsearch\" service=\"orders\">\n"
                        + "      <PatternLayout pattern=\"%m\"/>\n"
                        + "    </BatchHttp>\n"));

        assertNull(c.getConfiguration().getAppender("underTest"),
                "an appender with no url and no apiKey must not be built");

        String errors = listener.errors();
        assertTrue(errors.contains("url") && errors.contains("apiKey"),
                "the diagnostic must name both required attributes, got:\n" + errors);
        assertTrue(errors.contains("elasticsearch"),
                "the diagnostic must name the vendor, got:\n" + errors);
    }

    /** {@code vendor="elk"} posts _bulk to {@code <url>/<index>/_bulk} with an ApiKey header. */
    @Test
    void elkAliasShipsBulkToTheConfiguredDataStream(@TempDir Path dir) throws Exception {
        StringBuilder auth = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/logs-orders-test/_bulk", exchange -> {
            try {
                String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (body) {
                    body.append(received);
                    auth.append(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                byte[] ok = "{\"errors\":false}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.setProperty("batchhttp.cfg.url", "http://127.0.0.1:" + server.getAddress().getPort());

        LoggerContext c = configure(dir, config(
                "    <BatchHttp name=\"underTest\" vendor=\"elk\" url=\"${sys:batchhttp.cfg.url}\"\n"
                        + "               apiKey=\"encoded-key\" index=\"logs-orders-test\" service=\"orders\"\n"
                        + "               lingerMillis=\"100\" gzip=\"false\" spillEnabled=\"false\">\n"
                        + "      <PatternLayout pattern=\"%m\"/>\n"
                        + "    </BatchHttp>\n"));

        BatchHttpAppender appender = c.getConfiguration().getAppender("underTest");
        assertNotNull(appender, "the appender must build");
        c.getLogger("com.mulesoft.support.batchhttp.cfg").info("order placed");
        appender.stop();

        synchronized (body) {
            assertEquals("ApiKey encoded-key", auth.toString());
            assertTrue(body.toString().startsWith("{\"create\":{}}\n{\"@timestamp\":"), body.toString());
            assertTrue(body.toString().contains("\"message\":\"order placed\""), body.toString());
            assertTrue(body.toString().contains("\"service.name\":\"orders\""), body.toString());
        }
    }

    @Test
    void aValidConfigStillBuildsAndLogsNoStartupError(@TempDir Path dir) throws Exception {
        LoggerContext c = configure(dir, config(
                "    <BatchHttp name=\"underTest\" vendor=\"datadog\" apiKey=\"secret\" service=\"orders\"\n"
                        + "               spillEnabled=\"false\">\n"
                        + "      <PatternLayout pattern=\"%m\"/>\n"
                        + "    </BatchHttp>\n"));

        assertNotNull(c.getConfiguration().getAppender("underTest"),
                "a complete config must still build; the new failure path must not be over-eager");
        assertFalse(listener.errors().contains("could not build"),
                "a healthy config must produce no sink-build error");
    }

    // ------------------------------------------------------------------
    // Layout decoding: what reaches the sink must be the text the layout rendered.
    // ------------------------------------------------------------------

    /** Stand up an intake that captures one decoded request body. */
    private void startIntake() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/intake", exchange -> {
            try {
                boolean gz = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"));
                InputStream in = gz ? new GZIPInputStream(exchange.getRequestBody()) : exchange.getRequestBody();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                synchronized (body) { body.append(bos.toString(StandardCharsets.UTF_8)); }
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.setProperty("batchhttp.cfg.url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/intake");
    }

    /** Ship one message through a PatternLayout carrying {@code charsetAttr} and return the intake body. */
    private String shipThrough(Path dir, String charsetAttr, String message) throws Exception {
        startIntake();
        LoggerContext c = configure(dir, config(
                "    <BatchHttp name=\"underTest\" vendor=\"generic\" url=\"${sys:batchhttp.cfg.url}\"\n"
                        + "               lingerMillis=\"100\" maxBatchRecords=\"10\"\n"
                        // Spill defaults to on; off here so the test never creates a spill
                        // directory under the JVM temp dir.
                        + "               spillEnabled=\"false\">\n"
                        + "      <PatternLayout pattern=\"%m%n\"" + charsetAttr + "/>\n"
                        + "    </BatchHttp>\n"));

        BatchHttpAppender appender = c.getConfiguration().getAppender("underTest");
        assertNotNull(appender, "the appender must build");
        Logger log = c.getLogger("com.mulesoft.support.batchhttp.cfg");
        log.info(message);
        appender.stop(); // drains and flushes synchronously
        synchronized (body) { return body.toString(); }
    }

    /** Multi-byte text under the default (UTF-8) layout charset — the common path; must be byte-identical. */
    @Test
    void multiByteTextSurvivesTheDefaultUtf8Layout(@TempDir Path dir) throws Exception {
        String message = "café ☃ 日本語 🚀 done";
        String received = shipThrough(dir, "", message);
        assertTrue(received.contains(message),
                "multi-byte text must reach the sink intact, got:\n" + received);
    }

    /**
     * A layout configured with a non-UTF-8 charset. The layout's charset governs bytes written
     * to a file or socket; this appender ships UTF-8 JSON, so the text the layout rendered is
     * what must arrive. Encoding it to Latin-1 and decoding those bytes back as UTF-8 —
     * as the old {@code toByteArray} round-trip did — mangles every non-ASCII character.
     */
    @Test
    void nonUtf8LayoutCharsetDoesNotCorruptTheMessage(@TempDir Path dir) throws Exception {
        String message = "café ☃ 日本語 🚀 done";
        String received = shipThrough(dir, " charset=\"ISO-8859-1\"", message);
        assertFalse(received.contains("\uFFFD"),
                "no character may be replaced by U+FFFD, got:\n" + received);
        assertTrue(received.contains(message),
                "the rendered text must reach the sink intact regardless of the layout charset, got:\n" + received);
    }

    /** The trailing newline a {@code %n} adds is still stripped, and interior spacing is untouched. */
    @Test
    void trailingLayoutNewlineIsStrippedButTheMessageIsNot(@TempDir Path dir) throws Exception {
        String received = shipThrough(dir, "", "  padded message  ");
        assertTrue(received.contains("\"message\":\"  padded message  \""),
                "leading/trailing spaces must survive while the layout's newline is dropped, got:\n" + received);
    }
}
