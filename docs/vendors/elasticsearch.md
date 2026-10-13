# Elasticsearch

With `vendor="elasticsearch"` (aliases `elastic` and `elk`) the appender ships log
events to the Elasticsearch **Bulk API**: each batch is one
`POST <url>/<index>/_bulk` whose body is NDJSON, a `create` action plus an
[ECS](https://www.elastic.co/docs/reference/ecs) document per event. The target is a
**data stream** ([`index`](../configuration.md#opt-index)); the default name matches
Elasticsearch's built-in `logs-*-*` index template, so the data stream is created on
the first write with the logs mappings and lifecycle. It authenticates with an
Elasticsearch API key, sent as `Authorization: ApiKey <apiKey>`. The same sink works
with Elastic Cloud Hosted, Elastic Cloud Serverless and self-managed clusters. It
first shipped in 0.1.5 and has been a first-class vendor since 0.2.0.

> **Sample Mule project:** [`demo/mule/elastic-batch-http`](../../demo/mule/elastic-batch-http/)
> ships its logs through this appender, and its sibling
> [`demo/mule/elastic-stock-http`](../../demo/mule/elastic-stock-http/) ships the same
> logs through Log4j2's built-in `<Http>` appender, one POST per event to
> `/<data-stream>/_doc`. The two apps run an identical flow and write identical ECS
> documents into the same data stream; `service.name` is the intended discriminator
> (`host.name` also differs, because it is each app's own name or pod). Together
> they show the wiring for a Mule 4.9 app (Java 17), how the endpoint and key are
> injected on CloudHub, and what batching changes under load (see
> [Benchmark on CloudHub](#benchmark-on-cloudhub)).

**On this page:** [Set up](#set-up-elasticsearch) ·
[Configuration](#configuration) · [What gets sent](#what-gets-sent) ·
[Verify delivery](#verify-delivery) · [Benchmark](#benchmark-on-cloudhub) ·
[Troubleshooting](#troubleshooting)

## Set up Elasticsearch

1. **Endpoint.** In Kibana, open the deployment's or project's connection details and
   copy the *Elasticsearch endpoint*. On Serverless it looks like
   `https://<project>.es.<region>.<csp>.elastic.cloud:443`; on a self-managed cluster
   it is the cluster's HTTPS URL. This value is [`url`](../configuration.md#opt-url).
   It must start with `https://` or `http://`. If you can, pick a project or cluster in
   the same cloud region as your workers, because every batch pays that round trip.
2. **API key.** In Kibana, go to *API keys* and create a key restricted to what the
   appender needs:

   ```json
   {
     "mule-log-shipper": {
       "indices": [
         { "names": ["logs-mule*"], "privileges": ["create_doc", "auto_configure"] }
       ]
     }
   }
   ```

   `create_doc` lets the key append documents and nothing else. `auto_configure` lets
   the first write create the data stream. Widen `names` if your data stream uses a
   different pattern. Copy the **encoded** value: Kibana shows it only once, and it is
   the single base64 string, not the id/secret pair. This value is
   [`apiKey`](../configuration.md#opt-apikey).
3. **Data stream.** You don't need to create anything if the name matches `logs-*-*`.
   That covers `logs-mule-default` and any `logs-<dataset>-<namespace>` name such as
   `logs-orders-prod`. A name outside that pattern needs your own index template. A
   plain index needs the key to have privileges on it.
4. **Optional: parse the JSON inside `message`.** Elasticsearch stores `message` as
   text and does not lift JSON fields out of it. To get those fields as real fields,
   add a `logs@custom` ingest pipeline. The built-in `logs-*-*` template runs it on
   every write:

   ```text
   PUT _ingest/pipeline/logs@custom
   {
     "processors": [
       { "json": { "field": "message", "target_field": "mule", "ignore_failure": true } }
     ]
   }
   ```

## Configuration

Two attributes are required: [`url`](../configuration.md#opt-url) and
[`apiKey`](../configuration.md#opt-apikey). Pass both through `${env:...}` or
`${sys:...}` lookups and never write them into the file.

### Java application

```xml
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <!-- url: the Elasticsearch endpoint (scheme, host, optional port); the sink appends
             /<index>/_bulk. apiKey: the "encoded" API key Kibana shows once at creation. -->
        <BatchHttp name="elastic" vendor="elasticsearch"
                   url="${env:ELASTIC_URL}"
                   apiKey="${env:ELASTIC_API_KEY}"
                   index="logs-mule-default"
                   service="orders-api"
                   host="${hostName}">
            <PatternLayout pattern="%m"/>
        </BatchHttp>
        <Console name="console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="elastic"/>
            <AppenderRef ref="console"/>
        </Root>
    </Loggers>
</Configuration>
```

The multi-vendor Java example
[`log4j2-example.xml`](../../demo/java/benchmark/src/main/resources/log4j2-example.xml)
contains the same block next to the other vendors.

### Mule application

This is the appender wiring from the sample app's
[`log4j2.xml`](../../demo/mule/elastic-batch-http/src/main/resources/log4j2.xml),
shortened: the framework noise-reduction loggers and most comments are left out.
`packages="com.mulesoft.support.batchhttp.log4j2"` is required in every Mule app,
because the jar sits on the application classloader.

```xml
<Configuration status="INFO" packages="com.mulesoft.support.batchhttp.log4j2">
    <Properties>
        <Property name="app.service">${sys:app.service:-elastic-batch-http}</Property>
        <Property name="app.platform">${sys:app.platform:-${env:APP_PLATFORM:-unspecified}}</Property>
        <Property name="es.host">${sys:domain:-${env:HOSTNAME:-${hostName}}}</Property>
        <Property name="es.url">${sys:ELASTIC_URL:-${sys:elastic.url:-${env:ELASTIC_URL:-https://set-elastic-url.invalid}}}</Property>
        <Property name="es.dataStream">${sys:elastic.dataStream:-logs-mule-default}</Property>
    </Properties>
    <Appenders>
        <!-- CloudHub 1.0 custom-log-appender file, as documented by MuleSoft. -->
        <RollingFile name="FILE"
                     fileName="/opt/mule/mule-CURRENT/logs/mule-${sys:domain}.log"
                     filePattern="/opt/mule/mule-CURRENT/logs/mule-${sys:domain}-%i.log">
            <PatternLayout pattern="[%d{MM-dd HH:mm:ss.SSS}] %-5p %c{1} [%t]: %m%n"/>
            <DefaultRolloverStrategy max="10"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="10 MB"/>
            </Policies>
        </RollingFile>
        <BatchHttp name="elastic"
                   vendor="elasticsearch"
                   url="${es.url}"
                   apiKey="${sys:ELASTIC_API_KEY:-${sys:elastic.apiKey:-${env:ELASTIC_API_KEY:-MISSING_KEY}}}"
                   index="${es.dataStream}"
                   service="${app.service}"
                   host="${es.host}"
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
                   spillInstanceId="elastic-batch-http"
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
            <!-- gzip="false" is a deliberate A/B divergence: the stock sibling cannot compress.
                 Production should leave gzip on (omit the attribute). -->
            <!-- Prepend the platform facet and collapse the flow's pretty JSON to one line. -->
            <PatternLayout pattern="{&quot;platform&quot;:&quot;${app.platform}&quot;,%replace{%m}{^\x7B\s*|[\r\n]+\s*}{}"/>
        </BatchHttp>
    </Appenders>
    <Loggers>
        <AsyncLogger name="com.mulesoft.support.batchhttp.muledemo" level="INFO" additivity="false">
            <AppenderRef ref="elastic"/>
            <AppenderRef ref="FILE"/>
        </AsyncLogger>
        <AsyncRoot level="INFO">
            <AppenderRef ref="FILE"/>
        </AsyncRoot>
    </Loggers>
</Configuration>
```

Some things to know about this sample:

- **Injection.** Log4j2 parses `log4j2.xml`, so Mule `${...}` placeholders and
  `secure::` properties do not resolve there. On CloudHub 1.0 or 2.0, set `elastic.url`
  as an application property and `elastic.apiKey` as a **secure** application
  property. CloudHub passes both to the JVM as system properties. To run locally
  under the Mule standalone wrapper, pass `-M-Delastic.url=...` and
  `-M-Delastic.apiKey=...` (a bare `-D` never reaches the Mule JVM), or export
  `ELASTIC_URL` and `ELASTIC_API_KEY`. [Using it from a Mule app](../mule.md) covers
  this in detail.
- **Visible fallbacks.** If nothing is set, `url` falls back to a reserved `.invalid`
  host and `apiKey` to `MISSING_KEY`. A misconfigured app then fails loudly in the
  appender diagnostics instead of shipping logs somewhere.
- **Only the app's logger ships.** `AsyncRoot` writes to `FILE` only, so framework
  lines stay in the CloudHub log and are not sent to Elasticsearch.
- **One divergence for the A/B test:** `gzip="false"`, because the stock sibling
  cannot compress; the [sample README](../../demo/mule/elastic-batch-http/README.md)
  explains it. The other non-default values (spill sizes, `spillBacklogOrder`,
  `spillReplayThreads`, `maxBatchBytes`, `lingerMillis`, `queueCapacity`) are sample
  tuning; each is described in the [configuration reference](../configuration.md).
- **Build and run.** Run `mvn install -DskipTests` at the repo root, then
  `mvn clean package` in `demo/mule/elastic-batch-http` (the app listens on port 8085;
  the stock sibling on 8086). Run `curl "http://localhost:8085/log?count=50&level=info"`
  to emit 52 events, or `curl http://localhost:8085/boom` to emit one ERROR event.
  To deploy the pair to CloudHub 1.0, copy each module's
  `target/*-mule-application.jar` to `demo/mule/deployable/<module>.jar`
  ([runbook §2](../benchmark-runbook.md#2-build-the-artifacts)), export `ELASTIC_URL`,
  `ELASTIC_KEY` (the encoded key; the script reads this name, not `ELASTIC_API_KEY`),
  `ANYPOINT_CLIENT_ID`, `ANYPOINT_CLIENT_SECRET`, `ANYPOINT_ORG`, `ANYPOINT_ENV` and
  `REGION`, then run [`scripts/deploy-ch1.sh --elastic`](../../scripts/deploy-ch1.sh)
  (add `--dry-run` to print the commands with secrets masked). The script sets `elastic.url` and the
  secure `elastic.apiKey`, but not `app.platform`. Add `app.platform=cloudhub-1.0` to
  both apps in Runtime Manager, or their events arrive as `platform: unspecified`.

### Recommended production settings

```xml
<BatchHttp name="elastic" vendor="elasticsearch"
           url="${env:ELASTIC_URL}"
           apiKey="${env:ELASTIC_API_KEY}"
           index="logs-orders-prod"
           service="orders-api"
           host="${env:HOSTNAME}"
           maxBatchRecords="500"
           maxBatchBytes="4194304"
           queueCapacity="25000"
           spillInstanceId="orders-api"
           maxSpillFileBytes="16777216"
           maxTotalSpillBytes="536870912"
           diagnosticsLevel="INFO"
           statsIntervalMillis="60000">
    <PatternLayout pattern="%m"/>
</BatchHttp>
```

| Setting | Why |
| --- | --- |
| [`index`](../configuration.md#opt-index) `logs-orders-prod` | A `logs-<dataset>-<namespace>` name still matches the built-in `logs-*-*` template, so you get the logs mappings and lifecycle without creating a template. It also keeps one app's data separate from another's. The API key's `names` must cover this name (for example `logs-orders-*`; the key from [Set up](#set-up-elasticsearch) covers only `logs-mule*`), or every batch is rejected with 403 and dropped. |
| [`maxBatchRecords`](../configuration.md#opt-maxbatchrecords) / [`maxBatchBytes`](../configuration.md#opt-maxbatchbytes) | Elasticsearch has no per-request document cap and works best with bulk bodies of a few MiB. 500 records or 4 MiB means fewer, larger requests, well under the sink's 10 MiB ceiling. These values are not what the benchmark ran (it used the sample's settings). |
| [`gzip`](../configuration.md#opt-gzip) left unset | Compression stays on. Elasticsearch accepts gzip request bodies, and log text compresses well. The sample turns gzip off only to keep the A/B comparison fair. |
| [`queueCapacity`](../configuration.md#opt-queuecapacity) `25000` | Holds tens of seconds of a burst in memory (about 50 MB at roughly 2 KB retained per record) before overflow goes to disk. |
| [`spillInstanceId`](../configuration.md#opt-spillinstanceid) | Stays the same across restarts of this app and is unique per running instance, so a restart on the same host adopts its own backlog. |
| [`maxSpillFileBytes`](../configuration.md#opt-maxspillfilebytes) / [`maxTotalSpillBytes`](../configuration.md#opt-maxtotalspillbytes) | 512 MiB of on-disk backlog rides out a longer intake outage. Check that the volume has room. If the JVM temp dir is memory-backed, point [`spillDirectory`](../configuration.md#opt-spilldirectory) at real storage ([Resilience](../resilience.md)). |
| [`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO` + [`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) | Writes a one-line delivery heartbeat every minute. Combine it with `<Configuration status="INFO">` so the heartbeat prints. |

### Options with an Elasticsearch-specific meaning

| Option | Meaning for Elasticsearch |
| --- | --- |
| [`vendor`](../configuration.md#opt-vendor) | `elasticsearch`, or the alias `elastic` or `elk` (case-insensitive, trimmed). |
| [`url`](../configuration.md#opt-url) | **Required.** The Elasticsearch endpoint: `https://` or `http://`, host, and an optional port. The sink appends `/<index>/_bulk` and strips one trailing slash. A URL without a scheme is rejected at startup. |
| [`apiKey`](../configuration.md#opt-apikey) | **Required.** The encoded API key, sent as `Authorization: ApiKey <apiKey>`. Surrounding whitespace, such as a trailing newline from a secret file, is trimmed. Log4j2 masks the value in its status output. |
| [`index`](../configuration.md#opt-index) | The target data stream (or index). When it is unset or blank, the sink uses `logs-mule-default`. |
| [`service`](../configuration.md#opt-service) | Written to ECS `service.name` on every document. Omitted when unset or blank. |
| [`host`](../configuration.md#opt-host) | Written to ECS `host.name` on every document. Omitted when unset or blank. |
| [`token`](../configuration.md#opt-token) | Not read. Use `apiKey`. |
| [`site`](../configuration.md#opt-site), [`hostname`](../configuration.md#opt-hostname), [`source`](../configuration.md#opt-source), [`sourcetype`](../configuration.md#opt-sourcetype) | Ignored. |
| [`includeTimestamp`](../configuration.md#opt-includetimestamp) | Ignored. `@timestamp` is always sent, because data streams require it. |
| [`includeStackTrace`](../configuration.md#opt-includestacktrace) | Honoured. When an event carries a Throwable, the stack trace goes into ECS `error.stack_trace`. |
| [`Layout`](../configuration.md#opt-layout) | Its output becomes the document's `message`, with the trailing newline stripped and the size capped by [`maxMessageBytes`](../configuration.md#opt-maxmessagebytes). |

Every other option (batching, queue, retries, spill, TLS, diagnostics) applies
unchanged; see [the configuration reference](../configuration.md).

## What gets sent

Each event becomes two NDJSON lines in the bulk body: the action `{"create":{}}`, then
the document. `create` is the only action a data stream accepts. No `_id` is sent, so
Elasticsearch generates the document IDs. The body ends with the trailing newline
that `_bulk` requires.

| Source | Document field |
| --- | --- |
| Event time | `@timestamp`, as an ISO-8601 UTC instant with milliseconds, e.g. `2023-11-14T22:13:20.123Z` |
| Layout output | `message` |
| Event level | `log.level` |
| Logger name | `log.logger` |
| Thread name | `process.thread.name` |
| [`service`](../configuration.md#opt-service) (config) | `service.name`, only when set |
| [`host`](../configuration.md#opt-host) (config) | `host.name`, only when set |
| Throwable, when [`includeStackTrace`](../configuration.md#opt-includestacktrace) is on | `error.stack_trace` |
| Each MDC / ThreadContext entry `<key>` | `labels.<key>` (Mule sets `correlationId` and `processorPath`) |

MDC entries go under ECS `labels` because a bare key such as `host` or `log` would
collide with an ECS object field, and Elasticsearch would refuse the whole document
as a mapping conflict.

### Request

`POST <url>/<index>/_bulk?filter_path=errors,items.*.status,items.*.error.type,items.*.error.reason`
with `Content-Type: application/x-ndjson` and `Authorization: ApiKey <apiKey>`.
`filter_path` trims the response down to the per-item status and error, so reading
the response for a clean batch costs only a few bytes per document.

### Timestamp

`@timestamp` is always the event's own time, so the stored time equals
the time the event was generated. As a result, shipping delay does not show in
`@timestamp`. To see it you need `event.ingested`, which an ingest pipeline can add.

### Stack traces

Stack traces go to `error.stack_trace`, but only when the Log4j2 event
carries a Java Throwable. A Mule `<logger>` attaches none, so the sample's `/boom`
flow does not exercise this field. Its error type and description travel as JSON
inside `message`.

### Per-request limits and gzip

The sink has no per-request document cap. The engine flushes when its per-record size
estimate (the UTF-8 bytes of the message and fields plus a fixed allowance per record)
reaches the smaller of 10 MiB and
[`maxBatchBytes`](../configuration.md#opt-maxbatchbytes), and uses your own
[`maxBatchRecords`](../configuration.md#opt-maxbatchrecords) as the record cap. The
serialized bulk body is somewhat larger than the estimate (the `create` action lines,
ECS field names, `@timestamp`, the `labels.` prefix and JSON escaping are not counted).
The 10 MiB ceiling matters only if you set `maxBatchBytes` above it.

When [`gzip`](../configuration.md#opt-gzip) is on, the body is compressed
once per batch and sent with `Content-Encoding: gzip`.

### Which responses retry or drop

This happens at two levels.

- **The HTTP status of the request.** `2xx` is success. `408`, `429`, `5xx` and
  network errors (connection reset, timeout, DNS) are retried, up to
  [`maxRetries`](../configuration.md#opt-maxretries) times with exponential backoff.
  The sink honours `Retry-After`, capped by
  [`maxRetryBackoffMillis`](../configuration.md#opt-maxretrybackoffmillis). Any other
  status (a bad key or missing privilege as `401`/`403`, `404`, `413`, and `1xx`/`3xx`:
  redirects are not followed) is fatal: the
  batch is dropped at once with `ERROR ... dropped N record(s) — endpoint rejected the
  batch (HTTP 4xx); check credentials/endpoint, retrying will not help`. When retries
  run out, the batch goes to the disk spill buffer if spill is on, and is dropped if
  it is off ([Resilience](../resilience.md)).
- **The status of each document.** `_bulk` answers `200` even when it refused some of
  the documents, so this sink reads the response body. If it says `"errors":false`,
  the whole batch was accepted. Otherwise each item is classified:

  | Item status | Outcome |
  | --- | --- |
  | `2xx` | Delivered. |
  | `409` (version conflict on `create`) | Counted as delivered, not re-sent. |
  | `429` or `5xx` | Only these documents are re-sent, as a smaller batch on the normal backoff schedule. |
  | Any other `4xx` (mapping conflict, `document_parsing_exception`, …) | Permanent. Counted in `dropped`, with `ERROR ... dropped N of M record(s) — the intake accepted the request but refused them (status 400 document_parsing_exception: …); retrying will not help`, which quotes the first reason Elasticsearch gave. |

  If the response body cannot be parsed, the batch counts as accepted, so the sink
  never re-sends on a guess.

**Delivery is at-least-once.** Elasticsearch generates the IDs, so a re-sent batch can
store the same document twice. A re-send happens on a retry after a timeout, on a
spill replay, or when a segment exhausts its retries.

## Verify delivery

**In Kibana.** Open *Discover*, select the `logs-*` data view, and filter with KQL:

```text
service.name : "elastic-batch-http"
```

**Count with ES|QL.** This is how the benchmark counted delivery. The shipping key
from [Set up](#set-up-elasticsearch) is deliberately write-only, so the count needs a
separate key or user with `read` (and `view_index_metadata`) on `logs-mule*`, for
example a second, read-only API key. Send the query to the same endpoint with it:

```bash
curl -s -X POST "$ELASTIC_URL/_query?format=json" \
  -H "Authorization: ApiKey $ELASTIC_READ_KEY" -H "Content-Type: application/json" \
  -d '{"query":"FROM logs-mule-default | WHERE service.name == \"elastic-batch-http\" AND @timestamp >= \"2026-10-12T00:00:00Z\" | STATS n = COUNT(*)"}'
```

**Quick connectivity check without the appender.** Post a single document. A
`200` response with `"errors":false` proves that the endpoint, the key and its
privileges all work:

```bash
printf '{"create":{}}\n{"@timestamp":"2026-10-13T00:00:00.000Z","message":"connectivity check"}\n' |
  curl -s -X POST "$ELASTIC_URL/logs-mule-default/_bulk" \
    -H "Authorization: ApiKey $ELASTIC_API_KEY" -H "Content-Type: application/x-ndjson" \
    --data-binary @-
```

**On the appender side**, set
[`statsIntervalMillis`](../configuration.md#opt-statsintervalmillis) with
[`diagnosticsLevel`](../configuration.md#opt-diagnosticslevel) `INFO` and
`<Configuration status="INFO">` to get a heartbeat with `accepted`, `sentRecords`,
`dropped`, `queueDepth` and the spill counters. A non-zero `dropped` together with the
"intake accepted the request but refused them" ERROR means Elasticsearch refused
documents on mapping grounds.

## Benchmark on CloudHub

**Setup.** The run was on 2026-10-12 with engine 0.2.0 and disk spill on. Each
request emitted 22 log events (`count=20`). CloudHub 1.0 ran at `oha -c 10` over
`http`, CloudHub 2.0 at `-c 5` over `https`. Both used 0.1 vCore workers in Sydney
(`ap-southeast-2`), next to an Elastic Cloud Serverless project in the same region.
Every test started on a freshly restarted app, except Test 2, which ran 60 s after
Test 1 with no restart. Elasticsearch ran as one of three vendor lanes in parallel,
with one app per vendor under load at a time. For the method, the other vendors and
the raw tables, see [Benchmark results](../benchmark-results.md). To reproduce it,
see the [runbook](../benchmark-runbook.md).

**Headline (Test 3, 5 min).** On CloudHub 1.0 the batch appender served **136.15
req/s** against 0.98 for stock (**139.5×**). On CloudHub 2.0 it served **35.68
req/s** against 0.73 (**48.6×**). Batch delivered **100%** of its logs on both
planes. Stock delivered 36.1% and 32.9%.

### CloudHub 1.0 (0.1 vCore, `-c 10`)

| Test | Batch req/s | Stock req/s | Batch÷stock | Batch p90 (s) | Stock p90 (s) | Batch delivered | Stock delivered |
|---|---|---|---|---|---|---|---|
| 1 (1 min) | 82.88 | 2.11 | 39.3× | 0.192 | 13.25 | 100.0% | 48.3% |
| 2 (churn) | 97.08 | 0.52 | 185.1× | 0.130 | 51.59 | 100.2% | 0.0% |
| 3 (5 min) | 136.15 | 0.98 | 139.5× | 0.128 | 39.74 | 100.0% | 36.1% |
| 4 (5 min, `-t 30s`) | 148.50 | 1.26 | 118.3× | 0.124 | 0.50 | 100.0% | 76.9% |

### CloudHub 2.0 (0.1 vCore, `-c 5`)

| Test | Batch req/s | Stock req/s | Batch÷stock | Batch p90 (s) | Stock p90 (s) | Batch delivered | Stock delivered |
|---|---|---|---|---|---|---|---|
| 1 (1 min) | 18.14 | 2.68 | 6.8× | 0.443 | 1.30 | 100.0% | 33.1% |
| 2 (churn) | 46.75 | 0.27 | 175.9× | 0.186 | 26.65 | 100.0% | 0.0% |
| 3 (5 min) | 35.68 | 0.73 | 48.6× | 0.249 | 26.50 | 100.0% | 32.9% |
| 4 (5 min, `-t 30s`) | 38.05 | 0.76 | 50.3× | 0.224 | 26.09 | 100.0% | 109.0% |

The two planes ran at different concurrency on different hardware, so compare batch
with stock within one table, not CloudHub 1.0 with 2.0.

![Elasticsearch request throughput per test, batch vs stock appender, CloudHub 1.0 and 2.0, with the batch÷stock ratio above each pair](../img/vendors/elasticsearch-throughput.png)

![Elasticsearch request latency in Test 3, p50, p90 and p99 for batch vs stock on each plane, log scale](../img/vendors/elasticsearch-latency.png)

![Elasticsearch logs delivered as a share of logs generated, batch vs stock, per test and plane](../img/vendors/elasticsearch-delivered.png)

### What the Elasticsearch numbers show

- **How delivery is counted.** Elasticsearch delivery is counted by **event time**
  inside the load window: the documents whose `@timestamp` falls in the test,
  divided by HTTP 200 responses × 22. A backlog that lands late still counts, as long
  as it lands. Datadog and New Relic are counted by arrival time instead, so their
  churn and Test 4 percentages are not comparable one for one.
- **Batch lost nothing.** Batch delivered 100% in every test on both planes. Test 2
  on CloudHub 1.0 reads 100.2% because the app also logged requests that oha cut off
  at its deadline.
- **Stock loses its in-memory backlog at the next restart.** The stock `<Http>` appender
  sends one blocking POST per event on a single background thread. Under load it
  falls behind, and whatever is still queued when the next restart comes dies with
  the app: 33–48% delivered in Test 1 and Test 3. In the churn test (no restart) stock
  reads **0%** on both planes. Its events were still queued behind Test 1's backlog
  when the next restart came.
- **Test 4 caps each request at 30 s.** Stock had 96 requests time out on CloudHub
  1.0 (76.59% success) and 20 on CloudHub 2.0 (91.77% success). That cap is also why
  stock p90 on CloudHub 1.0 drops to 0.50 s. The 109.0% on CloudHub 2.0 is over 100%
  because the requests that timed out still logged their events, but they are not in
  the expected count.
- **Variance between runs.** A sequential, Elasticsearch-only control run on
  2026-10-11 was compared with these parallel lanes. On CloudHub 2.0, stock req/s
  agreed within 3% in the 5-minute tests (−2.6% in Test 3, −2.9% in Test 4). On
  CloudHub 1.0 the difference was −3.3% in Test 4 and −14.6% in Test 3. An earlier
  parallel round of the same stock apps read +2.9% and −7.1%, so a single CloudHub 1.0
  stock run moves that much on its own. Each figure is one run per app: read it as
  directional.
- **Test 1 under-reports warm batch throughput.** The batch JVM is still warming up
  in the 1-minute test. Read Test 3 as the sustained figure.
- **gzip was off** for both apps to keep the comparison fair. A production deployment
  with gzip on sends fewer bytes per batch.

## Troubleshooting

For general diagnostics (StatusLogger, the stats heartbeat, the counters), see
[Troubleshooting & diagnostics](../troubleshooting.md).

| Symptom | Cause | Fix |
| --- | --- | --- |
| At startup: `BatchHttp appender '<name>' could not build its 'elasticsearch' sink: ElasticsearchSink requires a url` (or `requires an apiKey`) `... Required log4j2.xml attributes for vendor 'elasticsearch': url, apiKey. This appender is disabled and will ship no logs.` | `url` or `apiKey` is explicitly empty or blank. The message names the vendor as written, trimmed and lower-cased (e.g. `'elastic'` for the alias). | Check that the lookup resolves. In Mule, use `${sys:...}`/`${env:...}`, not Mule `${...}` or `secure::`. This message is hidden under `<Configuration status="OFF">`. |
| Same startup ERROR with `ElasticsearchSink url must start with https:// or http://` | The endpoint has no scheme, e.g. it was copied without `https://`, or `url` is an unresolved `${env:ELASTIC_URL}`: Log4j2 leaves an unresolved lookup in place as literal text. | Use the full endpoint as Kibana shows it. |
| Mule app ships nothing; the diagnostics show DNS failures for `set-elastic-url.invalid` | The sample's fallback URL: `elastic.url` was never set. | On CloudHub, set the `elastic.url` application property. Locally, use `-M-Delastic.url=...` (not a bare `-D`) or `ELASTIC_URL`. |
| `ERROR ... endpoint rejected the batch (HTTP 401)` or `(HTTP 403)` | Wrong key format (the id or secret instead of the encoded value), a revoked key, the sample's `MISSING_KEY` fallback, a literal `${env:ELASTIC_API_KEY}` sent because the variable is unset, or a key missing `create_doc`/`auto_configure` on the target name. | Create the key as shown in [Set up](#set-up-elasticsearch) and copy the encoded value. Check that the role's `names` pattern covers your data stream. On CloudHub, set `elastic.apiKey` as a secure property. |
| `ERROR ... dropped N of M record(s) — the intake accepted the request but refused them (status 400 document_parsing_exception: …)` or `mapper_parsing_exception` | A field-mapping conflict, typically an MDC key, or a field an ingest pipeline adds, colliding with an existing mapping. | Fix or rename the conflicting field, or the pipeline's `target_field`. MDC is already namespaced under `labels.*` to avoid collisions such as `host` or `log`. Retrying will not help. |
| Duplicate documents after a timeout, a restart or a spill replay | Delivery is at-least-once, and Elasticsearch generates the document IDs. | This is expected. Deduplicate downstream if exact counts matter. |
| `message` shows the JSON as one text string | Elasticsearch does not parse JSON inside `message`. | Add the `logs@custom` ingest pipeline from [Set up](#set-up-elasticsearch). |
| `includeTimestamp="false"` has no effect | The sink always sends `@timestamp`, because data streams require it. | This is by design. |
| Writes fail for a custom `index` name | A name outside `logs-*-*` has no matching template, so the data stream is not created automatically. | Use a `logs-<dataset>-<namespace>` name, or create your own index template. |
| Backlog gone after a CloudHub restart | The spill buffer lives in the JVM temp dir. On CloudHub, a restart moves the app to a new worker (1.0) or pod (2.0), and the backlog stays behind. | This is expected on CloudHub. See [Resilience](../resilience.md) for what survives which restart. |

[Configuration reference](../configuration.md) · [Docs index](../README.md) ·
Other vendors: [Datadog](datadog.md) · [New Relic](newrelic.md) ·
[Dynatrace](dynatrace.md) · [Splunk](splunk.md) · [Generic HTTP](generic.md)
