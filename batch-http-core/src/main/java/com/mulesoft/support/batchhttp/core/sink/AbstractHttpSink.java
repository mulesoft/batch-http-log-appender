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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Shared batch-framing for the HTTP sinks. Every vendor renders a batch the same
 * way structurally — either a JSON array of per-record objects ({@code [obj,obj,…]})
 * or newline-delimited objects (NDJSON / Splunk HEC) — and differs only in how a
 * single record is serialized. This base owns the framing loop, the
 * {@code StringBuilder} sizing, and the UTF-8 encoding so each concrete sink only
 * declares its {@link #framing()} and how to {@link #writeRecord write one record}.
 */
public abstract class AbstractHttpSink implements LogSink {

    /** How per-record JSON objects are joined into a single request body. */
    protected enum BatchFraming {
        /** Wrap the batch in a JSON array: {@code [obj,obj,…]}. */
        JSON_ARRAY,
        /** One JSON object per line, newline-terminated. */
        NDJSON
    }

    /** The framing this sink's intake expects. */
    protected abstract BatchFraming framing();

    /** Append exactly one record as a single JSON object — no leading/trailing separators. */
    protected abstract void writeRecord(StringBuilder sb, LogRecord r);

    /**
     * Format epoch millis as an ISO-8601 UTC instant (e.g. {@code 2023-11-14T22:13:20.123Z}).
     * Shared by the sinks whose intake wants a human-readable timestamp string (Datadog's
     * {@code date}, Dynatrace's {@code timestamp}) rather than an epoch number.
     */
    protected static String isoUtc(long epochMillis) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }

    @Override
    public byte[] serialize(List<LogRecord> batch) {
        StringBuilder sb = new StringBuilder(batch.size() * 256);
        boolean array = framing() == BatchFraming.JSON_ARRAY;
        if (array) sb.append('[');
        for (int i = 0; i < batch.size(); i++) {
            if (array && i > 0) sb.append(',');
            writeRecord(sb, batch.get(i));
            if (!array) sb.append('\n'); // NDJSON: one object per line
        }
        if (array) sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
