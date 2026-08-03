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
 * Splunk HTTP Event Collector (HEC). POSTs newline-delimited (not array-wrapped)
 * JSON event objects to {@code /services/collector/event}. HEC accepts multiple
 * events concatenated back-to-back in one request body.
 *
 * <p>Auth is {@code Authorization: Splunk <token>}. HEC caps the request body at
 * {@code max_content_length}, which is configurable server-side, so the byte ceiling this
 * sink advertises is settable on the builder to match your deployment. See
 * https://docs.splunk.com/Documentation/Splunk/latest/Data/FormateventsforHTTPEventCollector
 */
public final class SplunkHecSink extends AbstractHttpSink {

    private final URI endpoint;
    private final String token;
    private final String index;
    private final String sourcetype;
    private final String host;
    private final int maxBytes;

    private SplunkHecSink(Builder b) {
        String base = b.baseUrl.endsWith("/") ? b.baseUrl.substring(0, b.baseUrl.length() - 1) : b.baseUrl;
        this.endpoint = URI.create(base + "/services/collector/event");
        this.token = b.token;
        this.index = b.index;
        this.sourcetype = b.sourcetype;
        this.host = b.host;
        this.maxBytes = b.maxBytes;
    }

    @Override
    public URI endpoint() { return endpoint; }

    @Override
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Authorization", "Splunk " + token);
        return h;
    }

    @Override
    protected BatchFraming framing() { return BatchFraming.NDJSON; }

    @Override
    protected void writeRecord(StringBuilder sb, LogRecord r) {
        sb.append('{');
        // HEC time is epoch seconds with optional millis fraction.
        Json.rawField(sb, "time", millisToEpochSeconds(r.timestampMillis()));
        if (host != null)       { sb.append(','); Json.field(sb, "host", host); }
        if (sourcetype != null) { sb.append(','); Json.field(sb, "sourcetype", sourcetype); }
        if (index != null)      { sb.append(','); Json.field(sb, "index", index); }
        sb.append(',');
        Json.quote(sb, "event");
        sb.append(':');
        // The event itself as a structured object.
        sb.append('{');
        Json.field(sb, "message", r.message());
        sb.append(','); Json.field(sb, "level", r.level());
        sb.append(','); Json.field(sb, "logger", r.loggerName());
        sb.append(','); Json.field(sb, "thread", r.threadName());
        if (r.throwable() != null) { sb.append(','); Json.field(sb, "exception", r.throwable()); }
        Json.appendFields(sb, r.attributes());
        sb.append('}');
        sb.append('}');
    }

    /**
     * Epoch seconds with a 3-digit millis fraction, e.g. {@code 1700000000.123}.
     * Pads the fraction by hand rather than via {@code String.format("%03d", …)} —
     * the formatter parses its pattern and allocates a {@code Formatter} per call,
     * which is needless overhead on the hot serialize path for what is just left-pad.
     */
    private static String millisToEpochSeconds(long millis) {
        long frac = millis % 1000;
        StringBuilder sb = new StringBuilder(16);
        sb.append(millis / 1000).append('.');
        if (frac < 100) sb.append('0');
        if (frac < 10)  sb.append('0');
        sb.append(frac);
        return sb.toString();
    }

    @Override
    public Limits limits() {
        return new Limits(Integer.MAX_VALUE, maxBytes);
    }

    @Override
    public String name() { return "SplunkHEC"; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String baseUrl;   // e.g. https://hec.example.com:8088
        private String token;
        private String index;
        private String sourcetype;
        private String host;
        private int maxBytes = 900 * 1024; // headroom under the 1 MB default

        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        public Builder token(String v) { this.token = v; return this; }
        public Builder index(String v) { this.index = v; return this; }
        public Builder sourcetype(String v) { this.sourcetype = v; return this; }
        public Builder host(String v) { this.host = v; return this; }
        public Builder maxBytes(int v) { this.maxBytes = v; return this; }

        public SplunkHecSink build() {
            if (baseUrl == null || baseUrl.isBlank())
                throw new IllegalArgumentException("SplunkHecSink requires a baseUrl");
            if (token == null || token.isBlank())
                throw new IllegalArgumentException("SplunkHecSink requires a token");
            return new SplunkHecSink(this);
        }
    }
}
