# Configuration reference

This page lists every option of the `<BatchHttp>` Log4j2 appender. Options are XML attributes on
the `<BatchHttp>` element in `log4j2.xml`; the only nested elements are a Log4j2 Layout and an
optional Filter. Every vendor page ([Datadog](vendors/datadog.md), [New Relic](vendors/newrelic.md),
[Elasticsearch](vendors/elasticsearch.md), [Dynatrace](vendors/dynatrace.md),
[Splunk HEC](vendors/splunk.md), [generic HTTP](vendors/generic.md)) links here instead of
repeating this list, so the defaults and the rules below are stated in one place only, and they
match the code (`BatchHttpAppender.createAppender`, version 0.2.0). Supply secrets (`apiKey`,
`token`) only through Log4j2 lookups, `${env:...}` or `${sys:...}`: `log4j2.xml` is resolved by
Log4j2, not by Mule, so Mule `${...}` placeholders and `secure::` properties do not work here
(see [Using it from a Mule app](mule.md)).

```xml
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <BatchHttp name="datadog" vendor="datadog"
                   apiKey="${env:DD_API_KEY}" service="orders-api"
                   maxBatchRecords="500" lingerMillis="1000">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <AsyncRoot level="INFO">
            <AppenderRef ref="datadog"/>
        </AsyncRoot>
    </Loggers>
</Configuration>
```

The `packages` attribute on `<Configuration>` is required wherever the library sits on the
application classloader (every Mule 4 / CloudHub app). Without it Log4j2 does not know the
`<BatchHttp>` element and ships nothing.

**Contents:**
[Required per vendor](#required-per-vendor) ·
[Log4j2 standard](#log4j2-standard) ·
[Identity and vendor](#identity-and-vendor) ·
[Connection and authentication](#connection-and-authentication) ·
[Batching](#batching) ·
[Queue and overflow](#queue-and-overflow) ·
[Retries and timeouts](#retries-and-timeouts) ·
[Disk spill](#disk-spill) ·
[TLS](#tls) ·
[Payload and fields](#payload-and-fields) ·
[Diagnostics](#diagnostics) ·
[Vendor field mapping](#vendor-field-mapping) ·
[Invalid values](#how-invalid-values-behave) ·
[Builder-only knobs](#builder-only-knobs) ·
[Defaults that changed](#defaults-that-changed)

## Required per vendor

| Vendor (`vendor=`) | Required attributes | Page |
|--------------------|---------------------|------|
| `datadog` | [`apiKey`](#opt-apikey) | [Datadog](vendors/datadog.md) |
| `newrelic` | [`apiKey`](#opt-apikey) | [New Relic](vendors/newrelic.md) |
| `elasticsearch` (aliases `elastic`, `elk`) | [`url`](#opt-url) + [`apiKey`](#opt-apikey) | [Elasticsearch](vendors/elasticsearch.md) |
| `dynatrace` | [`url`](#opt-url) + [`token`](#opt-token) (`apiKey` accepted as an alias) | [Dynatrace](vendors/dynatrace.md) |
| `splunk` (alias `splunkhec`) | [`url`](#opt-url) + [`token`](#opt-token) | [Splunk HEC](vendors/splunk.md) |
| `generic` (also: unset, or any unrecognised value) | [`url`](#opt-url) | [Generic HTTP](vendors/generic.md) |

When a required attribute is missing, the appender is not created. Log4j2's `StatusLogger`
reports one ERROR at startup naming the appender, the vendor, the reason, and every required
attribute of that vendor under its `log4j2.xml` name, followed by "This appender is disabled and
will ship no logs". This message is gated only by `<Configuration status>`, not by
[`diagnosticsLevel`](#opt-diagnosticslevel); `status="OFF"` hides it.

## Log4j2 standard

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-name"></a>`name` | String | — | all | Appender name, referenced from `<AppenderRef ref="..."/>`. Required: without it the factory logs "BatchHttp appender requires a name" and returns null, so the appender does not exist. It is also the default [`spillInstanceId`](#opt-spillinstanceid), which keeps the spill files of two appenders apart when they share a spill directory. |
| <a id="opt-ignoreexceptions"></a>`ignoreExceptions` | boolean | `true` | all | Standard Log4j2 flag. When true, an exception thrown inside `append()` is logged internally and swallowed; when false it propagates to the logging caller. In practice `append()` rarely throws: enqueueing never blocks on the network, and send, overflow and spill failures are counted and reported through diagnostics rather than thrown. Only a Layout that throws while rendering would surface here. |
| <a id="opt-layout"></a>`Layout` | element | — | all | Nested Log4j2 layout (for example `<PatternLayout>`, `<JsonTemplateLayout>`). It renders only the record's message field: level, logger, thread, timestamp, stack trace and MDC are sent as separate vendor fields whatever the layout (the stack trace subject to [`includeStackTrace`](#opt-includestacktrace), the Datadog and New Relic event time subject to [`includeTimestamp`](#opt-includetimestamp)). A layout's output has one trailing `\n`, `\r\n` or `\r` (what `%n` adds) stripped; other whitespace is kept. With no Layout the event's formatted message is used unchanged. See [Layout and charset](#layout-and-charset). |
| <a id="opt-filter"></a>`Filter` | element | — | all | Standard Log4j2 appender-level filter (for example `<ThresholdFilter level="INFO"/>`; use `<Filters>` to combine several). Events it rejects never reach the queue. Optional. |

### Layout and charset

For a `StringLayout` (`<PatternLayout>` and the JSON and CSV layouts) the appender takes the
text the layout has already rendered and ships it as UTF-8 JSON, so a `charset` attribute on the
layout has no effect on the request bytes. This differs from Log4j2's stock `HttpAppender`,
where the layout's charset governs the request encoding: when porting a config from it, drop the
attribute rather than expecting it to change anything. A non-string layout's bytes are decoded
as UTF-8. Multi-byte content (accents, CJK, emoji) is preserved either way.

Every non-null ThreadContext (MDC) entry becomes a structured attribute of the record. See
[Vendor field mapping](#vendor-field-mapping) for where it lands per vendor.

## Identity and vendor

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-vendor"></a>`vendor` | String | — | all | Selects the vendor sink: endpoint, auth header, body framing, field names and the vendor's per-request ceilings. Values: `datadog` \| `splunk` (alias `splunkhec`) \| `newrelic` \| `dynatrace` \| `elasticsearch` (aliases `elastic`, `elk`) \| `generic`; trimmed and case-insensitive. Unset means `generic`. An unrecognised value also falls back silently to `generic`, which then requires `url`: a misspelt vendor fails startup with "Required log4j2.xml attributes for vendor '...': url" if `url` is missing, or quietly ships generic NDJSON to `url` if it is set. See [Required per vendor](#required-per-vendor). |
| <a id="opt-service"></a>`service` | String | — | datadog, newrelic, elasticsearch | Service identity on every record, taken from config rather than from the log message. Datadog: reserved `service` attribute, emitted whenever the attribute is present (an empty value is sent as `""`). New Relic: plain `service` attribute, queryable in NRQL, omitted when null or blank. Elasticsearch: ECS `service.name`, omitted when null or blank. Ignored by splunk, dynatrace and generic. |
| <a id="opt-source"></a>`source` | String | — | datadog, dynatrace | Datadog: the `ddsource` field, which Datadog uses to pick a log pipeline; when unset the sink sends `java`. Dynatrace: the `log.source` attribute, omitted when unset; handy as a filter in Grail. Ignored by splunk, newrelic, elasticsearch and generic. |
| <a id="opt-hostname"></a>`hostname` | String | — | newrelic | New Relic only: the reserved `hostname` attribute on every record, which the New Relic Logs UI shows as the host. Omitted when null or blank. Not the same as [`host`](#opt-host), which for New Relic selects the intake endpoint. Ignored by every other vendor. |
| <a id="opt-index"></a>`index` | String | — | splunk, elasticsearch | Splunk HEC: the per-event `index` field, emitted when set; otherwise HEC uses the token's default index. Elasticsearch: the target data stream (or index) in `<url>/<index>/_bulk`. It is trimmed, and null or blank means `logs-mule-default`, which matches Elasticsearch's built-in `logs-*-*` template, so the stream is created on first write. Ignored by datadog, newrelic, dynatrace and generic. |
| <a id="opt-sourcetype"></a>`sourcetype` | String | — | splunk | Splunk HEC only: the per-event `sourcetype` field, emitted when set. Ignored by every other vendor. |

## Connection and authentication

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-url"></a>`url` | String | — | splunk, dynatrace, elasticsearch, generic | Endpoint URL; what is expected depends on the vendor. Splunk (required): the HEC base URL (scheme, host, port); one trailing slash is removed and `/services/collector/event` is always appended, so do not pass the full HEC path. Dynatrace (required): the environment URL Dynatrace publishes (SaaS or ActiveGate form) or a full `.../api/v2/logs/ingest` URL; trimmed, trailing slashes removed, ingest path appended only when absent. Elasticsearch (required): the endpoint (scheme, host, optional port); trimmed, must start with `http://` or `https://` (checked at startup), one trailing slash removed, then `/<index>/_bulk` is appended. Generic (required): the full endpoint, used verbatim. Ignored by datadog (use [`site`](#opt-site)) and newrelic (use [`host`](#opt-host)). A URL that cannot be parsed fails the build with the named-attribute ERROR, and the appender is disabled. |
| <a id="opt-site"></a>`site` | String | — | datadog | Datadog only: the log-intake host for the Datadog site your organisation lives in; the sink posts to `https://<site>/api/v2/logs`. It is a host name, not a site label, with no scheme or path; take the value Datadog documents for your site. Unset means the US1 intake (`http-intake.logs.datadoghq.com`). Ignored by every other vendor. |
| <a id="opt-host"></a>`host` | String | — | datadog, splunk, newrelic, elasticsearch | Means different things per vendor. New Relic: selects the intake; the sink posts to `https://<host>/log/v1`, unset means the US Log API (`log-api.newrelic.com`), and EU accounts must set their region's Log API host. It is never emitted as a field (use [`hostname`](#opt-hostname) for that). Datadog: the reserved `host` facet on every record, omitted when null or blank (Datadog then infers it). Splunk HEC: the per-event `host` field, emitted when set. Elasticsearch: ECS `host.name`, omitted when null or blank. Ignored by dynatrace and generic. |
| <a id="opt-apikey"></a>`apiKey` | String (sensitive) | — | datadog, newrelic, elasticsearch, dynatrace (alias), generic | Vendor API key; marked sensitive, so Log4j2 masks it in its own status and debug output. Datadog (required, must not be blank): `DD-API-KEY` header. New Relic (required): `Api-Key` header with a license or ingest key. Elasticsearch (required, trimmed): `Authorization: ApiKey <apiKey>` using the base64 "encoded" key Kibana shows; the key needs only `create_doc` and `auto_configure` on the target. Dynatrace: alias for [`token`](#opt-token), used only when `token` is absent. Generic (optional): sent as `Authorization: Bearer <apiKey>`. Ignored by splunk. Supply it through `${env:...}` or `${sys:...}` only. |
| <a id="opt-token"></a>`token` | String (sensitive) | — | splunk, dynatrace | Vendor token; marked sensitive. Splunk HEC (required, must not be blank): `Authorization: Splunk <token>`. Dynatrace (required): `Authorization: Api-Token <token>`, an access token with the `logs.ingest` scope. `apiKey` is used instead only when `token` is absent, so a `token` attribute that is present but resolves to empty still wins and fails the build. Ignored by datadog, newrelic, elasticsearch and generic (generic auth goes through `apiKey` only). Supply it through `${env:...}` or `${sys:...}` only. |

### Injecting secrets

Use a Log4j2 lookup with a fallback chain that ends in a visible sentinel, so a missing secret
shows up as an obvious `MISSING_KEY` rejection rather than the literal `${env:...}` text, which
Log4j2 leaves in place when a lookup does not resolve:

```xml
<BatchHttp name="datadog" vendor="datadog"
           apiKey="${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

A value that exists only inside Mule (a Mule properties file, a `secure::` placeholder) is
invisible to Log4j2; the secret must reach the JVM as a system property or an environment
variable. On CloudHub 1.0, Runtime Manager application properties surface as JVM system
properties, so `${sys:...}` resolves them. Under the standalone Mule wrapper (and Anypoint Code
Builder) a JVM argument must be written `-M-Ddatadog.apiKey=...`, because a bare `-D` never
reaches the Mule JVM. [Injecting secrets](mule.md#injecting-secrets-api-keys--tokens) covers each
runtime.

## Batching

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-maxbatchrecords"></a>`maxBatchRecords` | int | `200` | all | Flush a batch once it holds this many records. The engine uses the smaller of this and the vendor's record cap (see [Vendor per-request limits](#vendor-per-request-limits)). The same effective value sets the record count of each spill-replay sub-batch. `0` sends every record on its own. A negative value kills the flusher threads at start, so nothing is sent from the live queue; with spill on, overflow records still reach the intake through spill replay, one record per request. Treat it as a broken config. |
| <a id="opt-maxbatchbytes"></a>`maxBatchBytes` | int | `1048576` (1 MiB) | all | Flush when the estimated uncompressed body reaches this many bytes. The engine uses the smaller of this and the vendor's byte ceiling, so with the default, newrelic and splunk batches are capped at 900 KiB. The estimate counts real UTF-8 bytes (about 80 bytes of framing per record plus message, stack trace, logger, thread and MDC), so CJK or emoji text is not undercounted. The batch is closed before a record that would push it past the ceiling, not after. A single record bigger than the ceiling still goes out alone (and may be rejected with 413). gzip does not change the measure. Spill-replay sub-batches are cut by record count only, not by this limit. |
| <a id="opt-lingermillis"></a>`lingerMillis` | long | `1000` | all | Maximum age of a partial batch: it is flushed once its first record is this old. The window opens when the first record of a batch arrives and is not extended by later ones, so under a steady trickle a partial batch still ships every interval rather than waiting for a lull. An idle flusher blocks with no window open (no CPU spin). `0` or negative flushes as soon as each poll returns. |
| <a id="opt-flusherthreads"></a>`flusherThreads` | int | `1` | all | Number of background sender threads (`batch-http-flusher-<sink>-<n>`). Each takes records from the shared queue, builds its own batch with its own linger window, and sends synchronously with its own retry schedule. More than one raises throughput against a high-latency intake, but records can then arrive out of order. All flushers of one appender share one HTTP/1.1 client. Values below 1 are clamped to 1. |
| <a id="opt-gzip"></a>`gzip` | boolean | `true` | all | Gzip each request body and send `Content-Encoding: gzip`. A body is compressed once and reused across its retries. Set false to send plaintext, for example to read bodies in a proxy or mock intake, or to keep an A/B benchmark comparable with an appender that cannot compress (the Mule batch demos do this). Batch size limits always apply to the uncompressed bytes. |

### Vendor per-request limits

Each sink declares the per-request ceilings of its intake, and the engine flushes before crossing
them: the effective limits are the smaller of [`maxBatchRecords`](#opt-maxbatchrecords) /
[`maxBatchBytes`](#opt-maxbatchbytes) and the values below (uncompressed UTF-8 bytes, the unit
the intakes enforce). Vendors move their published ceilings, so check their documentation if you
raise the configured values.

| Vendor | Records per request | Uncompressed bytes per request |
|--------|---------------------|--------------------------------|
| datadog | 1000 | 5 MiB |
| newrelic | no cap | 900 KiB |
| splunk | no cap | 900 KiB (builder-settable, see [Builder-only knobs](#builder-only-knobs)) |
| dynatrace | 5000 | 4 MiB |
| elasticsearch | no cap | 10 MiB |
| generic | 500 | 1 MiB (both builder-settable) |

These limits are enforced on the live path. Spill-replay sub-batches are sized by record count
(the effective `maxBatchRecords`) alone, so a replayed sub-batch of very large records can exceed
a vendor's byte ceiling and be rejected.

## Queue and overflow

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-queuecapacity"></a>`queueCapacity` | int | `10000` | all | Capacity of the bounded in-memory queue between the application's log calls and the flushers. The default bounds worst-case heap use on a fractional-vCore CloudHub worker; raise it for high-throughput apps with larger heaps. When the queue is full, [`overflowPolicy`](#opt-overflowpolicy) decides what happens, and with spill on and [`spillOnOverflow`](#opt-spillonoverflow) overflowing records go to disk. Must be at least 1: `0` or negative fails the factory with only a generic Log4j2 factory-invocation error, and the appender is not created. |
| <a id="opt-overflowpolicy"></a>`overflowPolicy` | String (enum) | `DROP_NEWEST` | all | What a full queue does with a new record; case-insensitive, and an unrecognised value silently becomes `DROP_NEWEST`. `DROP_NEWEST` (never blocks): the incoming record is refused, or written to disk with spill on and `spillOnOverflow`. `DROP_OLDEST`: the oldest queued record is evicted to make room, and with spill on and `spillOnOverflow` the evicted record goes to disk. `BLOCK`: the calling application thread waits in 100 ms steps until there is room (back-pressure, can stall the app) and never spills; once the appender is stopping, a `BLOCK` offer returns promptly and counts a drop instead of hanging. Overflow is reported as a coalesced WARN, at most once every 5 s: "queue full (capacity=..., policy=...): N record(s) dropped, M diverted to spill". |

## Retries and timeouts

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-maxretries"></a>`maxRetries` | int | `3` | all | Retries after the first attempt, so a batch gets `maxRetries + 1` attempts in total. Retryable: HTTP 408, 429, any 5xx, and network errors or timeouts. Every other status (any other 4xx, and 1xx or 3xx: redirects are not followed) is final: the batch is dropped with an ERROR and never spilled, so point `url`, `site` or `host` at the final endpoint, not one that redirects. For Elasticsearch, documents refused with 429 or 5xx inside a 200 bulk response are re-sent on the same schedule; documents refused with any other 4xx are dropped, and a 409 counts as delivered. When retries run out, the batch is spilled (spill on) or dropped with an ERROR (spill off). `0` means a single attempt; a negative value means no attempt is ever made, so every batch is treated as retries-exhausted. |
| <a id="opt-initialretrybackoffmillis"></a>`initialRetryBackoffMillis` | long | `200` | all | Wait before the first retry. It doubles after each attempt, up to [`maxRetryBackoffMillis`](#opt-maxretrybackoffmillis). A jitter of 0 to min(backoff/4, 250 ms) is added to each wait so flushers do not retry in lockstep. A `Retry-After` header on a retryable response (delta-seconds or HTTP-date) replaces the computed wait for that attempt. `0` retries immediately. |
| <a id="opt-maxretrybackoffmillis"></a>`maxRetryBackoffMillis` | long | `10000` | all | Ceiling on the retry wait. It caps the doubling backoff after the first retry (the first retry waits [`initialRetryBackoffMillis`](#opt-initialretrybackoffmillis) plus jitter even if that is larger, so keep that value at or below this one), and it caps a server-supplied `Retry-After` exactly, so an absurd value cannot park a flusher. An absent, malformed or already-past `Retry-After` falls back to the exponential schedule. On that computed path the jitter is added after the cap, so a wait can exceed this value by up to 250 ms. |
| <a id="opt-requesttimeoutmillis"></a>`requestTimeoutMillis` | long | `10000` | all | Per-request HTTP timeout: how long to wait for the response to one POST. When it expires the attempt counts as a retryable network error. The TCP connect timeout is separate, fixed at 10 s, and not configurable. Must be greater than 0: `0` or negative is rejected by the JDK HttpClient on every send, so each batch fails with "unexpected error while batching" and is dropped. |
| <a id="opt-shutdowndraintimeoutmillis"></a>`shutdownDrainTimeoutMillis` | long | `5000` | all | On stop, the time the flushers get to send what is queued. After it, flushers still running are interrupted (2 s grace in total); with spill on and `spillRecoveryMode=ADOPT`, a flusher hands its in-flight batch to the spill buffer. Records still queued are then spilled for the next start to replay, within a budget of max(this value, 1000 ms). With spill off, or with `DELETE`/`IGNORE` (the next start would not replay them), they are counted as dropped with an ERROR, never lost silently. The final drain of still-queued records never sends over HTTP. With spill off or `DELETE`/`IGNORE`, though, a flusher interrupted after the drain budget still makes one last send of the batch it holds, and stop waits only the 2 s grace for it. Stopping takes roughly this value + 2 s + the spill write. The same value is the replayer's join timeout. |

## Disk spill

The spill buffer is the safety net for outages, overload and shutdown. How it works, what it
costs, and how crash recovery behaves are explained in [Resilience](resilience.md); the options
are listed here.

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-spillenabled"></a>`spillEnabled` | boolean | `true` | all | Turns the disk-spill buffer on. Records reach disk only on a failure path: a batch that fails after its retries (not a 4xx rejection), queue overflow when [`spillOnOverflow`](#opt-spillonoverflow) is on, or records still queued after [`shutdownDrainTimeoutMillis`](#opt-shutdowndraintimeoutmillis) (`ADOPT` mode only). The healthy path does no disk I/O. A background replayer re-sends segments once the endpoint is healthy and deletes each one on success; segments left by a crash are replayed at the next start. Replay is at-least-once, so duplicates are possible. If the buffer cannot be initialised (for example an unwritable directory), the appender logs an ERROR and keeps shipping without spill. Set false where the temp directory is memory-backed (tmpfs, where the buffer costs container RAM) or read-only, or point [`spillDirectory`](#opt-spilldirectory) at real storage. With spill on, overflow goes to disk, so `dropped` can stay at 0 under overload: alert on `spilledRecords` instead. |
| <a id="opt-spilldirectory"></a>`spillDirectory` | String | — | all | Directory for spill segment files, created when the appender starts even if nothing is ever spilled. Unset or blank means `${java.io.tmpdir}/batch-http-spill/<sink>`, where `<sink>` is `Datadog`, `NewRelic`, `SplunkHEC`, `Dynatrace`, `Elasticsearch`, or `Generic_<endpoint host>_` for generic. The OS temp dir may be memory-backed and is lost when a CloudHub 1.0 worker or a CloudHub 2.0 / RTF / Kubernetes pod is replaced, or when a reboot clears `/tmp`. Point this at persistent storage where buffered logs must survive. Crash recovery needs the same `spillDirectory` and [`spillInstanceId`](#opt-spillinstanceid) on the next start; for generic with this unset, changing the `url` host moves the default directory and strands old segments. |
| <a id="opt-spillinstanceid"></a>`spillInstanceId` | String | — | all | Id embedded in segment and lock file names (`spill-<id>-<seq>.log`, `.spill-<id>.lock`), so several apps or appenders sharing a directory never touch each other's files; each store only lists, replays, evicts and counts files with its own id. Characters outside `[A-Za-z0-9._-]` become `_`. Unset or blank means the appender [`name`](#opt-name). It must stay the same across restarts of one app (so the next start adopts its own segments) and differ for each instance running at the same time. If a live process holds the lock, its segments are left untouched with a WARN and ownership is retried on every replay pass. Old id-less `spill-<seq>.log` files are migrated on first start, following [`spillRecoveryMode`](#opt-spillrecoverymode). |
| <a id="opt-maxspillfilebytes"></a>`maxSpillFileBytes` | long | `8388608` (8 MiB) | all | Size at which the active segment is closed and a new one starts. The check is approximate: a record always lands whole in one segment, so an oversized record makes one oversized segment rather than being split. The default is a quarter of [`maxTotalSpillBytes`](#opt-maxtotalspillbytes), giving four segments: eviction sheds a quarter of the backlog at a time, and three closed segments stay replayable while a fourth fills. Segments are fsynced when they close. Keep `maxTotalSpillBytes` several times larger, because the active segment is never evicted. |
| <a id="opt-maxtotalspillbytes"></a>`maxTotalSpillBytes` | long | `33554432` (32 MiB) | all | Cap on all of this instance's segments together. A write that would exceed it evicts the oldest closed segment first; segments being replayed and the active segment are never evicted. If nothing can be evicted, the new record is refused. Evicted and refused records are counted in `spillStats().droppedRecords` (evicted ones also in `evictedRecords`) and reported by a coalesced WARN at most every 5 s, plus once at shutdown ("spill buffer full ... those logs are lost"). |
| <a id="opt-minfreediskbytes"></a>`minFreeDiskBytes` | long | `268435456` (256 MiB) | all | Free-space floor on the spill volume. A write that would leave less usable space evicts the oldest closed segment, and refuses the record if nothing can be evicted. It protects the volume, which is usually shared with the runtime's own logs and temp files, so it is deliberately not scaled to `maxTotalSpillBytes`. On a volume with less free space than this, spill writes nothing and only counts drops: lower it for a small dedicated volume. Free space is re-checked about every 4 MiB written. `0` or negative turns the check off. |
| <a id="opt-spillreplayintervalmillis"></a>`spillReplayIntervalMillis` | long | `10000` | all | How often the background replayer runs a pass. Each pass retries the directory lock if another instance held it, closes and fsyncs the active segment so records spilled during an outage become replayable, then ships closed segments in sub-batches of the effective `maxBatchRecords`. The first failed sub-batch ends the pass, which resumes next interval. When segments are already on disk at startup, the first pass runs at once. The interval also bounds how much spilled data a host crash can lose. Values below 1 are clamped to 1. |
| <a id="opt-spillonoverflow"></a>`spillOnOverflow` | boolean | `true` | all | With spill on, send queue-overflow records to disk instead of dropping them: under `DROP_NEWEST` the incoming record, under `DROP_OLDEST` the evicted oldest one. `BLOCK` never overflows, so it has no effect there, and none when `spillEnabled=false`. It does not affect the shutdown spill of still-queued records. With this on, overload shows up as `spilledRecords` rather than `dropped`. |
| <a id="opt-spillrecoverymode"></a>`spillRecoveryMode` | String (enum) | `ADOPT` | all | What to do at startup with segments carrying this instance's id that a previous run left behind (only when this instance holds the lock); case-insensitive, and an unrecognised value silently becomes `ADOPT`. `ADOPT`: take them over and replay them at once (INFO "recovered N spill segment(s)"). `IGNORE`: leave them untouched (and skip migrating old id-less files). `DELETE`: discard them with an INFO; they count in `evictedSegments`, not `droppedRecords`. It also governs shutdown: only `ADOPT` spills records still queued at stop. Watch for stale backlogs: intakes discard events older than their maximum age yet answer 2xx, so a days-old adopted backlog is counted as `sentRecords` without being searchable. Use `DELETE` or `IGNORE` where a host may present a stale spill directory. |
| <a id="opt-spillbacklogorder"></a>`spillBacklogOrder` | String (enum) | `OLDEST_FIRST` | all | Order in which the replayer drains the backlog; case-insensitive, and an unrecognised value silently becomes `OLDEST_FIRST`. `OLDEST_FIRST`: strict oldest-first, for completeness and in-order delivery. `NEWEST_FIRST`: freshest segments first. `DUAL_STREAM`: exactly two workers, one from each end, meeting in the middle, so recent logs surface at once while the history still progresses; it overrides [`spillReplayThreads`](#opt-spillreplaythreads). Any order other than single-worker `OLDEST_FIRST` reorders delivery and widens the duplicate window. |
| <a id="opt-spillreplaythreads"></a>`spillReplayThreads` | int | `1` | all | Parallel replay workers per pass, all claiming segments from the end that `spillBacklogOrder` chooses. Ignored for `DUAL_STREAM`, which always uses 2. More than 1 drains a large backlog faster but reorders delivery. [`spillReplayMaxBytesPerSec`](#opt-spillreplaymaxbytespersec) caps their combined rate. Values below 1 are clamped to 1. |
| <a id="opt-spillreplaymaxbytespersec"></a>`spillReplayMaxBytesPerSec` | long | `0` (unlimited) | all | Caps the average backlog drain rate. A token bucket shared by all replay workers is charged with the on-disk payload bytes of each shipped sub-batch, after it ships, and allows an initial burst of up to one second's worth. This keeps a large backlog from saturating the intake or starving live traffic. `0` or negative means unlimited. |
| <a id="opt-spillstartupreconcile"></a>`spillStartupReconcile` | boolean | `true` | all | At startup, before writing anything new, evict the oldest segments until both `maxTotalSpillBytes` and `minFreeDiskBytes` are satisfied, for a run that inherits a large backlog on a nearly full disk. Evicted records are counted in `droppedRecords` and `evictedRecords`, and a one-shot WARN reports how many were lost ("startup reconcile evicted N oldest spill segment(s) ..."). |
| <a id="opt-spillformat"></a>`spillFormat` | String (enum) | `BINARY` | all | How newly spilled records are encoded on disk; case-insensitive, and an unrecognised value silently becomes `BINARY`. `BINARY`: compact tag-length-value encoding that handles every character set, the fastest with the fewest allocations. `JSON`: UTF-8 JSON, readable by eye for support. Both use the same crash-tolerant framing (sync marker, length, CRC32C, payload), so a damaged record is skipped and counted in `corruptBytesSkipped` and replay continues with the next intact one. Each segment header names its codec, so both formats can coexist after a config change, and older JSON segments are still read. Every record keeps its original event timestamp, which replay restores exactly. |

## TLS

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-trustallcertificates"></a>`trustAllCertificates` | boolean | `false` | all (HTTPS only) | Insecure. When true, this appender's HTTP client trusts any server certificate (self-signed, expired, unknown CA) through its own trust-all `SSLContext`, like `curl -k`. It affects only this appender, not the rest of the JVM. When on, a WARN "TLS certificate validation DISABLED" is logged through the appender's diagnostics. No effect on `http://` URLs. Client-certificate (mTLS) auth is not supported. |
| <a id="opt-verifyhostname"></a>`verifyHostName` | boolean | `true` | all (HTTPS only) | Insecure when false: skips checking that the certificate's CN/SAN matches the requested host (same name as Log4j2's own SSL attribute). This is JVM-wide, not per appender; see [Trusting a self-signed or internal collector](#trusting-a-self-signed-or-internal-collector). A WARN is logged whenever this is false, whether or not the property was already set (subject to [`diagnosticsLevel`](#opt-diagnosticslevel) and `<Configuration status>`). |

### Trusting a self-signed or internal collector

By default the HTTPS transport does what a browser does: it validates the server's certificate
chain against the JVM trust store and checks that the host name matches the certificate. Leave
that on for every real vendor intake, which all present publicly trusted certificates. The two
attributes above are escape hatches for a private collector you control that presents a
self-signed or privately signed certificate, analogous to `curl -k` and to Log4j2's own
`SslConfiguration`. Both defeat protection against a man-in-the-middle. Prefer importing the
collector's CA into the JVM trust store where you can.

```xml
<BatchHttp name="internal" vendor="generic"
           url="https://collector.internal.example.com/ingest"
           apiKey="${env:INTERNAL_LOG_TOKEN}"
           trustAllCertificates="true" verifyHostName="false">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

`verifyHostName="false"` is JVM-global. The JDK `java.net.http.HttpClient` has no per-client
hostname-verification switch, so the appender sets the system property
`jdk.internal.httpclient.disableHostnameVerification=true`. That affects every `HttpClient` in
the JVM, and the JDK reads it only once, at the first HTTPS request: if any HTTPS traffic ran
earlier in the JVM, the appender's change has no effect. For a reliable result, pass the property
as a JVM argument instead; on Mule/CloudHub it needs the `-M-D` prefix to reach the Mule JVM:

```text
-M-Djdk.internal.httpclient.disableHostnameVerification=true
```

`trustAllCertificates` has no such caveat; it is fully per appender. The TLS WARN lines go
through the appender's diagnostics, so they appear only when [`diagnosticsLevel`](#opt-diagnosticslevel)
is `WARN` or finer and `<Configuration status>` allows WARN.

## Payload and fields

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-maxmessagebytes"></a>`maxMessageBytes` | int | `32768` (32 KiB) | all | Caps the message field of each record at this many UTF-8 bytes, applied once when the record is queued. The cut falls on a character boundary and a marker `…[truncated N chars]` is appended, where N counts the UTF-16 chars removed; the budget covers the kept part only, so a truncated message ends up about 25 bytes over the limit. Only the message is capped: MDC attributes and the rendered stack trace are not. `0` or negative turns capping off. See [Message size capping](#message-size-capping). |
| <a id="opt-includestacktrace"></a>`includeStackTrace` | boolean | `true` | all | When the event carries a Throwable, render its full stack trace text into the record (see [Where a stack trace lands](#where-a-stack-trace-lands)). false ships the message only. The stack trace is not subject to `maxMessageBytes`. |
| <a id="opt-includetimestamp"></a>`includeTimestamp` | boolean | `true` | datadog, newrelic | Send the event's own time in the vendor's reserved event-time field: Datadog `date` (ISO-8601 UTC) or New Relic `timestamp` (epoch millis). false leaves it out, so the vendor stamps each record with its arrival time instead. That is the only way to observe shipping delay, because a supplied event time hides ingest lag. Read only by the datadog and newrelic sinks; splunk, dynatrace, elasticsearch and generic always send their own time field (Elasticsearch data streams require `@timestamp`). |

### Message size capping

One oversized message (a developer dumping a whole HTTP body, a runaway loop concatenating into
one line) could otherwise bloat the in-memory queue, the HTTP request and a spill segment at
once. [`maxMessageBytes`](#opt-maxmessagebytes) bounds that:

- Truncation happens on a UTF-8 character boundary, so a multi-byte character is never split
  into invalid bytes, and the `…[truncated N chars]` marker makes the cut visible downstream.
- The budget applies to the kept prefix, not to the result. Size a downstream hard limit with
  the marker's slack (about 25 bytes, depending on the digits in N) in mind.
- The limit is in bytes, not characters, because bytes are what bound memory, the wire payload
  and the on-disk frame.
- It is cheap even for a pathological message. A line well under the budget is passed through
  without copying, and an oversized one is measured only up to the budget; the appender never
  encodes a 200 MB string in full just to measure it.

## Diagnostics

The appender's own health messages and the stats counters are explained in
[Troubleshooting and diagnostics](troubleshooting.md).

| Option | Type | Default | Applies to | Description |
|--------|------|---------|------------|-------------|
| <a id="opt-diagnosticslevel"></a>`diagnosticsLevel` | String (Log4j2 level) | `WARN` | all | Minimum level of the appender's own health messages, prefixed `batch-http[<sink>]` except the two TLS warnings, which start with `TLS ... DISABLED`. They go to Log4j2's StatusLogger, never back through this appender, so an outage cannot loop. Accepts any Log4j2 level name, case-insensitive (`OFF` \| `FATAL` \| `ERROR` \| `WARN` \| `INFO` \| `DEBUG` \| `TRACE` \| `ALL`); an unrecognised value becomes `WARN`. ERROR lines: drops, 4xx rejections, refusals, spill init failure. WARN: spilled after retries, coalesced queue-full and spill-buffer-full lines, startup reconcile eviction, lock held by another instance, damaged segments, the TLS warnings. INFO: stats heartbeat, recovered segments, `DELETE` discard, lock takeover. DEBUG: each retry attempt. So `WARN` shows every failure, `INFO` adds the heartbeat and recovery lines, `DEBUG`/`TRACE`/`ALL` add retry chatter, `ERROR` hides the WARN lines, and `FATAL`/`OFF` hide everything. A message is printed only if `<Configuration status>` is also at that level or finer. The missing-credential startup error is gated only by `status`. |
| <a id="opt-statsintervalmillis"></a>`statsIntervalMillis` | long | `0` (off) | all | When greater than 0, a daemon thread logs a one-line stats snapshot at INFO this often, so you can see delivery is healthy without looking at the spill directory: `batch-http[<sink>] stats accepted= sentRecords= sentBatches= dropped= failedBatches= queueDepth=`, plus `spill{onDiskBytes= segments= spilledRecords= droppedRecords= evictedSegments= evictedRecords= adoptedRecords= corruptBytesSkipped=}` when spill is on. A final snapshot is logged at shutdown. It shows only with `diagnosticsLevel` `INFO` or finer and `<Configuration status="INFO">` or finer. `0` or negative turns it off. |

## Vendor field mapping

The batching, queue, retry, spill, TLS and diagnostics options apply to every vendor. The
identity and connection attributes are vendor-specific; this matrix shows what each one becomes
per vendor. A `—` means the attribute is ignored for that vendor.

| Attribute | [datadog](vendors/datadog.md) | [splunk](vendors/splunk.md) | [newrelic](vendors/newrelic.md) | [dynatrace](vendors/dynatrace.md) | [elasticsearch](vendors/elasticsearch.md) | [generic](vendors/generic.md) |
|-----------|---------|--------|----------|-----------|---------------|---------|
| [`url`](#opt-url) | — | HEC base URL | — | environment URL | endpoint | full endpoint URL |
| [`site`](#opt-site) | intake host | — | — | — | — | — |
| [`host`](#opt-host) | reserved `host` facet | HEC `host` field | intake host (region) | — | `host.name` | — |
| [`hostname`](#opt-hostname) | — | — | reserved `hostname` | — | — | — |
| [`apiKey`](#opt-apikey) | `DD-API-KEY` | — | `Api-Key` | alias for `token` | `Authorization: ApiKey` | `Authorization: Bearer` |
| [`token`](#opt-token) | — | `Authorization: Splunk` | — | `Authorization: Api-Token` | — | — |
| [`service`](#opt-service) | reserved `service` | — | `service` attribute | — | `service.name` | — |
| [`source`](#opt-source) | `ddsource` (sent as `java` when unset) | — | — | `log.source` | — | — |
| [`index`](#opt-index) | — | HEC `index` | — | — | data stream | — |
| [`sourcetype`](#opt-sourcetype) | — | HEC `sourcetype` | — | — | — | — |
| [`includeTimestamp`](#opt-includetimestamp) | gates `date` | — (`time` always sent) | gates `timestamp` | — (`timestamp` always sent) | — (`@timestamp` always sent) | — (`timestamp` always sent) |

Fields every record carries, whatever the configuration:

| Record field | datadog | splunk (inside the HEC `event`) | newrelic | dynatrace | elasticsearch | generic |
|--------------|---------|--------|----------|-----------|---------------|---------|
| message | `message` | `message` | `message` | `content` | `message` | `message` |
| level | `status` | `level` | `level` | `loglevel` | `log.level` | `level` |
| logger | `logger.name` | `logger` | `logger.name` | `logger.name` | `log.logger` | `logger` |
| thread | `logger.thread_name` | `thread` | `thread.name` | `thread.name` | `process.thread.name` | `thread` |
| MDC entry | top-level field | field of `event` | top-level attribute | top-level attribute | `labels.<key>` | top-level field |

Body framing and content type: datadog, newrelic and dynatrace send a JSON array
(`application/json`; dynatrace adds `; charset=utf-8`); splunk sends newline-delimited HEC events
(`application/json`); elasticsearch sends bulk NDJSON with a `create` action line before each
document (`application/x-ndjson`); generic sends NDJSON (`application/x-ndjson`) unless built
with JSON-array framing.

### Where a stack trace lands

When [`includeStackTrace`](#opt-includestacktrace) is true and the event carries a Throwable:
datadog, newrelic and dynatrace emit it as `error.stack`; elasticsearch emits ECS
`error.stack_trace`; splunk and generic emit `exception`. A `<PatternLayout>` also appends the
stack trace to the message unless it sets `alwaysWriteExceptions="false"` (Log4j2's default is
true, even for a bare `%m` pattern).

## How invalid values behave

Log4j2 converts the attribute strings before the appender sees them, so a typo does not always
fail loudly:

- Booleans use `Boolean.valueOf`: anything other than `true` (ignoring case) is false, so a typo
  in a default-true flag (`gzip="ture"`) silently turns it off.
- An int or long value that does not parse (`"1_000"`, or more than 2147483647 for an int option)
  falls back to the default with a Log4j2 WARN.
- [`overflowPolicy`](#opt-overflowpolicy), [`spillRecoveryMode`](#opt-spillrecoverymode),
  [`spillBacklogOrder`](#opt-spillbacklogorder), [`spillFormat`](#opt-spillformat) and
  [`diagnosticsLevel`](#opt-diagnosticslevel) fall back silently to their default on an unknown
  value; an unknown [`vendor`](#opt-vendor) becomes `generic`.
- Out-of-range values that break rather than fail cleanly: `queueCapacity` below 1 (no appender,
  generic factory error), `requestTimeoutMillis` of 0 or less (every batch dropped),
  `maxBatchRecords` below 0 (flushers die; nothing ships from the live queue, only spilled overflow trickles out one record per request), `maxRetries` below 0 (no send is ever
  attempted). `flusherThreads`, `spillReplayThreads` and `spillReplayIntervalMillis` are clamped
  to at least 1.

## Builder-only knobs

A few sink settings have no `<BatchHttp>` attribute. They are reachable only when you embed the
engine programmatically through the sink builders in `batch-http-core`:

| Sink builder | Method | Purpose |
|--------------|--------|---------|
| `GenericNdjsonSink` | `framing(Framing)` | `NDJSON` (default) or `JSON_ARRAY`; `JSON_ARRAY` also switches the default `Content-Type` to `application/json`. |
| `GenericNdjsonSink` | `maxRecords(int)`, `maxBytes(int)` | Per-request record and uncompressed-byte ceilings the sink advertises to the engine (defaults 500 and 1 MiB). |
| `GenericNdjsonSink` | `header(String, String)` | Any request header, for example a non-Bearer auth scheme or a tenant id; call it repeatedly for several. From XML, generic auth is only `apiKey` as Bearer. |
| `SplunkHecSink` | `maxBytes(int)` | Per-request byte ceiling (default 900 KiB, headroom under HEC's default 1 MB); use it to match a different HEC `max_content_length`. |
| `DatadogSink` | `ddtags(String)` | The Datadog `ddtags` field. |
| `NewRelicSink`, `DynatraceSink` | `commonAttribute(String, String)` | An attribute added to every record. |

```java
LogSink sink = GenericNdjsonSink.builder()
        .url("https://logs.internal.example.com/bulk")
        .framing(GenericNdjsonSink.Framing.JSON_ARRAY)
        .header("X-Scope-OrgID", "team-42")
        .maxRecords(1000)
        .build();
```

## Defaults that changed

Five defaults moved in the 0.1.x line. Each changes the behaviour of an app that upgrades the jar
without touching its `log4j2.xml`; a value you set explicitly always wins. Every release is listed
in [CHANGELOG.md](../CHANGELOG.md).

| Option | Was | Now | What changes for an unconfigured app |
|--------|-----|-----|--------------------------------------|
| [`maxBatchRecords`](#opt-maxbatchrecords) | 100 | 200 | Half as many requests for the same volume; each body twice the size. |
| [`queueCapacity`](#opt-queuecapacity) | 100000 | 10000 | Worst-case queue memory drops to a few MB, which suits a 0.1 to 0.2 vCore CloudHub worker. |
| [`gzip`](#opt-gzip) | false | true | Bodies are compressed and carry `Content-Encoding: gzip`; a proxy or mock intake that assumed plaintext must decompress. |
| [`spillEnabled`](#opt-spillenabled) | false | true | The app writes log payloads to local storage on a failure path, and queue overflow goes to disk instead of being dropped. Read [Resilience](resilience.md) before upgrading. |
| [`maxTotalSpillBytes`](#opt-maxtotalspillbytes) | 512 MiB | 32 MiB | The on-disk buffer is bounded far more tightly; `maxSpillFileBytes` dropped to 8 MiB with it, so the cap is still four segments. |

---

[Docs index](README.md) · Vendors: [Datadog](vendors/datadog.md) · [New Relic](vendors/newrelic.md) ·
[Elasticsearch](vendors/elasticsearch.md) · [Dynatrace](vendors/dynatrace.md) · [Splunk](vendors/splunk.md) ·
[Generic HTTP](vendors/generic.md)
