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

import com.mulesoft.support.batchhttp.core.sink.DatadogSink;
import com.mulesoft.support.batchhttp.core.sink.DynatraceSink;
import com.mulesoft.support.batchhttp.core.sink.NewRelicSink;
import com.mulesoft.support.batchhttp.core.sink.SplunkHecSink;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SinkSerializationTest {

    private LogRecord sample() {
        return LogRecord.builder()
                .timestampMillis(1700000000123L)
                .level("ERROR")
                .loggerName("com.example.Svc")
                .threadName("worker-1")
                .message("boom \"quoted\" \n newline")
                .attribute("requestId", "abc-123")
                .build();
    }

    @Test
    void datadogProducesJsonArrayWithApiKeyHeader() {
        DatadogSink sink = DatadogSink.builder().apiKey("KEY").service("svc").build();
        assertEquals("KEY", sink.headers().get("DD-API-KEY"));
        assertTrue(sink.endpoint().toString().endsWith("/api/v2/logs"));

        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("[") && body.endsWith("]"));
        assertTrue(body.contains("\"status\":\"ERROR\""));
        assertTrue(body.contains("\"requestId\":\"abc-123\""));
        // Datadog's reserved "date" is ISO-8601 UTC for 1700000000123 ms = 2023-11-14T22:13:20.123Z.
        assertTrue(body.contains("\"date\":\"2023-11-14T22:13:20.123Z\""),
                "date must be ISO-8601 UTC, body was: " + body);
        // Quotes and newlines must be escaped to keep JSON valid.
        assertTrue(body.contains("\\\"quoted\\\""));
        assertTrue(body.contains("\\n"));
        // No host was set on this sink, so the reserved "host" facet must be omitted.
        assertFalse(body.contains("\"host\":"), "host must be omitted when unset, body was: " + body);
    }

    @Test
    void datadogEmitsHostFacetWhenSet() {
        DatadogSink sink = DatadogSink.builder().apiKey("KEY").service("svc").host("datadog-batch-http").build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"host\":\"datadog-batch-http\""),
                "host facet must be emitted when set, body was: " + body);
    }

    @Test
    void datadogOmitsBlankHostFacet() {
        DatadogSink sink = DatadogSink.builder().apiKey("KEY").service("svc").host("   ").build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertFalse(body.contains("\"host\":"), "blank host must be omitted, body was: " + body);
    }

    /**
     * Datadog treats {@code date} as the log's official event time, so supplying it makes the
     * stored timestamp equal the generation time and hides any shipping delay. Omitting it is
     * what lets Datadog stamp arrival instead — the switch this asserts.
     */
    @Test
    void datadogOmitsReservedDateWhenTimestampIsSuppressed() {
        DatadogSink sink = DatadogSink.builder()
                .apiKey("KEY").service("svc").includeTimestamp(false).build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertFalse(body.contains("\"date\":"),
                "date must be omitted when includeTimestamp is false, body was: " + body);
        // Suppressing one field must not disturb the rest of the record or its framing.
        assertTrue(body.startsWith("[{\"message\":"), "body was: " + body);
        assertTrue(body.endsWith("}]"), "body was: " + body);
        assertTrue(body.contains("\"status\":\"ERROR\""), "body was: " + body);
        assertTrue(body.contains("\"requestId\":\"abc-123\""), "body was: " + body);
    }

    /** The default must stay as it was for every existing user: {@code date} present. */
    @Test
    void datadogEmitsReservedDateByDefault() {
        DatadogSink sink = DatadogSink.builder().apiKey("KEY").service("svc").build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"date\":\"2023-11-14T22:13:20.123Z\""),
                "date must be emitted unless explicitly suppressed, body was: " + body);
    }

    @Test
    void splunkProducesNewlineDelimitedEventObjects() {
        SplunkHecSink sink = SplunkHecSink.builder()
                .baseUrl("https://hec.example.com:8088").token("T").sourcetype("mule").build();
        assertEquals("Splunk T", sink.headers().get("Authorization"));
        assertTrue(sink.endpoint().toString().endsWith("/services/collector/event"));

        String body = new String(sink.serialize(List.of(sample(), sample())), StandardCharsets.UTF_8);
        long lines = body.lines().filter(l -> !l.isBlank()).count();
        assertEquals(2, lines, "two events => two NDJSON lines");
        assertTrue(body.contains("\"event\":{"));
        assertTrue(body.contains("\"sourcetype\":\"mule\""));
    }

    @Test
    void newRelicProducesArrayWithApiKeyHeader() {
        NewRelicSink sink = NewRelicSink.builder().apiKey("NR").commonAttribute("env", "prod").build();
        assertEquals("NR", sink.headers().get("Api-Key"));

        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("[") && body.endsWith("]"));
        assertTrue(body.contains("\"timestamp\":1700000000123"));
        assertTrue(body.contains("\"env\":\"prod\""));
        // No service/hostname set on this sink, so both facets must be omitted.
        assertFalse(body.contains("\"service\":"), "service must be omitted when unset, body was: " + body);
        assertFalse(body.contains("\"hostname\":"), "hostname must be omitted when unset, body was: " + body);
    }

    @Test
    void newRelicEmitsServiceAndHostnameFacetsWhenSet() {
        NewRelicSink sink = NewRelicSink.builder()
                .apiKey("NR").service("CloudHub2").hostname("newrelic-batch-http").build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"service\":\"CloudHub2\""),
                "service facet must be emitted when set, body was: " + body);
        // "hostname" is New Relic's reserved host attribute; must NOT be the plain "host" key.
        assertTrue(body.contains("\"hostname\":\"newrelic-batch-http\""),
                "hostname facet must be emitted when set, body was: " + body);
        assertFalse(body.contains("\"host\":"),
                "must emit reserved \"hostname\", not plain \"host\", body was: " + body);
    }

    @Test
    void newRelicOmitsBlankServiceAndHostnameFacets() {
        NewRelicSink sink = NewRelicSink.builder()
                .apiKey("NR").service("   ").hostname("").build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertFalse(body.contains("\"service\":"), "blank service must be omitted, body was: " + body);
        assertFalse(body.contains("\"hostname\":"), "blank hostname must be omitted, body was: " + body);
    }

    /**
     * New Relic's reserved {@code timestamp} leads the record object, so suppressing it must
     * take its trailing comma with it — otherwise every shipped body would start
     * {@code [{,"message"…} } and the intake would reject the batch outright.
     */
    @Test
    void newRelicOmitsReservedTimestampWhenSuppressedAndKeepsValidJson() {
        NewRelicSink sink = NewRelicSink.builder()
                .apiKey("NR").service("svc").hostname("h")
                .commonAttribute("env", "prod").includeTimestamp(false).build();
        String body = new String(sink.serialize(List.of(sample(), sample())), StandardCharsets.UTF_8);
        assertFalse(body.contains("\"timestamp\":"),
                "timestamp must be omitted when includeTimestamp is false, body was: " + body);
        assertTrue(body.startsWith("[{\"message\":"),
                "the record must now open on message, with no stray comma, body was: " + body);
        assertFalse(body.contains("{,"), "no leading comma may survive, body was: " + body);
        assertTrue(body.contains("},{\"message\":"),
                "the array separator must still join two records, body was: " + body);
        assertTrue(body.endsWith("}]"), "body was: " + body);
        // The remaining fields are untouched.
        assertTrue(body.contains("\"level\":\"ERROR\""), "body was: " + body);
        assertTrue(body.contains("\"service\":\"svc\""), "body was: " + body);
        assertTrue(body.contains("\"hostname\":\"h\""), "body was: " + body);
        assertTrue(body.contains("\"env\":\"prod\""), "body was: " + body);
        assertTrue(body.contains("\"requestId\":\"abc-123\""), "body was: " + body);
    }

    /** The default must stay as it was for every existing user: {@code timestamp} present. */
    @Test
    void newRelicEmitsReservedTimestampByDefault() {
        NewRelicSink sink = NewRelicSink.builder().apiKey("NR").build();
        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"timestamp\":1700000000123"),
                "timestamp must be emitted unless explicitly suppressed, body was: " + body);
    }

    @Test
    void dynatraceProducesArrayWithApiTokenHeaderAndIsoTimestamp() {
        DynatraceSink sink = DynatraceSink.builder()
                .environmentUrl("https://abc12345.live.dynatrace.com")
                .apiToken("dt0c01.TOKEN").source("mule").build();
        assertEquals("Api-Token dt0c01.TOKEN", sink.headers().get("Authorization"));
        assertTrue(sink.endpoint().toString().endsWith("/api/v2/logs/ingest"));

        String body = new String(sink.serialize(List.of(sample())), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("[") && body.endsWith("]"));
        // Reserved Dynatrace keys.
        assertTrue(body.contains("\"content\":\"boom"), "message maps to content");
        assertTrue(body.contains("\"loglevel\":\"ERROR\""));
        assertTrue(body.contains("\"log.source\":\"mule\""));
        // Timestamp is ISO-8601 UTC for 1700000000123 ms = 2023-11-14T22:13:20.123Z.
        assertTrue(body.contains("\"timestamp\":\"2023-11-14T22:13:20.123Z\""),
                "timestamp must be ISO-8601 UTC, body was: " + body);
        assertTrue(body.contains("\"requestId\":\"abc-123\""));
        // Quotes and newlines must be escaped to keep JSON valid.
        assertTrue(body.contains("\\\"quoted\\\""));
        assertTrue(body.contains("\\n"));
    }

    @Test
    void dynatraceAcceptsFullIngestUrlWithoutDoublingPath() {
        DynatraceSink sink = DynatraceSink.builder()
                .environmentUrl("https://abc12345.live.dynatrace.com/api/v2/logs/ingest/")
                .apiToken("T").build();
        assertEquals("https://abc12345.live.dynatrace.com/api/v2/logs/ingest",
                sink.endpoint().toString());
    }

    @Test
    void builderRejectsMissingCredentials() {
        assertThrows(IllegalArgumentException.class, () -> DatadogSink.builder().build());
        assertThrows(IllegalArgumentException.class, () -> NewRelicSink.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> SplunkHecSink.builder().baseUrl("https://x").build());
        // Dynatrace needs both an environment URL and a token.
        assertThrows(IllegalArgumentException.class, () -> DynatraceSink.builder().apiToken("T").build());
        assertThrows(IllegalArgumentException.class,
                () -> DynatraceSink.builder().environmentUrl("https://x.live.dynatrace.com").build());
    }
}
