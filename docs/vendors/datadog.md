# Datadog

With `vendor="datadog"`, `<BatchHttp>` collects log events into batches and POSTs each batch as
one JSON array to the Datadog Logs intake (v2), `https://<site>/api/v2/logs`. The request
authenticates with a Datadog **API key** in the `DD-API-KEY` header. Every record carries
Datadog's reserved attributes (`message`, `status`, `ddsource`, and optionally `service`, `host`
and `date`), plus the logger, the thread and any MDC entries. Because of that, logs arrive already
faceted in the Log Explorer.

> **Sample Mule project:** [`demo/mule/datadog-batch-http`](../../demo/mule/datadog-batch-http/)
> is a Mule 4.9 app (Java 17) that ships its application logs to Datadog through `<BatchHttp>`.
> Its control sibling [`demo/mule/datadog-stock-http`](../../demo/mule/datadog-stock-http/) runs
> the same flow and workload through Log4j2's stock `<Http>` appender, which sends one synchronous
> POST per event. Together the pair is an A/B test where only the appender changes. It shows the
> difference in request throughput, latency, logs delivered and ingest lag, on your machine or on
> CloudHub ([results below](#benchmark-on-cloudhub)).

**On this page:** [Set up Datadog](#set-up-datadog) ·
[Configuration](#configuration) · [What gets sent](#what-gets-sent) ·
[Verify delivery](#verify-delivery) · [Benchmark on CloudHub](#benchmark-on-cloudhub) ·
[Troubleshooting](#troubleshooting)

## Set up Datadog

1. **Pick your site.** A Datadog organization lives on exactly one site (US1, EU, AP2, and so on),
   and its API keys only work against that site. Look up the site's **log-intake host** in
   [Datadog's site documentation](https://docs.datadoghq.com/getting_started/site/) and set it as
   [`site`](../configuration.md#opt-site). The set of sites changes over time, so this repo does
   not restate it. If you leave `site` unset, the sink uses Datadog's default site host, which
   rejects keys from any other site.
2. **Create an API key** under *Organization Settings → API Keys*. It must be an **API key**, not
   an Application key. Log intake needs no extra scopes, and a trial account is enough.
3. **No index or pipeline setup is required.** Datadog parses a JSON-string `message` into
   top-level attributes on its own. Keep Datadog's reserved names (`status`, `host`, `service`,
   `message`, `ddsource`, `ddtags`, `date`, `source`, `trace_id`) out of your JSON message, because
   they override the fields the appender sets. The demo flow calls its order state `orderStatus`
   for this reason: a `status` key would put `SHIPPED` in the level column. Its messages do carry
   `"source": "mule"`, which is harmless only because it equals the appender's `source="mule"`.
4. **Expect throttling under heavy load.** Datadog rate-limits Logs intake per organization and
   key. An HTTP 429 in the appender's diagnostics means the appender is backing off as told (see
   [which responses retry](#which-responses-retry-or-drop)).

## Configuration

`log4j2.xml` is read by Log4j2, not by Mule's property resolver. Supply the API key only through
Log4j2 lookups such as `${env:DD_API_KEY}` or `${sys:datadog.apiKey}`. Mule `${...}` and
`secure::` placeholders do not resolve in this file. For the details, see
[Using it from a Mule app](../mule.md).

### Java application

A minimal configuration. `site` falls back to the default site host when `DD_SITE` is unset:

```xml
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <!-- site: the log-intake host of your Datadog site; the sink appends /api/v2/logs -->
        <BatchHttp name="datadog" vendor="datadog"
                   site="${env:DD_SITE:-http-intake.logs.datadoghq.com}"
                   apiKey="${env:DD_API_KEY}"
                   service="orders-api"
                   host="${env:HOSTNAME:-${hostName}}"
                   source="java">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <Root level="INFO">
            <AppenderRef ref="datadog"/>
        </Root>
    </Loggers>
</Configuration>
```

[`log4j2-example.xml`](../../demo/java/benchmark/src/main/resources/log4j2-example.xml) has the same Datadog block alongside
the other vendors.

### Mule application

Below is an excerpt of the demo's
[`log4j2.xml`](../../demo/mule/datadog-batch-http/src/main/resources/log4j2.xml). The full file also
sets every spill and crash-resilience option explicitly and documents each one inline, plus
CloudHub's framework noise-reduction loggers. The `packages` attribute is required: without it,
Log4j2 does not know `<BatchHttp>` inside the Mule app's classloader.

```xml
<Configuration status="INFO" packages="com.mulesoft.support.batchhttp.log4j2">
    <Properties>
        <Property name="app.service">${sys:app.service:-datadog-batch-http}</Property>
        <Property name="app.platform">${sys:app.platform:-${env:APP_PLATFORM:-unspecified}}</Property>
        <Property name="dd.host">${sys:domain:-${env:HOSTNAME:-${hostName}}}</Property>
        <Property name="dd.site">${sys:dd.site:-${env:DD_SITE:-http-intake.logs.datadoghq.com}}</Property>
    </Properties>
    <Appenders>
        <!-- CloudHub 1.0 log file, as the custom-log-appender documentation prescribes -->
        <RollingFile name="FILE"
                     fileName="/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log"
                     filePattern="/opt/mule/mule-CURRENT/logs/mule-${sys:domain}-%i.log">
            <PatternLayout pattern="[%d{MM-dd HH:mm:ss.SSS}] %-5p %c{1} [%t]: %m%n"/>
            <DefaultRolloverStrategy max="10"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="10 MB"/>
            </Policies>
        </RollingFile>
        <BatchHttp name="datadog"
                   vendor="datadog"
                   site="${dd.site}"
                   apiKey="${sys:DD_API_KEY:-${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}}"
                   service="${app.service}"
                   host="${dd.host}"
                   source="mule"
                   maxBatchRecords="200"
                   maxBatchBytes="900000"
                   lingerMillis="2000"
                   queueCapacity="25000"
                   gzip="false"
                   includeStackTrace="true"
                   includeTimestamp="false"
                   diagnosticsLevel="INFO"
                   statsIntervalMillis="60000"
                   spillEnabled="${sys:batch.spillEnabled:-true}"
                   spillInstanceId="datadog-batch-http"
                   spillBacklogOrder="DUAL_STREAM"
                   spillReplayThreads="2">
            <!-- Prepends the platform facet and collapses the flow's pretty-printed JSON message to one line -->
            <PatternLayout pattern="{&quot;platform&quot;:&quot;${app.platform}&quot;,%replace{%m}{^\x7B\s*|[\r\n]+\s*}{}"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <!-- Application logs ship to Datadog and the local file, exactly once -->
        <AsyncLogger name="com.mulesoft.support.batchhttp.muledemo" level="INFO" additivity="false">
            <AppenderRef ref="datadog"/>
            <AppenderRef ref="FILE"/>
        </AsyncLogger>
        <AsyncLogger name="org.mule" level="INFO"/>
        <AsyncLogger name="com.mulesoft" level="INFO"/>
        <!-- Root carries the FILE appender only: framework lines are not shipped to Datadog -->
        <AsyncRoot level="INFO">
            <AppenderRef ref="FILE"/>
        </AsyncRoot>
    </Loggers>
</Configuration>
```

What the demo does, and why:

- **Key chain.** The API key comes from `-DDD_API_KEY`, `-Ddatadog.apiKey` or the `DD_API_KEY`
  environment variable. The chain ends in the visible sentinel `MISSING_KEY`, so a missing key
  fails loudly with HTTP 403 instead of shipping the literal lookup text. On CloudHub 1.0, application
  properties surface as JVM system properties, so a `datadog.apiKey` property set in Runtime
  Manager resolves through `${sys:datadog.apiKey}`. On standalone Mule, only `-M-D...` wrapper
  arguments become JVM system properties; a bare `-D` is swallowed.
- **Facets.** `service` is the app's own name (override it with `-Dapp.service`), `host` is the
  CloudHub 1.0 domain or else the container hostname, and the layout adds a `platform` field
  (`-Dapp.platform` or `APP_PLATFORM`) that separates CloudHub 1.0 from 2.0. See
  [Splitting CloudHub 1.0 from CloudHub 2.0](../mule.md#splitting-cloudhub-10-from-cloudhub-20) for how the two facets are meant to be
  used.
- **Two divergences that exist only for the A/B.** (The other non-default values in the excerpt
  are sizing choices.)
  [`gzip`](../configuration.md#opt-gzip) is off because the stock `<Http>` appender cannot
  compress, so leaving it on would vary two things at once.
  [`includeTimestamp`](../configuration.md#opt-includetimestamp) is off so that Datadog stamps
  arrival time, which makes ingest lag measurable (see [Timestamp](#timestamp)). Production should
  turn compression back on and usually wants its own event time.
- **Only application logs ship.** Root keeps framework lines in the local file. The vendor
  layout wraps the flow's JSON message, so a plain-text framework line would arrive as a mangled
  `message`.

Build and run it with `mvn install -DskipTests` at the repo root, then
`cd demo/mule/datadog-batch-http && mvn clean package`. The HTTP listener is on port 8083. The
module's [README](../../demo/mule/datadog-batch-http/README.md) includes a *Watch it work*
walkthrough of the spill buffer: point `dd.site` at an unreachable intake (a connection error is
retryable, while a bad key's 403 is dropped and never spilled), watch
`/tmp/batch-http-spill/Datadog/` fill, restart with the real site and watch it drain. The stock sibling listens on port 8084, and its
[README](../../demo/mule/datadog-stock-http/README.md) has the A/B measurement guide (local mock
intake or real Datadog). To deploy both to CloudHub 1.0, run `scripts/deploy-ch1.sh --datadog`
(the default selection). It needs `DD_KEY` and passes only `datadog.apiKey`, so add `app.platform`
(and `dd.site` if your org is not on the default site) in Runtime Manager *Properties* and
restart. CloudHub 2.0 deployment is manual; see [the runbook](../benchmark-runbook.md).

### Recommended production settings

The library defaults are a reasonable start. Each value below departs from a default for a
Datadog-specific reason:

```xml
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <BatchHttp name="datadog" vendor="datadog"
                   site="${env:DD_SITE:-http-intake.logs.datadoghq.com}"
                   apiKey="${env:DD_API_KEY}"
                   service="orders-api"
                   host="${env:HOSTNAME:-${hostName}}"
                   source="java"
                   queueCapacity="25000"
                   maxRetries="5"
                   maxRetryBackoffMillis="30000"
                   spillInstanceId="orders-api"
                   maxTotalSpillBytes="268435456">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <Root level="INFO">
            <AppenderRef ref="datadog"/>
        </Root>
    </Loggers>
</Configuration>
```

- [`gzip`](../configuration.md#opt-gzip) and
  [`includeTimestamp`](../configuration.md#opt-includetimestamp) are **left out on purpose**.
  Compression stays on, which the Datadog intake accepts. Every record keeps its own event time
  in `date`.
- [`queueCapacity`](../configuration.md#opt-queuecapacity) `25000`: at roughly 2 KB retained per
  record, that is about 50 MB of heap. It gives a burst tens of seconds of headroom on a
  fractional-vCore worker, and the spill buffer takes anything beyond it.
- [`maxRetries`](../configuration.md#opt-maxretries) `5` and
  [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis) `30000`: Datadog
  answers throttling with 429 and a `Retry-After` header. The appender honours `Retry-After` but
  caps it at `maxRetryBackoffMillis`, so a higher cap lets it wait as long as Datadog asked
  instead of retrying early. A batch that still fails goes to disk, not to the floor.
- [`spillInstanceId`](../configuration.md#opt-spillinstanceid): it defaults to the appender
  name. Name it after the app, so that two apps on one host that both call their appender
  `datadog` keep separate spill files and locks.
- [`maxTotalSpillBytes`](../configuration.md#opt-maxtotalspillbytes) `268435456` (256 MiB):
  Datadog discards logs older than its maximum accepted age. Past that window a larger backlog
  only costs disk, so size the buffer to the outage you expect to ride out, not to "everything".
  On hosts that may come back with an old spill directory, consider
  [`spillRecoveryMode`](../configuration.md#opt-spillrecoverymode) `DELETE` or `IGNORE` (see
  [Resilience](../resilience.md)).
- With `status="WARN"` as shown you see failures only. For a once-a-minute heartbeat, so "is it
  still shipping?" is answered by a log line, opt in with
  [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO` and
  [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) `60000`, and also set
  `<Configuration status="INFO">`: the heartbeat prints only when both allow INFO.

### Options with a Datadog-specific meaning

| Attribute | Meaning for Datadog |
|---|---|
| [`vendor`](../configuration.md#opt-vendor) | `datadog` selects this sink (case-insensitive). |
| [`site`](../configuration.md#opt-site) | Log-intake **host** of your Datadog site: host only, no scheme or path. The endpoint is always `https://<site>/api/v2/logs`. Unset uses Datadog's default site host. |
| [`apiKey`](../configuration.md#opt-apikey) | **Required.** Sent as the `DD-API-KEY` header. Marked sensitive, so Log4j2 masks it in status and debug output. |
| [`service`](../configuration.md#opt-service) | Datadog's reserved `service` attribute, a top-level field on every record. Omitted when unset. An empty string is still sent as `""`. |
| [`host`](../configuration.md#opt-host) | Datadog's reserved `host` attribute. Omitted when unset or blank, and then Datadog infers the host itself. |
| [`source`](../configuration.md#opt-source) | Emitted as `ddsource`, Datadog's source facet. The sink uses `java` when unset; the Mule demo sets `mule`. |
| [`includeTimestamp`](../configuration.md#opt-includetimestamp) | Controls Datadog's reserved `date` attribute (see [Timestamp](#timestamp)). |
| [`includeStackTrace`](../configuration.md#opt-includestacktrace) | Puts the event's `Throwable` in `error.stack`. |
| [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords), [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) | Also capped by the sink's per-request limits (see [Per-request limits and gzip](#per-request-limits-and-gzip)). |
| [`maxMessageBytes`](../configuration.md#opt-maxmessagebytes) | Truncates long messages. Datadog also caps the size of a single log. |
| [`Layout`](../configuration.md#opt-layout) | Renders `message`. The layout's charset has no effect, because bodies are always UTF-8 JSON. |
| [`url`](../configuration.md#opt-url), [`hostname`](../configuration.md#opt-hostname), [`token`](../configuration.md#opt-token), [`index`](../configuration.md#opt-index), [`sourcetype`](../configuration.md#opt-sourcetype) | Ignored for Datadog. Use `site`, not `url`. |

Every other option (batching, queue, retries, spill, TLS, diagnostics) applies unchanged. See
[configuration.md](../configuration.md).

`ddtags` cannot be set from `log4j2.xml`; there is no such attribute. To tag logs, put the values
in the message, the MDC, or `service`/`source`.

## What gets sent

Each request is a UTF-8 JSON array of records, `[{...},{...}]`, with
`Content-Type: application/json`, plus `Content-Encoding: gzip` when compression is on.

| From | Datadog field |
|---|---|
| Rendered layout output (or the formatted message when there is no layout) | `message` (reserved). Datadog parses a JSON-string message into top-level attributes, and the Mule demo relies on that. |
| Event level (`INFO`, `WARN`, `ERROR`, and so on) | `status` (reserved log level) |
| `source` attribute | `ddsource` |
| `service` attribute, if set | `service` (reserved) |
| `host` attribute, if not blank | `host` (reserved) |
| Logger name (`""` if none) | `logger.name` |
| Thread name (`""` if none) | `logger.thread_name` |
| Event time, when `includeTimestamp` is on | `date` (reserved event time) |
| The event's `Throwable`, when `includeStackTrace` is on and the event has one | `error.stack` |
| Each MDC / ThreadContext entry with a non-null value | A top-level attribute with the same key, after the fixed fields |

MDC keys are not de-duplicated. An MDC key named `service`, `host`, `status`, `message` or `date`
produces a duplicate JSON key, so keep Datadog's reserved names out of the MDC. The Mule demo's
`correlationId` and `processorPath` arrive through this path.

### Timestamp

With [`includeTimestamp`](../configuration.md#opt-includetimestamp) on, each record carries
`date`: the Log4j event time as an ISO-8601 UTC instant, such as `2023-11-14T22:13:20.123Z`.
Datadog stores it as the log's official time. This is what a production app usually wants, but
it makes shipping delay invisible by construction.

With it off, `date` is omitted and Datadog stamps the **arrival** time. The Mule demos do this on
purpose, so that arrival minus `generatedAt` (a field the flow writes into its own JSON message)
is the ingest lag. The stock sibling's layout has no `date` key either, so both halves of the A/B
are measured the same way.

Datadog rejects logs older than its
[published maximum age](https://docs.datadoghq.com/logs/log_collection/). A stale spill backlog
replayed after a long outage is accepted at the HTTP level, then discarded on Datadog's side, and
still counted in `sentRecords`. See [Resilience](../resilience.md).

### Stack traces

When [`includeStackTrace`](../configuration.md#opt-includestacktrace) is on and the event carries
a `Throwable`, the full `printStackTrace` text goes into `error.stack`. Mule's `<logger>` never attaches a `Throwable`; it logs a string. So the
demo's `/boom` endpoint exercises ERROR-level routing and the flow's own `errorType`/`reason`
fields, not `error.stack`.

### Per-request limits and gzip

The Datadog sink caps each request at **1000 records and 5 MiB of uncompressed body**, which stays
just under Datadog's documented ceilings. Datadog's
[API reference](https://docs.datadoghq.com/api/latest/logs/#send-logs) is authoritative for the
current values. The engine flushes at whichever of
[`maxBatchRecords`](../configuration.md#opt-maxbatchrecords)/[`maxBatchBytes`](../configuration.md#opt-maxbatchbytes)
and the sink cap is lower, so raising either option above the sink cap has no effect. Size is
estimated in UTF-8 bytes.

With [`gzip`](../configuration.md#opt-gzip) on, each batch is compressed once and the compressed
body is reused across retries. Datadog measures its per-request ceilings uncompressed, so
compression changes the bytes on the wire, not how many records fit in a batch.

### Which responses retry or drop

| Response | Outcome |
|---|---|
| Any 2xx (Datadog answers `202 Accepted`) | Success. The body is not inspected. |
| 408, 429, any 5xx, a network error or a timeout | Retried up to [`maxRetries`](../configuration.md#opt-maxretries) times with exponential backoff from [`initialRetryBackoffMillis`](../configuration.md#opt-initialretrybackoffmillis), capped at [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis). A `Retry-After` header takes precedence, and the same cap applies to it. When the retries run out, the batch goes to the spill buffer. With spill off it is dropped, with `batch-http[Datadog] dropped N record(s) — send failed after <maxRetries> retries (HTTP xxx) and spill is disabled`. |
| Any other status: 400 malformed, 401/403 bad or wrong-site key, 413 too large, and 1xx/3xx (redirects are not followed) | Fatal: never retried and never spilled. The records count as `dropped`, with `batch-http[Datadog] dropped N record(s) — endpoint rejected the batch (HTTP 403); check credentials/endpoint, retrying will not help`. If this happens during spill replay, the segment is deleted and its records count as dropped. |

## Verify delivery

**In Datadog:** open *Logs → Search* (Log Explorer) and query by service:

```text
service:orders-api
service:(datadog-batch-http OR datadog-stock-http)
@platform:cloudhub-2.0 @logger.name:com.mulesoft.support.batchhttp.muledemo
```

The first line is the Java sample. The second is the demo pair; group it by `service` in a count
or timeseries view to compare volumes. The third splits a plane and narrows to the demo's
application logs. Attribute search takes the `@` prefix, and `source:mule` or `source:java`
matches `ddsource`. Each event should show `status`, `service`, `host`, `ddsource`,
`logger.name`, `logger.thread_name`, any MDC keys (such as `correlationId`), and `error.stack`
when a `Throwable` was logged. Events from the demo also show `platform` and `generatedAt`. To see
ingest lag, open an event and compare `generatedAt` with the Datadog timestamp (with
`includeTimestamp` off).

**From the app:** set [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) with
[`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO` (and
`<Configuration status="INFO">`), then watch `sentRecords` climb in the heartbeat lines prefixed
`batch-http[Datadog]`. Under load, an empty `${java.io.tmpdir}/batch-http-spill/Datadog/` is the
healthy state.

**With the demo:** `curl "http://localhost:8083/log?count=50"` emits 52 events (a `batch.start`,
50 `order.processed` and a `batch.completed` WARN), and `curl "http://localhost:8083/boom"` emits
one ERROR event.

## Benchmark on CloudHub

**Setup:** run on 2026-10-12 with engine 0.2.0 and the batch app's disk spill on. The Datadog
lane ran in parallel with the New Relic and Elasticsearch lanes, one app per vendor at a time.
Load was `/log?count=20`, which is 22 log events per request. CloudHub 1.0 ran at `oha -c 10` over
http and CloudHub 2.0 at `-c 5` over https, on 0.1 vCore workers in Sydney (`ap-southeast-2`),
next to Datadog's AP2 site. Every test except the churn test starts on a freshly restarted app.
The test protocol, the delivery counting and the cross-vendor tables are in
[benchmark-results.md](../benchmark-results.md).

**Headline (Test 3, 5 minutes):** on CloudHub 1.0 the batch app served **140.24 req/s against
2.62 for stock (53.5×)**, with p90 latency of 0.128 s against 11.02 s, and delivered **100%** of
its logs against 57.1% for stock. On CloudHub 2.0: **42.03 against 1.48 req/s (28.5×)**, with
100% delivered against 83.0%.

Compare batch with stock **within a plane** only. The two planes ran at different concurrency on
different hardware.

### CloudHub 1.0 (0.1 vCore, `-c 10`)

| Test | Batch req/s | Stock req/s | Batch÷stock | Batch p90 (s) | Stock p90 (s) | Batch delivered | Stock delivered |
|---|---|---|---|---|---|---|---|
| 1 (1 min) | 75.35 | 5.13 | 14.7× | 0.211 | 7.09 | 100.0% | 31.0% |
| 2 (churn) | 146.85 | 2.54 | 57.9× | 0.124 | 10.94 | 100.0% | 139.0% |
| 3 (5 min) | 140.24 | 2.62 | 53.5× | 0.128 | 11.02 | 100.0% | 57.1% |
| 4 (5 min, `-t 30s`) | 125.47 | 2.70 | 46.5× | 0.137 | 10.70 | 100.0% | 54.7% |

### CloudHub 2.0 (0.1 vCore, `-c 5`)

| Test | Batch req/s | Stock req/s | Batch÷stock | Batch p90 (s) | Stock p90 (s) | Batch delivered | Stock delivered |
|---|---|---|---|---|---|---|---|
| 1 (1 min) | 14.35 | 3.42 | 4.2× | 0.587 | 5.37 | 100.0% | 44.1% |
| 2 (churn) | 33.21 | 1.75 | 18.9× | 0.251 | 5.79 | 100.1% | 154.1% |
| 3 (5 min) | 42.03 | 1.48 | 28.5× | 0.203 | 5.74 | 100.0% | 83.0% |
| 4 (5 min, `-t 30s`) | 35.63 | 1.48 | 24.1× | 0.247 | 5.68 | 100.0% | 100.0% |

![Datadog request throughput per test, batch vs stock appender, on CloudHub 1.0 and CloudHub 2.0, with the batch-to-stock ratio above each pair](../img/vendors/datadog-throughput.png)

![Datadog request latency in Test 3 (p50, p90, p99), batch vs stock, on a logarithmic scale for each plane](../img/vendors/datadog-latency.png)

![Logs delivered to Datadog as a share of logs generated, batch vs stock, per test and plane](../img/vendors/datadog-delivered.png)

### What the Datadog numbers show

- **Batch delivered 100% in every test on both planes.** In Tests 1 and 3, stock delivered 31–57%
  on CloudHub 1.0 and 44–83% on CloudHub 2.0: whatever stock still holds in memory when the next
  restart comes dies with the app. Test 4 was the last round and nothing restarted after it, so
  CloudHub 1.0 stock's 54.7% there is a backlog still undrained, or lost, when counting stopped;
  which of the two is not established.
- **Delivery is counted by arrival time.** The demos omit `date`, so Datadog stamps arrival. Each
  test's window runs from its start to the next test on the same app; for Test 4 it is the load
  plus 15 minutes. Readings over 100% (stock
  churn: 139% and 154%) are Test 1's late backlog landing in the churn window, which follows
  Test 1 with no restart.
- **Test 1 under-reports warm batch throughput.** The batch JVM is still warming up when the
  1-minute window opens. On CloudHub 2.0, batch rose from 14.4 to 33.2 to 42.0 req/s across
  Test 1, churn and Test 3, and the CloudHub 1.0 batch app's slowest request fell from 4.8 s in
  Test 1 to 0.64 s in churn. Read Test 3 as the headline.
- **Batch req/s is about the same at every vendor.** On CloudHub 1.0, Test 3 batch was 140 for
  Datadog against 134–136 for New Relic and Elasticsearch, so the worker bounds batch throughput,
  not the intake.
- **Correction to the 2026-08-04 run.** In that run, the CloudHub 2.0 Datadog stock app carried a
  leftover `AsyncLoggerConfig.RingBufferSize=20000` and `log4j2.debug=true` that no other app
  had. It measured 5.75 req/s with them against 1.46 without, so the 7.6× published then is not
  comparable. A re-test without them gave about 31×. The app in this run has neither property.
- **Gzip was off on both sides** for the A/B (see [Mule application](#mule-application)). A
  production configuration that leaves compression on sends fewer bytes per batch.

### Ingest lag: generation to arrival

Under load, the clearest real-world symptom of per-event shipping is **ingest lag**: the gap
between when the app generated an event and when Datadog received it. Each demo event carries
`generatedAt`, written by the flow itself, and Datadog stamps the arrival time. The screenshots
below come from the CloudHub 2.0 pair in an **earlier session** than the tables above. The times
displayed are the deployment's local time, UTC+10.

**Batch: negligible lag.** An event with `generatedAt` `…T23:27:09.999Z` arrives at `09:27:09.999`
local time (`23:27:09.999Z`), the same instant:

![Datadog batch app: the event's generatedAt equals its ingest time, so the lag is near zero](../img/datadog-batch-ingest-lag.png)

**Stock: minutes behind.** An event with `generatedAt` `…T23:26:11.753Z` is not received until
`09:29:14.813` local time (`23:29:14.813Z`). The synchronous sends queue up behind each other, a
backlog of about 3 minutes:

![Datadog stock app: ingest time trails the event's generatedAt by about 3 minutes](../img/datadog-stock-ingest-lag.png)

The backlog is also a **volume** gap. Over one Datadog query window covering both apps, the batch
app delivered **84.7K** events and the stock app **5.66K**. That window is wider than any measured
run and comes from the same earlier session. It spans warmups, the measured minutes and the
backlog draining afterwards, so neither total reconciles with the tables above:

![Datadog log counts over one query window: 84.7K batch events vs 5.66K stock events](../img/datadog-log-volume-batch-vs-stock.png)

## Troubleshooting

For general diagnostics (the stats heartbeat, the spill directory, startup messages), see
[troubleshooting.md](../troubleshooting.md).

| Symptom | Cause | Fix |
|---|---|---|
| Startup: `BatchHttp appender 'datadog' could not build its 'datadog' sink: DatadogSink requires an apiKey. Required log4j2.xml attributes for vendor 'datadog': apiKey. This appender is disabled and will ship no logs.` | `apiKey` is missing or blank. | Set it with `${env:DD_API_KEY}` or `${sys:datadog.apiKey}`. The message only prints when `<Configuration status>` is not `OFF`. |
| `dropped N record(s) — endpoint rejected the batch (HTTP 403)` | A literal `${env:DD_API_KEY}` (or `MISSING_KEY`) was sent. Log4j2 leaves an unresolved lookup in place rather than emptying it. | Provide the variable or property. End the chain in a visible sentinel, as the demo does, so the cause is obvious. |
| HTTP 403 with a valid-looking key | The key belongs to a different Datadog site than `site`. The default site host rejects keys from other sites. | Set `site` to your org's log-intake host from [Datadog's site list](https://docs.datadoghq.com/getting_started/site/). |
| Appender disabled at startup with `Illegal character in authority` | `site="${env:DD_SITE}"` with `DD_SITE` unset leaves the literal lookup in the URI. | Add a fallback, such as `${env:DD_SITE:-http-intake.logs.datadoghq.com}`, or leave `site` out. |
| HTTP 429 in the diagnostics, `spilledRecords` rising | Datadog throttles Logs intake per org and key. | Nothing is lost while spill is on: the appender honours `Retry-After` up to [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis) and then spills. Raise that cap if the waits are being cut short. |
| Nothing ships from a Mule app and the key looks right | Mule `${...}` or `secure::` placeholders don't resolve in `log4j2.xml`, or a bare `-D` never reached the Mule JVM. | Use `${sys:...}` or `${env:...}`. On CloudHub 1.0, set `datadog.apiKey` as an application property. On standalone Mule, pass `-M-Ddatadog.apiKey=...`. See [mule.md](../mule.md). |
| `BatchHttp` is unknown and nothing ships | `packages="com.mulesoft.support.batchhttp.log4j2"` is missing on `<Configuration>`. | Add it. Every Mule app needs it. |
| The level column shows a business value (such as `SHIPPED`), or `service`/`host` is wrong | The JSON message or the MDC contains a Datadog reserved key (`status`, `service`, `host`, `message`, `date`, `ddsource`, `ddtags`) that overrides the appender's field. | Rename the key in the payload, for example `orderStatus`. |
| The `SERVICE` column is empty (demo) | A present-but-empty `-Dapp.service=` beats the `${sys:app.service:-default}` default. | Delete the override; don't clear it. The same applies to `app.platform` and `APP_PLATFORM`. |
| `platform` reads `unspecified` on CloudHub 1.0 (demo) | `scripts/deploy-ch1.sh` only passes `datadog.apiKey`. | Add `app.platform` in Runtime Manager *Properties* on both apps of the pair, then restart. |
| A local http mock receives nothing | The Datadog sink always calls `https://<site>/api/v2/logs` and ignores `url`, so it tries TLS against the mock. | For mock runs, switch to `vendor="generic"` with `url="http://localhost:9098/api/v2/logs"`; see the [stock sibling's README](../../demo/mule/datadog-stock-http/README.md). |
| Logs accepted (2xx) after a long outage but not searchable | Replayed spill records older than Datadog's maximum accepted age are discarded on Datadog's side, yet still counted in `sentRecords`. | Consider [`spillRecoveryMode`](../configuration.md#opt-spillrecoverymode) `DELETE` or `IGNORE` on hosts that may present old spill directories. |
| A backlog disappears after a CloudHub restart | The spill directory is `${java.io.tmpdir}/batch-http-spill/Datadog/` (the demo's segments are `spill-datadog-batch-http-<seq>.log`). A restart onto a new worker (1.0) or pod (2.0) loses `/tmp`. | Expected on CloudHub. On hosts with persistent storage, point [`spillDirectory`](../configuration.md#opt-spilldirectory) at a durable volume. |

---

[Configuration reference](../configuration.md) · [Docs index](../README.md) · Other vendors:
[New Relic](newrelic.md) · [Elasticsearch](elasticsearch.md) · [Dynatrace](dynatrace.md) ·
[Splunk](splunk.md) · [Generic HTTP](generic.md)
