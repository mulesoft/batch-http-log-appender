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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives real HTTP status codes through {@link HttpTransport} to pin down its
 * {@link HttpTransport#classify status classification} — in particular the 408/429
 * carve-out (retryable, not fatal) and the boundary between retryable 5xx and terminal
 * 4xx. A regression here (e.g. 429 → FATAL) would silently drop retryable rate-limit
 * responses instead of backing off, so it is asserted directly at the transport layer.
 */
class HttpTransportClassifyTest {

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

    private LogSink sink() {
        return GenericNdjsonSink.builder()
                .url("http://127.0.0.1:" + port + "/intake")
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .build();
    }

    private HttpTransport.Result resultFor(int status) {
        responseStatus = status;
        HttpTransport transport = new HttpTransport(BatchConfig.builder().gzip(false).build());
        LogSink sink = sink();
        byte[] body = sink.serialize(List.of(LogRecord.builder()
                .timestampMillis(1700000000000L).level("INFO")
                .loggerName("classify.test").threadName("main").message("x").build()));
        return transport.send(sink, body).result;
    }

    @Test
    void success2xxIsSuccess() {
        assertEquals(HttpTransport.Result.SUCCESS, resultFor(200));
        assertEquals(HttpTransport.Result.SUCCESS, resultFor(202));
    }

    @Test
    void requestTimeout408IsRetryable() {
        assertEquals(HttpTransport.Result.RETRYABLE, resultFor(408),
                "408 Request Timeout must be retryable, not fatal");
    }

    @Test
    void tooManyRequests429IsRetryable() {
        assertEquals(HttpTransport.Result.RETRYABLE, resultFor(429),
                "429 Too Many Requests must be retryable so the engine backs off instead of dropping");
    }

    @Test
    void serverErrors5xxAreRetryable() {
        assertEquals(HttpTransport.Result.RETRYABLE, resultFor(500));
        assertEquals(HttpTransport.Result.RETRYABLE, resultFor(503));
    }

    @Test
    void otherClientErrors4xxAreFatal() {
        // Bad request / auth / payload-too-large: retrying the same body won't help.
        assertEquals(HttpTransport.Result.FATAL, resultFor(400));
        assertEquals(HttpTransport.Result.FATAL, resultFor(403));
        assertEquals(HttpTransport.Result.FATAL, resultFor(413));
    }
}
