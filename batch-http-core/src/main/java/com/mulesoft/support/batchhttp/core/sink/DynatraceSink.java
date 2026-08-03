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
 * Dynatrace Log Monitoring API v2. POSTs a JSON array of log objects to
 * {@code /api/v2/logs/ingest} on the environment's URL.
 *
 * <p>Auth is the {@code Authorization: Api-Token <token>} header, using an access
 * token that carries the {@code logs.ingest} scope.
 *
 * <p>The environment URL differs by deployment — SaaS and ActiveGate deployments publish
 * different URL shapes — so it is supplied whole rather than derived from a host. Use the URL
 * Dynatrace publishes for the environment; either the bare environment URL or a full
 * {@code .../api/v2/logs/ingest} URL is accepted.
 *
 * <p>Dynatrace maps three reserved attributes — {@code timestamp}, {@code loglevel},
 * and {@code content} — plus a flat map of any other attributes. The timestamp is
 * emitted as an ISO-8601 UTC instant (e.g. {@code 2023-11-14T22:13:20.123Z}) so it is
 * parsed unambiguously (a bare epoch number is read as seconds in some places, millis
 * in others). See
 * https://docs.dynatrace.com/docs/dynatrace-api/environment-api/log-monitoring-v2/post-ingest-logs
 */
public final class DynatraceSink extends AbstractHttpSink {

    private static final String INGEST_PATH = "/api/v2/logs/ingest";

    private final URI endpoint;
    private final String apiToken;
    private final String source;
    private final Map<String, String> commonAttributes;

    private DynatraceSink(Builder b) {
        this.endpoint = URI.create(normalizeEndpoint(b.environmentUrl));
        this.apiToken = b.apiToken;
        this.source = b.source;
        this.commonAttributes = b.commonAttributes;
    }

    /** Accept either the bare environment URL or a full ingest URL; never double-append the path. */
    private static String normalizeEndpoint(String envUrl) {
        String u = envUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith(INGEST_PATH)) return u;
        return u + INGEST_PATH;
    }

    @Override
    public URI endpoint() { return endpoint; }

    @Override
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Authorization", "Api-Token " + apiToken);
        return h;
    }

    @Override
    protected BatchFraming framing() { return BatchFraming.JSON_ARRAY; }

    @Override
    protected void writeRecord(StringBuilder sb, LogRecord r) {
        sb.append('{');
        // Dynatrace reserves "content" (message), "loglevel", and "timestamp".
        Json.field(sb, "content", r.message());
        sb.append(','); Json.field(sb, "loglevel", r.level());
        sb.append(','); Json.field(sb, "timestamp", isoUtc(r.timestampMillis()));
        sb.append(','); Json.field(sb, "logger.name", r.loggerName());
        sb.append(','); Json.field(sb, "thread.name", r.threadName());
        if (source != null) { sb.append(','); Json.field(sb, "log.source", source); }
        if (r.throwable() != null) { sb.append(','); Json.field(sb, "error.stack", r.throwable()); }
        Json.appendFields(sb, commonAttributes);
        Json.appendFields(sb, r.attributes());
        sb.append('}');
    }

    @Override
    public Limits limits() {
        // Dynatrace returns 413 once the request payload (or its JSON array) is too large;
        // the page doesn't publish the exact ceiling, so stay conservatively under the
        // commonly-cited ~5 MB request size and cap records to bound a single flush.
        return new Limits(5000, 4 * 1024 * 1024);
    }

    @Override
    public String name() { return "Dynatrace"; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String environmentUrl;
        private String apiToken;
        private String source;
        private final Map<String, String> commonAttributes = new LinkedHashMap<>();

        /**
         * The Dynatrace environment URL as Dynatrace publishes it for the environment (the
         * SaaS and ActiveGate forms differ), or a full {@code .../api/v2/logs/ingest} URL.
         * The ingest path is appended when absent.
         */
        public Builder environmentUrl(String v) { this.environmentUrl = v; return this; }
        /** Access token with the {@code logs.ingest} scope. */
        public Builder apiToken(String v) { this.apiToken = v; return this; }
        /** Optional value for the Dynatrace {@code log.source} attribute. */
        public Builder source(String v) { this.source = v; return this; }
        public Builder commonAttribute(String k, String v) { this.commonAttributes.put(k, v); return this; }

        public DynatraceSink build() {
            if (environmentUrl == null || environmentUrl.isBlank())
                throw new IllegalArgumentException("DynatraceSink requires an environmentUrl");
            if (apiToken == null || apiToken.isBlank())
                throw new IllegalArgumentException("DynatraceSink requires an apiToken");
            return new DynatraceSink(this);
        }
    }
}
