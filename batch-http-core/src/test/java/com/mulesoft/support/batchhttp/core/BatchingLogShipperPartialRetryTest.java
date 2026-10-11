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
import com.mulesoft.support.batchhttp.core.spill.SpillCodec;
import com.mulesoft.support.batchhttp.core.spill.SpillStore;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-record path a {@code _bulk}-style intake needs: a 200 response that refuses some of the
 * documents. Only the transiently refused ones (429 / 5xx) are re-sent, the permanently refused
 * ones are counted dropped, and every record still lands in exactly one bucket.
 */
class BatchingLogShipperPartialRetryTest {

    private static final String BULK_PATH = "/logs-mule-default/_bulk";

    /** Item 0 stored, item 1 throttled, item 2 refused for good (mapping conflict). */
    private static final String MIXED = "{\"errors\":true,\"items\":["
            + "{\"create\":{\"status\":201}},"
            + "{\"create\":{\"status\":429,\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"queue full\"}}},"
            + "{\"create\":{\"status\":400,\"error\":{\"type\":\"document_parsing_exception\",\"reason\":\"bad field\"}}}"
            + "]}";
    private static final String THROTTLED_ONE = "{\"errors\":true,\"items\":["
            + "{\"create\":{\"status\":429,\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"queue full\"}}}"
            + "]}";
    private static final String CLEAN = "{\"errors\":false}";

    private HttpServer server;
    private int port;
    private final List<String> requests = new CopyOnWriteArrayList<>();

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

    /** Answer the n-th bulk request with {@code responses[n]}, repeating the last one after that. */
    private void respondWith(String... responses) {
        AtomicInteger n = new AtomicInteger();
        server.createContext(BULK_PATH, exchange -> {
            try {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = responses[Math.min(n.getAndIncrement(), responses.length - 1)]
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
    }

    private BatchingLogShipper shipper(int maxRetries) {
        return new BatchingLogShipper(sink(), baseConfig(maxRetries).spillEnabled(false).build());
    }

    private ElasticsearchSink sink() {
        return ElasticsearchSink.builder().url("http://127.0.0.1:" + port).apiKey("test-key").build();
    }

    private static BatchConfig.Builder baseConfig(int maxRetries) {
        return BatchConfig.builder()
                .maxRetries(maxRetries)
                .initialRetryBackoff(Duration.ofMillis(10))
                .maxRetryBackoff(Duration.ofMillis(20))
                .lingerTime(Duration.ofSeconds(30)) // close()'s POISON drives one flush of all three records
                .maxBatchRecords(100)
                .gzip(false);
    }

    private static LogRecord rec(String msg) {
        return LogRecord.builder().message(msg).level("INFO").loggerName("l").threadName("t").build();
    }

    private static long docs(String bulkBody) {
        return bulkBody.lines().filter(l -> l.startsWith("{\"create\"")).count();
    }

    @Test
    void resendsOnlyTheThrottledDocumentAndDropsThePermanentlyRefusedOne() {
        respondWith(MIXED, CLEAN);

        BatchingLogShipper shipper = shipper(3);
        shipper.offer(rec("stored"));
        shipper.offer(rec("throttled"));
        shipper.offer(rec("refused"));
        shipper.close();

        assertEquals(2, requests.size(), "one bulk request, then one retry of the remainder");
        assertEquals(3, docs(requests.get(0)));
        assertEquals(1, docs(requests.get(1)), "the retry carries only the 429 document");
        assertTrue(requests.get(1).contains("\"message\":\"throttled\""), requests.get(1));

        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(2, s.sentRecords, "stored + throttled-then-stored");
        assertEquals(1, s.dropped, "the 400 document is not retried");
        assertEquals(1, s.sentBatches, "one batch, even though it took two requests");
        assertEquals(s.accepted, s.sentRecords + s.dropped, "every record lands in exactly one bucket");
    }

    @Test
    void exhaustedRetriesDropOnlyTheStillRefusedRemainder() {
        respondWith(MIXED, THROTTLED_ONE);

        BatchingLogShipper shipper = shipper(2);
        shipper.offer(rec("stored"));
        shipper.offer(rec("throttled"));
        shipper.offer(rec("refused"));
        shipper.close();

        assertEquals(3, requests.size(), "first attempt + 2 retries");
        assertEquals(1, docs(requests.get(2)));

        BatchingLogShipper.Stats s = shipper.stats();
        assertEquals(1, s.sentRecords);
        assertEquals(2, s.dropped, "the 400 document plus the never-accepted 429 one");
        assertEquals(1, s.failedBatches);
        assertEquals(s.accepted, s.sentRecords + s.dropped);
    }

    @Test
    void cleanBulkResponseIsOneRequest() {
        respondWith(CLEAN);

        BatchingLogShipper shipper = shipper(3);
        shipper.offer(rec("a"));
        shipper.offer(rec("b"));
        shipper.close();

        assertEquals(1, requests.size());
        assertEquals(2, shipper.stats().sentRecords);
        assertEquals(0, shipper.stats().dropped);
    }

    @Test
    void exhaustedRetriesSpillOnlyTheStillRefusedRemainder(@TempDir Path dir) {
        respondWith(MIXED, THROTTLED_ONE);

        BatchingLogShipper shipper = new BatchingLogShipper(sink(), baseConfig(2)
                .spillEnabled(true).spillDirectory(dir).minFreeDiskBytes(0)
                .spillReplayInterval(Duration.ofHours(1)) // keep the replayer out of this test
                .build());
        shipper.offer(rec("stored"));
        shipper.offer(rec("throttled"));
        shipper.offer(rec("refused"));
        shipper.close();

        BatchingLogShipper.Stats s = shipper.stats();
        SpillStore.SpillStats sp = shipper.spillStats();
        assertEquals(1, s.sentRecords);
        assertEquals(1, s.dropped, "only the 400 document; the 429 one went to disk");
        assertEquals(1, sp.spilledRecords, "the stored document is not spilled for a second send");
        assertEquals(s.accepted, s.sentRecords + s.dropped + sp.spilledRecords);
    }

    /**
     * A replayed sub-batch the intake only partly stores must not have its remainder spilled
     * separately: the segment is kept for the next pass, and a remainder also written to a new
     * segment would then be delivered twice.
     */
    @Test
    void replayKeepsAPartlyStoredSegmentRatherThanSpillingItsRemainderTwice(@TempDir Path dir) throws Exception {
        // A previous run left one segment of four records; replay sends it as two sub-batches of two.
        try (SpillStore previous = new SpillStore(dir, 1 << 20, 1 << 24, 0L, null,
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false, "t")) {
            assertTrue(previous.spill(List.of(rec("r0"), rec("r1"), rec("r2"), rec("r3"))));
        }

        // First request: r0 stored, r1 throttled. Then three 503s, then the intake recovers.
        Map<String, Integer> stored = new ConcurrentHashMap<>();
        AtomicInteger n = new AtomicInteger();
        server.createContext(BULK_PATH, exchange -> {
            try {
                List<String> msgs = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                        .lines().filter(l -> l.contains("\"message\":"))
                        .map(l -> l.replaceAll(".*\"message\":\"([^\"]*)\".*", "$1")).toList();
                int i = n.getAndIncrement();
                if (i >= 1 && i <= 3) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                String response = i == 0 ? "{\"errors\":true,\"items\":["
                        + "{\"create\":{\"status\":201}},"
                        + "{\"create\":{\"status\":429,\"error\":{\"type\":\"es_rejected_execution_exception\"}}}"
                        + "]}" : CLEAN;
                for (int k = 0; k < (i == 0 ? 1 : msgs.size()); k++) stored.merge(msgs.get(k), 1, Integer::sum);
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });

        BatchingLogShipper shipper = new BatchingLogShipper(sink(), baseConfig(1)
                .maxBatchRecords(2)
                .spillEnabled(true).spillDirectory(dir).spillInstanceId("t").minFreeDiskBytes(0)
                .spillReplayInterval(Duration.ofMillis(50))
                .build());
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!stored.containsKey("r3") && System.currentTimeMillis() < deadline) Thread.sleep(20);
            Thread.sleep(500); // several more passes: room for a stray second copy to arrive
        } finally {
            shipper.close();
        }

        assertEquals(1, stored.get("r1"), "the throttled record is delivered once: " + stored);
        assertEquals(1, stored.get("r2"), stored.toString());
        assertEquals(1, stored.get("r3"), stored.toString());
        assertTrue(stored.get("r0") >= 1, "at least once: " + stored);
    }
}
