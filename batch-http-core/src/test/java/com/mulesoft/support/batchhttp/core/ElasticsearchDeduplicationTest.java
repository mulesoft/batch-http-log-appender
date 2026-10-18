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

import com.mulesoft.support.batchhttp.core.sink.ElasticsearchSink;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The duplicate that at-least-once delivery produces, end to end: Elasticsearch stores a batch,
 * its response never reaches the appender, and the appender sends the batch again. A fake
 * {@code _bulk} endpoint keeps documents by {@code _id} the way Elasticsearch does: a second
 * {@code create} with a stored id is refused with 409, a document without an id is always stored.
 *
 * <p>With {@code deduplicate} on, every re-sent copy is refused and each record is stored once;
 * with it off, the same fault stores the batch twice. Both outcomes are asserted, so the test
 * shows the behaviour before and after the change.
 */
class ElasticsearchDeduplicationTest {

    private static final String BULK_PATH = "/logs-mule-default/_bulk";
    private static final Pattern ID = Pattern.compile("\"_id\":\"([^\"]+)\"");
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(300);
    private static final long LOST_RESPONSE_DELAY_MS = 1_200; // well past REQUEST_TIMEOUT

    private enum Behaviour { STORE, STORE_AND_LOSE_RESPONSE, FAIL_503 }

    private HttpServer server;
    private int port;
    private final Set<String> storedIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong storedDocs = new AtomicLong();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> idsSeen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A handler that sleeps (a lost response) must not hold up the retry behind it.
        server.setExecutor(Executors.newCachedThreadPool());
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** Serve {@code _bulk} like Elasticsearch, behaving per request as {@code plan} says. */
    private void fakeElasticsearch(IntFunction<Behaviour> plan) {
        server.createContext(BULK_PATH, exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Behaviour b = plan.apply(requestCount.getAndIncrement());
                if (b == Behaviour.FAIL_503) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                StringBuilder items = new StringBuilder();
                boolean errors = false;
                for (String line : body.split("\n")) {
                    if (!line.startsWith("{\"create\"")) continue; // the document line follows its action
                    Matcher m = ID.matcher(line);
                    int status;
                    if (!m.find()) {
                        storedDocs.incrementAndGet(); // auto-generated id: always a new document
                        status = 201;
                    } else {
                        idsSeen.add(m.group(1));
                        if (storedIds.add(m.group(1))) {
                            storedDocs.incrementAndGet();
                            status = 201;
                        } else {
                            status = 409;
                            errors = true;
                        }
                    }
                    if (items.length() > 0) items.append(',');
                    items.append("{\"create\":{\"status\":").append(status);
                    if (status == 409) items.append(",\"error\":{\"type\":\"version_conflict_engine_exception\"}");
                    items.append("}}");
                }
                if (b == Behaviour.STORE_AND_LOSE_RESPONSE) {
                    // Stored, but the answer arrives after the client gave up: the batch is re-sent.
                    try { Thread.sleep(LOST_RESPONSE_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                byte[] resp = ("{\"errors\":" + errors + ",\"items\":[" + items + "]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } catch (java.io.IOException clientGone) {
                // the appender timed out and closed the connection; nothing to answer
            } finally {
                exchange.close();
            }
        });
    }

    private ElasticsearchSink sink(boolean deduplicate) {
        return ElasticsearchSink.builder().url("http://127.0.0.1:" + port).apiKey("test-key")
                .deduplicate(deduplicate).build();
    }

    private static BatchConfig.Builder config(int batchRecords) {
        return BatchConfig.builder()
                .maxRetries(3)
                .initialRetryBackoff(Duration.ofMillis(10))
                .maxRetryBackoff(Duration.ofMillis(20))
                .requestTimeout(REQUEST_TIMEOUT)
                .lingerTime(Duration.ofSeconds(30))
                .maxBatchRecords(batchRecords) // a full batch flushes at once
                .gzip(false)
                .spillEnabled(false);
    }

    private static LogRecord rec(int i) {
        return LogRecord.builder().message("event " + i).level("INFO").loggerName("l").threadName("t").build();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    @Test
    void aBatchReSentAfterALostResponseIsStoredOnce() throws Exception {
        fakeElasticsearch(n -> n == 0 ? Behaviour.STORE_AND_LOSE_RESPONSE : Behaviour.STORE);
        int n = 50;
        BatchingLogShipper shipper = new BatchingLogShipper(sink(true), config(n).build());
        for (int i = 0; i < n; i++) shipper.offer(rec(i));
        awaitTrue(() -> shipper.stats().sentRecords == n, "the re-sent batch to settle");
        shipper.close();

        assertTrue(requestCount.get() >= 2, "the first response was lost, so the batch was sent again");
        assertEquals(n, storedDocs.get(), "every record stored exactly once");
        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(n, s.duplicatesSuppressed, "the whole re-sent batch was refused as already stored");
        assertEquals(n, s.sentRecords, "a refused copy counts as delivered, once");
        assertEquals(0, s.dropped);
        assertEquals(s.accepted, s.sentRecords + s.dropped, "conservation still holds");
    }

    @Test
    void withDeduplicateOffTheSameFaultStoresTheBatchTwice() throws Exception {
        fakeElasticsearch(n -> n == 0 ? Behaviour.STORE_AND_LOSE_RESPONSE : Behaviour.STORE);
        int n = 50;
        BatchingLogShipper shipper = new BatchingLogShipper(sink(false), config(n).build());
        for (int i = 0; i < n; i++) shipper.offer(rec(i));
        awaitTrue(() -> shipper.stats().sentRecords == n, "the re-sent batch to settle");
        shipper.close();

        assertEquals(2L * n, storedDocs.get(), "before the change: the lost response doubles the batch");
        assertEquals(0, shipper.stats().duplicatesSuppressed);
        assertTrue(idsSeen.isEmpty(), "no _id is sent when deduplicate is off");
    }

    @Test
    void idsSurviveTheSpillBufferAndAFailedReplay(@TempDir Path dir) throws Exception {
        // 1st request: 503 (no retries, so the batch spills). 2nd: the replay is stored but its
        // response is lost, so the segment stays on disk. 3rd onward: the replay is refused as
        // already stored — possible only if the ids came back from disk unchanged.
        fakeElasticsearch(n -> n == 0 ? Behaviour.FAIL_503
                : n == 1 ? Behaviour.STORE_AND_LOSE_RESPONSE : Behaviour.STORE);
        int n = 40;
        BatchingLogShipper shipper = new BatchingLogShipper(sink(true), config(n)
                .maxRetries(0)
                .spillEnabled(true)
                .spillDirectory(dir)
                .spillReplayInterval(Duration.ofMillis(150))
                .build());
        for (int i = 0; i < n; i++) shipper.offer(rec(i));
        awaitTrue(() -> shipper.stats().duplicatesSuppressed == n, "the second replay to be refused as duplicates");
        shipper.close();

        assertEquals(n, storedDocs.get(), "stored once despite a spill and a failed replay");
        assertEquals(n, Set.copyOf(idsSeen).size(), "the same n ids on every attempt");
        assertEquals(2L * n, idsSeen.size(), "two attempts reached the store: the lost one and the refused one");
    }

    @Test
    void aTruncatedRecordKeepsItsId() throws Exception {
        fakeElasticsearch(n -> Behaviour.STORE);
        int n = 5;
        BatchingLogShipper shipper = new BatchingLogShipper(sink(true), config(n).maxMessageBytes(64).build());
        for (int i = 0; i < n; i++) {
            shipper.offer(LogRecord.builder().message("x".repeat(500) + i).level("INFO").build());
        }
        awaitTrue(() -> shipper.stats().sentRecords == n, "the batch to be stored");
        shipper.close();

        assertEquals(n, idsSeen.size(), "truncation rebuilds the record; the id must come along");
        assertEquals(n, Set.copyOf(idsSeen).size());
    }
}
