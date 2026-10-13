# Dynatrace

With `vendor="dynatrace"`, the `<BatchHttp>` appender batches log events and POSTs each batch as
one JSON array to the Dynatrace **Log Monitoring API v2** (`{url}/api/v2/logs/ingest`). Each
event becomes one Dynatrace log record with the reserved `content`, `loglevel` and `timestamp`
attributes, plus `logger.name`, `thread.name`, an optional `log.source`, an optional
`error.stack`, and one attribute per MDC entry. Requests are authenticated with the
`Authorization: Api-Token <token>` header, using an access token that only needs the
`logs.ingest` scope. The `url` you configure is the environment URL. It works with SaaS and with
an ActiveGate.

> **Sample Mule project.** [`demo/mule/dynatrace-batch-http`](../../demo/mule/dynatrace-batch-http/)
> is a Mule 4.9 (Java 17) app that ships its logs to Dynatrace through this appender.
> [`demo/mule/dynatrace-stock-http`](../../demo/mule/dynatrace-stock-http/) is its control: the
> same flows and the same record shape, but sent through Log4j2's built-in `<Http>` appender, one
> synchronous POST per event. The pair isolates the effect of batching: the two apps store
> byte-identical `content`, and their records differ only in `log.source`. Both apps are
> configured through `log4j2.xml` alone. The [benchmark below](#benchmark-on-cloudhub) was
> measured with them.

**On this page:** [Set up Dynatrace](#set-up-dynatrace) ·
[Configuration](#configuration) · [What gets sent](#what-gets-sent) ·
[Verify delivery](#verify-delivery) · [Benchmark on CloudHub](#benchmark-on-cloudhub) ·
[Troubleshooting](#troubleshooting)

## Set up Dynatrace

1. **Find the environment URL.** Use the API address of the environment, not the address of
   the platform UI.
   - SaaS: if the Logs app opens at `https://<environment-id>.apps.dynatrace.com/…`, ingest
     lives at `https://<environment-id>.live.dynatrace.com`.
   - ActiveGate: `https://<activegate>:9999/e/<environment-id>`.
   - A URL that already ends in `/api/v2/logs/ingest` is accepted too. The sink adds the path
     only when it is missing.
2. **Create an access token.** In Dynatrace, open **Access Tokens** and generate a token with
   **only** the `logs.ingest` scope (*Ingest logs*). It starts with `dt0c01.` and is shown only
   once. A token with this scope can write log records and do nothing else.
3. **Optional: a platform token for counting.** To read records back over the API (for example,
   to count what arrived after a load test), you need a separate *platform* token (`dt0s16.…`)
   with `storage:logs:read` and `storage:buckets:read`. The appender never uses it, so keep it
   out of the app's configuration.

You don't have to create anything else first, because records land in the default logs bucket.
If you also want the JSON fields inside `content` as attributes, you can add an OpenPipeline
processing rule (see [Verify delivery](#verify-delivery)).

## Configuration

Dynatrace needs [`vendor`](../configuration.md#opt-vendor)`="dynatrace"`,
[`url`](../configuration.md#opt-url) (the environment URL) and
[`token`](../configuration.md#opt-token). [`apiKey`](../configuration.md#opt-apikey) is accepted
as an alias for `token`. Like every Log4j2 appender, it also needs a
[`name`](../configuration.md#opt-name). Supply the token only through a `${env:...}` or
`${sys:...}` lookup.

### Java application

Put `com.mulesoft.support.batchhttp:batch-http-log4j2` on the classpath (see
[Building](../building.md)), then add:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Minimal log4j2.xml for a plain Java app shipping to Dynatrace.
     Set DT_ENVIRONMENT_URL (the environment URL; the sink appends /api/v2/logs/ingest)
     and DT_API_TOKEN (an access token with only the logs.ingest scope) in the environment. -->
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <BatchHttp name="dynatrace"
                   vendor="dynatrace"
                   url="${env:DT_ENVIRONMENT_URL}"
                   token="${env:DT_API_TOKEN}"
                   source="orders-api">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
        <Console name="console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="INFO">
            <AppenderRef ref="dynatrace"/>
            <AppenderRef ref="console"/>
        </Root>
    </Loggers>
</Configuration>
```

Keep `status` at `WARN` or `INFO`. With `status="OFF"`, the appender's own diagnostics (startup
errors, drops) are suppressed. The repo's Java example config,
[`log4j2-example.xml`](../../demo/java/benchmark/src/main/resources/log4j2-example.xml), also
defines a Dynatrace appender next to the other vendors.

### Mule application

This is an excerpt of the sample app's
[`log4j2.xml`](../../demo/mule/dynatrace-batch-http/src/main/resources/log4j2.xml). Mule
`${...}` placeholders and `config.properties` values do **not** resolve in this file, because
Log4j2 parses it, not Mule. The URL and token come in as system properties (CloudHub application
properties) or environment variables. On CloudHub, set `dynatrace.url` as an application
property and `dynatrace.token` as a **secure** one. When you run the app locally under the Mule
standalone wrapper, pass `-M-Ddynatrace.url=...` and `-M-Ddynatrace.token=...`. A bare `-D`
doesn't reach the Mule JVM. [Using it from a Mule app](../mule.md) covers packaging and secret
injection in general.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Excerpt of demo/mule/dynatrace-batch-http/src/main/resources/log4j2.xml.
     log4j2.xml is parsed by Log4j2, not Mule: use ${sys:...} / ${env:...} lookups.
     On CloudHub set dynatrace.url as an application property and dynatrace.token as a SECURE one. -->
<Configuration status="INFO" packages="com.mulesoft.support.batchhttp.log4j2">
    <Properties>
        <!-- log.source: the CloudHub application name (CloudHub 2.0: add a `domain` application property). -->
        <Property name="dt.source">${sys:domain:-dynatrace-batch-http}</Property>
        <!-- Deployment-plane facet carried inside content; delete an override, do not clear it. -->
        <Property name="app.platform">${sys:app.platform:-${env:APP_PLATFORM:-unspecified}}</Property>
        <!-- Environment URL; the reserved .invalid fallback fails DNS loudly when unset. -->
        <Property name="dt.url">${sys:DT_ENVIRONMENT_URL:-${sys:dynatrace.url:-${env:DT_ENVIRONMENT_URL:-https://set-dynatrace-url.invalid}}}</Property>
    </Properties>
    <Appenders>
        <!-- CloudHub 1.0 custom log appender file, as documented by MuleSoft. -->
        <RollingFile name="FILE"
                     fileName="/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log"
                     filePattern="/opt/mule/mule-CURRENT/logs/mule-${sys:domain}-%i.log">
            <PatternLayout pattern="[%d{MM-dd HH:mm:ss.SSS}] %-5p %c{1} [%t]: %m%n"/>
            <DefaultRolloverStrategy max="10"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="10 MB"/>
            </Policies>
        </RollingFile>
        <!-- gzip="false" is a deliberate A/B divergence (the stock Http appender cannot compress);
             a real deployment should omit it and keep compression on. -->
        <BatchHttp name="dynatrace"
                   vendor="dynatrace"
                   url="${dt.url}"
                   token="${sys:DT_API_TOKEN:-${sys:dynatrace.token:-${env:DT_API_TOKEN:-MISSING_TOKEN}}}"
                   source="${dt.source}"
                   maxBatchRecords="200"
                   maxBatchBytes="900000"
                   lingerMillis="2000"
                   queueCapacity="25000"
                   overflowPolicy="DROP_NEWEST"
                   gzip="false"
                   maxRetries="3"
                   requestTimeoutMillis="10000"
                   shutdownDrainTimeoutMillis="5000"
                   flusherThreads="1"
                   includeStackTrace="true"
                   diagnosticsLevel="INFO"
                   statsIntervalMillis="60000"
                   spillEnabled="${sys:batch.spillEnabled:-true}"
                   spillInstanceId="dynatrace-batch-http"
                   spillReplayIntervalMillis="10000"
                   maxSpillFileBytes="16777216"
                   maxTotalSpillBytes="536870912"
                   minFreeDiskBytes="268435456"
                   spillOnOverflow="true"
                   spillFormat="BINARY"
                   spillRecoveryMode="ADOPT"
                   spillStartupReconcile="true"
                   spillBacklogOrder="DUAL_STREAM"
                   spillReplayThreads="2"
                   spillReplayMaxBytesPerSec="0">
            <!-- content = the flow's pretty JSON collapsed to one line, with the platform facet prepended. -->
            <PatternLayout pattern="{&quot;platform&quot;:&quot;${app.platform}&quot;,%replace{%m}{^\x7B\s*|[\r\n]+\s*}{}"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <!-- Application logs ship to Dynatrace and the local file. -->
        <AsyncLogger name="com.mulesoft.support.batchhttp.muledemo" level="INFO" additivity="false">
            <AppenderRef ref="dynatrace"/>
            <AppenderRef ref="FILE"/>
        </AsyncLogger>
        <AsyncLogger name="org.mule" level="INFO"/>
        <AsyncLogger name="com.mulesoft" level="INFO"/>
        <!-- Root carries the FILE appender only: framework lines are not shipped. -->
        <AsyncRoot level="INFO">
            <AppenderRef ref="FILE"/>
        </AsyncRoot>
    </Loggers>
</Configuration>
```

Some things in this demo are Dynatrace-specific:

- **`log.source` is the identity.** The Dynatrace sink sends no service or host field. The demo
  sets `source` to `${sys:domain}`, the CloudHub application name, so that one attribute tells
  the batch app, the stock app, and their CloudHub 1.0 and 2.0 deployments apart. CloudHub 1.0
  sets `domain` automatically. CloudHub 2.0 doesn't, so add an application property
  `domain=<app-name>`. Locally, the value falls back to `dynatrace-batch-http`.
- **The platform facet travels inside `content`.** The layout prepends `"platform":"…"` to the
  flow's JSON. `app.platform` is not a separate attribute.
- **`gzip="false"` is for the A/B comparison only.** The stock `<Http>` appender can't compress,
  so the demo turns compression off to vary only batching. In production, omit the attribute.
- **`includeTimestamp` is not set, and would have no effect.** The Datadog and New Relic batch
  demos set `includeTimestamp="false"` so the vendor stamps arrival time. That doesn't apply here: the
  Dynatrace sink always sends the event time (see [Timestamp](#timestamp)).
- **Port 8087** for the batch app and **8088** for the stock app. `GET /log?count=N` emits N+2
  events (`batch.start`, N `order.processed`, `batch.completed`), and `GET /boom` logs one ERROR
  event.

To build and run the pair, first run `mvn install -DskipTests` at the repo root to install the
appender (the demos are not modules of the root build), then package each module:

```bash
cd demo/mule/dynatrace-batch-http && mvn clean package
cd ../dynatrace-stock-http && mvn clean package
```

Run the jar in Anypoint Code Builder, or deploy it to a standalone Mule runtime with the URL and
token passed as `-M-D` arguments, then exercise it:

```bash
curl "http://localhost:8087/log?count=50&level=info"
curl "http://localhost:8087/boom"
```

The batch module's [README](../../demo/mule/dynatrace-batch-http/README.md) covers the Dynatrace
setup, the DQL counting and the record shape; the stock module's
[README](../../demo/mule/dynatrace-stock-http/README.md) has the A/B comparison guide.

To deploy to CloudHub 1.0, run `scripts/deploy-ch1.sh --dynatrace` with `DT_ENVIRONMENT_URL` and
`DT_API_TOKEN` exported. It deploys `dynatrace-batch-http` and `dynatrace-stock-http` (with
`CH1_APP_SUFFIX` appended), and passes `dynatrace.url` as an application property and
`dynatrace.token` as a masked one. It does not set `app.platform`. Add
`app.platform=cloudhub-1.0` to both apps in Runtime Manager and restart them. CloudHub 2.0 has
no script: deploy the same jars and set `dynatrace.url`, a secure `dynatrace.token`, `domain`
and `app.platform` as application properties. See [Using it from a Mule app](../mule.md) for
the platform facet and the property rules both planes share.

### Recommended production settings

This is a starting point for a service that logs at a steady rate and must not lose records
during a short Dynatrace or network outage:

```xml
<!-- Production-leaning Dynatrace appender. Secrets come from lookups only. -->
<BatchHttp name="dynatrace"
           vendor="dynatrace"
           url="${env:DT_ENVIRONMENT_URL}"
           token="${env:DT_API_TOKEN}"
           source="orders-api"
           queueCapacity="25000"
           maxRetries="5"
           spillDirectory="${sys:batch.spillDir:-/var/lib/orders-api/batch-http-spill}"
           spillInstanceId="orders-api-1"
           maxTotalSpillBytes="268435456"
           diagnosticsLevel="INFO"
           statsIntervalMillis="60000">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

| Option | Why this value |
| --- | --- |
| [`source`](../configuration.md#opt-source) | It is the only identity field Dynatrace receives. Use a stable service name and filter on `log.source`. |
| [`queueCapacity`](../configuration.md#opt-queuecapacity) | 25000 records gives tens of seconds of buffer at a few hundred records/s, which covers a slow flush before overflow kicks in. |
| [`maxRetries`](../configuration.md#opt-maxretries) | More retries ride out a brief `429` or `5xx` from the intake before a batch goes to disk. Backoff is capped by [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis), and a `Retry-After` header is honoured. |
| [`spillDirectory`](../configuration.md#opt-spilldirectory) | Spill is already on (see [`spillEnabled`](../configuration.md#opt-spillenabled)). Point it at a disk that survives a restart, so a backlog is replayed after one. On CloudHub, `/tmp` is lost when a restart moves the app to a new worker or pod. |
| [`spillInstanceId`](../configuration.md#opt-spillinstanceid) | Keep it stable across restarts of one instance and distinct between instances that share the directory. |
| [`maxTotalSpillBytes`](../configuration.md#opt-maxtotalspillbytes) | Bounds the disk the backlog may use. Size it to the outage you want to survive at your log rate. |
| [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel), [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) | Prints a one-line delivery heartbeat (accepted, sent, dropped, queue depth, spill) to the StatusLogger every minute. |

`gzip` is left out on purpose so compression stays on ([`gzip`](../configuration.md#opt-gzip)).
[Resilience](../resilience.md) explains spill, replay, and crash recovery.

### Options with a Dynatrace-specific meaning

| Option | Meaning for Dynatrace |
| --- | --- |
| [`vendor`](../configuration.md#opt-vendor) | `dynatrace`. The value is trimmed and lowercased. There is no alias. Diagnostics name the sink `batch-http[Dynatrace]`. |
| [`url`](../configuration.md#opt-url) | **Required.** The environment URL (SaaS `https://<environment-id>.live.dynatrace.com` or ActiveGate `https://<activegate>:9999/e/<environment-id>`). Whitespace and trailing slashes are trimmed, and `/api/v2/logs/ingest` is appended unless the URL already ends with it. There is no built-in default. |
| [`token`](../configuration.md#opt-token) | **Required** (or `apiKey`). An access token with the `logs.ingest` scope, sent as `Authorization: Api-Token <token>`. |
| [`apiKey`](../configuration.md#opt-apikey) | An alias for `token`, used only when `token` is absent. When both are set, `token` wins. |
| [`source`](../configuration.md#opt-source) | Optional. Sent as `log.source` on every record, and left out when unset. It is the only identity field the sink sends. |
| [`includeStackTrace`](../configuration.md#opt-includestacktrace) | When true and the event carries a throwable, the rendered stack trace goes in `error.stack`. |
| [`Layout`](../configuration.md#opt-layout) | Renders `content`. Without a layout, `content` is the formatted message. A single trailing newline (from `%n`) is stripped. |
| [`Filter`](../configuration.md#opt-filter) | A standard Log4j2 filter. Nothing vendor-specific. |
| [`service`](../configuration.md#opt-service), [`host`](../configuration.md#opt-host), [`hostname`](../configuration.md#opt-hostname), [`site`](../configuration.md#opt-site), [`index`](../configuration.md#opt-index), [`sourcetype`](../configuration.md#opt-sourcetype) | Ignored. To get a service-like facet, put it in the layout or in MDC. |
| [`includeTimestamp`](../configuration.md#opt-includetimestamp) | Ignored. `timestamp` is always sent. |
| [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords), [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) | Capped by the Dynatrace sink ceiling (see [Per-request limits](#per-request-limits-and-gzip)). |

Every other option (batching, queue, retries, spill, TLS, diagnostics) applies unchanged; see
[the configuration reference](../configuration.md).

## What gets sent

Each batch is one POST to `{url}/api/v2/logs/ingest` with these headers:
`Content-Type: application/json; charset=utf-8`, `Authorization: Api-Token <token>`, and
`Content-Encoding: gzip` when compression is on. The body is one JSON array, `[{…},{…},…]`,
with one object per event. The fields appear in this order:

| Dynatrace attribute | Comes from |
| --- | --- |
| `content` (reserved) | The layout output, or the formatted message when there is no layout |
| `loglevel` (reserved) | The event level, for example `INFO` or `WARN` |
| `timestamp` (reserved) | The event time as an ISO-8601 UTC instant with milliseconds, for example `2023-11-14T22:13:20.123Z` |
| `logger.name` | The logger name (empty string if none) |
| `thread.name` | The thread name (empty string if none) |
| `log.source` | The `source` attribute, only when set |
| `error.stack` | The rendered throwable, when `includeStackTrace` is true and the event has one |
| one attribute per key | Each non-null MDC (ThreadContext) entry, as a string. Mule sets `correlationId` and `processorPath`. |

MDC keys are not de-duplicated against the fixed fields. A key named `content`, `loglevel`,
`timestamp`, `logger.name`, `thread.name`, `log.source` or `error.stack` would produce a
duplicate JSON key, so avoid them.

### Timestamp

The event time is **always** sent as `timestamp`, and `includeTimestamp` is ignored for
Dynatrace. The sink sends an ISO string rather than an epoch number on purpose, because
Dynatrace reads bare epoch numbers as seconds in some places and as milliseconds in others.
As a result, Dynatrace stores the time the event was generated, not the time it arrived. Unlike
the Datadog and New Relic demos, you can't read ingest lag off the stored timestamp.

### Stack traces

When `includeStackTrace` is on and the event carries a throwable, the rendered stack trace is in
`error.stack`. The demo's `/boom` flow logs a string through Mule's `<logger>`, which attaches no
throwable, so the demo never produces an `error.stack`. A `<PatternLayout>` also appends the
stack trace to `content` unless it sets `alwaysWriteExceptions="false"`: Log4j2's default is
true, even for a bare `%m` pattern.

### Per-request limits and gzip

The Dynatrace sink caps a request at **5000 records** and about **4 MiB** of uncompressed body.
The byte cap applies to the engine's per-record estimate (UTF-8 bytes of the fields plus a fixed
allowance, before JSON escaping), so a body of quote-heavy `content` can be somewhat larger. Dynatrace answers `413` when a payload is too large but doesn't publish an
exact ceiling, so the sink stays under the commonly cited ~5 MB. The engine uses the smaller of
these values and [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords) /
[`maxBatchBytes`](../configuration.md#opt-maxbatchbytes). With the library defaults, your own
settings are what limit a batch. The ceiling only matters if you raise them above it.

With [`gzip`](../configuration.md#opt-gzip) on, the body is compressed once per batch and the
compressed bytes are reused on every retry. The limits always apply to the uncompressed size.

### Which responses retry or drop

| Response | Outcome |
| --- | --- |
| Any `2xx` | Success for the whole batch. Dynatrace answers `204` when it accepted every record. |
| `200` with rejected records | Also counted as fully delivered. The sink doesn't parse the body, where Dynatrace reports a partial ingest. |
| `408`, `429`, `5xx`, network error or timeout | Retried up to [`maxRetries`](../configuration.md#opt-maxretries) times with jittered exponential backoff (starting at [`initialRetryBackoffMillis`](../configuration.md#opt-initialretrybackoffmillis), capped at [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis)). A `Retry-After` header takes precedence, also capped. When retries run out, the batch goes to the disk spill buffer and is replayed later. With spill off, it is counted `dropped`. |
| Any other status (`400`, `401`, `403`, `413`, …, and `1xx`/`3xx`: redirects are not followed) | Fatal. No retry, and the records are counted `dropped` with an ERROR such as `batch-http[Dynatrace] dropped N record(s) — endpoint rejected the batch (HTTP 401); check credentials/endpoint, retrying will not help`. The same rule applies to replayed spill segments. |

## Verify delivery

In Dynatrace, open the **Logs** app and filter on `log.source`, which holds the value of your
`source` attribute. You can also run DQL in a notebook:

```text
fetch logs
| filter log.source == "dynatrace-batch-http"
| sort timestamp desc
```

To count arrivals, for example to compare against what a load test emitted:

```text
fetch logs, from:now()-1h
| filter log.source == "dynatrace-batch-http"
| summarize count()
```

Over the API, this is a Grail query against the platform address of the environment. It is
authenticated with the **platform** token, not the ingest token:

```bash
curl -s -X POST \
  "https://<environment-id>.apps.dynatrace.com/platform/storage/query/v1/query:execute" \
  -H "Authorization: Bearer $DT_PLATFORM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"fetch logs, from:now()-1h | filter log.source == \"dynatrace-batch-http\" | summarize count()"}'
```

If the query doesn't finish within the request, the response has `"state":"RUNNING"` and a
`requestToken`. Fetch the result with `GET …/query:poll?request-token=<token>`.

For the demos, filter on the name the deployment actually carries: the CloudHub 1.0 app name,
the value of the `domain` property on CloudHub 2.0, or `dynatrace-batch-http` locally.

On the appender side, set [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel)`="INFO"`
and [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) to print the stats
heartbeat (accepted, sentRecords, dropped, queueDepth, spill) to the StatusLogger. Keep
`<Configuration status>` at `INFO` or `WARN`. If the DQL count falls short of `sentRecords`
while nothing was dropped, look for `200` partial-ingest replies.

**Optional: parse `content`.** Dynatrace stores `content` as text. To get the JSON fields inside
it (`event`, `orderId`, `platform`, …) as attributes, add an OpenPipeline processing rule with a
DQL processor such as `parse content, "JSON:mule"` for records with the matching `log.source`.
That puts the parsed object into one record field named `mule` (`mule[event]`, `mule[orderId]`,
…); add a `fieldsFlatten mule` step if you want them as top-level attributes.
When comparing an A/B pair, apply the rule to both apps.

## Benchmark on CloudHub

The Dynatrace pair was measured on **2026-10-13**, with engine **0.2.0** and **disk spill on**
(`gzip="false"`, as explained above). The protocol was the same as for the other vendors:
`/log?count=20` (22 log events per request), CloudHub 1.0 at `oha -c 10` over http, and
CloudHub 2.0 at `-c 5` over https. Workers were 0.1 vCore in Sydney (`ap-southeast-2`), and every
test except Test 2 (churn), which runs 60 s after Test 1 with no restart, started on a fresh
restart. The Dynatrace lane ran on its own; the other three vendors were
measured on 2026-10-12. The intake was a Dynatrace SaaS environment on AWS Sydney, in the same
region as the workers. Delivery was counted in Grail by `log.source` and by event time inside the
load window. [Benchmark results](../benchmark-results.md) describes the method and the
cross-vendor comparison.

**Headline (Test 3, 5 minutes):** batch delivered **100%** on both planes (888,206 of 888,206
records on CloudHub 1.0, 268,554 of 268,554 on 2.0) at **134.54 req/s** (CH1) and **40.68 req/s**
(CH2). That is **6.5×** and **4.4×** the stock appender's request rate. On CloudHub 1.0, stock
delivered only **50.9%**.

### CloudHub 1.0 (0.1 vCore, `-c 10`)

| Test | Batch req/s | Stock req/s | Batch÷stock | Batch p90 (s) | Stock p90 (s) | Batch delivered | Stock delivered |
|---|---|---|---|---|---|---|---|
| 1 (1 min) | 67.16 | 24.68 | 2.7× | 0.256 | 0.80 | 100.0% | 58.5% |
| 2 (churn) | 136.95 | 24.44 | 5.6× | 0.125 | 0.98 | 100.0% | 56.2% |
| 3 (5 min) | 134.54 | 20.58 | 6.5× | 0.127 | 1.04 | 100.0% | 50.9% |
| 4 (5 min, `-t 30s`) | 139.02 | 24.85 | 5.6× | 0.124 | 0.84 | 100.0% | 50.7% |

### CloudHub 2.0 (0.1 vCore, `-c 5`)

| Test | Batch req/s | Stock req/s | Batch÷stock | Batch p90 (s) | Stock p90 (s) | Batch delivered | Stock delivered |
|---|---|---|---|---|---|---|---|
| 1 (1 min) | 17.58 | 6.28 | 2.8× | 0.428 | 1.31 | 100.0% | 100.0% |
| 2 (churn) | 41.69 | 10.57 | 3.9× | 0.197 | 0.79 | 100.0% | 100.0% |
| 3 (5 min) | 40.68 | 9.18 | 4.4× | 0.205 | 0.76 | 100.0% | 100.0% |
| 4 (5 min, `-t 30s`) | 37.20 | 10.26 | 3.6× | 0.229 | 0.68 | 100.0% | 100.0% |

Test 1 is a cold 1-minute run. Test 2 repeats it 60 s later with no restart. Test 3 is the warm
5-minute headline. Test 4 is Test 3 with a 30 s per-request cap.

![Dynatrace request throughput, batch vs stock appender, CloudHub 1.0 and 2.0](../img/vendors/dynatrace-throughput.png)

![Dynatrace request latency, batch vs stock appender, CloudHub 1.0 and 2.0](../img/vendors/dynatrace-latency.png)

![Dynatrace logs delivered as a share of logs generated, batch vs stock, per test](../img/vendors/dynatrace-delivered.png)

### What the Dynatrace numbers show

- **Batch is bounded by the worker, not by Dynatrace.** Batch req/s matches the other vendors on
  the same workers (about 135 on CloudHub 1.0 and 40 on CloudHub 2.0 in the 5-minute tests), and
  batch delivered 100% in every test.
- **The batch ÷ stock gap is much smaller than elsewhere (4.4–6.5× in Test 3, against 28–140× for
  the other vendors).** A single-record POST to the in-region Dynatrace intake takes about
  4.4 ms, so the stock appender pays far less per event than it does against a distant intake. If
  your Dynatrace environment is in a different region from your workers, expect a wider gap.
- **CloudHub 1.0 stock lost about half its records.** Its delivery held at a flat ~225 records/s
  for the whole run, which is the rate of one serialized Log4j2 background sender. The app was
  generating about 450–550 records/s (453/s in Test 3, about 540/s in Tests 1, 2 and 4). On
  CloudHub 2.0, the stock app's own load was about 220 records/s, just under that rate, so it
  delivered 100%.
- **This is not Dynatrace throttling.** A 914 req/s single-record burst from a laptop got HTTP
  `204` for every request, and 100% of the records were stored.
- **Where the undelivered stock records were lost is unconfirmed.** The timing points at the
  async logging queue rather than a restart: nothing restarted after Test 4, and its shortfall
  (81,093 records) would have drained at ~225 records/s in about 6 minutes, well before it was
  counted about 15 minutes after the load ended. The benchmark didn't measure queue drops
  directly.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Startup ERROR `could not build its 'dynatrace' sink: DynatraceSink requires an environmentUrl` (or `requires an apiToken`), then no logs | `url` or `token` is missing or blank. The appender is disabled. The URL is checked first, and the message's required-attributes list always names both. A malformed `url` is reported the same way. | Set `url` and `token` (or `apiKey`). If you see no startup ERROR at all, check that `<Configuration status>` is not `OFF`. |
| Appender disabled although `apiKey` is set | `token=""` is present but empty (for example, a lookup that resolved to empty). An empty `token` is still used instead of `apiKey`. | Use only one of the two attributes. |
| ERROR `… (HTTP 401) …`, records `dropped` | The token is bad, expired, or missing. In the demos, an unset token ships the literal `MISSING_TOKEN`. | Generate a new `logs.ingest` token and check the lookup resolves. |
| ERROR `… (HTTP 403) …` | The token lacks the `logs.ingest` scope. | Create a token with **Ingest logs**. |
| `404` or a wrong-host error | The URL is the `*.apps.dynatrace.com` UI address. | Use `*.live.dynatrace.com` (SaaS) or the ActiveGate `/e/<environment-id>` form. |
| DNS errors naming `set-dynatrace-url.invalid`, then spill or drops | The demo's URL fallback: no URL was supplied. | Set `dynatrace.url` or `DT_ENVIRONMENT_URL`. |
| ERROR `… (HTTP 413) …` | The request is too large. This only happens if `maxBatchBytes` or `maxBatchRecords` was raised above the sink ceiling. | Lower them (see [limits](#per-request-limits-and-gzip)). |
| Fewer records in Dynatrace than `sentRecords`, no drops reported | A `200` partial ingest is counted as full success. | Compare the DQL count with the heartbeat, and check the rejected records' format. |
| `service` or `host` doesn't show up | The Dynatrace sink has no such fields. `service`, `host`, `hostname`, `site`, `index`, `sourcetype` and `includeTimestamp` have no effect. | Use `source` (`log.source`), the layout, or MDC. |
| `${...}` values in `log4j2.xml` don't resolve in Mule | Log4j2 parses the file, so Mule properties and `config.properties` aren't visible to it. | Use `${sys:...}` or `${env:...}`. CloudHub application properties arrive as system properties. Under the standalone wrapper, use `-M-D…`. |
| `log.source` is the module name or empty on CloudHub 2.0 | `domain` isn't set automatically on CloudHub 2.0, or it was set to an empty value. | Add `domain=<app-name>`. To remove an override, delete the property rather than clearing it. The same applies to `app.platform`. |
| Duplicate keys in stored records | An MDC key collides with `content`, `loglevel`, `timestamp`, `logger.name`, `thread.name`, `log.source` or `error.stack`. | Rename the MDC key. |
| Stock `<Http>` sibling posts to a wrong path or loses events | The stock appender doesn't normalize the URL and never retries. Any non-2xx response loses the event. | Give it the bare environment URL with no trailing slash, and read the StatusLogger. |

For the general diagnostics (stats heartbeat fields, spill warnings, shutdown drain), see
[Troubleshooting & diagnostics](../troubleshooting.md).

---

[Configuration reference](../configuration.md) · [Docs index](../README.md) · Other vendors:
[Datadog](datadog.md) · [New Relic](newrelic.md) · [Elasticsearch](elasticsearch.md) ·
[Splunk](splunk.md) · [Generic HTTP](generic.md)
