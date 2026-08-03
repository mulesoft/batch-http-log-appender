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

import com.mulesoft.support.batchhttp.core.sink.LogSink;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * Sends serialized batches over HTTP using the JDK 11+ {@link HttpClient}
 * (connection pooling + keep-alive for free, no third-party dependency). Handles
 * optional gzip and classifies responses into retryable vs. terminal.
 *
 * <p>Deliberately pinned to HTTP/1.1 with a single-thread daemon executor. A log
 * shipper sends a steady trickle of independent POSTs from one (or a few) flusher
 * threads — it has no use for HTTP/2 stream multiplexing, and HTTP/2's extra
 * framing/flow-control machinery is pure overhead here. The default {@link HttpClient}
 * executor is an <em>unbounded cached pool of non-daemon threads</em>, which on a
 * fractional-vCore CloudHub worker both wastes threads and can keep the JVM from
 * exiting cleanly; a single daemon thread is plenty for serialized batch sends.
 *
 * <p>The transport owns that executor and must be {@link #close() closed} when its
 * owner shuts down. A pool thread is created by the platform with the creating
 * thread's context classloader — under Mule that is the deploying application's
 * classloader — so an executor left running after an appender stops pins the whole
 * app classloader for the life of the JVM. With repeated redeploys into one runtime
 * that is a genuine leak, not a tidiness concern, hence both the explicit
 * {@link #close()} and the core-thread timeout below as a second line of defence.
 */
public final class HttpTransport implements AutoCloseable {

    /** Classification of a single send attempt. */
    public enum Result {
        SUCCESS,        // 2xx
        RETRYABLE,      // 408/429/5xx or network error — worth retrying
        FATAL           // 4xx (except 408/429) — body is bad, retrying won't help
    }

    /**
     * Result of a single send attempt plus the diagnostic detail behind it: the HTTP
     * status (0 for a network-level failure with no response) and a short human-readable
     * reason ({@code "HTTP 403"}, {@code "ConnectException: Connection refused"}). Lets the
     * engine log <em>why</em> a batch failed without the transport itself touching a logger.
     */
    public static final class Outcome {
        public final Result result;
        /** HTTP status code, or 0 when the request never produced a response (network error/timeout). */
        public final int statusCode;
        /** Short, log-safe reason for the outcome (never null). */
        public final String detail;
        /**
         * Delay the endpoint explicitly asked us to wait, in millis, parsed from a
         * {@code Retry-After} response header; {@code -1} when the header was absent or
         * unparseable. Vendors that rate-limit (Datadog's Logs intake answers 429 with it)
         * use this to state when capacity returns, so honouring it backs off correctly
         * instead of hammering with our own guess.
         */
        public final long retryAfterMillis;

        Outcome(Result result, int statusCode, String detail) {
            this(result, statusCode, detail, -1L);
        }

        Outcome(Result result, int statusCode, String detail, long retryAfterMillis) {
            this.result = result;
            this.statusCode = statusCode;
            this.detail = detail;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    private final HttpClient client;
    private final ExecutorService executor;
    private final BatchConfig config;

    /** JDK-internal switch that turns off HttpClient hostname verification. Read once, at first HTTPS use. */
    private static final String DISABLE_HOSTNAME_VERIFICATION_PROP =
            "jdk.internal.httpclient.disableHostnameVerification";

    public HttpTransport(BatchConfig config) {
        this(config, "default");
    }

    /**
     * @param name short identifier (normally the sink name) woven into this transport's
     *             thread names, so a thread dump from a JVM hosting several appenders
     *             shows which one each transport thread belongs to.
     */
    public HttpTransport(BatchConfig config, String name) {
        this.config = config;
        this.executor = singleDaemonExecutor(name);

        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .executor(executor);

        if (config.tlsTrustAllCertificates) {
            // Per-client trust-all context: this transport accepts any server cert (self-signed,
            // expired, unknown CA). Scoped to this HttpClient, so the rest of the JVM keeps normal
            // validation. curl -k / Log4j2 "no TrustStore" equivalent.
            b.sslContext(trustAllContext());
            config.diagnostics.log(Diagnostics.Level.WARN,
                    "TLS certificate validation DISABLED (tlsTrustAllCertificates=true): this transport "
                            + "trusts any server certificate. Use only against a trusted internal collector.");
        }

        if (!config.tlsVerifyHostName) {
            // The JDK HttpClient offers no per-client hostname-verification toggle (it ignores
            // SSLParameters.setEndpointIdentificationAlgorithm(null)); the only lever is this
            // JVM-global system property, read once by the JDK at first HTTPS use. We set it here as
            // a convenience, but if any HTTPS traffic ran earlier it is already cached — hence the
            // hint to set it as a -D/-M-D JVM arg for reliable effect.
            disableHostnameVerificationGlobally(config.diagnostics);
        }

        this.client = b.build();
    }

    /** Build an {@link SSLContext} whose trust manager accepts every certificate chain. */
    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = { new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { /* trust all */ }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { /* trust all */ }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            // Effectively impossible with the JDK's built-in TLS provider; fail loud rather than
            // silently fall back to strict validation the caller explicitly opted out of.
            throw new IllegalStateException("Failed to build trust-all SSLContext", e);
        }
    }

    /**
     * Set the JVM-global hostname-verification switch and always warn. The JDK caches the value at
     * first HTTPS use and exposes no way to tell whether that already happened, so we cannot detect
     * (only warn) that a late set may have no effect — hence the unconditional WARN and the -D hint.
     */
    private static void disableHostnameVerificationGlobally(Diagnostics diagnostics) {
        String existing = System.getProperty(DISABLE_HOSTNAME_VERIFICATION_PROP);
        if (!"true".equalsIgnoreCase(existing)) {
            System.setProperty(DISABLE_HOSTNAME_VERIFICATION_PROP, "true");
        }
        diagnostics.log(Diagnostics.Level.WARN,
                "TLS hostname verification DISABLED (tlsVerifyHostName=false) via the JVM-global '"
                        + DISABLE_HOSTNAME_VERIFICATION_PROP + "' property. This affects every "
                        + "java.net.http.HttpClient in the JVM and only takes effect if set before the "
                        + "first HTTPS request; for reliable effect pass it as a -D (Mule: -M-D) JVM arg.");
    }

    /**
     * One daemon thread for the client's async plumbing — never blocks JVM exit, never grows
     * unbounded. Built by hand rather than via {@code Executors.newSingleThreadExecutor} so the
     * core thread can be given a keep-alive: an idle transport then releases its thread (and the
     * classloader that thread pins) on its own, even if an embedder forgets to {@link #close()}.
     */
    private static ExecutorService singleDaemonExecutor(String name) {
        final AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "batch-http-transport-" + name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), tf);
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    /**
     * Release the transport's thread pool. Idempotent. Called by the engine's own
     * {@code close()} after the flushers have stopped, so no send is in flight; leaving it
     * unclosed strands a live thread holding the deploying application's classloader.
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * A request body prepared once for one-or-more send attempts. Gzip (when enabled) is
     * applied here rather than inside each {@link #send} call, so a batch retried against a
     * failing endpoint is compressed once, not re-deflated on every attempt. Reuse the same
     * instance across a retry loop (see {@link #prepare}).
     */
    public static final class Payload {
        final byte[] bytes;
        final boolean gzipped;
        Payload(byte[] bytes, boolean gzipped) {
            this.bytes = bytes;
            this.gzipped = gzipped;
        }
    }

    /** Prepare a serialized batch body for sending — gzipping once if configured — so retries reuse it. */
    public Payload prepare(byte[] body) {
        if (config.gzip) {
            return new Payload(gzip(body), true);
        }
        return new Payload(body, false);
    }

    private static byte[] gzip(byte[] data) {
        // Deflating into an in-memory ByteArrayOutputStream cannot actually raise IOException;
        // the checked type is an artifact of the stream API, so we treat it as unreachable.
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        // 8 KiB deflate buffer (vs. the 512-byte JDK default): far fewer deflate() calls
        // on a multi-KiB batch body, which is the only thing we ever gzip.
        try (GZIPOutputStream gz = new GZIPOutputStream(bos, 8192)) {
            gz.write(data);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("in-memory gzip failed", e);
        }
        return bos.toByteArray();
    }

    /** Performs one send attempt of an already-prepared body. Never throws — maps errors to an {@link Outcome}. */
    public Outcome send(LogSink sink, Payload payload) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(sink.endpoint())
                    .timeout(config.requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload.bytes));

            for (Map.Entry<String, String> h : sink.headers().entrySet()) {
                req.header(h.getKey(), h.getValue());
            }
            if (payload.gzipped) {
                req.header("Content-Encoding", "gzip");
            }

            HttpResponse<Void> resp = client.send(req.build(), HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            Result result = classify(status);
            long retryAfter = result == Result.RETRYABLE
                    ? parseRetryAfter(resp.headers().firstValue("Retry-After").orElse(null))
                    : -1L;
            return new Outcome(result, status, "HTTP " + status, retryAfter);
        } catch (java.io.IOException e) {
            // connection reset, timeout, DNS, etc. — transient, retry
            return new Outcome(Result.RETRYABLE, 0, Diagnostics.describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Result.RETRYABLE, 0, "interrupted");
        }
    }

    /** Convenience for single-attempt callers/tests: prepare (gzip once) and send in one call. */
    public Outcome send(LogSink sink, byte[] body) {
        return send(sink, prepare(body));
    }

    private static Result classify(int status) {
        if (status >= 200 && status < 300) return Result.SUCCESS;
        if (status == 408 || status == 429 || status >= 500) return Result.RETRYABLE;
        return Result.FATAL; // other 4xx: malformed/auth/too-large — don't hammer the endpoint
    }

    /**
     * Parse a {@code Retry-After} value into millis. RFC 9110 allows either delta-seconds
     * (what Datadog and most log intakes send on a 429) or an HTTP-date; both are accepted,
     * and anything else — absent, malformed, or a date already in the past — yields
     * {@code -1} so the caller falls back to its own exponential backoff.
     */
    static long parseRetryAfter(String header) {
        if (header == null) return -1L;
        String v = header.trim();
        if (v.isEmpty()) return -1L;
        try {
            long seconds = Long.parseLong(v);
            return seconds >= 0 ? seconds * 1000L : -1L;
        } catch (NumberFormatException notDeltaSeconds) {
            // fall through to the HTTP-date form
        }
        try {
            long millis = java.time.ZonedDateTime
                    .parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - System.currentTimeMillis();
            return millis > 0 ? millis : -1L;
        } catch (java.time.format.DateTimeParseException notAnHttpDate) {
            return -1L;
        }
    }
}
