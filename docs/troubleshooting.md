# Troubleshooting & diagnostics

When logs aren't arriving, the first question is always *"is the appender failing, and
why?"* The engine answers that itself — it logs its own health to Log4j2's **`StatusLogger`**,
the out-of-band channel Log4j2 components use to report status. It deliberately does **not**
log through an ordinary logger: this appender is often wired to the root logger, so routing
its diagnostics through a normal `Logger` would feed them straight back into itself — an
endpoint outage would then amplify into a feedback loop. `StatusLogger` never re-enters the
appender pipeline, so it is safe by construction.

This page covers where those messages appear, what each one means, and the counters behind them.
The two options involved are [`diagnosticsLevel`](configuration.md#opt-diagnosticslevel) and
[`statsIntervalMillis`](configuration.md#opt-statsintervalmillis); vendor-specific symptoms
(rejected keys, wrong endpoints) are on the vendor pages.

- [Where the messages go](#where-the-messages-go)
- [What gets logged](#what-gets-logged)
- ["I see no spill files — is it broken?"](#i-see-no-spill-files--is-it-broken)
- [Programmatic counters](#programmatic-counters)

## Where the messages go

`StatusLogger` output is controlled by the `status` attribute on the configuration root and
goes to the Log4j2 status console (and any status listeners):

```xml
<Configuration status="WARN" packages="com.mulesoft.support.batchhttp.log4j2"/>
```

In Mule/CloudHub the status output appears on the runtime's own stderr/console log — the same
place Log4j2 reports a bad `log4j2.xml`. Set `status="INFO"` to also see the heartbeat. This is
**separate** from the `<BatchHttp>` appender's own
[`diagnosticsLevel`](configuration.md#opt-diagnosticslevel): `status` is the floor for *all*
Log4j2 internal logging, `diagnosticsLevel` gates *this appender's* messages within that.

> **Avoid `status="OFF"` — it disables the whole safety net.** `StatusLogger` is the *only*
> channel this appender has for reporting on itself, so turning status output off means no
> dropped-batch warnings, no 4xx rejection, no queue-overflow notice, and no startup
> credential failure: the appender goes back to failing exactly as silently as it did before
> the v0.1.3 self-diagnostics were added, and the only symptom is logs not arriving.
> `status="WARN"` is the minimum that keeps the failure reporting — it is what the stock
> Mule demos and the Java example use, while the batch Mule demos use `status="INFO"` so
> their heartbeat surfaces too. If your concern is status-log noise rather than this
> appender, leave `diagnosticsLevel` at its `WARN` default (which already limits the appender
> to failures only) or attach your own `StatusListener` to route the messages elsewhere,
> instead of silencing `status` globally. Note this is a property of Log4j2's status plumbing
> rather than something the appender can enforce, and it is not covered by a test, so treat
> it as an operational recommendation.

## What gets logged

| Situation | Level | What you see |
|-----------|-------|--------------|
| Batch rejected with a 4xx (bad API key, wrong endpoint, payload too large) | `ERROR` | `dropped N record(s) — endpoint rejected the batch (HTTP 403); check credentials/endpoint, retrying will not help` |
| Bulk intake (Elasticsearch) answered `200` but refused some documents for good (mapping conflict, …) | `ERROR` | `dropped N of M record(s) — the intake accepted the request but refused them (document_parsing_exception: …); retrying will not help` |
| Send failed after all retries, spill **on** | `WARN` | `send failed after 3 retries (HTTP 503) — spilled N record(s) to disk for retry` |
| Send failed after all retries, spill **on** but the buffer refused (some of) them | `ERROR` | `dropped N record(s) — send failed after 3 retries (HTTP 503) and the spill buffer refused them (counted in spillStats().droppedRecords)` |
| Send failed after all retries, spill **off** | `ERROR` | `dropped N record(s) — send failed after 3 retries (…) and spill is disabled` |
| Queue full (endpoint not keeping up) | `WARN` | coalesced once per 5s: `queue full (capacity=…, policy=DROP_NEWEST): N record(s) dropped, M diverted to spill in the last 5s — the endpoint is not keeping up` |
| Spill buffer full — oldest segment evicted / new record refused | `WARN` | coalesced, at most once per 5s plus once at shutdown: `spill buffer full (maxTotalSpillBytes=…, minFreeDiskBytes=…): evicted N oldest segment(s) holding M record(s), refused K new record(s) since the last report — those logs are lost` |
| Startup reconcile evicted a previous run's backlog | `WARN` | `startup reconcile evicted N oldest spill segment(s) holding M record(s) (B bytes) left by a previous run to satisfy maxTotalSpillBytes=…/minFreeDiskBytes=… — those logs are lost` |
| `spillRecoveryMode=DELETE` discarded previous-run segments | `INFO` | `spillRecoveryMode=DELETE discarded N segment(s) (M record(s), B bytes) left by a previous run` |
| Segments from a previous run / crash adopted | `INFO` | `recovered N spill segment(s) (B bytes, M record(s)) left by a previous run (spillRecoveryMode=ADOPT) — replaying now` |
| Spill directory locked by another live instance at startup | `WARN` | `spill directory <dir> is locked by another live instance with spillInstanceId=<id>; N existing segment(s) left untouched (ownership is retried every spillReplayInterval) — give each running instance a distinct spillInstanceId` |
| That lock was released later and taken over | `INFO` | `spill directory <dir> is no longer locked by another instance — took it over (N segment(s) adopted for replay)` |
| Replayed segment had damaged bytes | `WARN` | `replayed spill segment <file>: R record(s) delivered, B corrupt byte(s) skipped (torn tail or damaged record)` |
| Spill segment unreadable (I/O error) | `WARN` | once per segment per run: `spill segment <file> could not be read (<IOException>) — skipped this pass, will retry` |
| A live batch threw while being serialized/sent (sink bug) | `ERROR` | `dropped N record(s) — unexpected error while batching: …` (only the records the intake had not already stored) |
| A replayed sub-batch threw while being re-shipped | `ERROR` | `dropped N replayed record(s) — unexpected error while re-shipping: …` |
| Records still queued at shutdown, spill **on** (`spillRecoveryMode=ADOPT`) | `WARN` | `shutdown: spilled K of N record(s) still queued after shutdownDrainTimeout=5000ms to disk — they will be replayed on the next start` |
| Records still queued at shutdown, spill **off** (or `spillRecoveryMode` `DELETE`/`IGNORE`) | `ERROR` | `dropped N record(s) still queued at shutdown — not sent within shutdownDrainTimeout=5000ms and spill is disabled` (or `… and spillRecoveryMode=DELETE would not replay them on the next start`) |
| A record offered while the appender was stopping missed the final drain | `ERROR` | `dropped 1 record(s) offered while the appender was stopping — accepted after the final shutdown drain` (with `ADOPT` spill it is spilled instead) |
| Records still queued at shutdown, spill refused them | `ERROR` | `dropped M record(s) still queued at shutdown — the spill buffer refused them (counted in spillStats().droppedRecords)` (or `… — could not be spilled within the shutdown budget`) |
| Spill buffer could not initialise | `ERROR` | `spill disabled — could not initialise spill buffer: AccessDeniedException… (live shipping continues; failed batches will be dropped, not buffered)` |
| Each retry attempt | `DEBUG` | `send attempt 2/4 failed (HTTP 503), retrying` |
| Periodic heartbeat (`statsIntervalMillis > 0`) | `INFO` | `stats accepted=… sentRecords=… sentBatches=… dropped=… failedBatches=… queueDepth=… spill{onDiskBytes=… segments=… spilledRecords=… droppedRecords=… evictedSegments=… evictedRecords=… adoptedRecords=… corruptBytesSkipped=…}` |

The retry count and drain timeout in these sample lines are whatever
[`maxRetries`](configuration.md#opt-maxretries) and
[`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis) are set to; the
queue and spill limits are [`queueCapacity`](configuration.md#opt-queuecapacity),
[`overflowPolicy`](configuration.md#opt-overflowpolicy),
[`maxTotalSpillBytes`](configuration.md#opt-maxtotalspillbytes) and
[`minFreeDiskBytes`](configuration.md#opt-minfreediskbytes). What each failure path does is
explained in [resilience.md](resilience.md).

[`diagnosticsLevel`](configuration.md#opt-diagnosticslevel) is the verbosity dial: `WARN` (the
default) shows every failure above; `INFO` adds the heartbeat and the `INFO` rows above
(crash-recovery adoption, `DELETE` discard, lock takeover); `DEBUG` adds per-attempt retry
lines. All messages are prefixed `batch-http[<sinkName>]` so you can grep one appender out of a
busy status log.

## "I see no spill files — is it broken?"

No — that's the **healthy** state. The spill directory is created eagerly at startup (so it
exists as soon as the appender does, spill being on by default), but segment files are written
**only on a failure path**: a batch that fails after all retries, queue overflow with
[`spillOnOverflow`](configuration.md#opt-spillonoverflow), or records still queued when the
appender stops after [`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis)
(a slow endpoint at redeploy). An empty `${java.io.tmpdir}/batch-http-spill/<sink>` under load
means the endpoint is accepting your logs and there is nothing to buffer (where the directory is
when [`spillDirectory`](configuration.md#opt-spilldirectory) is unset is explained in
[resilience.md](resilience.md#it-is-on-by-default--read-this-before-upgrading)). To confirm
delivery positively rather than by the absence of spill files, set
[`statsIntervalMillis`](configuration.md#opt-statsintervalmillis) and watch `sentRecords` climb
in the heartbeat — or check the intake (e.g. Datadog → Logs) directly. To *prove* the safety net
works, point at a bad key/endpoint and watch segment files appear, then drain and delete once
connectivity returns.

## Programmatic counters

Beyond the logs, `BatchingLogShipper.stats()` and `spillStats()` expose live counters
(`accepted`/`sentRecords`/`dropped`/`queueDepth`, `spilledRecords`/`droppedRecords`/`segmentCount`,
`evictedSegments`/`evictedRecords`/`adoptedRecords`/`corruptBytesSkipped`) for wiring into JMX
or a metrics system. `evictedSegments` also counts segments discarded by
`spillRecoveryMode=DELETE`. The heartbeat line is just these rendered to the status
log on a timer. See [the invariant](memory-safety.md#the-invariant-to-check) in
memory-safety.md for how they reconcile.

## See also

- [resilience.md](resilience.md) — retry, shutdown, disk spill and crash recovery.
- [memory-safety.md](memory-safety.md) — using the counters to prove bounded memory and full drain.
- [configuration.md](configuration.md) — every `<BatchHttp>` option.
- [mule.md](mule.md#injecting-secrets-api-keys--tokens) — a key that never reached Log4j2 is the
  most common cause of a 4xx rejection in a Mule app.
- [Docs index](README.md)
