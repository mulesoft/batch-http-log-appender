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
package com.mulesoft.support.batchhttp.log4j2;

import com.mulesoft.support.batchhttp.core.BatchConfig;
import com.mulesoft.support.batchhttp.core.BatchingLogShipper;
import com.mulesoft.support.batchhttp.core.LogRecord;
import com.mulesoft.support.batchhttp.core.sink.DatadogSink;
import com.mulesoft.support.batchhttp.core.sink.DynatraceSink;
import com.mulesoft.support.batchhttp.core.sink.ElasticsearchSink;
import com.mulesoft.support.batchhttp.core.sink.GenericNdjsonSink;
import com.mulesoft.support.batchhttp.core.sink.LogSink;
import com.mulesoft.support.batchhttp.core.sink.NewRelicSink;
import com.mulesoft.support.batchhttp.core.sink.SplunkHecSink;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.StringLayout;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A batching, asynchronous replacement for Log4j2's stock {@code HttpAppender}.
 *
 * <p>Instead of one synchronous HTTP request per event, events are enqueued
 * (non-blocking) and shipped in batches by background threads to Datadog,
 * Splunk HEC, New Relic, Dynatrace, Elasticsearch, or any HTTP intake.
 *
 * <p>Example {@code log4j2.xml}:
 * <pre>{@code
 * <BatchHttp name="datadog" vendor="datadog"
 *            apiKey="${env:DD_API_KEY}" service="orders" source="java"
 *            maxBatchRecords="500" lingerMillis="1000" gzip="true">
 *     <PatternLayout pattern="%m"/>
 * </BatchHttp>
 * }</pre>
 */
@Plugin(name = "BatchHttp", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class BatchHttpAppender extends AbstractAppender {

    private final BatchingLogShipper shipper;
    private final boolean includeStackTrace;

    private BatchHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                              boolean ignoreExceptions, BatchingLogShipper shipper, boolean includeStackTrace) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.shipper = shipper;
        this.includeStackTrace = includeStackTrace;
    }

    @Override
    public void append(LogEvent event) {
        // Layout (if any) renders the message; otherwise use the formatted message.
        // Strip only a trailing line separator a `%n` in the pattern would add — not a full
        // trim(), which would also eat meaningful leading/trailing spaces in the message.
        String message;
        Layout<? extends Serializable> layout = getLayout();
        if (layout instanceof StringLayout) {
            // PatternLayout and the JSON/CSV layouts are all StringLayouts: take the text they
            // already rendered. Encoding it to bytes only to decode it again would cost an extra
            // copy per event on the hot path, and the layout's charset is not ours to apply —
            // it governs bytes written to a file or socket, whereas this appender ships UTF-8
            // JSON. A non-UTF-8 layout charset (e.g. ISO-8859-1) would round-trip every
            // non-ASCII character into U+FFFD.
            message = stripTrailingNewline(((StringLayout) layout).toSerializable(event));
        } else if (layout != null) {
            // A binary layout has no text form; decode its bytes as UTF-8 (never the platform
            // default, which would corrupt non-ASCII text on any non-UTF-8 host).
            message = stripTrailingNewline(new String(layout.toByteArray(event), java.nio.charset.StandardCharsets.UTF_8));
        } else {
            message = event.getMessage().getFormattedMessage();
        }

        LogRecord.Builder b = LogRecord.builder()
                .timestampMillis(event.getTimeMillis())
                .level(event.getLevel().toString())
                .loggerName(event.getLoggerName() == null ? "" : event.getLoggerName())
                .threadName(event.getThreadName() == null ? "" : event.getThreadName())
                .message(message);

        if (includeStackTrace && event.getThrown() != null) {
            b.throwable(renderThrowable(event.getThrown()));
        }
        // Promote MDC/ThreadContext entries to structured attributes.
        event.getContextData().forEach((k, v) -> {
            if (v != null) b.attribute(k, v.toString());
        });

        shipper.offer(b.build());
    }

    /** Drop a single trailing {@code \r\n}, {@code \n}, or {@code \r} that a {@code %n} in the layout appends. */
    private static String stripTrailingNewline(String s) {
        int end = s.length();
        if (end > 0 && s.charAt(end - 1) == '\n') end--;
        if (end > 0 && s.charAt(end - 1) == '\r') end--;
        return end == s.length() ? s : s.substring(0, end);
    }

    private static String renderThrowable(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        setStopping();
        boolean stopped = super.stop(timeout, timeUnit, false);
        shipper.close(); // drains and flushes remaining events
        setStopped();
        return stopped;
    }

    /** Exposed for tests/diagnostics. */
    public BatchingLogShipper.Stats stats() { return shipper.stats(); }

    // ------------------------------------------------------------------
    // Log4j2 plugin factory: maps log4j2.xml attributes onto the engine.
    // ------------------------------------------------------------------
    @PluginFactory
    public static BatchHttpAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filter") Filter filter,
            @PluginAttribute(value = "ignoreExceptions", defaultBoolean = true) boolean ignoreExceptions,
            // --- routing / vendor selection ---
            @PluginAttribute("vendor") String vendor,           // datadog | splunk | newrelic | dynatrace | elasticsearch | generic
            @PluginAttribute("url") String url,                 // generic / splunk base url; dynatrace environment url; elasticsearch endpoint
            @PluginAttribute("site") String site,               // datadog intake host
            @PluginAttribute("host") String host,               // newrelic intake endpoint (per region) / splunk host field
            @PluginAttribute("hostname") String hostname,       // newrelic per-log hostname facet (reserved attr)
            @PluginAttribute(value = "apiKey", sensitive = true) String apiKey,
            @PluginAttribute(value = "token", sensitive = true) String token,
            @PluginAttribute("service") String service,
            @PluginAttribute("source") String source,
            @PluginAttribute("index") String index,
            @PluginAttribute("sourcetype") String sourcetype,
            // --- batching tuning ---
            @PluginAttribute(value = "maxBatchRecords", defaultInt = 200) int maxBatchRecords,
            @PluginAttribute(value = "maxBatchBytes", defaultInt = 1048576) int maxBatchBytes,
            @PluginAttribute(value = "lingerMillis", defaultLong = 1000) long lingerMillis,
            @PluginAttribute(value = "queueCapacity", defaultInt = 10_000) int queueCapacity,
            @PluginAttribute(value = "overflowPolicy", defaultString = "DROP_NEWEST") String overflowPolicy,
            @PluginAttribute(value = "gzip", defaultBoolean = true) boolean gzip,
            @PluginAttribute(value = "maxRetries", defaultInt = 3) int maxRetries,
            @PluginAttribute(value = "initialRetryBackoffMillis", defaultLong = 200) long initialRetryBackoffMillis,
            @PluginAttribute(value = "maxRetryBackoffMillis", defaultLong = 10_000) long maxRetryBackoffMillis,
            @PluginAttribute(value = "requestTimeoutMillis", defaultLong = 10_000) long requestTimeoutMillis,
            @PluginAttribute(value = "shutdownDrainTimeoutMillis", defaultLong = 5_000) long shutdownDrainTimeoutMillis,
            @PluginAttribute(value = "flusherThreads", defaultInt = 1) int flusherThreads,
            @PluginAttribute(value = "maxMessageBytes", defaultInt = 32768) int maxMessageBytes,
            @PluginAttribute(value = "includeStackTrace", defaultBoolean = true) boolean includeStackTrace,
            // Datadog's `date` and New Relic's `timestamp` are reserved: supplying either makes
            // the vendor use it as the log's official time instead of stamping arrival, so
            // shipping delay becomes unobservable. false omits it (datadog/newrelic only).
            @PluginAttribute(value = "includeTimestamp", defaultBoolean = true) boolean includeTimestamp,
            // --- TLS (insecure escape hatches for self-signed / internal collectors) ---
            // trustAllCertificates=true accepts any server cert (self-signed/unknown CA) for THIS
            // appender's transport only. verifyHostName mirrors Log4j2's SSL attribute (default true);
            // false is JVM-global (see BatchConfig / the transport) so prefer a -M-D JVM arg on Mule.
            @PluginAttribute(value = "trustAllCertificates", defaultBoolean = false) boolean trustAllCertificates,
            @PluginAttribute(value = "verifyHostName", defaultBoolean = true) boolean verifyHostName,
            // --- disk-spill buffer (durability safety net; on by default) ---
            // spillEnabled=true means an appender that configures nothing writes log payloads to
            // ${java.io.tmpdir}/batch-http-spill/<sink>/ on a failure path. Set false where that
            // is unwanted — notably a memory-backed /tmp, where the buffer costs RAM, not disk.
            @PluginAttribute(value = "spillEnabled", defaultBoolean = true) boolean spillEnabled,
            @PluginAttribute("spillDirectory") String spillDirectory,
            @PluginAttribute("spillInstanceId") String spillInstanceId,
            @PluginAttribute(value = "maxSpillFileBytes", defaultLong = 8388608L) long maxSpillFileBytes,
            @PluginAttribute(value = "maxTotalSpillBytes", defaultLong = 33554432L) long maxTotalSpillBytes,
            @PluginAttribute(value = "minFreeDiskBytes", defaultLong = 268435456L) long minFreeDiskBytes,
            @PluginAttribute(value = "spillReplayIntervalMillis", defaultLong = 10_000) long spillReplayIntervalMillis,
            @PluginAttribute(value = "spillOnOverflow", defaultBoolean = true) boolean spillOnOverflow,
            // --- crash-resilience hardening ---
            @PluginAttribute(value = "spillRecoveryMode", defaultString = "ADOPT") String spillRecoveryMode,
            @PluginAttribute(value = "spillBacklogOrder", defaultString = "OLDEST_FIRST") String spillBacklogOrder,
            @PluginAttribute(value = "spillReplayThreads", defaultInt = 1) int spillReplayThreads,
            @PluginAttribute(value = "spillReplayMaxBytesPerSec", defaultLong = 0L) long spillReplayMaxBytesPerSec,
            @PluginAttribute(value = "spillStartupReconcile", defaultBoolean = true) boolean spillStartupReconcile,
            @PluginAttribute(value = "spillFormat", defaultString = "BINARY") String spillFormat,
            // --- diagnostics (self-observability) ---
            // Threshold for the appender's OWN health messages, written to Log4j2's StatusLogger
            // (NOT back through this appender): WARN (default) surfaces drops/overflow/fatal
            // rejections; INFO/DEBUG add the stats heartbeat and per-attempt retry chatter.
            @PluginAttribute(value = "diagnosticsLevel", defaultString = "WARN") String diagnosticsLevel,
            // When > 0, log a one-line stats snapshot to the StatusLogger this often (a delivery
            // heartbeat). 0 (default) disables it. Requires diagnosticsLevel INFO or finer to show.
            @PluginAttribute(value = "statsIntervalMillis", defaultLong = 0L) long statsIntervalMillis) {

        if (name == null) {
            LOGGER.error("BatchHttp appender requires a name");
            return null;
        }

        String v = normalizeVendor(vendor);
        LogSink sink;
        try {
            sink = buildSink(v, url, site, host, hostname, apiKey, token,
                    service, source, index, sourcetype, includeTimestamp);
        } catch (RuntimeException e) {
            // The sink builders reject a missing credential by throwing. Left uncaught it would
            // escape the @PluginFactory and Log4j2 would report only a generic "unable to invoke
            // factory method", leaving the operator with no idea which attribute is missing.
            // Naming the vendor and its required attributes here is the difference between a
            // two-minute fix and an hour of guessing from a CloudHub runtime log.
            LOGGER.error("BatchHttp appender '{}' could not build its '{}' sink: {}. Required "
                            + "log4j2.xml attributes for vendor '{}': {}. This appender is disabled "
                            + "and will ship no logs.",
                    name, v, e.getMessage(), v, requiredAttributes(v), e);
            return null;
        }

        BatchConfig config = BatchConfig.builder()
                .maxBatchRecords(maxBatchRecords)
                .maxBatchBytes(maxBatchBytes)
                .lingerTime(Duration.ofMillis(lingerMillis))
                .queueCapacity(queueCapacity)
                .overflowPolicy(parseEnum(BatchConfig.OverflowPolicy.class, overflowPolicy, BatchConfig.OverflowPolicy.DROP_NEWEST))
                .gzip(gzip)
                .maxRetries(maxRetries)
                .initialRetryBackoff(Duration.ofMillis(initialRetryBackoffMillis))
                .maxRetryBackoff(Duration.ofMillis(maxRetryBackoffMillis))
                .requestTimeout(Duration.ofMillis(requestTimeoutMillis))
                .shutdownDrainTimeout(Duration.ofMillis(shutdownDrainTimeoutMillis))
                .flusherThreads(flusherThreads)
                .tlsTrustAllCertificates(trustAllCertificates)
                .tlsVerifyHostName(verifyHostName)
                .maxMessageBytes(maxMessageBytes)
                .spillEnabled(spillEnabled)
                .spillDirectory(spillDirectory)
                // Default the spill instance id to the appender name so each appender's
                // segment files are distinct even when two apps share a spill directory.
                .spillInstanceId(spillInstanceId != null && !spillInstanceId.isBlank() ? spillInstanceId : name)
                .maxSpillFileBytes(maxSpillFileBytes)
                .maxTotalSpillBytes(maxTotalSpillBytes)
                .minFreeDiskBytes(minFreeDiskBytes)
                .spillReplayInterval(Duration.ofMillis(spillReplayIntervalMillis))
                .spillOnOverflow(spillOnOverflow)
                .spillRecoveryMode(parseEnum(BatchConfig.SpillRecoveryMode.class, spillRecoveryMode, BatchConfig.SpillRecoveryMode.ADOPT))
                .spillBacklogOrder(parseEnum(BatchConfig.SpillBacklogOrder.class, spillBacklogOrder, BatchConfig.SpillBacklogOrder.OLDEST_FIRST))
                .spillReplayThreads(spillReplayThreads)
                .spillReplayMaxBytesPerSec(spillReplayMaxBytesPerSec)
                .spillStartupReconcile(spillStartupReconcile)
                .spillFormat(parseEnum(BatchConfig.SpillFormat.class, spillFormat, BatchConfig.SpillFormat.BINARY))
                // Route the engine's own diagnostics to Log4j2's StatusLogger (recursion-safe —
                // never flows back through this appender), gated at the configured level.
                .diagnostics(new StatusLoggerDiagnostics(
                        org.apache.logging.log4j.Level.toLevel(diagnosticsLevel, org.apache.logging.log4j.Level.WARN)))
                .statsInterval(statsIntervalMillis > 0 ? Duration.ofMillis(statsIntervalMillis) : Duration.ZERO)
                .build();

        BatchingLogShipper shipper = new BatchingLogShipper(sink, config);
        return new BatchHttpAppender(name, filter, layout, ignoreExceptions, shipper, includeStackTrace);
    }

    /**
     * Case-insensitively parse {@code s} into an enum constant of {@code type}, falling
     * back to {@code fallback} when the value is null, blank, or unrecognized. One generic
     * parser replaces the per-enum copies (overflow policy, recovery mode, backlog order,
     * spill format) so adding an enum-valued attribute needs no new helper.
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String s, E fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            // Locale.ROOT: the default locale would fold "ignore" to "İGNORE" on a Turkish-locale
            // JVM, silently falling back instead of selecting SpillRecoveryMode.IGNORE.
            return Enum.valueOf(type, s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Unrecognized and unset both mean "generic"; {@code Locale.ROOT} so a Turkish-locale JVM folds ASCII the same way. */
    private static String normalizeVendor(String vendor) {
        return vendor == null ? "generic" : vendor.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The {@code log4j2.xml} attributes a vendor cannot start without, named as the operator
     * writes them (the sink builders speak their own vocabulary — {@code baseUrl},
     * {@code environmentUrl} — which does not appear in any config file).
     */
    private static String requiredAttributes(String vendor) {
        switch (vendor) {
            case "datadog":   return "apiKey";
            case "splunk":
            case "splunkhec": return "url, token";
            case "newrelic":  return "apiKey";
            case "dynatrace": return "url, token (apiKey is accepted as an alias for token)";
            case "elasticsearch":
            case "elastic":
            case "elk":       return "url, apiKey";
            default:          return "url";
        }
    }

    /** Builds the vendor sink, or throws {@link IllegalArgumentException} naming the missing credential. */
    private static LogSink buildSink(String v, String url, String site, String host, String hostname,
                                     String apiKey, String token, String service, String source,
                                     String index, String sourcetype, boolean includeTimestamp) {
        switch (v) {
            case "datadog":
                // `host` maps to Datadog's reserved host facet (optional); `site` is the
                // intake host. Distinct concepts, so datadog reads both attributes.
                return DatadogSink.builder()
                        .site(site).apiKey(apiKey).service(service).source(source).host(host)
                        .includeTimestamp(includeTimestamp).build();
            case "splunk":
            case "splunkhec":
                return SplunkHecSink.builder()
                        .baseUrl(url).token(token).index(index).sourcetype(sourcetype).host(host).build();
            case "newrelic":
                // `host` selects the intake endpoint for the account's region; `hostname` and
                // `service` are optional per-log facets (New Relic's reserved host attribute +
                // a plain attribute). Distinct concepts, so newrelic reads all three.
                return NewRelicSink.builder()
                        .host(host).apiKey(apiKey).service(service).hostname(hostname)
                        .includeTimestamp(includeTimestamp).build();
            case "dynatrace":
                // Dynatrace uses the environment URL (via `url`) and an Api-Token; accept
                // the token under either `token` or `apiKey` for config convenience.
                return DynatraceSink.builder()
                        .environmentUrl(url)
                        .apiToken(token != null ? token : apiKey)
                        .source(source)
                        .build();
            case "elasticsearch":
            case "elastic":
            case "elk":
                // `url` is the Elasticsearch endpoint and `index` the target data stream (default
                // logs-mule-default); `service`/`host` become the ECS service.name/host.name fields.
                return ElasticsearchSink.builder()
                        .url(url).apiKey(apiKey).index(index).service(service).host(host).build();
            case "generic":
            default:
                GenericNdjsonSink.Builder gb = GenericNdjsonSink.builder().url(url);
                if (apiKey != null) gb.header("Authorization", "Bearer " + apiKey);
                return gb.build();
        }
    }
}
