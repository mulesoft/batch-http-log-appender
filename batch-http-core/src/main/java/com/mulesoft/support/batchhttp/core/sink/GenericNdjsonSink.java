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
 * Catch-all sink for "any other HTTP based logging endpoint". POSTs the batch
 * either as a JSON array or as newline-delimited JSON (NDJSON), with fully
 * configurable URL and headers. Use this for Loki-style HTTP receivers, in-house
 * collectors, etc. For Elasticsearch use {@link ElasticsearchSink}, which frames the
 * Bulk API and reads its per-document results.
 */
public final class GenericNdjsonSink extends AbstractHttpSink {

    /**
     * Public builder-facing framing choice. Mirrors the base {@link BatchFraming} (which is
     * {@code protected}, so it can't be exposed directly on the builder). The two enums must
     * stay in lockstep — {@link #framing()} maps every constant explicitly and rejects any
     * unmapped one rather than silently defaulting, so adding a constant to one enum without
     * the other fails loudly instead of misrouting.
     */
    public enum Framing { JSON_ARRAY, NDJSON }

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Framing framing;
    private final int maxRecords;
    private final int maxBytes;

    private GenericNdjsonSink(Builder b) {
        this.endpoint = URI.create(b.url);
        this.headers = b.headers;
        this.framing = b.framing;
        this.maxRecords = b.maxRecords;
        this.maxBytes = b.maxBytes;
    }

    @Override
    public URI endpoint() { return endpoint; }

    @Override
    public Map<String, String> headers() { return headers; }

    @Override
    protected BatchFraming framing() {
        // Explicit, exhaustive mapping (if/else, never switch-on-enum: see the classloader note
        // in BatchingLogShipper.offer). A new Framing constant with no branch here fails loudly.
        if (framing == Framing.JSON_ARRAY) return BatchFraming.JSON_ARRAY;
        if (framing == Framing.NDJSON) return BatchFraming.NDJSON;
        throw new IllegalStateException("unmapped Framing: " + framing);
    }

    @Override
    protected void writeRecord(StringBuilder sb, LogRecord r) {
        sb.append('{');
        Json.rawField(sb, "timestamp", Long.toString(r.timestampMillis()));
        sb.append(','); Json.field(sb, "level", r.level());
        sb.append(','); Json.field(sb, "logger", r.loggerName());
        sb.append(','); Json.field(sb, "thread", r.threadName());
        sb.append(','); Json.field(sb, "message", r.message());
        if (r.throwable() != null) { sb.append(','); Json.field(sb, "exception", r.throwable()); }
        Json.appendFields(sb, r.attributes());
        sb.append('}');
    }

    @Override
    public Limits limits() { return new Limits(maxRecords, maxBytes); }

    @Override
    public String name() { return "Generic(" + endpoint.getHost() + ")"; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Framing framing = Framing.NDJSON;
        private int maxRecords = 500;
        private int maxBytes = 1024 * 1024;

        public Builder url(String v) { this.url = v; return this; }
        public Builder header(String k, String v) { this.headers.put(k, v); return this; }
        public Builder framing(Framing v) { this.framing = v; return this; }
        public Builder maxRecords(int v) { this.maxRecords = v; return this; }
        public Builder maxBytes(int v) { this.maxBytes = v; return this; }

        public GenericNdjsonSink build() {
            if (url == null || url.isBlank())
                throw new IllegalArgumentException("GenericNdjsonSink requires a url");
            if (!headers.containsKey("Content-Type")) {
                headers.put("Content-Type",
                        framing == Framing.JSON_ARRAY ? "application/json" : "application/x-ndjson");
            }
            return new GenericNdjsonSink(this);
        }
    }
}
