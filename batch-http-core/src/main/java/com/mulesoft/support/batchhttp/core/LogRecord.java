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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A framework-agnostic representation of a single log event. Logging adapters
 * (Log4j2, Logback, ...) translate their native event type into this record so
 * the batching engine and sinks never depend on a specific logging framework.
 */
public final class LogRecord {

    private final long timestampMillis;
    private final String level;
    private final String loggerName;
    private final String threadName;
    private final String message;
    private final String throwable; // pre-rendered stack trace, or null
    private final Map<String, String> attributes;

    private LogRecord(Builder b) {
        this.timestampMillis = b.timestampMillis;
        this.level = b.level;
        this.loggerName = b.loggerName;
        this.threadName = b.threadName;
        this.message = b.message;
        this.throwable = b.throwable;
        // The builder's map is lazily created (null until the first attribute) and a
        // builder is single-use, so we can hand it straight over without a defensive
        // copy. Most events carry no MDC, so the common case allocates no map at all.
        this.attributes = (b.attributes == null || b.attributes.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(b.attributes);
    }

    public long timestampMillis() { return timestampMillis; }
    public String level() { return level; }
    public String loggerName() { return loggerName; }
    public String threadName() { return threadName; }
    public String message() { return message; }
    public String throwable() { return throwable; }
    public Map<String, String> attributes() { return attributes; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long timestampMillis = 0L;
        private String level = "INFO";
        private String loggerName = "";
        private String threadName = "";
        private String message = "";
        private String throwable;
        // Lazily allocated on the first attribute so a log line with no MDC pays nothing.
        private Map<String, String> attributes;

        public Builder timestampMillis(long v) { this.timestampMillis = v; return this; }
        public Builder level(String v) { this.level = v; return this; }
        public Builder loggerName(String v) { this.loggerName = v; return this; }
        public Builder threadName(String v) { this.threadName = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder throwable(String v) { this.throwable = v; return this; }
        public Builder attribute(String k, String v) {
            if (k != null && v != null) {
                if (this.attributes == null) this.attributes = new LinkedHashMap<>();
                this.attributes.put(k, v);
            }
            return this;
        }
        public LogRecord build() { return new LogRecord(this); }
    }
}
