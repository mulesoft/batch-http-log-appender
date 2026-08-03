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
 * New Relic Log API. POSTs a JSON array of log objects to {@code /log/v1}.
 * Auth is the {@code Api-Key} header (a license/ingest key).
 *
 * <p>The intake caps the payload size, compressed or uncompressed; {@link #limits()} is what
 * this sink advertises to the engine. Each New Relic region has its own Log API host, selected
 * by {@link Builder#host(String)} — take yours from New Relic's documentation. See
 * https://docs.newrelic.com/docs/logs/log-api/introduction-log-api/
 */
public final class NewRelicSink extends AbstractHttpSink {

    private final URI endpoint;
    private final String apiKey;
    private final String service;
    private final String hostname;
    private final Map<String, String> commonAttributes;
    private final boolean includeTimestamp;

    private NewRelicSink(Builder b) {
        String host = b.host == null ? "log-api.newrelic.com" : b.host;
        this.endpoint = URI.create("https://" + host + "/log/v1");
        this.apiKey = b.apiKey;
        // Optional per-log facets, emitted only when set (see writeRecord). These are
        // distinct from `host` above, which selects the intake endpoint.
        this.service = (b.service == null || b.service.isBlank()) ? null : b.service;
        this.hostname = (b.hostname == null || b.hostname.isBlank()) ? null : b.hostname;
        this.commonAttributes = b.commonAttributes;
        this.includeTimestamp = b.includeTimestamp;
    }

    @Override
    public URI endpoint() { return endpoint; }

    @Override
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Api-Key", apiKey);
        return h;
    }

    @Override
    protected BatchFraming framing() { return BatchFraming.JSON_ARRAY; }

    @Override
    protected void writeRecord(StringBuilder sb, LogRecord r) {
        sb.append('{');
        // New Relic reserves "timestamp" (epoch millis), "message", "level". "timestamp" is
        // omitted when includeTimestamp is false, which hands the timestamp back to New Relic;
        // it leads the object, so the trailing comma goes with it.
        if (includeTimestamp) {
            Json.rawField(sb, "timestamp", Long.toString(r.timestampMillis()));
            sb.append(',');
        }
        Json.field(sb, "message", r.message());
        sb.append(','); Json.field(sb, "level", r.level());
        // Optional identity facets, from config (not the log message). "hostname" is New
        // Relic's reserved host attribute (its Logs UI surfaces it); "service" is a plain
        // attribute, queryable in NRQL. Both omitted when unset.
        if (service  != null) { sb.append(','); Json.field(sb, "service", service); }
        if (hostname != null) { sb.append(','); Json.field(sb, "hostname", hostname); }
        sb.append(','); Json.field(sb, "logger.name", r.loggerName());
        sb.append(','); Json.field(sb, "thread.name", r.threadName());
        if (r.throwable() != null) { sb.append(','); Json.field(sb, "error.stack", r.throwable()); }
        Json.appendFields(sb, commonAttributes);
        Json.appendFields(sb, r.attributes());
        sb.append('}');
    }

    @Override
    public Limits limits() {
        return new Limits(Integer.MAX_VALUE, 900 * 1024); // headroom under 1 MB
    }

    @Override
    public String name() { return "NewRelic"; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String host;
        private String apiKey;
        private String service;
        private String hostname;
        private final Map<String, String> commonAttributes = new LinkedHashMap<>();
        private boolean includeTimestamp = true;

        /** Log API host for the region this account lives in; the sink appends {@code /log/v1}
         *  to it. Optional — defaults to New Relic's default host. This selects the intake
         *  endpoint; it is NOT emitted as a log attribute (see {@link #hostname}). */
        public Builder host(String v) { this.host = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        /** Optional {@code service} facet emitted on every record (plain attribute, queryable in NRQL). */
        public Builder service(String v) { this.service = v; return this; }
        /** Optional {@code hostname} facet emitted on every record — New Relic's reserved host
         *  attribute. Distinct from {@link #host}, which selects the intake endpoint. */
        public Builder hostname(String v) { this.hostname = v; return this; }
        public Builder commonAttribute(String k, String v) { this.commonAttributes.put(k, v); return this; }
        /**
         * Whether to emit New Relic's reserved {@code timestamp} attribute (epoch millis), the
         * log's official event time. Default true. Set false to omit it, which leaves New Relic
         * stamping the log with its own <em>arrival</em> time instead — the only way to observe
         * shipping delay, since a supplied {@code timestamp} makes ingest lag invisible by
         * construction.
         */
        public Builder includeTimestamp(boolean v) { this.includeTimestamp = v; return this; }

        public NewRelicSink build() {
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalArgumentException("NewRelicSink requires an apiKey");
            return new NewRelicSink(this);
        }
    }
}
