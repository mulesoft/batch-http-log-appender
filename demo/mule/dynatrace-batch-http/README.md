# dynatrace-batch-http

A sample **Mule 4.9** application that forwards its logs to **Dynatrace** (Log Monitoring
API v2, SaaS or ActiveGate) using the `batch-http-log4j2` batching appender from this repo.
(Maven artifactId: `mule-dynatrace-demo`.)

Mule 4 logs through Log4j2, so the appender is a pure `log4j2.xml` change — no flow
code is aware of it.

This is the Dynatrace counterpart of [`elastic-batch-http`](../elastic-batch-http):
the same flow and workload. Its stock-`HttpAppender` control sibling — for measuring the
impact of batching — is [`dynatrace-stock-http`](../dynatrace-stock-http).

## Layout

```text
dynatrace-batch-http/
├── pom.xml                          mule-application packaging; depends on batch-http-log4j2 0.2.0
├── mule-artifact.json               minMuleVersion 4.9.15
└── src/main/
    ├── mule/app.xml                 HTTP-triggered flows that emit log events (port 8087)
    └── resources/
        ├── config.properties        Mule properties (http.port=8087)
        └── log4j2.xml               <BatchHttp vendor="dynatrace"> appender wiring
```

## Prerequisite

Install the appender into your local Maven repo (from the repo root):

```bash
mvn install -DskipTests
```

## Dynatrace setup

1. **Environment URL.** The API address of the environment, which is **not** the address of
   the platform UI. If the Logs app opens at `https://<environment-id>.apps.dynatrace.com/…`,
   the ingest endpoint lives at `https://<environment-id>.live.dynatrace.com`. Behind an
   ActiveGate it is `https://<activegate>:9999/e/<environment-id>`. The sink appends
   `/api/v2/logs/ingest`, and also accepts a URL that already ends in it.
2. **Access token.** In Dynatrace → *Access Tokens*, generate a token with **only** the
   `logs.ingest` scope (*Ingest logs*). It starts `dt0c01.` and is shown once. That scope
   lets the token write log records and nothing else.
3. **Platform token (optional, for counting).** Reading the logs back over the API — for
   example to count what arrived after a load test — needs a separate *platform* token
   (`dt0s16.…`) with `storage:logs:read` and `storage:buckets:read`. The app never uses it;
   keep it out of the app's properties. See [Counting with DQL](#counting-with-dql).

Nothing has to be created in Dynatrace first: records land in the default logs bucket.

## Injecting the environment URL and token

`log4j2.xml` is parsed by **Log4j2**, not Mule — so Mule `${...}` properties do **not**
resolve there. Both values are Log4j2 lookups that prefer a system property and fall back
to an environment variable:

```text
url    ${sys:DT_ENVIRONMENT_URL:-${sys:dynatrace.url:-${env:DT_ENVIRONMENT_URL:-https://set-dynatrace-url.invalid}}}
token  ${sys:DT_API_TOKEN:-${sys:dynatrace.token:-${env:DT_API_TOKEN:-MISSING_TOKEN}}}
```

| Value | Application / system property | Environment variable |
| --- | --- | --- |
| Environment URL | `dynatrace.url` | `DT_ENVIRONMENT_URL` |
| Access token (`logs.ingest`) | `dynatrace.token` | `DT_API_TOKEN` |
| Log source (optional, CloudHub 2.0) | `domain` | — |

- **CloudHub 1.0 / 2.0:** add `dynatrace.url` as an application property and `dynatrace.token`
  as a **secure** application property. CloudHub passes both to the JVM as system properties.
- **Locally / Anypoint Code Builder:** pass them as JVM args, or export the environment
  variables. Under the Mule standalone wrapper a JVM arg must be written
  `-M-Ddynatrace.url=...`; a bare `-D` never reaches the Mule JVM.

Unset, the URL falls back to a reserved `.invalid` host, so a misconfigured app fails DNS
loudly (in the appender's diagnostics) instead of shipping anywhere. A missing token ships
the literal `MISSING_TOKEN`, which Dynatrace refuses with `401`.

## Telling the apps apart: `log.source`

The Dynatrace sink has no `service` or `host` attribute; the one identity it sends is
`log.source`, so that attribute has to separate the batch app from its stock sibling **and**
a CloudHub 1.0 deployment from a CloudHub 2.0 one. It is set to the CloudHub application
name, `${sys:domain:-dynatrace-batch-http}`:

- **CloudHub 1.0** sets the `domain` system property to the application name on every app,
  for example `dynatrace-batch-http` plus any `$CH1_APP_SUFFIX` you deployed with. Nothing to
  configure.
- **CloudHub 2.0** does not. Add an application property `domain=dynatrace-batch-http`
  (or whatever the app is called there).
- **Locally** it falls back to `dynatrace-batch-http`.

If you stop wanting an override, delete the property rather than clearing it: a
present-but-empty `domain` resolves to an empty `log.source`.

## Exercising it

```bash
# Emit 52 events — a batch.start INFO, 50 order.processed INFOs, a batch.completed WARN
curl "http://localhost:8087/log?count=50&level=info"

# Emit an ERROR event carrying the raised error's type and description
curl "http://localhost:8087/boom"
```

## Finding the logs

In Dynatrace, open the **Logs** app and filter on `log.source`, or run the same filter as
DQL in a notebook:

```text
fetch logs
| filter log.source == "dynatrace-batch-http"
| sort timestamp desc
```

Use the name the deployment actually carries: the application name on CloudHub 1.0 (the module
name plus any `$CH1_APP_SUFFIX`), the `domain` property's value on CloudHub 2.0, and
`dynatrace-batch-http` locally.

### Counting with DQL

`summarize count()` gives the number of records that arrived, for comparing against what the
load test emitted. Over the API this is a Grail query, authenticated with the **platform**
token (not the ingest token), against the platform address of the environment:

```bash
curl -s -X POST \
  "https://<environment-id>.apps.dynatrace.com/platform/storage/query/v1/query:execute" \
  -H "Authorization: Bearer $DT_PLATFORM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"fetch logs, from:now()-1h | filter log.source == \"dynatrace-batch-http\" | summarize count()"}'
```

A query that does not finish within the request returns `"state":"RUNNING"` and a
`requestToken`; fetch the result with `GET …/query:poll?request-token=<token>`.

## What a log record looks like

Each log event becomes one Dynatrace log record:

| Attribute | Source |
| --- | --- |
| `timestamp` | the event time, ISO-8601 UTC (always sent; `includeTimestamp` is ignored) |
| `content` | the layout output — here the flow's JSON, single-lined, with `platform` prepended |
| `loglevel` | the Log4j2 level |
| `logger.name`, `thread.name` | the Log4j2 event |
| `log.source` | `${sys:domain}` — the CloudHub application name — or this app's name |
| `error.stack` | the event's throwable, when there is one |
| `correlationId`, `processorPath` | MDC entries, one top-level attribute each |

### Optional: parse the JSON inside `content`

Dynatrace stores `content` as text. To get `event`, `orderId`, `platform` and friends as
attributes, add an OpenPipeline processing rule (a DQL processor with `parse content, "JSON:mule"`
or similar) for records matching `log.source` of either app. `JSON:mule` puts the parsed object
into one record field named `mule` (`mule[orderId]`, …); add a `fieldsFlatten mule` step to get
top-level attributes. Apply it to both apps of the
pair alike; they ship the identical `content` string, so they stay comparable either way.

## Failures

Dynatrace answers `204` when it took the whole request. The engine retries `408`, `429` and
`5xx` on its backoff schedule and spills the batch to disk once the retries run out; any other `4xx` (for example `401` for a bad token, `400` for a
malformed body, `413` for an oversized request) is permanent and the batch is counted
`dropped` with a diagnostic. A `200` reply means Dynatrace ingested only part of the array and
names the rejected records in its body; the sink counts the whole batch as delivered, so watch
the appender's diagnostics and the DQL count if records go missing.

## Demo-specific settings

This app deliberately overrides a library default so the A/B with
[`dynatrace-stock-http`](../dynatrace-stock-http) varies only batching:

- `gzip="false"` — the stock appender cannot compress. Production should leave gzip on.

Disk spill is **on**, the library default: `spillEnabled="${sys:batch.spillEnabled:-true}"`.
Queue overflow and batches that fail every retry go to disk and replay when Dynatrace
catches up. Set the system property `batch.spillEnabled=false` to measure batching alone;
overflow then drops per `overflowPolicy`. See
[`datadog-batch-http`](../datadog-batch-http#resilience-the-disk-spill-safety-net-on-in-this-demo)
for what spill does and what turning it off changes.
