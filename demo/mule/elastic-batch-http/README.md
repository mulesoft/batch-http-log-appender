# elastic-batch-http

A sample **Mule 4.9** application that forwards its logs to **Elasticsearch** (the ELK
stack, Elastic Cloud Hosted or Elastic Cloud Serverless) using the `batch-http-log4j2`
batching appender from this repo. (Maven artifactId: `mule-elastic-demo`.)

Mule 4 logs through Log4j2, so the appender is a pure `log4j2.xml` change — no flow
code is aware of it.

This is the Elasticsearch counterpart of [`datadog-batch-http`](../datadog-batch-http):
the same flow and workload. Its stock-`HttpAppender` control sibling — for measuring the
impact of batching — is [`elastic-stock-http`](../elastic-stock-http).

## Layout

```text
elastic-batch-http/
├── pom.xml                          mule-application packaging; depends on batch-http-log4j2 0.1.5+
├── mule-artifact.json               minMuleVersion 4.9.15
└── src/main/
    ├── mule/app.xml                 HTTP-triggered flows that emit log events (port 8085)
    └── resources/
        ├── config.properties        Mule properties (http.port=8085)
        └── log4j2.xml               <BatchHttp vendor="elasticsearch"> appender wiring
```

## Prerequisite

Install the appender into your local Maven repo (from the repo root):

```bash
mvn install -DskipTests
```

`vendor="elasticsearch"` first shipped in `0.1.5`; this app will not start on an older
appender jar.

## Elasticsearch setup

1. **Endpoint.** In Kibana, open the project's connection details and copy the
   *Elasticsearch endpoint* (`https://<project>.es.<region>.<csp>.elastic.cloud:443`).
2. **API key.** In Kibana → *API keys*, create a key restricted to what the appender needs:

   ```json
   {
     "mule-log-shipper": {
       "indices": [
         { "names": ["logs-mule*"], "privileges": ["create_doc", "auto_configure"] }
       ]
     }
   }
   ```

   `create_doc` lets the key append documents and nothing else; `auto_configure` lets the
   first write create the data stream. Copy the **encoded** value — Kibana shows it once.
3. **Data stream.** Nothing to create. The default target, `logs-mule-default`, matches
   Elasticsearch's built-in `logs-*-*` index template, so it is created on first write with
   the logs mappings and lifecycle. Override it with the `elastic.dataStream` property.

## Injecting the endpoint and API key

`log4j2.xml` is parsed by **Log4j2**, not Mule — so Mule `${...}` properties do **not**
resolve there. Both values are Log4j2 lookups that prefer a system property and fall back
to an environment variable:

```text
url    ${sys:ELASTIC_URL:-${sys:elastic.url:-${env:ELASTIC_URL:-https://set-elastic-url.invalid}}}
apiKey ${sys:ELASTIC_API_KEY:-${sys:elastic.apiKey:-${env:ELASTIC_API_KEY:-MISSING_KEY}}}
```

| Value | Application / system property | Environment variable |
| --- | --- | --- |
| Elasticsearch endpoint | `elastic.url` | `ELASTIC_URL` |
| Encoded API key | `elastic.apiKey` | `ELASTIC_API_KEY` |
| Target data stream (optional) | `elastic.dataStream` | — |

- **CloudHub 1.0 / 2.0:** add `elastic.url` as an application property and `elastic.apiKey`
  as a **secure** application property. CloudHub passes both to the JVM as system properties.
- **Locally / Anypoint Code Builder:** pass them as JVM args, or export the environment
  variables. Under the Mule standalone wrapper a JVM arg must be written
  `-M-Delastic.url=...`; a bare `-D` never reaches the Mule JVM.

Unset, the URL falls back to a reserved `.invalid` host, so a misconfigured app fails DNS
loudly (in the appender's diagnostics) instead of shipping anywhere.

## Exercising it

```bash
# Emit 52 events — a batch.start INFO, 50 order.processed INFOs, a batch.completed WARN
curl "http://localhost:8085/log?count=50&level=info"

# Emit an ERROR event carrying the raised error's type and description
curl "http://localhost:8085/boom"
```

In Kibana → *Discover*, pick the `logs-*` data view and filter on
`service.name : "elastic-batch-http"`.

## What a document looks like

Each log event becomes one ECS document:

| Field | Source |
| --- | --- |
| `@timestamp` | the event time (data streams require it, so it is always sent) |
| `message` | the layout output — here the flow's JSON, single-lined, with `platform` prepended |
| `log.level`, `log.logger`, `process.thread.name` | the Log4j2 event |
| `service.name` | `app.service` in `log4j2.xml` (this app's name by default) |
| `host.name` | `${sys:domain}` on CloudHub 1.0, the pod hostname on CloudHub 2.0 |
| `error.stack_trace` | the event's throwable, when there is one |
| `labels.<key>` | every MDC entry (Mule sets `correlationId` and `processorPath`) |

MDC entries go under `labels` because a bare key such as `host` or `log` would collide
with an ECS object field and get the whole document refused as a mapping conflict.

### Optional: parse the JSON inside `message`

Elasticsearch stores `message` as text; unlike Datadog, it does not lift JSON fields out
of it by default. To get `event`, `orderId`, `platform` and friends as real fields, add a
`logs@custom` ingest pipeline — the built-in `logs-*-*` template runs it on every write,
for this app and its stock sibling alike:

```text
PUT _ingest/pipeline/logs@custom
{
  "processors": [
    { "json": { "field": "message", "target_field": "mule", "ignore_failure": true } }
  ]
}
```

The pair stays comparable either way: both apps ship the identical `message` string.

## Partial failures

The Bulk API answers `200` even when it refused some of the documents in a request, and
reports a status per document. The sink reads those statuses:

- `429` / `5xx` on a document — transient. Only those documents are re-sent, on the
  normal backoff schedule.
- Any other `4xx` (for example a mapping conflict) — permanent. Re-sending the same
  document would fail the same way, so it is counted `dropped` and the appender logs an
  ERROR naming the first reason Elasticsearch gave.
- `409` (version conflict) — counted as delivered and not re-sent. The sink lets
  Elasticsearch generate document IDs, so a re-sent batch (a retry after a timeout, or a
  spill replay) can store duplicate documents: delivery is at-least-once, as with the other
  sinks.

## Demo-specific settings

This app deliberately overrides two library defaults so the A/B with
[`elastic-stock-http`](../elastic-stock-http) varies only batching:

- `gzip="false"` — the stock appender cannot compress. Production should leave gzip on.
- `spillEnabled="false"` — the spill buffer's threads and disk I/O would compete for the
  worker's fractional vCore. Production should leave spill on. See
  [`datadog-batch-http`](../datadog-batch-http#resilience-the-disk-spill-safety-net-disabled-in-this-demo)
  for what it does and how to turn it back on.
