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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch Bulk API ({@code POST /<data-stream>/_bulk}) — the ingest path for the ELK stack,
 * Elastic Cloud Hosted and Elastic Cloud Serverless alike. Each record is a {@code create} action
 * line followed by an ECS-named document ({@code @timestamp}, {@code message}, {@code log.level},
 * {@code log.logger}, …); {@code create} is the only action a data stream accepts.
 *
 * <p>Unlike the other intakes, {@code _bulk} answers 200 even when it refused some documents, and
 * reports a status per document. This sink therefore {@linkplain #reportsPerRecordResults() asks
 * for the response body} and tells the engine which documents to re-send (429 / 5xx) and which
 * were refused for good (e.g. a field-mapping conflict). {@code filter_path} trims that response
 * to the per-item status and error, so a clean batch costs a few bytes per document to read.
 *
 * <p>Auth is {@code Authorization: ApiKey <encoded>} — the "encoded" value Kibana shows when the
 * key is created. See
 * https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-bulk
 */
public final class ElasticsearchSink extends AbstractHttpSink {

    /** Default target: a data stream matching Elasticsearch's built-in {@code logs-*-*} template. */
    static final String DEFAULT_DATA_STREAM = "logs-mule-default";

    private static final String FILTER_PATH =
            "filter_path=errors,items.*.status,items.*.error.type,items.*.error.reason";

    /** Longest permanent-refusal reason kept for diagnostics; ES reasons can quote field values. */
    private static final int MAX_REASON_CHARS = 300;

    private final URI endpoint;
    private final String apiKey;
    private final String service;
    private final String host;

    private ElasticsearchSink(Builder b) {
        String base = b.url.endsWith("/") ? b.url.substring(0, b.url.length() - 1) : b.url;
        String dataStream = b.index == null || b.index.isBlank() ? DEFAULT_DATA_STREAM : b.index.trim();
        this.endpoint = URI.create(base + "/" + dataStream + "/_bulk?" + FILTER_PATH);
        this.apiKey = b.apiKey;
        this.service = b.service == null || b.service.isBlank() ? null : b.service;
        this.host = b.host == null || b.host.isBlank() ? null : b.host;
    }

    @Override
    public URI endpoint() { return endpoint; }

    @Override
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-ndjson");
        h.put("Authorization", "ApiKey " + apiKey);
        return h;
    }

    @Override
    protected BatchFraming framing() { return BatchFraming.NDJSON; }

    /**
     * A bulk "record" is two NDJSON lines: the action, then the document. The base class appends
     * the newline that ends the document line; the one between the two is written here.
     */
    @Override
    protected void writeRecord(StringBuilder sb, LogRecord r) {
        sb.append("{\"create\":{}}\n");
        sb.append('{');
        // Data streams require @timestamp, so — unlike Datadog/New Relic — it is always sent.
        Json.field(sb, "@timestamp", isoUtc(r.timestampMillis()));
        sb.append(','); Json.field(sb, "message", r.message());
        sb.append(','); Json.field(sb, "log.level", r.level());
        sb.append(','); Json.field(sb, "log.logger", r.loggerName());
        sb.append(','); Json.field(sb, "process.thread.name", r.threadName());
        if (service != null) { sb.append(','); Json.field(sb, "service.name", service); }
        if (host != null)    { sb.append(','); Json.field(sb, "host.name", host); }
        if (r.throwable() != null) { sb.append(','); Json.field(sb, "error.stack_trace", r.throwable()); }
        // MDC entries go under ECS `labels`: a bare key such as "host" or "log" would collide with
        // an ECS object field and get the whole document refused as a mapping conflict.
        for (Map.Entry<String, String> e : r.attributes().entrySet()) {
            sb.append(','); Json.field(sb, "labels." + e.getKey(), e.getValue());
        }
        sb.append('}');
    }

    @Override
    public Limits limits() {
        // No per-request document cap; Elastic recommends bulk bodies of a few MiB to a few tens.
        return new Limits(Integer.MAX_VALUE, 10 * 1024 * 1024);
    }

    @Override
    public String name() { return "Elasticsearch"; }

    @Override
    public boolean reportsPerRecordResults() { return true; }

    @Override
    public RecordResults inspectResponse(byte[] body, int batchSize) {
        String json = new String(body, StandardCharsets.UTF_8);
        // filter_path keeps the response order: "errors" comes first, so a clean batch is
        // recognized without parsing the per-item list at all.
        int errors = json.indexOf("\"errors\":");
        if (errors < 0 || json.startsWith("false", errors + 9)) return RecordResults.ALL_ACCEPTED;

        Object root;
        try {
            root = new MiniJson(json).parse();
        } catch (RuntimeException unparseable) {
            return RecordResults.ALL_ACCEPTED; // never re-send on a guess — see LogSink#inspectResponse
        }
        Object items = root instanceof Map ? ((Map<?, ?>) root).get("items") : null;
        if (!(items instanceof List)) return RecordResults.ALL_ACCEPTED;

        List<?> list = (List<?>) items;
        int n = Math.min(list.size(), batchSize);
        int[] retry = new int[n];
        int retryCount = 0;
        int rejected = 0;
        String firstReason = null;
        for (int i = 0; i < n; i++) {
            Map<?, ?> result = actionResult(list.get(i));
            if (result == null) continue;
            int status = result.get("status") instanceof Number ? ((Number) result.get("status")).intValue() : 200;
            // 409 is a version conflict on `create`: not worth re-sending, so count it delivered.
            // (IDs are auto-generated, so a re-sent document is stored again, not refused.)
            if ((status >= 200 && status < 300) || status == 409) continue;
            if (status == 429 || status >= 500) {
                retry[retryCount++] = i;
            } else {
                rejected++;
                if (firstReason == null) firstReason = describe(status, result.get("error"));
            }
        }
        if (retryCount == 0 && rejected == 0) return RecordResults.ALL_ACCEPTED;
        return new RecordResults(java.util.Arrays.copyOf(retry, retryCount), rejected, firstReason);
    }

    /** An item is {@code {"create":{...}}}; return the inner object whatever the action name. */
    private static Map<?, ?> actionResult(Object item) {
        if (!(item instanceof Map)) return null;
        for (Object v : ((Map<?, ?>) item).values()) {
            if (v instanceof Map) return (Map<?, ?>) v;
        }
        return null;
    }

    private static String describe(int status, Object error) {
        StringBuilder sb = new StringBuilder("status ").append(status);
        if (error instanceof Map) {
            Object type = ((Map<?, ?>) error).get("type");
            Object reason = ((Map<?, ?>) error).get("reason");
            if (type != null) sb.append(' ').append(type);
            if (reason != null) sb.append(": ").append(reason);
        }
        return sb.length() > MAX_REASON_CHARS ? sb.substring(0, MAX_REASON_CHARS) + "…" : sb.toString();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String url;     // e.g. https://my-project.es.ap-southeast-2.aws.elastic.cloud:443
        private String apiKey;
        private String index;
        private String service;
        private String host;

        /** Elasticsearch endpoint (scheme, host, optional port); the sink appends the data stream and {@code /_bulk}. */
        public Builder url(String v) { this.url = v; return this; }
        /** The base64 "encoded" API key, sent as {@code Authorization: ApiKey <key>}. */
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        /** Target data stream (or index). Optional — defaults to {@code logs-mule-default}. */
        public Builder index(String v) { this.index = v; return this; }
        /** ECS {@code service.name}. Optional; omitted when null/blank. */
        public Builder service(String v) { this.service = v; return this; }
        /** ECS {@code host.name}. Optional; omitted when null/blank. */
        public Builder host(String v) { this.host = v; return this; }

        public ElasticsearchSink build() {
            if (url == null || url.isBlank())
                throw new IllegalArgumentException("ElasticsearchSink requires a url");
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalArgumentException("ElasticsearchSink requires an apiKey");
            // Checked here rather than left to the first send: a URL without a scheme makes the
            // HTTP client throw on every request, which no retry or diagnostic can fix.
            url = url.trim();
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("https://") && !lower.startsWith("http://"))
                throw new IllegalArgumentException("ElasticsearchSink url must start with https:// or http://");
            // A key read from an env var or secret file often carries a trailing newline, which
            // the HTTP client refuses as a header value.
            apiKey = apiKey.trim();
            return new ElasticsearchSink(this);
        }
    }

    /**
     * Just enough JSON reading for a bulk response: objects, arrays, strings, numbers, literals.
     * The library stays dependency-free (see {@link Json}), and the response is small and
     * trusted-shape, so a full parser would be weight without benefit. Throws on malformed input.
     */
    static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            ws();
            if (i != s.length()) throw new IllegalArgumentException("trailing data at " + i);
            return v;
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true");  return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null");  return null;
                default:  return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                m.put(key, value());
                ws();
                if (peek() == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (peek() == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default:  sb.append(e); // \" \\ \/
                }
            }
        }

        private Number number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' at " + i);
            String n = s.substring(start, i);
            return n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0
                    ? (Number) Double.parseDouble(n) : (Number) Long.parseLong(n);
        }

        private void literal(String word) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("bad literal at " + i);
            i += word.length();
        }

        private void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
            i++;
        }

        private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
