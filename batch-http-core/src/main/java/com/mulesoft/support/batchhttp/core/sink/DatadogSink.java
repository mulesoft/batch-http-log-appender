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

import com.mulesoft.support.batchhttp.core.Json;
import com.mulesoft.support.batchhttp.core.LogRecord;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Datadog Logs intake (v2). POSTs a JSON array of log objects to
 * {@code /api/v2/logs} on the site's HTTP intake host.
 *
 * <p>The intake caps the record count and the uncompressed body size of a request, and the
 * size of a single log. {@link #limits()} is what this sink advertises to the engine; Datadog's
 * API reference is authoritative for the currently published ceilings. See
 * https://docs.datadoghq.com/api/latest/logs/#send-logs
 */
public final class DatadogSink extends AbstractHttpSink {

    private final URI endpoint;
    private final String apiKey;
    private final String service;
    private final String source;
    private final String host;
    private final String ddtags;
    private final boolean includeTimestamp;

    private DatadogSink(Builder b) {
        // Each Datadog site has its own log-intake host; callers take theirs from Datadog's
        // documentation. Unset falls back to the default site.
        String intakeHost = b.site == null ? "http-intake.logs.datadoghq.com" : b.site;
        this.endpoint = URI.create("https://" + intakeHost + "/api/v2/logs");
        this.apiKey = b.apiKey;
        this.service = b.service;
        this.source = b.source == null ? "java" : b.source;
        // Datadog's reserved "host" facet, identifying the emitting host/app. Optional:
        // when unset (null/blank) it is omitted and Datadog infers host from the intake.
        this.host = (b.host == null || b.host.isBlank()) ? null : b.host;
        this.ddtags = b.ddtags;
        this.includeTimestamp = b.includeTimestamp;
    }

    @Override
    public URI endpoint() { return endpoint; }

    @Override
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("DD-API-KEY", apiKey);
        return h;
    }

    @Override
    protected BatchFraming framing() { return BatchFraming.JSON_ARRAY; }

    @Override
    protected void writeRecord(StringBuilder sb, LogRecord r) {
        sb.append('{');
        Json.field(sb, "message", r.message());
        sb.append(',');
        Json.field(sb, "status", r.level());
        sb.append(',');
        Json.field(sb, "ddsource", source);
        if (service != null) { sb.append(','); Json.field(sb, "service", service); }
        if (host != null)    { sb.append(','); Json.field(sb, "host", host); }
        if (ddtags != null)  { sb.append(','); Json.field(sb, "ddtags", ddtags); }
        sb.append(','); Json.field(sb, "logger.name", r.loggerName());
        sb.append(','); Json.field(sb, "logger.thread_name", r.threadName());
        // Datadog's reserved "date" attribute accepts either epoch millis or an ISO-8601
        // string; emit ISO-8601 UTC (e.g. 2023-11-14T22:13:20.123Z) so the timestamp is
        // human-readable in the Datadog UI while remaining the log's official event time.
        // Omitted when includeTimestamp is false, which hands the timestamp back to Datadog.
        if (includeTimestamp) { sb.append(','); Json.field(sb, "date", isoUtc(r.timestampMillis())); }
        if (r.throwable() != null) {
            sb.append(','); Json.field(sb, "error.stack", r.throwable());
        }
        Json.appendFields(sb, r.attributes());
        sb.append('}');
    }

    @Override
    public Limits limits() {
        // Stay just under the documented ceilings to leave headroom for framing.
        return new Limits(1000, 5 * 1024 * 1024);
    }

    @Override
    public String name() { return "Datadog"; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String site;
        private String apiKey;
        private String service;
        private String source;
        private String host;
        private String ddtags;
        private boolean includeTimestamp = true;

        /** Log-intake host for the Datadog site this organization lives in; the sink appends
         *  {@code /api/v2/logs} to it. Optional — defaults to Datadog's default site. */
        public Builder site(String v) { this.site = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder service(String v) { this.service = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        /** Datadog reserved {@code host} facet. Optional; omitted when null/blank. */
        public Builder host(String v) { this.host = v; return this; }
        public Builder ddtags(String v) { this.ddtags = v; return this; }
        /**
         * Whether to emit Datadog's reserved {@code date} attribute, the log's official event
         * time. Default true. Set false to omit it, which leaves Datadog stamping the log with
         * its own <em>arrival</em> time instead — the only way to observe shipping delay, since
         * a supplied {@code date} makes ingest lag invisible by construction.
         */
        public Builder includeTimestamp(boolean v) { this.includeTimestamp = v; return this; }

        public DatadogSink build() {
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalArgumentException("DatadogSink requires an apiKey");
            return new DatadogSink(this);
        }
    }
}
