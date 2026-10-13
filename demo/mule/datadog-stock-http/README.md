# datadog-stock-http

A **control sibling** of [`datadog-batch-http`](../datadog-batch-http), built for one
purpose: **measure the performance impact of batching** by comparing this app against
the batch one under an identical workload. (Maven artifactId:
`mule-datadog-stock-http-demo`.)

This app is byte-for-byte the same Mule application — same flow, same log lines, same
Datadog endpoint — with **exactly one variable changed**: instead of the repo's batching
`<BatchHttp>` appender, it uses **Log4j2's stock, built-in `<Http>` appender**, which
sends **one synchronous HTTP POST per log event**. No batching, no queue coalescing, and no
compression or disk-spill option at all — every event is its own round-trip. The batch sibling
has both of those options: it turns compression off so the pair does not also differ by
compression, and keeps disk spill on, the library default.

| | `datadog-batch-http` (batch) | `datadog-stock-http` (this, control) |
|---|---|---|
| Appender | `<BatchHttp vendor="datadog">` (this repo) | Log4j2 stock `<Http>` |
| Requests to Datadog | 1 per **batch** (≤200 events) | 1 per **event** |
| Body | JSON array, uncompressed (demo sets `gzip="false"`; library default is `true`) | one JSON object, uncompressed |
| Queue / linger | bounded queue, time/size/count flush | none |
| Durability | retry, then the disk-spill safety net (on, the library default; `batch.spillEnabled=false` turns it off) | none (event lost on failure) |
| Custom dependency | `batch-http-log4j2` jar bundled | **none** — stock log4j-core (Mule provides it) |
| HTTP port | 8083 | **8084** (so both run side-by-side) |

> Because the stock `HttpAppender` is part of `log4j-core` — which the Mule runtime
> already provides on its container classloader — this app bundles **no custom jar**.
> That is what makes it a clean control: the only difference from the batch app is the
> appender element in `log4j2.xml`.

## Layout

```
datadog-stock-http/
├── pom.xml                          mule-application packaging; NO appender dependency
├── mule-artifact.json               minMuleVersion 4.9.15
└── src/main/
    ├── mule/app.xml                 same flows as the batch sibling (port 8084)
    └── resources/
        ├── config.properties        Mule properties (http.port=8084)
        └── log4j2.xml               STOCK <Http> appender wiring (1 POST/event)
```

## Build

No prerequisite install — this app depends only on the Mule connector and the
runtime-provided log4j. From this directory:

```bash
mvn clean package
```

Produces `target/mule-datadog-stock-http-demo-1.0.0-SNAPSHOT-mule-application.jar`.

## Injecting the Datadog API key

`log4j2.xml` is parsed by **Log4j2**, not Mule, so the key is injected via a Log4j2
lookup (same chain as the batch sibling):

```
DD-API-KEY = ${sys:DD_API_KEY:-${sys:datadog.apiKey:-${env:DD_API_KEY:-MISSING_KEY}}}
```

Provide one of `-Ddatadog.apiKey=...` (JVM arg) or `export DD_API_KEY=...`.

---

## Running the A/B performance PoC

The goal is to send the **same number of log events** through each app and compare:

- **requests hitting the intake** (batching should be ~`events / 200`, this demo's
  `maxBatchRecords`; stock = `events`),
- **wall-clock to absorb the burst** and the request thread / async-logger backlog,
- **app throughput / CPU** while logging under load.

### Option A — measure against a local mock intake (recommended for clean numbers)

A mock removes network variance and needs no real Datadog account, so the difference you
measure is purely the appender behavior. A tiny mock that counts requests and adds a
fixed latency per request (to simulate intake round-trip) is all you need.

1. **Start the mock** on port 9099 (counts requests, 5 ms latency each). Save as
   `/tmp/mock-intake.js` and run with `node /tmp/mock-intake.js`:

   ```js
   const http = require('http');
   const zlib = require('zlib');
   let requests = 0, records = 0;
   http.createServer((req, res) => {
     const chunks = [];
     req.on('data', c => chunks.push(c));
     req.on('end', () => {
       requests++;
       let buf = Buffer.concat(chunks);
       // The batch appender compresses only when gzip="true" (these demos override it to
       // "false" for the A/B, but the library default is "true"); the stock <Http> appender
       // never does. Decompress on the header OR the gzip magic bytes, so this mock reads
       // both configurations instead of printing binary garbage.
       const gzipped = req.headers['content-encoding'] === 'gzip'
                    || (buf.length > 1 && buf[0] === 0x1f && buf[1] === 0x8b);
       if (gzipped) {
         try { buf = zlib.gunzipSync(buf); } catch { /* not really gzip — fall through to raw */ }
       }
       const body = buf.toString('utf8');
       try {
         // JSON array (batched) or a single object (stock, one per request)
         const j = JSON.parse(body);
         records += Array.isArray(j) ? j.length : 1;
       } catch {
         // NDJSON (the generic sink) — one record per non-empty line
         records += body.split('\n').filter(l => l.trim()).length;
       }
       setTimeout(() => { res.writeHead(202); res.end(); }, 5); // 5 ms simulated intake latency
     });
   }).listen(9099, () => console.log('mock intake on :9099'));
   process.on('SIGINT', () => { console.log(`\nrequests=${requests} records=${records}`); process.exit(0); });
   ```

2. **Point each app at the mock.** Use a **separate mock instance per app** (e.g. ports
   9099 and 9098) so each app's `requests`/`records` totals are isolated.

   - **Stock app (this one):** `-Ddd.logUrl=http://localhost:9099/api/v2/logs` — the stock
     `<Http>` appender takes a whole URL, so no file edit is needed.
   - **Batch app:** it has no `dd.logUrl` knob, and pointing `dd.site` at `localhost:9099`
     does **not** work: the `datadog` sink always builds
     `https://<site>/api/v2/logs`, so it would try TLS against a plain-HTTP mock. For the
     measurement run, edit its `log4j2.xml` to address the mock directly with the generic
     sink instead:

     ```xml
     <!-- keep the other attributes unchanged -->
     <BatchHttp name="datadog" vendor="generic" url="http://localhost:9098/api/v2/logs"/>
     ```

     Keep every batching attribute (`maxBatchRecords`, `lingerMillis`, `gzip`, …) as-is —
     the batching behavior under test is the engine's, not the sink's. The generic sink
     frames the batch as NDJSON, which the mock above counts line by line.

3. **Drive identical load** against each app and time it:

   ```bash
   # stock app (port 8084)
   time curl -s "http://localhost:8084/log?count=2000" >/dev/null
   # batch app (port 8083)
   time curl -s "http://localhost:8083/log?count=2000" >/dev/null
   ```

4. **Read the mock's totals** (Ctrl-C each mock to print its `requests`/`records`).
   `count=2000` emits exactly **2002** shipped events (a `batch.start`, 2000
   `order.processed`, a `batch.completed`) — the `<AsyncRoot>` carries only `RollingFile`, so
   framework logs land in the app log file and are never shipped.
   Expected shape — same `records` on both sides, but:
   - stock: `requests ≈ records` — about **2002** POSTs,
   - batch: `requests ≈ ceil(records / maxBatchRecords)` — about **11** POSTs at this
     demo's `maxBatchRecords="200"` (`ceil(2002 / 200)`), a ~190× reduction, plus a few
     linger-triggered partial flushes (a handful either way).

   Raising `maxBatchRecords` moves that number directly: at `500` the same burst is ~5
   POSTs. `200` is the library default and a deliberately conservative demo setting — it
   keeps each request small enough to read by eye in the mock while still showing the
   two-orders-of-magnitude gap.

### Option B — measure against real Datadog

Leave `dd.logUrl` unset so the URL is built from `dd.site`, point that at your own Datadog
site, and provide a real API key to each app. Drive
the same `count=` load against both and compare in **Datadog → Logs** plus the runtime's
own throughput/latency. This is the most faithful sibling but adds live-network variance —
prefer Option A for clean, repeatable numbers and use B to confirm end-to-end.

`dd.site` is the same property, with the same default, in
[`datadog-batch-http`](../datadog-batch-http), which passes it to its appender's `site`
attribute. Pass the same value to both apps — or neither — so the pair always ships to one
site; a comparison whose two halves went to different sites is not a comparison. Set it with
`-Ddd.site=<your-datadog-intake-host>` or `DD_SITE`; unset, both fall back to Datadog's
default site. Take the host for your site from
[Datadog's own documentation](https://docs.datadoghq.com/getting_started/site/).

Each app's Datadog `service` facet (its `app.service` Log4j2 property) **defaults** to that app's
own name — `datadog-stock-http` here, `datadog-batch-http` for the batch sibling — so the two
runs are distinguishable out of the box: split them with `service:datadog-stock-http` vs
`service:datadog-batch-http`. `host` is `${sys:domain}` (the CloudHub 1.0 app name) falling
through to the container/machine hostname off CH1, so it varies with *where* the app runs — but
only by a suffix or a hash, which makes it a poor column to split platforms on. Use `service` for
the A/B pair and `platform` (below) for CH1 vs CH2.

`service` **is** overridable per deployment, with `-Dapp.service=...`; `host` deliberately is not,
because its chain already resolves on every plane. For the overridable one, one rule matters: if
you stop wanting an override, **delete the property — do not clear it**. Log4j2 substitutes a
`${sys:x:-default}` default only when the property is *absent*; one that is present but empty
resolves to `""` and beats the default, silently blanking the facet — which is how a cleared
`app.service` emptied the `SERVICE` column for a whole run. Keep the two roles apart: **`service`
identifies the app** (batch versus stock) and **`platform` identifies the plane** (CloudHub 1.0
versus 2.0). Neither should be used for the other's purpose — overloading `service` with a plane
name is what set that incident in motion.

### Telling CloudHub 1.0 and CloudHub 2.0 apart: the `platform` facet

Both apps of the pair are deployed to **both CloudHub 1.0 and CloudHub 2.0**, and neither facet
above splits the two planes cleanly. So every event also carries a dedicated **`platform`**
attribute, injected into this app's `<PatternLayout>` (the batch sibling emits it from its own
vendor layout, so both halves land the identical facet):

| Method | How |
|--------|-----|
| JVM system property | `-Dapp.platform=cloudhub-1.0` |
| Environment variable | `export APP_PLATFORM=cloudhub-2.0` |

Both override keys are namespaced on purpose: `sys:` and `env:` are shared with whatever the
container already defines, and a bare `PLATFORM` is a plausible base-image variable that would
silently shadow the facet. The `<Properties>` entry is named `app.platform` to match, alongside
`app.service`; the attribute on the wire is **`platform`**, and that is what you query on.

Unset, it resolves to **`unspecified`** — honest about not knowing rather than guessing a
plane — so the app stays deployable as shipped. The values are yours to pick; filter with
`@platform:cloudhub-1.0`. `platform` is not a Datadog reserved attribute (those are `host`,
`source`, `status`, `service`, `trace_id`, `message`, plus the intake's `ddsource`/`ddtags`/`date`)
and is not a key the flow emits, so nothing overwrites it.

**Set it on both apps of the pair.** It is the same property, with the same chain and the same
default, in [`datadog-batch-http`](../datadog-batch-http). A value on one half only makes the A/B
look like a comparison of two platforms, so pass the same value to both — or to neither.

**The scripted CloudHub 1.0 deploy does not set it.** `scripts/deploy-ch1.sh` passes a single
`--property` — the Datadog API key — so an app deployed through it reports `platform: unspecified`
until you add `app.platform` by hand in the Runtime Manager **Properties** tab (CloudHub 1.0
surfaces those as JVM system properties) and restart. Do that for both halves before a benchmark
run, or the CH1 side of the comparison arrives unlabelled.

The empty-value trap applies to this facet exactly as it does to `app.service`: `APP_PLATFORM=`
(present but blank) resolves to `""` and beats the `unspecified` default, so the same rule holds —
**delete the override, do not clear it**. The consequence is milder here: a blank `platform` costs
one descriptive filter, whereas a blank `service` breaks every query keyed on the app's identity.

### What to record per run

| Metric | Where |
|---|---|
| events emitted | the `count=` you sent |
| requests to intake | mock totals (Option A) or Datadog request count |
| wall-clock to drain | `time` on the curl + when the mock stops receiving |
| request-thread blocking | Mule logs / thread dump while under load |

### Measuring the per-event blocking cost directly

By default the Datadog logger is an `<AsyncLogger>`, matching the batch app, so the stock
appender's blocking POSTs queue behind Log4j2's disruptor thread (you observe
backlog/back-pressure). To instead measure the **raw per-event latency added to the
request thread**, change `<AsyncLogger name="com.mulesoft.support.batchhttp.muledemo" …>`
to a synchronous `<Logger …>` in `log4j2.xml` — then each `logger` component in the flow
blocks on its POST, and the `/log?count=N` wall-clock directly reflects `N ×` per-request
latency.

## Notes / caveats for a fair comparison

- **Keep the workload identical** — same `count`, same flow, same log message shape. The
  flow here is intentionally a copy of the batch app's.
- **Payload parity is enforced** — the layout spells out the `status`, `logger.name`,
  `logger.thread_name`, `correlationId` and `processorPath` fields that the batch app's sink
  derives from the Log4j2 event, and both layouts emit `platform` from the same property, so both
  apps ship the same attribute set and `service` is the
  only field that differs. The rendered bodies are not the same size, though: an
  `order.processed` event is 695 B here against 767 B on the batch side, which carries the
  flow's JSON escaped inside a `message` string. `generatedAt` is not a layout field on
  either side — both apps produce it from the same DataWeave expression in `app.xml`, so the
  event time being compared is genuinely the same clock reading in both runs.
- **Both bodies are plaintext** — but only because the batch demo *explicitly* sets
  `gzip="false"`, not because the appender can't compress: the library defaults `gzip` to
  `true`, and the batch app overrides it precisely so this A/B varies the appender alone.
  That is what keeps a bytes-on-the-wire comparison apples to apples. A production batch app
  would leave compression on, and the byte comparison would then differ substantially in the
  batch app's favour on this kind of repetitive JSON — worth measuring separately rather than
  folding into the batching result.
- **Neither app sends `date`** — it is Datadog's reserved event time, and supplying it would
  make the stored timestamp equal `generatedAt`, hiding the shipping delay this app is meant to
  exhibit. Both siblings omit it (here by leaving it out of the layout, on the batch side via
  `includeTimestamp="false"`), so Datadog stamps arrival time on both and `date - generatedAt`
  is a real measurement rather than a tautology.
- **Isolate intake counters** — use a separate mock instance (or endpoint) per app so
  request/record totals don't mix.
- **Match timeouts** — this app's `connectTimeoutMillis`/`readTimeoutMillis` (10s) mirror
  the batch app's `requestTimeoutMillis` so a slow/dead intake penalizes both equally.
- **First-request warmup** — discard the first run or send a warmup burst; JIT/connection
  setup skews the first measurement for both apps.

See the [local mock-intake benchmark](../../../docs/building.md#run-the-benchmark--example) for the standalone (non-Mule) version of this
comparison (~400× wall-clock, ~500× fewer requests on a local mock). That harness
(`demo/java/benchmark`) sets `maxBatchRecords(500)` explicitly, which is why its request
ratio is ~500× rather than the ~190× these Mule apps show at `200` — the ratio tracks the
batch size, not some fixed property of the engine.
