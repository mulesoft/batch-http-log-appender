# Troubleshooting and diagnostics

When logs aren't arriving, the first question is always *"is the appender failing, and why?"*
The appender answers that itself. It logs its own health in two places: through the logger
`com.mulesoft.support.batchhttp.diagnostics.<appender name>`, which reaches your application
log, and to Log4j2's `StatusLogger`, which prints on the console. This page covers where those
messages appear, which level to set, what each message means, and the counters behind them.

Three settings control them: the level of the diagnostics logger,
[`diagnosticsLevel`](configuration.md#opt-diagnosticslevel) for the console, and
[`statsIntervalMillis`](configuration.md#opt-statsintervalmillis) for the heartbeat message.
Vendor-specific symptoms (rejected keys, wrong endpoints) are on the vendor pages.

On this page:

- [Start here](#start-here)
- [Where the messages go](#where-the-messages-go)
- [Setting the level on your platform](#setting-the-level-on-your-platform)
- [Which level to choose: keeping the noise down](#which-level-to-choose-keeping-the-noise-down)
- [How the diagnostics logger works](#how-the-diagnostics-logger-works)
- [What gets logged](#what-gets-logged)
- ["I see no spill files — is it broken?"](#i-see-no-spill-files--is-it-broken)
- [Programmatic counters](#programmatic-counters)

## Start here

1. Look in the application log for lines from the logger
   `com.mulesoft.support.batchhttp.diagnostics.<appender name>`, prefixed
   `batch-http[<sinkName>]`, and match them against [What gets logged](#what-gets-logged). With
   the root logger at `INFO` (the usual setting), failures are already there, and so is the
   heartbeat message once [`statsIntervalMillis`](configuration.md#opt-statsintervalmillis) is
   set.
2. Need more or less? Pick a level from
   [Which level to choose](#which-level-to-choose-keeping-the-noise-down) and set it as shown in
   [Setting the level on your platform](#setting-the-level-on-your-platform).
3. To confirm delivery positively, set
   [`statsIntervalMillis`](configuration.md#opt-statsintervalmillis) and watch `sentRecords` climb
   in the heartbeat message.
4. If the appender does not start at all, the reason is only on the console: keep
   `status="WARN"` or finer on the configuration root, never `status="OFF"` (see
   [Where the messages go](#where-the-messages-go)).

## Where the messages go

While the appender runs, each message goes to two channels, each with its own level control:

| | Diagnostics logger | Console (`StatusLogger`) |
| --- | --- | --- |
| Logger | `com.mulesoft.support.batchhttp.diagnostics.<appender name>` | Log4j2's internal status logger |
| Ends up in | Every appender on the root logger (or on the logger you route it to): the application log, Runtime Manager logs, your file appender | The runtime's stderr/console log |
| Level set by | The logger's level, inherited from root unless you declare it | [`diagnosticsLevel`](configuration.md#opt-diagnosticslevel) and `<Configuration status>`; both must allow it |
| Default | Root's level, usually `INFO`: failures, plus the heartbeat message if enabled | `WARN`: failures only |
| Covers | Messages raised while the appender runs | Everything, including startup failures and the final stats line at shutdown |
| On CloudHub 2.0 | Shown in Runtime Manager logs | Not shown: the platform does not collect the console |

The diagnostics logger is the channel to read on any platform that shows application logs but
not the console. The console is the safety net for what happens before the appender starts.

### The console copy

`StatusLogger` output is controlled by the `status` attribute on the configuration root and goes
to the Log4j2 status console (and any status listeners):

```xml
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2"/>
```

In Mule the status output appears on the runtime's own stderr/console log — the same place
Log4j2 reports a bad `log4j2.xml`. Set `status="INFO"` and `diagnosticsLevel="INFO"` to also see
the heartbeat message there. Where the console is not shown, as on CloudHub 2.0, read the
diagnostics logger instead.

<details>
<summary>Why keep <code>StatusLogger</code> as well</summary>

`StatusLogger` is the out-of-band channel Log4j2 components use to report status. It never
re-enters the appender pipeline and works before the configuration has started, so it is the only
place a startup failure (a missing API key, an unknown vendor) can be reported. The diagnostics
logger covers the running appender, which is where almost every failure happens.

</details>

This is **separate** from the `<BatchHttp>` appender's own
[`diagnosticsLevel`](configuration.md#opt-diagnosticslevel):

- `status` is the floor for *all* Log4j2 internal logging;
- `diagnosticsLevel` gates *this appender's* messages within that;
- neither gates the logger copy, which follows its logger level.

> [!WARNING]
> **Avoid `status="OFF"` — it disables the console safety net.** With status output off the
> console shows no dropped-batch warning, no 4xx rejection, no queue-overflow notice, and no
> startup credential failure. The logger copy still reports the first three while the appender
> runs, but the startup credential failure and the messages raised before start go only to
> `StatusLogger`.

Turning status off makes the appender fail exactly as silently as it did before the v0.1.3
self-diagnostics were added. `status="WARN"` is the minimum that keeps the failure reporting:

- the stock Mule demos and the Java example use `status="WARN"`;
- the batch Mule demos use `status="INFO"` so their heartbeat message prints too.

If your concern is status-log noise rather than this appender, don't silence `status` globally.
Either leave `diagnosticsLevel` at its `WARN` default (which already limits the appender to
failures only), or attach your own `StatusListener` to route the messages elsewhere. This is a
property of Log4j2's status plumbing rather than something the appender can enforce, and it is
not covered by a test, so treat it as an operational recommendation.

## Setting the level on your platform

The level is an ordinary logger level: set it wherever you already set logging levels for your
application. Use the parent name for every `<BatchHttp>` appender, or add `.<appender name>`
for one of them.

| Platform | Where to set it | Notes |
| --- | --- | --- |
| CloudHub 2.0 | Runtime Manager → the application → **Settings → Logging**: package `com.mulesoft.support.batchhttp.diagnostics`, level of your choice | Same as `anypoint-cli-v4 runtime-mgr application modify --scopeLoggingConfig "com.mulesoft.support.batchhttp.diagnostics:DEBUG"` (note 1) |
| Runtime Fabric | Runtime Manager → the application → **Logging** tab: package name and level | `TRACE` only through the **Properties** tab; don't use both for the same package |
| CloudHub 1.0 | Runtime Manager → the application → **Settings → Logging** | Not verified with a custom `log4j2.xml` (note 2) |
| Anything else (standalone Mule, plain Java, Kubernetes) | `log4j2.xml`, below | A level set in `log4j2.xml` also works on the platforms above |

1. Runtime Manager on CloudHub 2.0 accepts `DEBUG`, `INFO`, `WARN` and `ERROR`, not `OFF`; the
   quietest it offers is `ERROR` (drops and rejections only). Each change creates a new
   deployment version and replaces the replicas one by one, like any redeploy: the old replica
   drains its queue within
   [`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis), and a spill
   backlog on its disk is lost with it. Change the level when the intake is healthy, not in the
   middle of an outage you are trying to ride out.
2. On CloudHub 1.0 this appender runs with **Disable CloudHub logs** and the application's own
   `log4j2.xml`. We have not verified that the Runtime Manager level applies to such an
   application; if it does not take effect, declare the logger in `log4j2.xml`.

In `log4j2.xml`, declare the logger next to your others. No `<AppenderRef>` is needed: the events
flow to the root logger's appenders.

```xml
<Loggers>
    <!-- every <BatchHttp>: OFF, ERROR, WARN, INFO (+ heartbeat), DEBUG (+ retries) -->
    <AsyncLogger name="com.mulesoft.support.batchhttp.diagnostics" level="INFO"/>
    <!-- one appender, by its name="..." -->
    <AsyncLogger name="com.mulesoft.support.batchhttp.diagnostics.elastic" level="DEBUG"/>
    <AsyncRoot level="INFO"> ... </AsyncRoot>
</Loggers>
```

With nothing declared, the level is the root logger's, so `<Root level="INFO">` already shows
failures and, with `statsIntervalMillis` set, the heartbeat message. Which level to declare
instead is covered next.

## Which level to choose: keeping the noise down

A healthy appender logs nothing but its heartbeat message, if you enabled one. Messages appear
when the intake fails, then one per affected batch, so an outage is where the volume comes from.
Pick the level for how much of that you want to read:

| Situation | Diagnostics logger level | Log messages you see |
| --- | --- | --- |
| Production, recommended | `WARN` | Nothing while healthy. Errors: records lost for good (`dropped …` after a 4xx rejection, refused documents, a full spill buffer). Warnings: `spilled … to disk for retry`, `queue full`, `spill buffer full`, damaged spill segments, TLS checks disabled |
| Production, with proof of delivery | `INFO` | Everything above, plus the heartbeat message `stats accepted=… sentRecords=… dropped=… queueDepth=…`, one per appender per [`statsIntervalMillis`](configuration.md#opt-statsintervalmillis) (`300000` = every 5 minutes, 288 a day), and recovery notices (`recovered … spill segment(s)`, lock takeover) |
| Busy platform, log volume is billed | `ERROR` | Errors only: records lost for good. Outages that spill to disk and recover stay quiet |
| Investigating a problem | `DEBUG`, on one appender only | Everything above, plus `send attempt N/M failed (…), retrying` for each failed attempt; with `statsIntervalMillis="60000"` a heartbeat message every minute. Set it back afterwards |

`TRACE` and `ALL` show the same as `DEBUG`. `FATAL` and `OFF` show nothing, since the appender
logs nothing at `FATAL`.

- **If you set nothing, you get `INFO`.** The diagnostics logger inherits the root logger's
  level, usually `INFO`: failures, recovery notices and, if `statsIntervalMillis` is set, the
  heartbeat message. Declare `WARN` to keep the failures without the heartbeat message.
- **During an outage, `WARN` is the noisy level.** With spill on, each batch that fails its
  retries logs one `spilled N record(s) to disk` line, per flusher thread, for as long as the
  intake is down; the queue overflow on top of that is coalesced into one line per 5 seconds.
  `ERROR` hides both and still reports any record that is actually lost.
- **`DEBUG` multiplies the failure lines.** It adds one line per retry attempt, up to
  [`maxRetries`](configuration.md#opt-maxretries) per batch. Set it on one appender
  (`com.mulesoft.support.batchhttp.diagnostics.<appender name>`), not the parent, and only while
  you are looking.
- **Use `ERROR` rather than `OFF`.** `OFF` also hides the 4xx rejections that tell you an API key
  is wrong, and CloudHub 2.0 Runtime Manager does not offer it.
- **Avoid printing each line twice.** Where the console and the application log are the same
  stream (a plain Java app with a console appender on root), set
  [`diagnosticsLevel`](configuration.md#opt-diagnosticslevel)`="OFF"` so only the logger copy
  remains. Keep `<Configuration status="WARN">`: the startup credential failure is reported
  there either way.
- **Changing the level on CloudHub 2.0 redeploys the application** (note 1 above). Choose the
  production level up front rather than switching during an incident.

## How the diagnostics logger works

- **Name.** Each appender logs through `com.mulesoft.support.batchhttp.diagnostics.<appender
  name>`, using the `name` attribute of its `<BatchHttp>` element. The messages keep their
  `batch-http[<sinkName>]` prefix, so a line also says which vendor sink raised it.
- **Lookup.** The logger is looked up in the appender's own Log4j2 configuration, not through
  the global `LogManager`. In Mule every application has its own logger context, so the
  messages land in the application that owns the appender, with that application's levels and
  appenders. The lookup is done on the first message after start and then cached.
- **When it is active.** From when the appender starts until it begins to stop. Before that,
  the configuration that will hold the logger is still being built; after that, the platform
  may already be removing its own appenders. Messages outside that window go to the console
  only:
  - at startup: the startup credential failure, spill recovery (`recovered …`, `startup
    reconcile evicted …`, `spillRecoveryMode=DELETE discarded …`), a spill directory locked by
    another instance, a spill buffer that could not initialise, and the two TLS warnings;
  - at shutdown: the `shutdown: spilled …` and `dropped … still queued at shutdown` lines and
    the final `stats` line.

  Everything raised while the appender runs (drops, retries, queue and spill-buffer overflow,
  replay damage, lock takeover, the heartbeat message) goes to both. To confirm a crash
  recovery where the console is not shown, look at `adoptedRecords` in the first heartbeat
  message after start.
- **No loop.** Every `<BatchHttp>` appender discards events from
  `com.mulesoft.support.batchhttp.diagnostics` and its children, its own and other appenders',
  before they reach the queue. A failing intake therefore cannot feed its own error messages
  back into itself, even when the appender sits on the root logger. A message is also never
  logged while another one is being logged on the same thread.
- **Every vendor.** The messages come from the engine, not the vendor sink, so this works the
  same for `datadog`, `splunk`, `newrelic`, `dynatrace`, `elasticsearch` and `generic`; the test
  suite runs all six side by side on one root logger.
- **Volume.** The heartbeat message is one line per `statsIntervalMillis`. Failure lines are
  one per affected batch, except queue full and spill buffer full, which are coalesced to at
  most one every 5 seconds; see
  [Which level to choose](#which-level-to-choose-keeping-the-noise-down).

## What gets logged

All messages are prefixed `batch-http[<sinkName>]` so you can grep one appender out of a busy
log. The tables below list every message with its level; a level shows its own rows and every
row above it in severity (see
[Which level to choose](#which-level-to-choose-keeping-the-noise-down)). The level works the same
on both channels: the diagnostics logger's level in your log,
[`diagnosticsLevel`](configuration.md#opt-diagnosticslevel) on the console.

The retry count and drain timeout in the sample lines are whatever
[`maxRetries`](configuration.md#opt-maxretries) and
[`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis) are set to. The
queue and spill limits are [`queueCapacity`](configuration.md#opt-queuecapacity),
[`overflowPolicy`](configuration.md#opt-overflowpolicy),
[`maxTotalSpillBytes`](configuration.md#opt-maxtotalspillbytes) and
[`minFreeDiskBytes`](configuration.md#opt-minfreediskbytes). What each failure path does is
explained in [resilience.md](resilience.md).

### Rejected and failed sends

| Situation | Level | What you see |
| --- | --- | --- |
| Batch rejected with a 4xx (bad API key, wrong endpoint, payload too large) | `ERROR` | `dropped N record(s) — endpoint rejected the batch (HTTP 403); check credentials/endpoint, retrying will not help` |
| Bulk intake (Elasticsearch) answered `200` but refused some documents for good (mapping conflict, …) | `ERROR` | `dropped N of M record(s) — the intake accepted the request but refused them (document_parsing_exception: …); retrying will not help` |
| Send failed after all retries, spill **on** | `WARN` | `send failed after 3 retries (HTTP 503) — spilled N record(s) to disk for retry` |
| Send failed after all retries, spill **on** but the buffer refused (some of) them | `ERROR` | `dropped N record(s) — send failed after 3 retries (HTTP 503) and the spill buffer refused them (counted in spillStats().droppedRecords)` |
| Send failed after all retries, spill **off** | `ERROR` | `dropped N record(s) — send failed after 3 retries (…) and spill is disabled` |
| Each retry attempt | `DEBUG` | `send attempt 2/4 failed (HTTP 503), retrying` |

### Queue and spill buffer capacity

| Situation | Level | What you see |
| --- | --- | --- |
| Queue full (endpoint not keeping up) | `WARN` | `queue full (capacity=…, policy=DROP_NEWEST): N record(s) dropped, M diverted to spill in the last 5s — the endpoint is not keeping up` (note 1) |
| Spill buffer full — oldest segment evicted / new record refused | `WARN` | `spill buffer full (maxTotalSpillBytes=…, minFreeDiskBytes=…): evicted N oldest segment(s) holding M record(s), refused K new record(s) since the last report — those logs are lost` (note 2) |

1. Coalesced once per 5s.
2. Coalesced, at most once per 5s plus once at shutdown.

### Startup and crash recovery

| Situation | Level | What you see |
| --- | --- | --- |
| Startup reconcile evicted a previous run's backlog | `WARN` | `startup reconcile evicted N oldest spill segment(s) holding M record(s) (B bytes) left by a previous run to satisfy maxTotalSpillBytes=…/minFreeDiskBytes=… — those logs are lost` |
| `spillRecoveryMode=DELETE` discarded previous-run segments | `INFO` | `spillRecoveryMode=DELETE discarded N segment(s) (M record(s), B bytes) left by a previous run` |
| Segments from a previous run / crash adopted | `INFO` | `recovered N spill segment(s) (B bytes, M record(s)) left by a previous run (spillRecoveryMode=ADOPT) — replaying now` |
| Spill directory locked by another live instance at startup | `WARN` | `spill directory <dir> is locked by another live instance with spillInstanceId=<id>; N existing segment(s) left untouched (ownership is retried every spillReplayInterval) — give each running instance a distinct spillInstanceId` |
| That lock was released later and taken over | `INFO` | `spill directory <dir> is no longer locked by another instance — took it over (N segment(s) adopted for replay)` |
| Replayed segment had damaged bytes | `WARN` | `replayed spill segment <file>: R record(s) delivered, B corrupt byte(s) skipped (torn tail or damaged record)` |
| Spill segment unreadable (I/O error) | `WARN` | `spill segment <file> could not be read (<IOException>) — skipped this pass, will retry` (once per segment per run) |
| Spill buffer could not initialise | `ERROR` | `spill disabled — could not initialise spill buffer: AccessDeniedException… (live shipping continues; failed batches will be dropped, not buffered)` |

### Shutdown

| Situation | Level | What you see |
| --- | --- | --- |
| Records still queued at shutdown, spill **on** (`spillRecoveryMode=ADOPT`) | `WARN` | `shutdown: spilled K of N record(s) still queued after shutdownDrainTimeout=5000ms to disk — they will be replayed on the next start` |
| Records still queued at shutdown, spill **off** (or `spillRecoveryMode` `DELETE`/`IGNORE`) | `ERROR` | `dropped N record(s) still queued at shutdown — not sent within shutdownDrainTimeout=5000ms and spill is disabled` (note 1) |
| A record offered while the appender was stopping missed the final drain | `ERROR` | `dropped 1 record(s) offered while the appender was stopping — accepted after the final shutdown drain` (note 2) |
| Records still queued at shutdown, spill refused them | `ERROR` | `dropped M record(s) still queued at shutdown — the spill buffer refused them (counted in spillStats().droppedRecords)` (note 3) |

1. Or `… and spillRecoveryMode=DELETE would not replay them on the next start`.
2. With `ADOPT` spill the record is spilled instead.
3. Or `… — could not be spilled within the shutdown budget`.

### Unexpected errors and the heartbeat message

| Situation | Level | What you see |
| --- | --- | --- |
| A live batch threw while being serialized/sent (sink bug) | `ERROR` | `dropped N record(s) — unexpected error while batching: …` (only the records the intake had not already stored) |
| A replayed sub-batch threw while being re-shipped | `ERROR` | `dropped N replayed record(s) — unexpected error while re-shipping: …` |
| Periodic heartbeat message (`statsIntervalMillis > 0`) | `INFO` | `stats accepted=… sentRecords=… sentBatches=… dropped=… failedBatches=… queueDepth=… [duplicatesSuppressed=…] spill{onDiskBytes=… segments=… spilledRecords=… droppedRecords=… evictedSegments=… evictedRecords=… adoptedRecords=… corruptBytesSkipped=…}` |

## "I see no spill files — is it broken?"

No — that's the **healthy** state. The spill directory is created eagerly at startup (so it exists
as soon as the appender does, spill being on by default), but segment files are written only on a
failure path:

- a batch that fails after all retries;
- queue overflow with [`spillOnOverflow`](configuration.md#opt-spillonoverflow);
- records still queued when the appender stops after
  [`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis) (a slow endpoint
  at redeploy).

An empty `${java.io.tmpdir}/batch-http-spill/<sink>` under load means the endpoint is accepting
your logs and there is nothing to buffer. Where the directory is when
[`spillDirectory`](configuration.md#opt-spilldirectory) is unset is explained in
[resilience.md](resilience.md#it-is-on-by-default--read-this-before-upgrading).

To confirm delivery positively rather than by the absence of spill files, set
[`statsIntervalMillis`](configuration.md#opt-statsintervalmillis) and watch `sentRecords` climb in
the heartbeat message, or check the intake (e.g. Datadog → Logs) directly.

> [!TIP]
> To *prove* the safety net works, point at a bad key/endpoint and watch segment files appear,
> then drain and delete once connectivity returns.

## Programmatic counters

Beyond the logs, `BatchingLogShipper.stats()` and `spillStats()` expose live counters for wiring
into JMX or a metrics system. The heartbeat message is just these, logged on a timer.

| Method | Counters |
| --- | --- |
| `stats()` | `accepted`, `sentRecords`, `dropped`, `queueDepth` |
| `spillStats()` | `spilledRecords`, `droppedRecords`, `segmentCount`, `evictedSegments`, `evictedRecords`, `adoptedRecords`, `corruptBytesSkipped` |

`evictedSegments` also counts segments discarded by `spillRecoveryMode=DELETE`. See
[the invariant](memory-safety.md#the-invariant-to-check) in memory-safety.md for how the counters
reconcile.

## See also

- [resilience.md](resilience.md) — retry, shutdown, disk spill and crash recovery.
- [memory-safety.md](memory-safety.md) — using the counters to prove bounded memory and full drain.
- [configuration.md](configuration.md) — every `<BatchHttp>` option.
- [mule.md](mule.md#injecting-secrets-api-keys--tokens) — a key that never reached Log4j2 is the
  most common cause of a 4xx rejection in a Mule app.

---

[Docs index](README.md) · [Configuration reference](configuration.md) ·
[Resilience](resilience.md) · [Memory safety](memory-safety.md) · [Mule guide](mule.md)
