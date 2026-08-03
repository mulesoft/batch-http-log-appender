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

import java.util.Map;

/**
 * Minimal, dependency-free JSON string writer. Only what the sinks need:
 * escaping a string value and appending key/value pairs. Keeping this in-house
 * avoids pulling Jackson/Gson into a logging library (where a transitive
 * version clash with the host app is a real risk).
 */
public final class Json {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {}

    /** Appends a JSON-escaped, quoted string to {@code sb}. Null becomes {@code null} literal. */
    public static void quote(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        // Manual \\uXXXX — avoids a String.format (pattern parse + Formatter
                        // alloc) per control char on the hot serialize path.
                        sb.append('\\').append('u')
                          .append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
                          .append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Appends {@code "key":"value"} (value JSON-escaped). */
    public static void field(StringBuilder sb, String key, String value) {
        quote(sb, key);
        sb.append(':');
        quote(sb, value);
    }

    /** Appends {@code "key":<raw>} where raw is already valid JSON (number, object, etc). */
    public static void rawField(StringBuilder sb, String key, String rawValue) {
        quote(sb, key);
        sb.append(':').append(rawValue);
    }

    /**
     * Appends each entry of {@code fields} as {@code ,"key":"value"} — every pair prefixed with a
     * leading comma. Every sink emits its trailing attribute maps this way: the maps always follow
     * at least one mandatory field, so the leading comma is always correct. Centralizes the
     * prefix-comma bookkeeping that was hand-written in each sink's attribute loop.
     */
    public static void appendFields(StringBuilder sb, Map<String, String> fields) {
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(',');
            field(sb, e.getKey(), e.getValue());
        }
    }
}
