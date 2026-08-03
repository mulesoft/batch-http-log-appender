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

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A destination for batches of log records. Each vendor (Datadog, Splunk,
 * New Relic, ...) differs only in: where to POST, what headers/auth to send,
 * how to serialize a batch, and the intake size limits. Everything else —
 * batching, retry, compression, lifecycle — lives in the generic engine.
 */
public interface LogSink {

    /** Absolute URI of the intake endpoint to POST batches to. */
    URI endpoint();

    /** Headers to attach to every request (auth token, content-type, ...). */
    Map<String, String> headers();

    /** Serialize a batch of records into the request body bytes (already framed for this vendor). */
    byte[] serialize(List<LogRecord> batch);

    /** Vendor-imposed limits the engine must respect when forming batches. */
    Limits limits();

    /** Human-readable sink name for diagnostics. */
    default String name() { return getClass().getSimpleName(); }

    /**
     * Hard limits enforced by the intake API. The engine flushes before crossing any of
     * these, taking {@code min(config value, this limit)} for each (see
     * {@code BatchingLogShipper}). A vendor with no explicit record ceiling passes
     * {@link Integer#MAX_VALUE} for {@code maxRecordsPerBatch} as a sentinel meaning
     * "no vendor record cap — the batch is bounded by the configured record count and by
     * {@code maxUncompressedBytes}" (New Relic and Splunk HEC do this, capping by bytes only).
     * It is not a preallocation hint: the engine independently caps its initial batch
     * capacity, so the sentinel never oversizes anything.
     */
    final class Limits {
        public final int maxRecordsPerBatch;
        public final int maxUncompressedBytes;

        public Limits(int maxRecordsPerBatch, int maxUncompressedBytes) {
            this.maxRecordsPerBatch = maxRecordsPerBatch;
            this.maxUncompressedBytes = maxUncompressedBytes;
        }
    }
}
