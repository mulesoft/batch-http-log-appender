# datadog-batch-http

A sample **Mule 4.9** application that forwards its logs to **Datadog** using the
`batch-http-log4j2` batching appender from this repo. (Maven artifactId:
`mule-datadog-demo`.)

Mule 4 logs through Log4j2, so the appender is a pure `log4j2.xml` change — no flow
code is aware of it.

This is the Datadog counterpart of [`newrelic-batch-http`](../newrelic-batch-http). Its
stock-`HttpAppender` control sibling — for measuring the impact of batching — is
[`datadog-stock-http`](../datadog-stock-http).

## Layout

```text
datadog-batch-http/
├── pom.xml                          mule-application packaging; depends on batch-http-log4j2
├── mule-artifact.json               minMuleVersion 4.9.15
└── src/main/
    ├── mule/app.xml                 HTTP-triggered flows that emit log events (port 8083)
    └── resources/
        ├── config.properties        Mule properties (http.port=8083)
        └── log4j2.xml               <BatchHttp vendor="datadog"> appender wiring
```

## Prerequisite

Install the appender into your local Maven repo (from the repo root):

```bash
mvn install -DskipTests
```

## Injecting the Datadog API key

`log4j2.xml` is parsed by **Log4j2**, not Mule — so Mule `${...}` properties do **not**
resolve there. The key is injected via a Log4j2 lookup that prefers a JVM system
property and falls back to an environment variable:

```text
apiKey="${sys:DD_API_KEY:-${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}}"
```

Provide **one** of:

| Method | How |
|--------|-----|
| JVM system property | `-Ddatadog.apiKey=YOUR_DD_API_KEY` |
| Environment variable | `export DD_API_KEY=YOUR_DD_API_KEY` |

- **Local / standalone Mule:** add `-Ddatadog.apiKey=...` to the runtime's JVM args
  (e.g. `conf/wrapper.conf` or `MULE_OPTS`).
- **CloudHub 2.0:** set a (secure) application property and map it to a JVM argument,
  or set the `DD_API_KEY` env var in the deployment.
- **Datadog site:** the appender's `site` is the log-intake host for the site your
  organization lives in, and the sink appends `/api/v2/logs` to it. Your API key only works
  against your own site, so set it per deployment rather than editing the file:

  | Method | How |
  |--------|-----|
  | JVM system property | `-Ddd.site=<your-datadog-intake-host>` |
  | Environment variable | `export DD_SITE=<your-datadog-intake-host>` |

  Unset, it falls back to Datadog's default site, so the app is deployable as shipped. Take
  the host for your site from [Datadog's own documentation](https://docs.datadoghq.com/getting_started/site/) —
  the set of sites changes over time, so this repo does not restate it.

  **Set it on both apps of the pair.** `dd.site` is the same property, with the same default,
  in [`datadog-stock-http`](../datadog-stock-http), which builds its whole intake URL from it.
  The A/B comparison is only meaningful if both halves ship to one site, so pass the same
  value to both — or neither.

## Exercising it

Once deployed (default port `8083`):

```bash
# Emit 52 events — a batch.start INFO, 50 order.processed INFOs, a batch.completed
# WARN — which batch and ship to Datadog
curl "http://localhost:8083/log?count=50"

# Emit an ERROR event carrying the raised error's type and description
curl "http://localhost:8083/boom"
```

Then look in **Datadog → Logs**, filtered on
`logger.name:com.mulesoft.support.batchhttp.muledemo`. Each event carries structured
attributes: `status` (level), `generatedAt` (when the flow emitted the event, ISO-8601 UTC),
`ddsource`, `service`, `host`, `platform`, `logger.name`, `logger.thread_name`, any MDC/ThreadContext
entries (e.g. Mule's `correlationId`), and `error.stack` when the log event carries a Java
throwable. Mule's `<logger>` never attaches one — it logs a string — so `/boom` exercises
ERROR-level routing and the flow's own `errorType`/`reason` fields, not `error.stack`. The
`includeStackTrace` path is covered by the library's own tests instead.

There is deliberately **no `date` attribute** (`includeTimestamp="false"` on `<BatchHttp>`).
`date` is Datadog's reserved event time: supply it and Datadog stores it as the log's
timestamp, so it always equals `generatedAt` and shipping delay is invisible. Omitting it
lets Datadog stamp **arrival** time, making `date - generatedAt` the ingest lag. The stock
sibling omits it the same way, so the two are measured identically.

The flow calls its order state `orderStatus`, not `status`: `status` is Datadog's reserved log
level, and a `status` key in the message is parsed on top of the appender's, which would show
`SHIPPED` in the level column instead of `INFO`. The same applies to `host`, `service`,
`message`, `ddsource`, `ddtags` and `date` — the flow's payload avoids all of them.

The `service` facet comes from the `app.service` Log4j2 property, which **defaults** to this app's
own name, `datadog-batch-http` (the stock sibling defaults to `datadog-stock-http`), so the two are
distinguishable in Datadog out of the box with no configuration — filter on
`service:datadog-batch-http`.

It **is** overridable per deployment, with `-Dapp.service=...`, and one rule matters: if you stop
wanting an override, **delete the property — do not clear it**. Log4j2 substitutes a
`${sys:x:-default}` default only when the property is *absent*; one that is present but empty
resolves to `""` and beats the default, silently blanking the facet. That is what emptied Datadog's
`SERVICE` column once — the field had been overloaded with a plane name and was then cleared rather
than removed.

Keep the two roles apart: **`service` identifies the app** (batch versus stock) and **`platform`
identifies the plane** (CloudHub 1.0 versus 2.0). Neither should be used for the other's purpose;
overloading `service` with a plane name is what set that incident in motion, and `platform` exists
so there is no reason to. The `host` facet is `${sys:domain}` (the CloudHub 1.0 app name) falling
through to the container/machine hostname off CH1, with no overridable front link — that chain
already resolves on every plane, so a settable one would add the same empty-value risk for no gain.

### Telling CloudHub 1.0 and CloudHub 2.0 apart: the `platform` facet

This app is deployed to **both CloudHub 1.0 and CloudHub 2.0**, and neither facet above splits
the two: `service` is this app's name and is identical on both, and `host` is the CH1 domain on
one against the pod name on the other — the same string plus a suffix or a hash. So every event
also carries a dedicated **`platform`** attribute, emitted from the vendor `<PatternLayout>`:

| Method | How |
|--------|-----|
| JVM system property | `-Dapp.platform=cloudhub-1.0` |
| Environment variable | `export APP_PLATFORM=cloudhub-2.0` |

Both override keys are namespaced on purpose: `sys:` and `env:` are shared with whatever the
container already defines, and a bare `PLATFORM` is a plausible base-image variable that would
silently shadow the facet. The `<Properties>` entry is named `app.platform` to match, alongside
`app.service`; the attribute on the wire is **`platform`**, and that is what you query on.

Unset, it resolves to **`unspecified`** — honest about not knowing rather than guessing a
plane — so the app stays deployable as shipped. The values are yours to pick; Datadog facets on
whatever string arrives, so filter with `@platform:cloudhub-1.0`. `platform` is not a Datadog
reserved attribute (those are `host`, `source`, `status`, `service`, `trace_id`, `message`, plus
the intake's `ddsource`/`ddtags`/`date`) and is not a key the flow emits, so nothing overwrites it.

**Set it on both apps of the pair.** `platform` is the same property, with the same chain and the
same default, in [`datadog-stock-http`](../datadog-stock-http). A value on one half only makes the
A/B look like a comparison of two platforms, so pass the same value to both — or to neither.

**The scripted CloudHub 1.0 deploy does not set it.** `scripts/deploy-ch1.sh` passes a single
`--property` — the Datadog API key — so an app deployed through it reports `platform: unspecified`
until you add `app.platform` by hand in the Runtime Manager **Properties** tab (CloudHub 1.0
surfaces those as JVM system properties) and restart. Do that for both halves before a benchmark
run, or the CH1 side of the comparison arrives unlabelled.

The empty-value trap applies to this facet exactly as it does to `app.service`: `APP_PLATFORM=`
(present but blank) resolves to `""` and beats the `unspecified` default, so the same rule holds —
**delete the override, do not clear it**. The consequence is milder here: a blank `platform` costs
one descriptive filter, whereas a blank `service` breaks every query keyed on the app's identity.

## How the appender is wired (log4j2.xml)

```xml
<Configuration status="INFO" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <BatchHttp name="datadog" vendor="datadog"
                   site="${dd.site}"
                   apiKey="${sys:DD_API_KEY:-${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}}"
                   service="${app.service}" host="${dd.host}" source="mule"
                   maxBatchRecords="200" lingerMillis="2000" gzip="false"
                   includeTimestamp="false"/>
    </Appenders>
    <Loggers>
        <AsyncLogger name="com.mulesoft.support.batchhttp.muledemo" level="INFO" additivity="false">
            <AppenderRef ref="datadog"/>
            <AppenderRef ref="FILE"/>
        </AsyncLogger>
    </Loggers>
</Configuration>
```

(Abridged — the real file also sets the spill and diagnostics attributes described below,
and wires the framework noise-reduction loggers plus an `<AsyncRoot>`. `status="INFO"` lets the appender's own health lines and stats heartbeat print on the
console; the same lines also reach the log file through the
`com.mulesoft.support.batchhttp.diagnostics` logger.)

`maxBatchRecords="200"` is the library default, spelled out here so this file reads as a
complete reference. So a burst of N events becomes roughly `ceil(N / 200)` POSTs (plus the
odd partial flush when `lingerMillis` fires first).

`gzip="false"` is **not** a default — the library defaults it to `true`, and this app
deliberately overrides it. The A/B control sibling (`datadog-stock-http`) uses Log4j2's stock
`<Http>` appender, which has no compression option at all, so leaving gzip on would make the
pair differ by batching *and* compression and a bytes-on-the-wire comparison would stop being
apples to apples. With it off, **each request body is plaintext JSON with no
`Content-Encoding` header** — also easier to read in a proxy or mock intake. A production
deployment should leave compression on (just omit the attribute): it cuts wire bytes
substantially on this kind of repetitive JSON. Note that Datadog's per-request ceilings are
measured **uncompressed** either way, so gzip changes what crosses the network, not how many
records fit in a batch.

The `<AsyncRoot>` carries **only** `RollingFile`: framework logs keep landing in the
CloudHub log file but are never shipped to Datadog. The vendor layout wraps the flow's own
JSON message, so a plain-text framework line would render as an invalid JSON document.
Shipping is therefore scoped to the application logger above, which is `additivity="false"`
and names `RollingFile` itself — so its events reach the file exactly once and never bubble
up to root.

Two details that matter inside Mule:

1. **`packages="com.mulesoft.support.batchhttp.log4j2"`** tells Log4j2 to scan our
   plugin package so the `<BatchHttp>` element is recognized in the Mule app
   classloader.
2. The POM **excludes `log4j-core`/`log4j-api`** from the appender dependency — the
   Mule runtime already provides Log4j2 on its container classloader, and bundling a
   second copy would break plugin discovery / cause a `LinkageError`.

## Datadog intake specifics

The `datadog` sink POSTs a JSON array to `/api/v2/logs` on the configured `site`, with
the API key in the `DD-API-KEY` header. The engine enforces the sink's declared per-request
ceilings — a record count and an uncompressed body size — and flushes before crossing them;
`DatadogSink.limits()` is the authoritative statement of what it enforces, and Datadog's own
documentation of the currently published ceilings is authoritative for what the intake will
accept. Reserved Datadog attributes used by the sink: `message`, `status`,
`ddsource`, `service`, and `host`. The sink can also emit `date` as an ISO-8601 UTC instant
(e.g. `2023-11-14T22:13:20.123Z`), which Datadog accepts alongside epoch millis; that is the
library default, but this demo turns it off with `includeTimestamp="false"` so Datadog
stamps arrival time and ingest lag stays measurable.

## Resilience: the disk-spill safety net (on in this demo)

The appender ships a **disk-spill buffer** that is **on by default in the library**: a batch
that fails after retries is written to rotating segment files instead of being lost, a
background thread re-ships them once the endpoint recovers, and segments left behind by a
crash are replayed on the next startup.

**This demo leaves it on**: `log4j2.xml` sets `spillEnabled="${sys:batch.spillEnabled:-true}"`,
the library default. Nothing touches disk on the happy path; records are written to rotating
segment files only when a batch fails after retries are exhausted, or when the queue would
overflow (`spillOnOverflow="true"`). The overload signal is therefore **`spilledRecords`** in the
stats heartbeat, as [Disk-spill buffer](../../../docs/resilience.md#disk-spill-buffer) describes. Records lost because the spill
buffer itself is full land in `spillStats().droppedRecords` (`evictedRecords` for an evicted
oldest segment), never in `dropped`, which counts terminal rejections (a 4xx) and serialization
failures. The buffer is bounded (512 MiB total, with a 256 MiB
free-disk floor); when either limit is hit the **oldest** segment is dropped, so logging can
never exhaust the disk.

The buffer runs a replay driver thread that polls on an interval, adds up to
`spillReplayThreads` transient workers during a replay pass, and does disk I/O on the failure
and overflow paths. On a 0.1 vCore worker — and more so on a CPU-capped CloudHub 2.0 replica —
those share the core that serves requests. In the 2026-10-12 CloudHub benchmark
that cost batch req/s only where the queue overflowed the whole time, and nothing measurable in
the 5-minute tests. A spill backlog lives in the worker's `/tmp`: on CloudHub a restart moves the
app to a new worker (1.0) or pod (2.0), so a backlog still on disk at that moment is lost.

### Turning it off

Set the system property `batch.spillEnabled=false` (for example as a CloudHub application
property) to measure batching alone. Two consequences while it is off:

- **Overflow drops again.** `spillOnOverflow` only takes effect when spill is enabled, so a
  record the in-memory queue cannot accept is discarded per `overflowPolicy` (`DROP_NEWEST`),
  and the overload signal becomes **`dropped`** in the stats heartbeat.
  `queueCapacity="25000"` is then the entire buffer.
- **A failed batch is lost.** Batches that exhaust `maxRetries="3"` are counted and dropped
  rather than parked on disk, so an intake outage costs logs.

Every other spill attribute stays in `log4j2.xml` and is inert while spill is off.

### The crash-resilience knobs

With spill on, the crash-resilience settings in the file apply as written (see the root
README for the full reference):

- **`spillRecoveryMode="ADOPT"`** — segment files left by a previous crash are
  replayed on the next startup, so logs buffered just before the crash still reach
  Datadog. A `FileLock` on the spill directory ensures a second instance pointed at the
  same directory won't double-ship or delete a live sibling's files.
- **`spillStartupReconcile="true"`** — on startup, if the inherited backlog is over
  the size cap or under the free-disk floor, the oldest segments are evicted before
  any new log is written.
- **`spillBacklogOrder="DUAL_STREAM"` + `spillReplayThreads="2"`** — after an outage,
  one stream ships the freshest logs first (so recent activity surfaces immediately)
  while a second drains the historical backlog in parallel.
- **`spillFormat="BINARY"`** — records are stored in a compact binary encoding that
  round-trips every character set (non-Latin scripts, emoji, control characters).
  Switch to `spillFormat="JSON"` if you'd rather read the spill files by eye.

Segment files for this demo live under:

```text
/tmp/batch-http-spill/Datadog/       # %TEMP%\batch-http-spill\Datadog\ on Windows
```

(`Datadog` is the sanitized sink name; override the location with `spillDirectory`.)
This app sets `spillInstanceId="datadog-batch-http"`, so segments are named
`spill-datadog-batch-http-<seq>.log` and the lock file is
`.spill-datadog-batch-http.lock`. The id defaults to the appender `name` when unset, but
setting it explicitly is the right habit: it must be stable across restarts (so the app
recovers its own orphaned segments) and distinct per running instance (so co-located apps
sharing one spill directory never collide).

### Watch it work

This walkthrough uses the demo as shipped, with spill on. (With `batch.spillEnabled=false`
the failed batches are dropped and counted instead, and nothing appears on disk.)

1. Start the app with a valid key but an **unreachable** intake, so every send fails with a
   connection error. That is retryable, so batches spill once their retries run out. (Starting
   without a key does not work here: Datadog answers `403`, which is final, so those batches are
   dropped and never spilled.)

   ```bash
   # start with -Ddd.site=127.0.0.1:9 (-M-Ddd.site=127.0.0.1:9 under the standalone Mule wrapper)
   curl "http://localhost:8083/log?count=2000"
   ```

2. After a few seconds the failed batches land on disk — watch them appear and rotate:

   ```bash
   watch -n1 'ls -lh /tmp/batch-http-spill/Datadog/'
   ```

3. Remove the `dd.site` override (or set your real Datadog site) and restart the app. The replayer drains the backlog and the
   files disappear as each segment ships successfully — the buffered logs then show up
   in Datadog.

See [Disk-spill buffer](../../../docs/resilience.md#disk-spill-buffer) for the tuning notes and
the [spill options](../../../docs/configuration.md#disk-spill) for the full attribute reference.
