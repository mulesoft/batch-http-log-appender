# elastic-stock-http

The **A/B control** for [`elastic-batch-http`](../elastic-batch-http). (Maven artifactId:
`mule-elastic-stock-http-demo`.)

Same Mule app, same flow, same workload, same Elasticsearch project and data stream —
with exactly **one** variable changed: instead of the batching `<BatchHttp>` appender it
uses Log4j2's **stock, built-in `<Http>` appender**, which makes one synchronous POST per
log event to the single-document index API (`POST /<data-stream>/_doc`). Run both
side-by-side and compare request counts, latency and app throughput to measure what
batching buys.

There is **no** dependency on `batch-http-log4j2` here: the stock `HttpAppender` is part
of `log4j-core`, which the Mule runtime already provides.

## Layout

```text
elastic-stock-http/
├── pom.xml                          mule-application packaging; no logging dependency
├── mule-artifact.json               minMuleVersion 4.9.15
└── src/main/
    ├── mule/app.xml                 byte-identical flow to the batch sibling (port 8086)
    └── resources/
        ├── config.properties        Mule properties (http.port=8086)
        └── log4j2.xml               stock <Http> appender wiring
```

## Endpoint and API key

Identical to the batch sibling — the same `elastic.url` / `elastic.apiKey` properties (or `ELASTIC_URL` / `ELASTIC_API_KEY` env vars), the
same API key privileges, the same default data stream (`logs-mule-default`). See
[`elastic-batch-http`](../elastic-batch-http#elasticsearch-setup). The key is sent as an
`Authorization: ApiKey <encoded>` request header.

To measure against a local mock intake instead of a real project, start the runtime with
`-M-Delastic.docUrl=http://localhost:9099/logs-mule-default/_doc`, which replaces the whole URL.

## Keeping the documents identical

The stock appender ships nothing but its layout's text, so the layout spells out the
same ECS document the batch sibling's sink builds from the Log4j2 event: `@timestamp`
(event time, UTC), `message`, `log.level`, `log.logger`, `process.thread.name`,
`service.name`, `host.name`, `labels.correlationId`, `labels.processorPath`.

`message` is the byte-identical string the batch sibling ships — `{"platform":"…",`
followed by the flow's collapsed JSON — embedded as a JSON string with `%enc{…}{JSON}`.
The only intended difference in the stored documents is `service.name`.

## Exercising it

```bash
curl "http://localhost:8086/log?count=50&level=info"
curl "http://localhost:8086/boom"
```

In Kibana → *Discover* (`logs-*` data view), filter on `service.name : "elastic-stock-http"`.

## Caveats for a fair comparison

- **Run the pair from the same plane and region.** The intake's round-trip time is what
  the stock appender pays per event; an Elasticsearch project in the same cloud region as
  the workers (as in this repo's runs) makes that round-trip short, which narrows the gap
  between the two appenders. Report the region next to the numbers.
- **No partial-failure handling.** The index API answers per document, so the stock
  appender sees every refusal as a failed append (Log4j2 reports it to the StatusLogger
  and the event is lost); nothing is retried.
- **AsyncLogger in both.** The logger wiring is identical to the batch sibling. With the
  stock appender behind an `AsyncLogger`, the disruptor's background thread serializes one
  blocking POST per event, so the backlog you observe is the cost of not batching.
