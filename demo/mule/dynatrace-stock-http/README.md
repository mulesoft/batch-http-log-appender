# dynatrace-stock-http

The **A/B control** for [`dynatrace-batch-http`](../dynatrace-batch-http). (Maven artifactId:
`mule-dynatrace-stock-http-demo`.)

Same Mule app, same flow, same workload, same Dynatrace environment and ingest endpoint —
with exactly **one** variable changed: instead of the batching `<BatchHttp>` appender it
uses Log4j2's **stock, built-in `<Http>` appender**, which makes one synchronous POST per
log event to the Log Monitoring API v2 (`POST /api/v2/logs/ingest`). That endpoint accepts
a single JSON object as well as an array, and answers `204`. Run both side-by-side and
compare request counts, latency and app throughput to measure what batching buys.

There is **no** dependency on `batch-http-log4j2` here: the stock `HttpAppender` is part
of `log4j-core`, which the Mule runtime already provides.

## Layout

```text
dynatrace-stock-http/
├── pom.xml                          mule-application packaging; no logging dependency
├── mule-artifact.json               minMuleVersion 4.9.15
└── src/main/
    ├── mule/app.xml                 byte-identical flow to the batch sibling (port 8088)
    └── resources/
        ├── config.properties        Mule properties (http.port=8088)
        └── log4j2.xml               stock <Http> appender wiring
```

## Environment URL and token

Identical to the batch sibling — the same `dynatrace.url` / `dynatrace.token` properties (or
`DT_ENVIRONMENT_URL` / `DT_API_TOKEN` env vars), the same access token with only the
`logs.ingest` scope. See [`dynatrace-batch-http`](../dynatrace-batch-http#dynatrace-setup).
In CloudHub set `dynatrace.token` as a **secure** application property. The token is sent as
an `Authorization: Api-Token <token>` request header.

One difference: the batch sink normalizes the URL, but this appender just appends
`/api/v2/logs/ingest` to it. Give it the **bare** environment URL
(`https://<environment-id>.live.dynatrace.com`), with no trailing slash and no path.

To measure against a local mock intake instead of a real environment, start the runtime with
`-M-Ddynatrace.ingestUrl=http://localhost:9099/api/v2/logs/ingest`, which replaces the whole URL.

## Telling the apps apart

`log.source` is `${sys:domain:-dynatrace-stock-http}`, the same rule as the batch sibling:
CloudHub 1.0 supplies the application name (the module name plus any `$CH1_APP_SUFFIX` you deployed with); on
CloudHub 2.0 add an application property `domain=dynatrace-stock-http`; locally it is
`dynatrace-stock-http`. See
[`dynatrace-batch-http`](../dynatrace-batch-http#telling-the-apps-apart-logsource).

## Keeping the records identical

The stock appender ships nothing but its layout's text, so the layout spells out the
same log object the batch sibling's sink builds from the Log4j2 event, in the same order:
`content`, `loglevel`, `timestamp` (event time, ISO-8601 UTC), `logger.name`, `thread.name`,
`log.source`, and the MDC entries `correlationId` and `processorPath` as top-level attributes.

`content` is the byte-identical string the batch sibling ships — `{"platform":"…",`
followed by the flow's collapsed JSON — embedded as a JSON string with `%enc{…}{JSON}`.
The only intended difference in the stored records is `log.source`.

## Exercising it

```bash
curl "http://localhost:8088/log?count=50&level=info"
curl "http://localhost:8088/boom"
```

In Dynatrace, open the **Logs** app, or run as DQL:

```text
fetch logs
| filter log.source == "dynatrace-stock-http"
| sort timestamp desc
```

Add `| summarize count()` to count what arrived; over the API that needs the platform token
described in the batch sibling's [Counting with DQL](../dynatrace-batch-http#counting-with-dql).

## Caveats for a fair comparison

- **Run the pair from the same plane and region.** The intake's round-trip time is what
  the stock appender pays per event; a Dynatrace environment hosted in the same cloud region
  as the workers makes that round-trip short, which narrows the gap between the two
  appenders. Report the region next to the numbers.
- **No failure handling.** Every non-2xx answer (`401` for a bad token, `429` when
  throttled) is a failed append: Log4j2 reports it to the StatusLogger and the event is
  lost; nothing is retried.
- **AsyncLogger in both.** The logger wiring is identical to the batch sibling. With the
  stock appender behind an `AsyncLogger`, the disruptor's background thread serializes one
  blocking POST per event, so the backlog you observe is the cost of not batching.
