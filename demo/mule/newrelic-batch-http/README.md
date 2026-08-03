# newrelic-batch-http

A sample **Mule 4.9** application that forwards its logs to **New Relic** using the
`batch-http-log4j2` batching appender from this repo. (Maven artifactId:
`mule-newrelic-demo`.)

Mule 4 logs through Log4j2, so the appender is a pure `log4j2.xml` change — no flow
code is aware of it.

Its stock-`HttpAppender` control sibling — for measuring the impact of batching — is
[`newrelic-stock-http`](../newrelic-stock-http).

## Layout

```
newrelic-batch-http/
├── pom.xml                          mule-application packaging; depends on batch-http-log4j2
├── mule-artifact.json               minMuleVersion 4.9.15
└── src/main/
    ├── mule/app.xml                 HTTP-triggered flows that emit log events (port 8081)
    └── resources/
        ├── config.properties        Mule properties (http.port=8081)
        └── log4j2.xml               <BatchHttp vendor="newrelic"> appender wiring
```

## Prerequisite

Install the appender into your local Maven repo (from the repo root):

```bash
mvn install -DskipTests
```

## Injecting the New Relic API key

`log4j2.xml` is parsed by **Log4j2**, not Mule — so Mule `${...}` properties do **not**
resolve there. The key is injected via a Log4j2 lookup that prefers a JVM system
property and falls back to an environment variable:

```
apiKey="${sys:NEW_RELIC_API_KEY:-${sys:newrelic.apiKey:-${env:NEW_RELIC_API_KEY:-MISSING_KEY}}}"
```

Provide **one** of:

| Method | How |
|--------|-----|
| JVM system property | `-Dnewrelic.apiKey=YOUR_INGEST_KEY` |
| Environment variable | `export NEW_RELIC_API_KEY=YOUR_INGEST_KEY` |

- **Local / standalone Mule:** add `-Dnewrelic.apiKey=...` to the runtime's JVM args
  (e.g. `conf/wrapper.conf` or `MULE_OPTS`).
- **CloudHub 2.0:** set a (secure) application property and map it to a JVM argument,
  or set the `NEW_RELIC_API_KEY` env var in the deployment.
- **Log API host:** the appender's `host` is the Log API endpoint for the data centre your
  account lives in, and the sink appends `/log/v1` to it. Your ingest key only works against
  your own region, so set it per deployment rather than editing the file:

  | Method | How |
  |--------|-----|
  | JVM system property | `-Dnr.logHost=<your-log-api-host>` |
  | Environment variable | `export NEW_RELIC_LOG_HOST=<your-log-api-host>` |

  Unset, it falls back to New Relic's default Log API host, so the app is deployable as
  shipped. Take the host for your region from
  [New Relic's own documentation](https://docs.newrelic.com/docs/logs/log-api/introduction-log-api/).
  Note this is the intake *endpoint*, distinct from the `hostname` facet the sink puts on each
  record.

  **Set it on both apps of the pair.** `nr.logHost` is the same property, with the same
  default, in [`newrelic-stock-http`](../newrelic-stock-http), which builds its whole intake
  URL from it. The A/B comparison is only meaningful if both halves ship to one region, so
  pass the same value to both — or neither.

## Exercising it

Once deployed (default port `8081`):

```bash
# Emit 52 events — a batch.start INFO, 50 order.processed INFOs, a batch.completed
# WARN — which batch and ship to New Relic
curl "http://localhost:8081/log?count=50"

# Emit an ERROR event carrying the raised error's type and description
curl "http://localhost:8081/boom"
```

Then look in **New Relic → Logs**, filtered on `logger.name = com.mulesoft.support.batchhttp.muledemo`.
Each event carries structured attributes: `level`, `generatedAt` (when the flow emitted the
event, ISO-8601 UTC), `service`, `hostname`, `platform`, `logtype`, `logger.name`, `thread.name`, any
MDC/ThreadContext entries (e.g. Mule's `correlationId`), and `error.stack` when the log event
carries a Java throwable. Mule's `<logger>` never attaches one — it logs a string — so `/boom`
exercises ERROR-level routing and the flow's own `errorType`/`reason` fields, not `error.stack`.
The `includeStackTrace` path is covered by the library's own tests instead.

There is deliberately **no `timestamp` attribute** (`includeTimestamp="false"` on
`<BatchHttp>`). `timestamp` is New Relic's reserved event time: supply it and New Relic stores
it as the log's timestamp, so it always equals `generatedAt` and shipping delay is invisible.
Omitting it lets New Relic stamp **arrival** time, making `timestamp - generatedAt` the ingest
lag. The stock sibling omits it the same way, so the two are measured identically.

The Logs UI shows the `message` attribute as the log line, and New Relic
[parses a `message` that contains JSON](https://docs.newrelic.com/docs/logs/log-api/introduction-log-api/#json-parsing)
into attributes — *replacing* the original. Since the flow logs a JSON object, that object
carries its own `message` key holding a short human-readable summary (`order.processed: order
7 of 30, USD 139.65, SHIPPED`); without it the message column renders blank. The stock sibling
emits the same key from the same flow.

The `service` facet comes from the `app.service` Log4j2 property, which **defaults** to this app's
own name, `newrelic-batch-http` (the stock sibling defaults to `newrelic-stock-http`), so the two
are distinguishable in New Relic out of the box with no configuration — query
`service = 'newrelic-batch-http'`.

It **is** overridable per deployment, with `-Dapp.service=...`, and one rule matters: if you stop
wanting an override, **delete the property — do not clear it**. Log4j2 substitutes a
`${sys:x:-default}` default only when the property is *absent*; one that is present but empty
resolves to `""` and beats the default, silently blanking the facet. That is what emptied the
equivalent Datadog column on the sibling demos once — the field had been overloaded with a plane
name and was then cleared rather than removed.

Keep the two roles apart: **`service` identifies the app** (batch versus stock) and **`platform`
identifies the plane** (CloudHub 1.0 versus 2.0). Neither should be used for the other's purpose;
overloading `service` with a plane name is what set that incident in motion, and `platform` exists
so there is no reason to. The `hostname` facet is `${sys:domain}` (the CloudHub 1.0 app name)
falling through to the container/machine hostname off CH1, with no overridable front link — that
chain already resolves on every plane, so a settable one would add the same risk for no gain.

### Telling CloudHub 1.0 and CloudHub 2.0 apart: the `platform` facet

This app is deployed to **both CloudHub 1.0 and CloudHub 2.0**, and neither facet above splits
the two: `service` is this app's name and is identical on both, and `hostname` is the CH1 domain
on one against the pod name on the other — the same string plus a suffix or a hash. So every event
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
plane — so the app stays deployable as shipped. The values are yours to pick; query it in NRQL
like any other attribute, e.g. `WHERE platform = 'cloudhub-1.0'`. `platform` is none of New
Relic's restricted attribute names (`message`, `timestamp`, `level`, `accountId`, `appId`,
`eventType`, `entity.*`), is not one of the specially-treated `hostname`/`service`, and is not a
key the flow emits, so nothing overwrites it.

**Set it on both apps of the pair.** `platform` is the same property, with the same chain and the
same default, in [`newrelic-stock-http`](../newrelic-stock-http). A value on one half only makes
the A/B look like a comparison of two platforms, so pass the same value to both — or to neither.

**The scripted CloudHub 1.0 deploy does not set it.** `scripts/deploy-ch1.sh` passes a single
`--property` — the New Relic licence key — so an app deployed through it reports
`platform: unspecified` until you add `app.platform` by hand in the Runtime Manager **Properties**
tab (CloudHub 1.0 surfaces those as JVM system properties) and restart. Do that for both halves
before a benchmark run, or the CH1 side of the comparison arrives unlabelled.

The empty-value trap applies to this facet exactly as it does to `app.service`: `APP_PLATFORM=`
(present but blank) resolves to `""` and beats the `unspecified` default, so the same rule holds —
**delete the override, do not clear it**. The consequence is milder here: a blank `platform` costs
one descriptive filter, whereas a blank `service` breaks every query keyed on the app's identity.

## How the appender is wired (log4j2.xml)

```xml
<Configuration status="INFO" packages="com.mulesoft.support.batchhttp.log4j2">
    <Appenders>
        <BatchHttp name="newrelic" vendor="newrelic"
                   host="${nr.logHost}"
                   apiKey="${sys:NEW_RELIC_API_KEY:-${sys:newrelic.apiKey:-${env:NEW_RELIC_API_KEY:-MISSING_KEY}}}"
                   service="${app.service}" hostname="${nr.hostname}"
                   maxBatchRecords="200" lingerMillis="2000" gzip="false"
                   includeTimestamp="false"
                   spillEnabled="false"
                   spillInstanceId="newrelic-batch-http"
                   maxSpillFileBytes="16777216"
                   maxTotalSpillBytes="536870912"
                   minFreeDiskBytes="268435456"
                   spillReplayIntervalMillis="10000"
                   spillOnOverflow="true"
                   spillFormat="BINARY"
                   spillRecoveryMode="ADOPT"
                   spillStartupReconcile="true"
                   spillBacklogOrder="DUAL_STREAM"
                   spillReplayThreads="2"
                   spillReplayMaxBytesPerSec="0"/>
    </Appenders>
    <Loggers>
        <AsyncLogger name="com.mulesoft.support.batchhttp.muledemo" level="INFO" additivity="false">
            <AppenderRef ref="newrelic"/>
            <AppenderRef ref="FILE"/>
        </AsyncLogger>
    </Loggers>
</Configuration>
```

(Abridged — the real file also sets the diagnostics attributes (`diagnosticsLevel="INFO"`,
`statsIntervalMillis="60000"`) and wires the framework noise-reduction loggers plus an
`<AsyncRoot>`. `status="INFO"` is what lets the appender's own `StatusLogger` health lines
and stats heartbeat print.)

`maxBatchRecords="200"` is the library default, spelled out here so this file reads as a
complete reference. So a burst of N events becomes roughly `ceil(N / 200)` POSTs (plus the
odd partial flush when `lingerMillis` fires first).

`gzip="false"` is **not** a default — the library defaults it to `true`, and this app
deliberately overrides it. The A/B control sibling (`newrelic-stock-http`) uses Log4j2's
stock `<Http>` appender, which has no compression option at all, so leaving gzip on would
make the pair differ by batching *and* compression and a bytes-on-the-wire comparison would
stop being apples to apples. With it off, **each request body is plaintext JSON with no
`Content-Encoding` header** — also easier to read in a proxy or mock intake. A production
deployment should leave compression on (just omit the attribute): it cuts wire bytes
substantially on this kind of repetitive JSON, and because New Relic's 1 MB payload limit
applies to the body **as sent**, it buys real headroom against that limit too. The engine's
own `maxBatchBytes="900000"` ceiling is uncompressed either way.

The `<AsyncRoot>` carries **only** `RollingFile`: framework logs keep landing in the
CloudHub log file but are never shipped to New Relic. The vendor layout wraps the flow's own
JSON message, so a plain-text framework line would render as an invalid JSON document.
Shipping is therefore scoped to the application logger above, which is `additivity="false"`
and names `RollingFile` itself — so its events reach the file exactly once and never bubble
up to root.

Two details that matter inside Mule:

1. **`packages="com.mulesoft.support.batchhttp.log4j2"`** tells Log4j2 to scan our plugin package so
   the `<BatchHttp>` element is recognized in the Mule app classloader.
2. The POM **excludes `log4j-core`/`log4j-api`** from the appender dependency — the
   Mule runtime already provides Log4j2 on its container classloader, and bundling a
   second copy would break plugin discovery / cause a `LinkageError`.

## Resilience: the disk-spill safety net (disabled in this demo)

The appender ships a **disk-spill buffer** that is **on by default in the library**: a batch
that fails after retries is written to rotating segment files instead of being lost, a
background thread re-ships them once the endpoint recovers, and segments left behind by a
crash are replayed on the next startup.

**This demo turns it off** with `spillEnabled="false"`. That is a benchmark decision, not a
recommendation. The buffer runs a replay driver thread that polls on an interval, adds up to
`spillReplayThreads` transient workers during a replay pass, and does disk I/O on the failure
and overflow paths. On a 0.1 vCore worker — and more so on a CloudHub 2.0 replica capped near
0.28–0.4 vCPU — those compete for the same core that serves requests, so leaving them on would
mean this A/B measures batching *plus* durability machinery. **A production deployment should
leave spill on**: just omit the attribute.

Two consequences while it is off:

- **Overflow drops again.** `spillOnOverflow` only takes effect when spill is enabled, so a
  record the in-memory queue cannot accept is discarded per `overflowPolicy` (`DROP_NEWEST`).
  The overload signal for this app is therefore **`dropped`** in the stats heartbeat. That is
  the *opposite* of the guidance in the root README's spill section, which describes the
  library default — with spill on, overflow goes to disk and `spilledRecords` is the signal.
  Both statements are correct for their configuration; this app is the overridden one.
  `queueCapacity="25000"` is consequently the entire buffer.
- **A failed batch is lost.** Batches that exhaust `maxRetries="3"` are counted and dropped
  rather than parked on disk, so an intake outage costs logs.

Every other spill attribute is still present in `log4j2.xml` and is **inert** while
`spillEnabled="false"`: `spillInstanceId`, `spillReplayIntervalMillis`, `maxSpillFileBytes`,
`maxTotalSpillBytes`, `minFreeDiskBytes`, `spillOnOverflow`, `spillFormat`,
`spillRecoveryMode`, `spillStartupReconcile`, `spillBacklogOrder`, `spillReplayThreads` and
`spillReplayMaxBytesPerSec`. They are kept on purpose — the file is an exhaustive reference,
and re-enabling durability is then a one-word edit rather than a rediscovery of the tuning.

### Turning it back on

Set `spillEnabled="true"` and the configuration already in the file applies as written. Nothing
touches disk on the happy path; records are written to rotating segment files only when a batch
fails after retries are exhausted, or when the queue would overflow. The buffer is bounded
(512 MiB total, with a 256 MiB free-disk floor); when either limit is hit the **oldest** segment
is dropped, so logging can never exhaust the disk. The crash-resilience knobs then take effect
too (see the root README for the full reference):

- **`spillRecoveryMode="ADOPT"`** — segment files left by a previous crash are
  replayed on the next startup, so logs buffered just before the crash still reach
  New Relic. A `FileLock` on the spill directory ensures a second instance pointed at
  the same directory won't double-ship or delete a live sibling's files.
- **`spillStartupReconcile="true"`** — on startup, if the inherited backlog is over
  the size cap or under the free-disk floor, the oldest segments are evicted before
  any new log is written.
- **`spillBacklogOrder="DUAL_STREAM"` + `spillReplayThreads="2"`** — after an outage,
  one stream ships the freshest logs first (so recent activity surfaces immediately)
  while a second drains the historical backlog in parallel.
- **`spillFormat="BINARY"`** — records are stored in a compact binary encoding that
  round-trips every character set (non-Latin scripts, emoji, control characters).
  Switch to `spillFormat="JSON"` if you'd rather read the spill files by eye.
- **`spillInstanceId="newrelic-batch-http"`** — the id baked into each segment file
  name (`spill-newrelic-batch-http-<seq>.log`) so this app never overwrites the spill
  files of another app sharing the same directory. It defaults to the appender `name`;
  it is set explicitly here to show the knob. Give each co-located app a distinct,
  restart-stable id.

With spill enabled, segment files for this demo would live under:

```
/tmp/batch-http-spill/NewRelic/      # %TEMP%\batch-http-spill\NewRelic\ on Windows
```

(`NewRelic` is the sanitized sink name; override the location with `spillDirectory`.)
Within that directory the files are named `spill-newrelic-batch-http-<seq>.log` — the
`spillInstanceId` keeps them distinct from any sibling app's segments.

### Watch it work

This walkthrough needs `spillEnabled="true"`; as shipped, the failed batches are dropped and
counted instead, and nothing appears on disk.

1. Start the app **without** a valid key (so shipping fails):

   ```bash
   # no NEW_RELIC_API_KEY / -Dnewrelic.apiKey set -> sends are rejected
   curl "http://localhost:8081/log?count=2000"
   ```

2. After a few seconds the failed batches land on disk — watch them appear and rotate:

   ```bash
   watch -n1 'ls -lh /tmp/batch-http-spill/NewRelic/'
   ```

3. Provide a real key and restart the app. The replayer drains the backlog (this app sets
   `spillBacklogOrder="DUAL_STREAM"`, so one worker takes the newest segments and one the
   oldest) and the files disappear as each segment ships successfully — the buffered logs
   then show up in New Relic.

See the repo root README's **Disk-spill buffer** section for the full attribute
reference and tuning notes.
