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

import java.nio.file.Path;
import java.time.Duration;

/**
 * Tuning knobs for the batching engine. Sensible defaults are chosen for a
 * typical web service: flush on 200 records, 1 MiB, or every 1 second —
 * whichever comes first.
 */
public final class BatchConfig {

    public enum OverflowPolicy {
        /** Drop the incoming event when the queue is full (default; never blocks the app). */
        DROP_NEWEST,
        /** Evict the oldest queued event to make room for the new one. */
        DROP_OLDEST,
        /** Block the calling thread until space frees up (back-pressure; can stall the app). */
        BLOCK
    }

    /** What to do on startup with spill segments left behind by a previous run/crash. */
    public enum SpillRecoveryMode {
        /** Take ownership of orphaned segments and replay them (default — never lose logs). */
        ADOPT,
        /** Leave orphaned segments untouched (e.g. a sibling process owns the directory). */
        IGNORE,
        /** Discard orphaned segments on startup (when stale duplicates are worse than loss). */
        DELETE
    }

    /** How the background replayer drains the spilled backlog. */
    public enum SpillBacklogOrder {
        /** Strict FIFO: oldest segment first (default — completeness, in-order delivery). */
        OLDEST_FIRST,
        /** Most-recent segment first (surface the freshest logs during a large backlog). */
        NEWEST_FIRST,
        /** Two parallel streams: one drains newest-first, one oldest-first, meeting in the middle. */
        DUAL_STREAM
    }

    /** On-disk encoding of each spilled record. */
    public enum SpillFormat {
        /** Length-prefixed binary TLV (default — fastest, fewest allocations, no escaping). */
        BINARY,
        /** Length-prefixed UTF-8 JSON (human-inspectable; handy for support/debugging). */
        JSON
    }

    public final int maxBatchRecords;
    public final int maxBatchBytes;
    public final Duration lingerTime;
    public final int queueCapacity;
    public final OverflowPolicy overflowPolicy;
    public final boolean gzip;
    public final int maxRetries;
    public final Duration initialRetryBackoff;
    public final Duration maxRetryBackoff;
    public final Duration requestTimeout;
    public final Duration shutdownDrainTimeout;
    public final int flusherThreads;

    // --- TLS (insecure escape hatches for self-signed / internal collectors) ---
    /**
     * When true, the HTTPS transport trusts <em>any</em> server certificate — self-signed,
     * expired, or signed by an unknown CA — instead of validating the chain against the JVM
     * trust store. Mirrors curl's {@code -k} / Log4j2's disabled {@code TrustStore}. This is
     * a per-appender switch (each transport gets its own trust-all {@link javax.net.ssl.SSLContext}),
     * so it does not weaken TLS for the rest of the JVM. <b>Off by default</b>; intended only for
     * an internal collector with a private/self-signed cert.
     */
    public final boolean tlsTrustAllCertificates;
    /**
     * When false, the HTTPS transport skips verifying that the certificate's CN/SAN matches the
     * requested host — the equivalent of Log4j2's {@code verifyHostName="false"}. Defaults to
     * <b>true</b> (verification on). <b>Caveat:</b> unlike cert-chain trust, the JDK
     * {@link java.net.http.HttpClient} does not expose per-client hostname verification; the only
     * lever is the JVM-global system property {@code jdk.internal.httpclient.disableHostnameVerification},
     * which the JDK reads once at first HTTPS use. The engine sets it when this is false, but for it
     * to take effect reliably it should be set before any HTTPS traffic — on Mule/CloudHub, pass it
     * as a {@code -M-Djdk.internal.httpclient.disableHostnameVerification=true} JVM arg.
     */
    public final boolean tlsVerifyHostName;

    /**
     * Maximum size of a single log message, in UTF-8 bytes. A longer message is truncated
     * on a character boundary with a {@code …[truncated N chars]} marker appended, so one
     * oversized event (a developer dumping hundreds of megabytes into a log line) cannot
     * blow up the in-memory queue, the HTTP request, or a spill segment. Default 32 KiB.
     * Zero or negative disables truncation. Applied once, when the record is enqueued.
     */
    public final int maxMessageBytes;

    // --- disk-spill buffer (durability safety net; on by default) ---
    /**
     * When true (default), batches that fail after retries — or records the queue would drop on
     * overflow — are written to disk instead of being lost. On by default, so an embedding that
     * configures nothing still buffers through an intake outage; set false where writing log
     * payloads to local storage is unwanted (a memory-backed {@code /tmp}, a read-only volume).
     */
    public final boolean spillEnabled;
    /** Directory for spill segment files. When null, a default under /tmp is derived per-sink. */
    public final Path spillDirectory;
    /**
     * Identifier embedded in each spill segment file name so two or more apps that share a
     * spill directory never overwrite each other's logs. Must be stable across restarts of
     * the same app and distinct per concurrently-running instance. When null/blank the
     * engine falls back to the sink name, and finally to the legacy {@code spill-<seq>.log}
     * layout if even that is empty.
     */
    public final String spillInstanceId;
    /**
     * Active segment rolls over once it reaches this size. Default 8 MiB — a quarter of the
     * default total cap, so eviction sheds a quarter of the buffer at a time and three closed
     * segments stay available for replay while a fourth is being filled.
     */
    public final long maxSpillFileBytes;
    /** Total bytes across all spill segments; exceeding it evicts the oldest segment. Default 32 MiB. */
    public final long maxTotalSpillBytes;
    /**
     * Floor on filesystem usable space; dropping below it evicts the oldest segment. Default
     * 256 MiB. It bounds our impact on the <em>volume</em>, not on this buffer, so it is
     * deliberately not scaled to {@link #maxTotalSpillBytes}: the spill directory usually lives
     * on a filesystem shared with the runtime's own logs and temp files, and this is the margin
     * left for them. On a volume with less than this usable, spill degrades to counting drops
     * rather than writing — lower it (or set 0 to disable the probe) for a small dedicated volume.
     */
    public final long minFreeDiskBytes;
    /** How often the background replayer attempts to re-ship spilled segments. */
    public final Duration spillReplayInterval;
    /** When spilling, also divert records the queue would drop on overflow (vs. counting them dropped). */
    public final boolean spillOnOverflow;

    // --- crash-resilience hardening ---
    /** What to do on startup with segments left by a previous run/crash. Default ADOPT. */
    public final SpillRecoveryMode spillRecoveryMode;
    /** Backlog drain strategy. Default OLDEST_FIRST. */
    public final SpillBacklogOrder spillBacklogOrder;
    /** Number of backlog replay worker threads (ignored for DUAL_STREAM, which uses 2). Default 1. */
    public final int spillReplayThreads;
    /** Cap on backlog drain throughput, in bytes/sec, so replay never starves live traffic. 0 = unlimited. */
    public final long spillReplayMaxBytesPerSec;
    /** When true, prune segments to satisfy the total cap and free-space floor on startup. Default true. */
    public final boolean spillStartupReconcile;
    /** On-disk record encoding. Default BINARY. */
    public final SpillFormat spillFormat;

    // --- diagnostics (self-observability) ---
    /**
     * Sink for the engine's own health messages (dropped batches, fatal rejections, queue
     * overflow, spill init failure, periodic stats). Never null — defaults to
     * {@link Diagnostics#NOOP} so an embedding that does not wire one stays silent and
     * behaves exactly as before.
     */
    public final Diagnostics diagnostics;
    /**
     * When &gt; 0, the engine logs a one-line {@link BatchingLogShipper.Stats} snapshot at this
     * interval (a "still alive / here's what I've shipped" heartbeat), so an operator can see
     * delivery is healthy without inspecting the spill directory. 0 disables the heartbeat.
     */
    public final Duration statsInterval;

    private BatchConfig(Builder b) {
        this.maxBatchRecords = b.maxBatchRecords;
        this.maxBatchBytes = b.maxBatchBytes;
        this.lingerTime = b.lingerTime;
        this.queueCapacity = b.queueCapacity;
        this.overflowPolicy = b.overflowPolicy;
        this.gzip = b.gzip;
        this.maxRetries = b.maxRetries;
        this.initialRetryBackoff = b.initialRetryBackoff;
        this.maxRetryBackoff = b.maxRetryBackoff;
        this.requestTimeout = b.requestTimeout;
        this.shutdownDrainTimeout = b.shutdownDrainTimeout;
        this.flusherThreads = b.flusherThreads;
        this.tlsTrustAllCertificates = b.tlsTrustAllCertificates;
        this.tlsVerifyHostName = b.tlsVerifyHostName;
        this.maxMessageBytes = b.maxMessageBytes;
        this.spillEnabled = b.spillEnabled;
        this.spillDirectory = b.spillDirectory;
        this.spillInstanceId = b.spillInstanceId;
        this.maxSpillFileBytes = b.maxSpillFileBytes;
        this.maxTotalSpillBytes = b.maxTotalSpillBytes;
        this.minFreeDiskBytes = b.minFreeDiskBytes;
        this.spillReplayInterval = b.spillReplayInterval;
        this.spillOnOverflow = b.spillOnOverflow;
        this.spillRecoveryMode = b.spillRecoveryMode;
        this.spillBacklogOrder = b.spillBacklogOrder;
        this.spillReplayThreads = b.spillReplayThreads;
        this.spillReplayMaxBytesPerSec = b.spillReplayMaxBytesPerSec;
        this.spillStartupReconcile = b.spillStartupReconcile;
        this.spillFormat = b.spillFormat;
        this.diagnostics = b.diagnostics != null ? b.diagnostics : Diagnostics.NOOP;
        this.statsInterval = b.statsInterval != null ? b.statsInterval : Duration.ZERO;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int maxBatchRecords = 200;
        private int maxBatchBytes = 1024 * 1024;
        private Duration lingerTime = Duration.ofSeconds(1);
        // 10k bounds worst-case queue memory (~tens of MB of LogRecords) sensibly on a
        // fractional-vCore CloudHub worker. Raise it for high-throughput apps on larger heaps.
        private int queueCapacity = 10_000;
        private OverflowPolicy overflowPolicy = OverflowPolicy.DROP_NEWEST;
        private boolean gzip = true;
        private int maxRetries = 3;
        private Duration initialRetryBackoff = Duration.ofMillis(200);
        private Duration maxRetryBackoff = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration shutdownDrainTimeout = Duration.ofSeconds(5);
        private int flusherThreads = 1;
        private boolean tlsTrustAllCertificates = false;        // secure by default
        private boolean tlsVerifyHostName = true;               // secure by default
        private int maxMessageBytes = 32 * 1024;                // 32 KiB
        private boolean spillEnabled = true;
        private Path spillDirectory = null;
        private String spillInstanceId = null;
        private long maxSpillFileBytes = 8L * 1024 * 1024;      // 8 MiB — four segments per buffer
        private long maxTotalSpillBytes = 32L * 1024 * 1024;    // 32 MiB
        private long minFreeDiskBytes = 256L * 1024 * 1024;     // 256 MiB
        private Duration spillReplayInterval = Duration.ofSeconds(10);
        private boolean spillOnOverflow = true;
        private SpillRecoveryMode spillRecoveryMode = SpillRecoveryMode.ADOPT;
        private SpillBacklogOrder spillBacklogOrder = SpillBacklogOrder.OLDEST_FIRST;
        private int spillReplayThreads = 1;
        private long spillReplayMaxBytesPerSec = 0L;            // 0 = unlimited
        private boolean spillStartupReconcile = true;
        private SpillFormat spillFormat = SpillFormat.BINARY;
        private Diagnostics diagnostics = Diagnostics.NOOP;
        private Duration statsInterval = Duration.ZERO;

        public Builder maxBatchRecords(int v) { this.maxBatchRecords = v; return this; }
        public Builder maxBatchBytes(int v) { this.maxBatchBytes = v; return this; }
        public Builder lingerTime(Duration v) { this.lingerTime = v; return this; }
        public Builder queueCapacity(int v) { this.queueCapacity = v; return this; }
        public Builder overflowPolicy(OverflowPolicy v) { this.overflowPolicy = v; return this; }
        public Builder gzip(boolean v) { this.gzip = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder initialRetryBackoff(Duration v) { this.initialRetryBackoff = v; return this; }
        public Builder maxRetryBackoff(Duration v) { this.maxRetryBackoff = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }
        public Builder shutdownDrainTimeout(Duration v) { this.shutdownDrainTimeout = v; return this; }
        public Builder flusherThreads(int v) { this.flusherThreads = v; return this; }
        public Builder tlsTrustAllCertificates(boolean v) { this.tlsTrustAllCertificates = v; return this; }
        public Builder tlsVerifyHostName(boolean v) { this.tlsVerifyHostName = v; return this; }
        public Builder maxMessageBytes(int v) { this.maxMessageBytes = v; return this; }
        public Builder spillEnabled(boolean v) { this.spillEnabled = v; return this; }
        public Builder spillDirectory(Path v) { this.spillDirectory = v; return this; }
        public Builder spillDirectory(String v) { this.spillDirectory = (v == null || v.isBlank()) ? null : Path.of(v); return this; }
        public Builder spillInstanceId(String v) { this.spillInstanceId = (v == null || v.isBlank()) ? null : v; return this; }
        public Builder maxSpillFileBytes(long v) { this.maxSpillFileBytes = v; return this; }
        public Builder maxTotalSpillBytes(long v) { this.maxTotalSpillBytes = v; return this; }
        public Builder minFreeDiskBytes(long v) { this.minFreeDiskBytes = v; return this; }
        public Builder spillReplayInterval(Duration v) { this.spillReplayInterval = v; return this; }
        public Builder spillOnOverflow(boolean v) { this.spillOnOverflow = v; return this; }
        public Builder spillRecoveryMode(SpillRecoveryMode v) { if (v != null) this.spillRecoveryMode = v; return this; }
        public Builder spillBacklogOrder(SpillBacklogOrder v) { if (v != null) this.spillBacklogOrder = v; return this; }
        public Builder spillReplayThreads(int v) { this.spillReplayThreads = v; return this; }
        public Builder spillReplayMaxBytesPerSec(long v) { this.spillReplayMaxBytesPerSec = v; return this; }
        public Builder spillStartupReconcile(boolean v) { this.spillStartupReconcile = v; return this; }
        public Builder spillFormat(SpillFormat v) { if (v != null) this.spillFormat = v; return this; }
        public Builder diagnostics(Diagnostics v) { this.diagnostics = v != null ? v : Diagnostics.NOOP; return this; }
        public Builder statsInterval(Duration v) { this.statsInterval = v != null ? v : Duration.ZERO; return this; }

        public BatchConfig build() { return new BatchConfig(this); }
    }
}
