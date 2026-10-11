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
package com.mulesoft.support.batchhttp.core.sink;

import com.mulesoft.support.batchhttp.core.LogRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchSinkTest {

    private static final String URL = "https://my-project.es.ap-southeast-2.aws.elastic.cloud:443";

    private static LogRecord sample() {
        return LogRecord.builder()
                .timestampMillis(1700000000123L)
                .level("ERROR")
                .loggerName("com.example.Svc")
                .threadName("worker-1")
                .message("boom \"quoted\" \n newline")
                .throwable("java.lang.IllegalStateException: x\n\tat A.b(A.java:1)")
                .attribute("correlationId", "abc-123")
                .build();
    }

    private static ElasticsearchSink.RecordResults inspect(String body, int n) {
        return ElasticsearchSink.builder().url(URL).apiKey("K").build()
                .inspectResponse(body.getBytes(StandardCharsets.UTF_8), n);
    }

    @Test
    void endpointTargetsDefaultDataStreamBulkWithFilterPath() {
        ElasticsearchSink sink = ElasticsearchSink.builder().url(URL + "/").apiKey("ENC").build();
        String uri = sink.endpoint().toString();
        assertTrue(uri.startsWith(URL + "/" + ElasticsearchSink.DEFAULT_DATA_STREAM + "/_bulk?filter_path="),
                "trailing slash must not double, default data stream applies; was " + uri);
        assertEquals("ApiKey ENC", sink.headers().get("Authorization"));
        assertEquals("application/x-ndjson", sink.headers().get("Content-Type"));
        assertTrue(sink.reportsPerRecordResults());
    }

    @Test
    void explicitIndexReplacesDefaultDataStream() {
        ElasticsearchSink sink = ElasticsearchSink.builder().url(URL).apiKey("K").index("logs-orders-prod").build();
        assertEquals("/logs-orders-prod/_bulk", sink.endpoint().getPath());
    }

    @Test
    void serializesCreateActionPlusEcsDocumentPerRecord() {
        ElasticsearchSink sink = ElasticsearchSink.builder().url(URL).apiKey("K")
                .service("elastic-batch-http").host("worker-0").build();
        String body = new String(sink.serialize(List.of(sample(), sample())), StandardCharsets.UTF_8);

        String[] lines = body.split("\n", -1);
        assertEquals(5, lines.length, "2 records = 4 lines + the trailing newline _bulk requires; was " + body);
        assertEquals("", lines[4]);
        assertEquals("{\"create\":{}}", lines[0]);
        assertEquals("{\"create\":{}}", lines[2]);

        String doc = lines[1];
        assertTrue(doc.startsWith("{\"@timestamp\":\"2023-11-14T22:13:20.123Z\""), doc);
        assertTrue(doc.contains("\"log.level\":\"ERROR\""));
        assertTrue(doc.contains("\"log.logger\":\"com.example.Svc\""));
        assertTrue(doc.contains("\"process.thread.name\":\"worker-1\""));
        assertTrue(doc.contains("\"service.name\":\"elastic-batch-http\""));
        assertTrue(doc.contains("\"host.name\":\"worker-0\""));
        assertTrue(doc.contains("\"error.stack_trace\":\"java.lang.IllegalStateException: x\\n\\tat A.b(A.java:1)\""));
        assertTrue(doc.contains("\"labels.correlationId\":\"abc-123\""), "MDC goes under ECS labels");
        // Escaped newlines keep each document on its own NDJSON line.
        assertTrue(doc.contains("boom \\\"quoted\\\" \\n newline"));
        assertTrue(doc.endsWith("}"));
    }

    @Test
    void omitsBlankServiceAndHost() {
        ElasticsearchSink sink = ElasticsearchSink.builder().url(URL).apiKey("K").service(" ").host("").build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertFalse(body.contains("service.name"));
        assertFalse(body.contains("host.name"));
    }

    @Test
    void builderRequiresUrlAndApiKey() {
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchSink.builder().apiKey("K").build());
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchSink.builder().url(URL).build());
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchSink.builder().url("my-project.es.cloud:443").apiKey("K").build(),
                "a URL without a scheme must fail at build, not on every send");
    }

    @Test
    void apiKeyIsTrimmedOfTheNewlineASecretFileLeaves() {
        ElasticsearchSink sink = ElasticsearchSink.builder().url(URL).apiKey("ENC\n").build();
        assertEquals("ApiKey ENC", sink.headers().get("Authorization"));
    }

    @Test
    void cleanBulkResponseIsAllAccepted() {
        assertTrue(inspect("{\"errors\":false}", 3).allAccepted());
        assertTrue(inspect("{\"errors\":false,\"items\":[{\"create\":{\"status\":201}}]}", 1).allAccepted());
    }

    @Test
    void mixedBulkResponseSeparatesRetryableFromRejected() {
        String body = "{\"errors\":true,\"items\":["
                + "{\"create\":{\"status\":201}},"
                + "{\"create\":{\"status\":429,\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"queue full\"}}},"
                + "{\"create\":{\"status\":400,\"error\":{\"type\":\"document_parsing_exception\",\"reason\":\"failed to parse field [x] \\\"q\\\"\"}}},"
                + "{\"create\":{\"status\":409,\"error\":{\"type\":\"version_conflict_engine_exception\",\"reason\":\"exists\"}}},"
                + "{\"create\":{\"status\":503,\"error\":{\"type\":\"unavailable_shards_exception\",\"reason\":\"x\"}}}"
                + "]}";
        LogSink.RecordResults r = inspect(body, 5);
        assertArrayEquals(new int[]{1, 4}, r.retryable, "429 and 5xx are re-sent; 409 means already stored");
        assertEquals(1, r.rejected);
        assertEquals("status 400 document_parsing_exception: failed to parse field [x] \"q\"", r.rejectedDetail);
    }

    @Test
    void unparseableOrUnexpectedBodyCountsAsAccepted() {
        assertTrue(inspect("{\"errors\":true,\"items\":[{\"create\":", 2).allAccepted(), "truncated");
        assertTrue(inspect("<html>proxy error</html>", 2).allAccepted(), "not JSON at all");
        assertTrue(inspect("{\"errors\":true}", 2).allAccepted(), "no items to attribute errors to");
    }

    @Test
    void moreItemsThanRecordsNeverYieldsAnOutOfRangeIndex() {
        String body = "{\"errors\":true,\"items\":[{\"create\":{\"status\":429}},{\"create\":{\"status\":429}}]}";
        assertArrayEquals(new int[]{0}, inspect(body, 1).retryable);
    }

    @Test
    void miniJsonParsesNestedValuesAndUnicodeEscapes() {
        Object v = new ElasticsearchSink.MiniJson(" {\"a\":[1,2.5,true,null,\"\\u00e9\\n\"],\"b\":{}} ").parse();
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals("[1, 2.5, true, null, é\n]", m.get("a").toString());
        assertEquals(Map.of(), m.get("b"));
        assertThrows(IllegalArgumentException.class, () -> new ElasticsearchSink.MiniJson("{\"a\":1} x").parse());
    }
}
