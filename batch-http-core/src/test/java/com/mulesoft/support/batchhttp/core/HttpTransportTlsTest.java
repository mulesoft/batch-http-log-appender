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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the {@link HttpTransport} TLS escape hatches against a real in-JVM HTTPS
 * server presenting a <em>self-signed</em> certificate the JVM trust store does not know.
 *
 * <p>The two knobs are genuinely independent in the JDK {@link java.net.http.HttpClient}:
 * a trust-all {@link SSLContext} defeats <b>chain</b> validation (self-signed → accepted),
 * while <b>hostname</b> verification is governed only by the JVM-global
 * {@code jdk.internal.httpclient.disableHostnameVerification} property. Since the server here
 * is reached over {@code 127.0.0.1} and the cert's SAN is {@code 127.0.0.1}, hostname
 * verification passes regardless, so these tests isolate the chain-trust knob.
 */
class HttpTransportTlsTest {

    private static final String HOSTNAME_VERIFY_PROP = "jdk.internal.httpclient.disableHostnameVerification";

    private static HttpsServer server;
    private static int port;
    private static SSLContext serverCtx;
    private String savedHostnameProp;

    @BeforeAll
    static void startServer(@TempDir Path tmp) throws Exception {
        serverCtx = selfSignedServerContext(tmp);
        assumeTrue(serverCtx != null, "keytool unavailable — cannot generate a self-signed cert for the TLS test");

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        port = server.getAddress().getPort();
        server.createContext("/intake", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void saveHostnameProp() {
        // A test that flips tlsVerifyHostName=false sets this JVM-global property; snapshot its
        // prior value so we can restore it exactly (rather than blindly clearing, which would
        // clobber a -D arg an outer runner may have set) and never leak state to other test classes.
        savedHostnameProp = System.getProperty(HOSTNAME_VERIFY_PROP);
    }

    @AfterEach
    void restoreHostnameProp() {
        if (savedHostnameProp == null) {
            System.clearProperty(HOSTNAME_VERIFY_PROP);
        } else {
            System.setProperty(HOSTNAME_VERIFY_PROP, savedHostnameProp);
        }
    }

    private LogSink sink() {
        return GenericNdjsonSink.builder()
                .url("https://127.0.0.1:" + port + "/intake")
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .build();
    }

    private static LogRecord record() {
        return LogRecord.builder()
                .timestampMillis(1700000000123L)
                .level("INFO").loggerName("tls.test").threadName("main").message("hello").build();
    }

    @Test
    void defaultConfigRejectsSelfSignedCert() {
        HttpTransport transport = new HttpTransport(BatchConfig.builder().gzip(false).build());
        HttpTransport.Outcome outcome = transport.send(sink(), body());
        // Untrusted chain → handshake fails → no HTTP response → RETRYABLE with status 0.
        assertEquals(HttpTransport.Result.RETRYABLE, outcome.result,
                "default (secure) transport must not trust a self-signed cert, got: " + outcome.detail);
        assertEquals(0, outcome.statusCode, "handshake failure yields no HTTP status");
    }

    @Test
    void trustAllCertificatesAcceptsSelfSignedCert() {
        HttpTransport transport = new HttpTransport(
                BatchConfig.builder().gzip(false).tlsTrustAllCertificates(true).build());
        HttpTransport.Outcome outcome = transport.send(sink(), body());
        assertEquals(HttpTransport.Result.SUCCESS, outcome.result,
                "tlsTrustAllCertificates=true must accept the self-signed cert, got: " + outcome.detail);
        assertEquals(200, outcome.statusCode);
    }

    /**
     * The hostname-verification knob has no per-client lever in the JDK {@link java.net.http.HttpClient};
     * building a transport with {@code tlsVerifyHostName=false} must set the JVM-global
     * {@code jdk.internal.httpclient.disableHostnameVerification} property. We assert that observable
     * contract rather than a wire-level effect on purpose: the JDK reads the property <em>once</em> per
     * JVM (at first HTTPS use), so within a shared Surefire fork — where earlier tests here already made
     * HTTPS calls — a wire assertion would be order-dependent and flaky. The actual bypass behavior was
     * verified empirically against a wrong-host cert during design.
     */
    @Test
    void verifyHostNameFalseSetsGlobalDisableProperty() {
        System.clearProperty(HOSTNAME_VERIFY_PROP); // @AfterEach restores the fork's baseline
        assertNull(System.getProperty(HOSTNAME_VERIFY_PROP));

        new HttpTransport(BatchConfig.builder().gzip(false).tlsVerifyHostName(false).build());
        assertEquals("true", System.getProperty(HOSTNAME_VERIFY_PROP),
                "tlsVerifyHostName=false must set the JVM-global hostname-verification switch");
    }

    @Test
    void defaultConfigLeavesHostnamePropertyUntouched() {
        System.clearProperty(HOSTNAME_VERIFY_PROP);
        new HttpTransport(BatchConfig.builder().gzip(false).build()); // secure default
        assertNull(System.getProperty(HOSTNAME_VERIFY_PROP),
                "a secure-default transport must not touch the JVM-global hostname switch");
    }

    private byte[] body() {
        return sink().serialize(List.of(record()));
    }

    /**
     * Generate a throwaway self-signed keypair with {@code keytool} and load it into an
     * {@link SSLContext} for the server side. Returns null if keytool is not on the PATH,
     * letting the caller skip the test rather than fail spuriously.
     */
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
        int exit = p.waitFor();
        if (exit != 0 || !Files.exists(ksPath)) {
            return null; // keytool missing/failed — signal skip
        }

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
