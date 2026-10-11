# Changelog

All notable changes to the Batch HTTP Log Appender are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The README's "New in v0.1.x" notes carry the
longer, upgrade-oriented explanation of each release.

## [0.1.5] — 2026-10-11

### Added
- **Elasticsearch sink** — `vendor="elasticsearch"` (aliases `elastic`, `elk`) ships to the
  Elasticsearch Bulk API (`POST <url>/<index>/_bulk`, `create` actions) as ECS documents:
  `@timestamp`, `message`, `log.level`, `log.logger`, `process.thread.name`, `service.name`,
  `host.name`, MDC as `labels.*`, and `error.stack_trace` for a logged throwable. Works with Elastic Cloud Hosted, Serverless and self-managed clusters.
  Required: `url` (the Elasticsearch endpoint) and `apiKey` (the encoded API key, sent as
  `Authorization: ApiKey …`; needs `create_doc` + `auto_configure` on the target). `index`
  names the data stream and defaults to `logs-mule-default`.
- **Per-document retry for bulk intakes.** A bulk request can return HTTP 200 while refusing
  individual documents. The Elasticsearch sink reads the per-item results: documents refused
  transiently (`429`, `5xx`) are re-sent alone, documents refused permanently (e.g. a mapping
  conflict) are counted as `dropped` with the reason in the diagnostics log, and stored
  documents (and a `409` version conflict) are not re-sent. Elasticsearch generates the document IDs,
  so a batch re-sent after a timeout or replayed from spill can still store duplicates
  (at-least-once, as with every sink). New SPI hooks on `LogSink`:
  `reportsPerRecordResults()`, `inspectResponse(byte[], int)` and `LogSink.RecordResults`
  (defaults keep every existing sink's behaviour; their response bodies are still discarded
  unread).
- **Elasticsearch demo pair** — Mule apps `demo/mule/elastic-batch-http` (the `<BatchHttp>`
  appender) and `demo/mule/elastic-stock-http` (the stock Log4j2 `<Http>` appender against
  the index API), an A/B pair matching the Datadog and New Relic demos.
- `scripts/deploy-ch1.sh --elastic` deploys the Elasticsearch pair (needs `ELASTIC_URL` and
  `ELASTIC_KEY`).

### Fixed
- `sentBatches` counts a batch once, not once per partial acceptance of it.
- A spill-replay sub-batch the intake only partly stored no longer has its remainder written
  to disk a second time: the segment is kept whole for the next pass, so a later failure in
  the same segment cannot deliver those records twice.

### Changed
- The Datadog and New Relic batch demos build against `0.1.5`.
- Build: `maven-compiler-plugin` is now version-pinned in the parent `pluginManagement`
  (silences Maven's "version is missing" model warning; same plugin version as before).

No other sink's behaviour changed.

## [0.1.4] — 2026-08-04

### Added
- Benchmark documentation: the reproducible 2026-08-04 CloudHub benchmark of the batch
  appender vs the stock Log4j2 `<Http>` appender — `docs/benchmark-results.md`, the rendered
  `docs/index.html`, the charts under `docs/img/`, and the `docs/benchmark-runbook.md`
  reproduction guide. The tables keep the `0.1.3` label because that is the build measured.

### Fixed
- Restored the Apache-2.0 license header on the generated `docs/index.html`.

No appender code changed.

## [0.1.3]

### Added
- **Self-diagnostics.** The appender reports its own health — dropped batches with the HTTP
  status, fatal 4xx rejections, queue overflow, spill init failure — via Log4j2's
  `StatusLogger` (`diagnosticsLevel`, default `WARN`), plus an opt-in stats heartbeat
  (`statsIntervalMillis`). Previously a bad key or an unreachable endpoint failed silently.
- Opt-in OWASP CVE scan: `mvn -Psecurity verify`.

### Changed
- Third-party dependencies bumped to current patched releases (log4j `2.26.0`,
  JUnit `5.14.4`).

### Fixed
- `corruptBytesSkipped` under-reported (`0`) for a torn trailing frame or a wholly unreadable
  spill segment. A crash-recovery replay may now report a non-zero value where it read zero.

## [0.1.2]

### Fixed
- The background flusher no longer busy-spins: an idle flusher polled with a zero timeout and
  could pin a CPU core at ~100%. It now blocks until the next record arrives.

## [0.1.1]

### Changed
- `queueCapacity` default lowered from `100000` to `10000`, bounding worst-case queue memory
  on small (0.1–0.2 vCore) CloudHub workers. See "Default changes — read before upgrading"
  in the README for every default that has moved.

## [0.1.0]

- Initial release: the batching Log4j2 appender (`<BatchHttp>`) on a vendor-neutral core
  engine. Pre-publication history lives in the development repository.

[0.1.5]: https://github.com/mulesoft/batch-http-log-appender/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/mulesoft/batch-http-log-appender/releases/tag/v0.1.4
