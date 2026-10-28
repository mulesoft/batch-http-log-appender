# Changelog

All notable changes to the Batch HTTP Log Appender are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). Defaults that moved between releases are listed in
[docs/configuration.md](docs/configuration.md#defaults-that-changed).

## [0.3.0] — 2026-10-28

The appender's own health messages (drops, rejections, queue overflow, the stats heartbeat)
now appear in the application log, including on platforms that don't show the console, such as
CloudHub 2.0. Their level is set like any other logger's: in `log4j2.xml` or in Runtime
Manager's logging settings. Upgrading needs no configuration change, but read the first
**Changed** item: with the root logger at `INFO`, these lines now reach every appender on root.

### Added
- **Diagnostics logger.** Every message the appender writes to Log4j2's `StatusLogger` is also
  logged through `com.mulesoft.support.batchhttp.diagnostics.<appender name>`, at the same
  level. It reaches the root logger's appenders: the application log, a file appender and, on
  CloudHub 2.0, the Runtime Manager logs. Set the level on the parent name for every appender
  or on `.<appender name>` for one; on CloudHub 2.0 through **Settings → Logging** or
  `anypoint-cli-v4 runtime-mgr application modify --scopeLoggingConfig`. Verified on CloudHub
  2.0 at `ERROR`, `WARN`, `INFO` and `DEBUG`, and through an endpoint outage with spill and
  replay (every record delivered, no diagnostic shipped).
- **No feedback loop.** Every `<BatchHttp>` appender discards events from that logger and its
  children, so a failing intake never ships its own error messages, even with the appender on
  the root logger next to other `<BatchHttp>` appenders. Tested with all six vendors
  (`datadog`, `splunk`, `newrelic`, `dynatrace`, `elasticsearch`, `generic`) on one root logger.
- `docs/troubleshooting.md`: where each copy goes, how to set the level on CloudHub 1.0,
  CloudHub 2.0, Runtime Fabric and in `log4j2.xml`, how the logger is looked up, which
  messages stay console-only, what each level shows, and which level keeps the noise down.

### Changed
- **More lines in the application log by default.** The logger inherits the root level, so
  with `<Root level="INFO">` failures and, where `statsIntervalMillis` is set, the heartbeat now
  appear in the application log as well as on the console. To keep the previous behaviour,
  declare `<Logger name="com.mulesoft.support.batchhttp.diagnostics" level="OFF"/>` (on CloudHub
  2.0, where Runtime Manager offers no `OFF`, use `ERROR`).
- `diagnosticsLevel` now gates the console copy only; the log copy follows the logger level.
  Set `diagnosticsLevel="OFF"` where the console and the application log are the same stream,
  so each line appears once.
- Messages raised before the appender starts (startup credential failure, spill recovery, the
  TLS warnings) and after it begins stopping (shutdown drain lines, final stats) still go to the
  console only.
- The four batch Mule demos build against `0.3.0`; their `log4j2.xml` comments and the vendor
  pages describe both copies.

### Fixed
- log4j bumped from `2.26.0` to `2.26.1`, which fixes
  [CVE-2026-49844](https://nvd.nist.gov/vuln/detail/CVE-2026-49844) (CVSS 5.9: invalid JSON
  from `MapMessage` with a NaN or infinite value). Releases up to `0.2.2` pinned `2.26.0`. The
  appender itself does not use `MapMessage`, and in a Mule application log4j comes from the
  Mule runtime, whose patch releases carry the fix. The version matters for standalone Java
  applications that take log4j from this dependency. `mvn -Psecurity verify` now reports no
  known vulnerability.

## [0.2.2] — 2026-10-19

Elasticsearch now stores each record once, even when a batch is sent again. Upgrading from 0.2.1
needs no configuration change: bump the dependency version.

### Added
- **Elasticsearch: re-sent batches are stored once** (`deduplicate`, on by default). The engine
  gives each accepted record a unique id and keeps it through every retry, the spill buffer and a
  replay after a crash; the Elasticsearch sink sends it as the document `_id`, so a batch
  re-sent after a lost response (a timeout, a reset connection, a 5xx after the write) or a
  failed replay is refused with 409 instead of stored twice. The stats line reports these as
  `duplicatesSuppressed`. `deduplicate="false"` restores Elasticsearch-generated ids.
- `LogSink#wantsRecordIds`, `LogRecord#recordId` and `RecordResults#duplicates` in the core API.

### Changed
- Elasticsearch documents now carry an appender-assigned `_id` by default. Elasticsearch indexes
  documents with explicit ids somewhat more slowly; a copy re-sent after a data stream rollover
  can still be stored twice.
- The Elasticsearch batch demo builds against `0.2.2`; on CloudHub 2.0 it delivered exactly
  100% of its logs in all four benchmark tests (2026-10-19), with Test 3 throughput in line with
  0.2.0.
- Spill frames carry the record id when it is present. Frames without one are unchanged, so
  segments written with `deduplicate="false"` or by earlier versions still replay; a 0.2.1 reader
  cannot replay id-bearing segments, which matters only when downgrading with a backlog on disk.

## [0.2.1] — 2026-10-13

A documentation and samples release: no engine or appender code changed, so upgrading from
0.2.0 needs no configuration changes.

### Added
- **Dynatrace sample Mule pair** — `demo/mule/dynatrace-batch-http` (the `<BatchHttp>` appender)
  and `demo/mule/dynatrace-stock-http` (the stock Log4j2 `<Http>` appender, one POST per event),
  an A/B pair matching the Datadog, New Relic and Elasticsearch demos; `scripts/deploy-ch1.sh
  --dynatrace` deploys it (needs `DT_ENVIRONMENT_URL` and `DT_API_TOKEN`).
- **Dynatrace CloudHub benchmark** (2026-10-13, engine 0.2.0, its lane alone, same protocol as
  the 2026-10-12 run): batch delivered 100% in every test on both planes at 6.5× (CloudHub 1.0)
  and 4.4× (CloudHub 2.0) the stock appender's request rate in the 5-minute Test 3. Added to
  `docs/benchmark-results.md`, `docs/index.html` and the charts; delivery counted in Grail with
  DQL by event time.
- **Per-vendor documentation pages** under `docs/vendors/` (Datadog, New Relic, Elasticsearch,
  Dynatrace, Splunk HEC, generic HTTP): setup, configuration, what gets sent, verifying delivery,
  troubleshooting, and each vendor's CloudHub benchmark with its own charts
  (`docs/img/vendors/`). Each page links its sample Mule project, where one exists.
- **`docs/configuration.md`**, the single reference of every `<BatchHttp>` option (type, code
  default, the vendors it applies to), with a stable anchor per option that every other page links.
- **`DocumentationConsistencyTest`** (`batch-http-log4j2`): fails the build when
  `docs/configuration.md` misses, duplicates or mis-defaults a plugin option, when an `xml`
  sample in `README.md` or `docs/` is not well-formed or uses an attribute or enum value the
  plugin does not accept, or when a `configuration.md#opt-...` link does not resolve.

### Changed
- `README.md` is now a short landing page: pitch, supported vendors, quick start, benchmark
  summary and a documentation map. Its long sections moved under `docs/` (index:
  `docs/README.md`): architecture, building, Mule usage and secrets, resilience and disk spill,
  troubleshooting, memory safety, the option reference and the per-vendor examples. Links into
  the moved sections were updated across the repository.
- `docs/benchmark-results.md` and `docs/benchmark-runbook.md` cover four vendors.
- The roadmap moved to `docs/roadmap.md`; the batch demos build against `0.2.1`.

## [0.2.0] — 2026-10-12

The minor version marks Elasticsearch as a first-class vendor alongside Datadog, Splunk HEC,
New Relic and Dynatrace. The sink and its per-document bulk retry shipped in 0.1.5; 0.2.0 adds
the durability and accounting work below, runs every demo with disk spill on, and republishes
the CloudHub benchmark with all three vendors and measured log delivery. No configuration
changes are needed to upgrade from 0.1.5. If you alert on the spill counters, read
[Crash recovery](docs/resilience.md#crash-recovery-runtime--jvm-death) and
[The invariant to check](docs/memory-safety.md#the-invariant-to-check) first.

### Fixed
- **Evicted spill records are now counted.** When the spill buffer deletes its oldest segment
  to stay under `maxTotalSpillBytes` or above `minFreeDiskBytes`, during spilling or in the
  startup reconcile, the records in that segment are added to `spillStats().droppedRecords`
  and to the new `evictedRecords`. That includes segments adopted from a previous run.
  Before, only `evictedSegments` moved, so `accepted == sentRecords + dropped + spillDropped`
  fell short after any eviction. The store keeps a per-segment record count: counted as
  records are written for this run's segments, and by a decode-free CRC scan for adopted ones.
- **A record the spill buffer refuses is counted once**, in `spillStats().droppedRecords`.
  Before, it was also added to `stats().dropped`, on the failed-batch path and on the
  queue-overflow path.
- **Records still queued at shutdown are no longer lost uncounted.** After
  `shutdownDrainTimeout`, `close()` writes whatever is still in the queue to the spill buffer,
  regardless of `spillOnOverflow`, and the next start replays it. With spill off or
  unavailable, or with `spillRecoveryMode` `DELETE`/`IGNORE` (the next start would not replay
  them), it counts those records as `dropped` and logs an ERROR with the count.
  `close()` never makes another HTTP attempt for them, so it stays bounded. The POISON
  shutdown sentinel is never spilled, shipped or counted, and `queueDepth` is 0 after every
  `close()`.
- A flusher interrupted at shutdown hands its in-flight batch to the spill buffer at once
  (with `spillRecoveryMode=ADOPT`), instead of retrying past the grace window and being
  refused by a store that has already closed. With other modes it keeps its real send attempt.
- A record offered while `close()` runs can no longer be stranded in the queue, even when the
  offer is still in progress after `close()` stops waiting for it: an offer that lands after
  the final drain spills or counts its own record.
- **Crash recovery replays at startup.** Segments adopted from a previous run or a crash now
  replay on the first pass, right after the appender starts, instead of one
  `spillReplayInterval` (10 s) later. Before, a runtime in a crash loop never replayed its
  backlog.
- A spill segment that cannot be read (an I/O error) no longer blocks every later segment.
  It is kept, retried on later passes, and reported once. Draining continues with the
  segments behind it.
- A segment evicted between being listed and being claimed by a replay worker no longer
  aborts the pass. Eviction never deletes a segment a worker is replaying.
- A damaged or missing segment header no longer makes the segment look empty, which got it
  deleted with nothing counted. The reader salvages the intact frames behind the header and
  counts the rest as corrupt.
- v1-framed segments (JSON written by 0.1.5 and earlier) now report the bytes they discard
  in `corruptBytesSkipped`: a torn tail, a corrupt length, a partial length prefix, or a
  payload that does not decode.
- A disk error part-way through a batch no longer counts records already written as dropped.
  It also no longer leaves a torn frame in the middle of a segment: the next record goes to a
  fresh segment.
- A replay sub-batch whose serialization throws is counted as dropped (ERROR logged) instead
  of keeping the segment and re-sending the sub-batches before it on every pass.
- A record larger than `maxSpillFileBytes` (written whole into an oversized segment) is no
  longer rejected as corrupt on replay.
- A live batch whose serialization throws is counted as dropped, not lost uncounted. Only the
  records the intake had not already stored are counted: a bulk retry subset that fails to
  serialize after a partial acceptance no longer counts the stored records as sent *and* dropped.
- **A second same-id store in one JVM no longer releases the first store's directory lock.**
  On Linux and macOS closing any descriptor of a file drops the process's lock on it, so a
  store that probed a lock file held in the same JVM (a leaked appender after a Mule redeploy,
  a Log4j2 reconfiguration) freed it for other processes on every replay pass. A second
  runtime sharing the directory could then adopt, replay and delete a live store's segments.
  Stores now take a JVM-wide claim (shared across classloaders) before opening the lock file.
- Spill bookkeeping: `totalBytes` now includes segment headers, deleting a segment that is
  already gone no longer decrements `segmentCount`, and a segment that `DELETE` recovery could
  not delete no longer counts toward the cap.

### Changed
- `spillFormat="JSON"` segments now use the same `SYNC + length + CRC32C` framing as
  `BINARY`. Damage in the middle of a JSON segment is skipped instead of ending it, and a
  flipped byte is rejected by the checksum instead of being delivered garbled. The payload is
  still readable JSON. This release still reads v1 JSON segments written by 0.1.5 and earlier,
  and a 0.1.5 reader can read the new segments.
- Spill segments are fsynced when they roll, when the replayer seals them (once per replay
  pass) and on shutdown, and the spill directory is fsynced (best effort) when a segment is
  created. A host crash or power loss should now lose at most about one replay interval or one
  segment of spilled data, where the filesystem honours fsync. Process crashes were already safe: each frame is
  handed to the OS before `spill` returns.
- `close()` gives the flushers one shared 2 s grace after interrupting them, rather than 2 s
  each. It restores the caller's interrupt flag, and an interrupted caller no longer cuts the
  joins short.
- A second store with the same `spillInstanceId` creates its segment files exclusively, so
  two writers never append to the same file.
- **Demos: disk spill is on by default** in the Datadog, New Relic and Elastic batch demos
  (`spillEnabled="${sys:batch.spillEnabled:-true}"`, the library default). Set the property
  `batch.spillEnabled=false` to measure batching alone. The demos build against `0.2.0`.
- **Benchmark republished** (`docs/benchmark-results.md`, `docs/index.html`, `docs/img/`): all
  twelve demo apps (Datadog, New Relic, Elastic × CloudHub 1.0 / 2.0 × batch / stock) on
  engine 0.2.0 with spill on, run as three parallel vendor lanes with one app per vendor at a
  time, and with logs delivered counted at each vendor. It also corrects the CloudHub 2.0
  Datadog stock result: the earlier run's stock app carried a leftover
  `AsyncLoggerConfig.RingBufferSize=20000` and `log4j2.debug=true`, which made stock look
  about four times faster than it is. A caveat now notes that New Relic results depend on
  location: the workers run in Sydney and New Relic's intake is in the US.

### Added
- Coalesced WARN when the spill buffer evicts or refuses records: `spill buffer full (…):
  evicted N oldest segment(s) holding M record(s), refused K new record(s) since the last
  report — those logs are lost`. There is also a one-shot WARN for startup-reconcile
  evictions and an INFO for `spillRecoveryMode=DELETE` discards.
- Shutdown diagnostics: a WARN `shutdown: spilled K of N record(s) still queued …` and an
  ERROR for records that could not be spilled or were dropped with spill off.
- Recovery diagnostics: an INFO when segments from a previous run are adopted, a WARN when
  the spill directory lock is held by another live instance (ownership is retried every
  replay pass, and the segments are adopted once the holder lets go), a WARN per replayed
  segment with corrupt bytes skipped, and a WARN for an unreadable segment.
- `SpillStats.evictedRecords` and `SpillStats.adoptedRecords`. The stats heartbeat also shows
  `evictedRecords`, `adoptedRecords` and `corruptBytesSkipped`.
- Additive API: `SpillStore.LossListener` / `LossKind` and a constructor overload taking one,
  `SpillStore.spillRecords(List)` (returns how many were written),
  `SpillStore.tryReacquireOwnership()`, `recoveredSegments()`, `recoveredBytes()`,
  `ignoredSegments()`, `directory()`, and a `SpillReplayer` constructor overload taking
  `Diagnostics`.
- A crash-recovery test forks a real JVM. The child spills through the engine against a dead
  intake and is killed with `Runtime.halt` (no `close()`). The test then corrupts a record in
  the middle of a segment and tears the open segment's tail. It proves that, after a restart
  on the same directory, every intact record is delivered exactly once, the damaged ones are
  skipped and counted to the byte, and the engine does not hang. It runs for both formats.

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
  spill segment. A crash-recovery replay may now report a non-zero value where it read zero;
  nothing regressed, the old number was under-reporting, so revisit any alert threshold set
  against it.

## [0.1.2]

### Fixed
- The background flusher no longer busy-spins: an idle flusher polled with a zero timeout and
  could pin a CPU core at ~100%. It now blocks until the next record arrives.

## [0.1.1]

### Changed
- `queueCapacity` default lowered from `100000` to `10000`, bounding worst-case queue memory
  on small (0.1–0.2 vCore) CloudHub workers. See
  [Defaults that changed](docs/configuration.md#defaults-that-changed) for every default that has
  moved.

## [0.1.0]

- Initial release: the batching Log4j2 appender (`<BatchHttp>`) on a vendor-neutral core
  engine. Pre-publication history lives in the development repository.

[0.3.0]: https://github.com/mulesoft/batch-http-log-appender/compare/v0.2.2...v0.3.0
[0.2.2]: https://github.com/mulesoft/batch-http-log-appender/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/mulesoft/batch-http-log-appender/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/mulesoft/batch-http-log-appender/compare/v0.1.5...v0.2.0
[0.1.5]: https://github.com/mulesoft/batch-http-log-appender/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/mulesoft/batch-http-log-appender/releases/tag/v0.1.4
