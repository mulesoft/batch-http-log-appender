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
package com.mulesoft.support.batchhttp.core.spill;

import com.mulesoft.support.batchhttp.core.Json;
import com.mulesoft.support.batchhttp.core.LogRecord;

import java.nio.charset.StandardCharsets;

/**
 * UTF-8 JSON spill codec: one flat JSON object per record. Human-inspectable, which
 * is handy when supporting a customer ("what's in the spill files?"). Encoding reuses
 * {@link Json} (the same writer the sinks use); decoding uses a purpose-built reader
 * for exactly the narrow shape we emit rather than a general JSON parser.
 *
 * <p>Object shape:
 * <pre>{@code
 * {"ts":1700000000123,"lvl":"INFO","logger":"x","thread":"main","msg":"hi",
 *  "exc":"...stacktrace...","attr":{"requestId":"abc"}}
 * }</pre>
 * {@code exc} is omitted when the record has no throwable; {@code attr} is omitted
 * when there are no attributes; {@code ts} is a bare JSON number, everything else a
 * quoted string.
 */
public final class JsonSpillCodec implements SpillCodec {

    @Override
    public byte codecId() { return ID_JSON; }

    /**
     * JSON segments use the same {@code VERSION_V2} sync-marker + CRC framing as binary ones
     * (since 0.1.6), so a record damaged in the middle of a JSON segment is skipped and replay
     * resumes at the next intact record, and a flipped byte inside a string field is rejected
     * by the checksum instead of being delivered garbled. The payload between the 12-byte frame
     * headers is still plain UTF-8 JSON, readable by eye. Segments written by 0.1.5 and earlier
     * carry {@code VERSION_V1} in their header and are still read (the reader dispatches on
     * the header byte), and a 0.1.5 reader already accepts a v2 JSON segment.
     */
    @Override
    public byte framingVersion() { return SpillSegmentHeader.VERSION_V2; }

    @Override
    public byte[] encode(LogRecord r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        Json.rawField(sb, "ts", Long.toString(r.timestampMillis()));
        sb.append(','); Json.field(sb, "lvl", r.level());
        sb.append(','); Json.field(sb, "logger", r.loggerName());
        sb.append(','); Json.field(sb, "thread", r.threadName());
        sb.append(','); Json.field(sb, "msg", r.message());
        if (r.throwable() != null) {
            sb.append(','); Json.field(sb, "exc", r.throwable());
        }
        if (!r.attributes().isEmpty()) {
            sb.append(',');
            Json.quote(sb, "attr");
            sb.append(":{");
            boolean first = true;
            for (var e : r.attributes().entrySet()) {
                if (!first) sb.append(',');
                Json.field(sb, e.getKey(), e.getValue());
                first = false;
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public LogRecord decode(byte[] payload) {
        try {
            return new Parser(new String(payload, StandardCharsets.UTF_8)).parseRecord();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Minimal recursive-descent reader for the flat record object. Understands exactly
     * the token set {@link #encode} emits: an object whose values are either quoted
     * strings, a bare integer ({@code ts}), or one nested object ({@code attr}).
     */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        LogRecord parseRecord() {
            LogRecord.Builder b = LogRecord.builder();
            expect('{');
            if (peek() != '}') {
                do {
                    String key = readString();
                    expect(':');
                    if ("ts".equals(key)) {
                        b.timestampMillis(readLong());
                    } else if ("attr".equals(key)) {
                        readAttributes(b);
                    } else {
                        String val = readString();
                        if ("lvl".equals(key)) b.level(val);
                        else if ("logger".equals(key)) b.loggerName(val);
                        else if ("thread".equals(key)) b.threadName(val);
                        else if ("msg".equals(key)) b.message(val);
                        else if ("exc".equals(key)) b.throwable(val);
                        // Unknown keys are ignored for forward-compatibility.
                    }
                } while (consumeIf(','));
            }
            expect('}');
            return b.build();
        }

        private void readAttributes(LogRecord.Builder b) {
            expect('{');
            if (peek() != '}') {
                do {
                    String k = readString();
                    expect(':');
                    String v = readString();
                    b.attribute(k, v);
                } while (consumeIf(','));
            }
            expect('}');
        }

        private long readLong() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == start) throw new IllegalStateException("expected number");
            return Long.parseLong(s.substring(start, i));
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    if (e == '"') out.append('"');
                    else if (e == '\\') out.append('\\');
                    else if (e == 'n') out.append('\n');
                    else if (e == 'r') out.append('\r');
                    else if (e == 't') out.append('\t');
                    else if (e == 'b') out.append('\b');
                    else if (e == 'f') out.append('\f');
                    else if (e == 'u') {
                        char u = (char) Integer.parseInt(s.substring(i, i + 4), 16);
                        i += 4;
                        out.append(u);
                    } else {
                        throw new IllegalStateException("bad escape: \\" + e);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalStateException("unterminated string");
        }

        private char peek() {
            if (i >= s.length()) throw new IllegalStateException("unexpected end");
            return s.charAt(i);
        }

        private void expect(char c) {
            if (peek() != c) throw new IllegalStateException("expected '" + c + "' at " + i);
            i++;
        }

        private boolean consumeIf(char c) {
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }
    }
}
