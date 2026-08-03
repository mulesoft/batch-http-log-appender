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
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rate-limited intakes do not merely say "try later", they say <em>when</em>: Datadog's Logs
 * API answers a throttled burst with {@code 429} plus a {@code Retry-After}. Ignoring that
 * header and retrying on our own (much shorter) exponential schedule just earns another 429 and
 * burns the retry budget, after which a batch that would have succeeded is spilled or dropped.
 * These tests pin both the parsing and the fact that the engine actually waits.
 */
class HttpTransportRetryAfterTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
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

    // ---- header parsing ----

    @Test
    void parsesDeltaSecondsForm() {
        assertEquals(0L, HttpTransport.parseRetryAfter("0"));
        assertEquals(2_000L, HttpTransport.parseRetryAfter("2"));
        assertEquals(120_000L, HttpTransport.parseRetryAfter(" 120 "));
    }

    @Test
    void parsesHttpDateForm() {
        String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30));
        long millis = HttpTransport.parseRetryAfter(future);
        assertTrue(millis > 20_000 && millis <= 31_000,
                "an HTTP-date 30s out should yield ~30000ms, got " + millis);
    }

    @Test
    void rejectsAbsentPastOrMalformedValues() {
        assertEquals(-1L, HttpTransport.parseRetryAfter(null), "absent header");
        assertEquals(-1L, HttpTransport.parseRetryAfter(""), "empty header");
        assertEquals(-1L, HttpTransport.parseRetryAfter("soon"), "unparseable header");
        assertEquals(-1L, HttpTransport.parseRetryAfter("-5"), "negative delta-seconds");
        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        assertEquals(-1L, HttpTransport.parseRetryAfter(past), "a date already in the past");
    }

    // ---- surfaced on the Outcome ----

    @Test
    void retryAfterIsSurfacedOnA429AndAbsentOnSuccess() {
        server.createContext("/throttle", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Retry-After", "3");
                exchange.sendResponseHeaders(429, -1);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/fine", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });

        try (HttpTransport transport = new HttpTransport(BatchConfig.builder().gzip(false).build())) {
            LogSink throttled = sink("/throttle");
            HttpTransport.Outcome limited = transport.send(throttled, throttled.serialize(List.of(rec("x"))));
            assertEquals(HttpTransport.Result.RETRYABLE, limited.result);
            assertEquals(3_000L, limited.retryAfterMillis,
                    "a 429 carrying Retry-After must surface the delay the endpoint asked for");

            LogSink ok = sink("/fine");
            HttpTransport.Outcome success = transport.send(ok, ok.serialize(List.of(rec("x"))));
            assertEquals(HttpTransport.Result.SUCCESS, success.result);
            assertEquals(-1L, success.retryAfterMillis, "no Retry-After on a healthy response");
        }
    }

    // ---- honoured by the engine's retry loop ----

    @Test
    void engineWaitsTheRequestedDelayBeforeRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong firstAttemptNanos = new AtomicLong();
        AtomicLong secondAttemptNanos = new AtomicLong();
        server.createContext("/limited", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                int n = attempts.incrementAndGet();
                if (n == 1) {
                    firstAttemptNanos.set(System.nanoTime());
                    exchange.getResponseHeaders().add("Retry-After", "1"); // one second, please
                    exchange.sendResponseHeaders(429, -1);
                } else {
                    if (n == 2) secondAttemptNanos.set(System.nanoTime());
                    exchange.sendResponseHeaders(200, -1);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });

        BatchConfig config = BatchConfig.builder()
                .maxRetries(3)
                // Our own schedule would retry in ~10ms; only the header can stretch that to ~1s.
                .initialRetryBackoff(Duration.ofMillis(10))
                .maxRetryBackoff(Duration.ofSeconds(30))
                .lingerTime(Duration.ofSeconds(30)) // close()'s POISON drives the single flush
                .maxBatchRecords(100)
                .gzip(false)
                // Spill is on by default; off here so a throttled send is retried in place
                // rather than buffered, and no test writes into the JVM temp directory.
                .spillEnabled(false)
                .build();

        try (BatchingLogShipper shipper = new BatchingLogShipper(sink("/limited"), config)) {
            shipper.offer(rec("throttled-then-accepted"));
        }

        assertEquals(2, attempts.get(), "should be one 429 then a successful retry");
        long waitedMillis = (secondAttemptNanos.get() - firstAttemptNanos.get()) / 1_000_000L;
        assertTrue(waitedMillis >= 900,
                "engine must honour Retry-After: 1 rather than its own 10ms backoff, waited "
                        + waitedMillis + "ms");
    }

    @Test
    void absurdRetryAfterIsClampedToMaxRetryBackoff() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/hostile", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                int n = attempts.incrementAndGet();
                if (n == 1) {
                    exchange.getResponseHeaders().add("Retry-After", "86400"); // a whole day
                    exchange.sendResponseHeaders(429, -1);
                } else {
                    exchange.sendResponseHeaders(200, -1);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });

        BatchConfig config = BatchConfig.builder()
                .maxRetries(2)
                .initialRetryBackoff(Duration.ofMillis(10))
                .maxRetryBackoff(Duration.ofMillis(300))   // the ceiling that must win
                .lingerTime(Duration.ofSeconds(30))
                .maxBatchRecords(100)
                .gzip(false)
                .spillEnabled(false)
                .build();

        long start = System.nanoTime();
        try (BatchingLogShipper shipper = new BatchingLogShipper(sink("/hostile"), config)) {
            shipper.offer(rec("do-not-park-the-flusher-for-a-day"));
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(2, attempts.get(), "the retry must still happen");
        assertTrue(elapsedMillis < 5_000,
                "a 24h Retry-After must be clamped by maxRetryBackoff, took " + elapsedMillis + "ms");
    }
}
