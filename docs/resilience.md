# Resilience, disk spill and crash recovery

What the appender does when the intake is slow, failing or unreachable, and when the process
itself dies: retry and backoff, graceful shutdown, the disk-spill buffer (on by default), and
recovery of spilled records after a runtime or JVM crash. Every option named here is defined in
[configuration.md](configuration.md); how the engine reports these events is in
[troubleshooting.md](troubleshooting.md).

- [Resilience at a glance](#resilience-at-a-glance)
- [Disk-spill buffer](#disk-spill-buffer)
  - [It is on by default — read this before upgrading](#it-is-on-by-default--read-this-before-upgrading)
  - [How the buffer works](#how-the-buffer-works)
  - [Replay is at-least-once, not exactly-once](#replay-is-at-least-once-not-exactly-once)
  - [Segment sizing](#segment-sizing-is-fuzzy-and-never-splits-a-record)
  - [Crash-corruption tolerance](#crash-corruption-tolerance-both-formats)
  - [Spill options](#spill-options)
- [Crash recovery (runtime / JVM death)](#crash-recovery-runtime--jvm-death)
- [Crash-resilience behavior](#crash-resilience-behavior)

## Resilience at a glance

- **Non-blocking by default** — logging never stalls the app; under overload the
  configured [`overflowPolicy`](configuration.md#opt-overflowpolicy) applies.
- **Retry with exponential backoff + jitter** on retryable failures (429/5xx/408/network).
  Every other status (other 4xx, and 1xx/3xx: redirects are not followed) is terminal and is not retried. The schedule is set by
  [`maxRetries`](configuration.md#opt-maxretries),
  [`initialRetryBackoffMillis`](configuration.md#opt-initialretrybackoffmillis) and
  [`maxRetryBackoffMillis`](configuration.md#opt-maxretrybackoffmillis); each attempt is bounded
  by [`requestTimeoutMillis`](configuration.md#opt-requesttimeoutmillis).
- **`Retry-After` is honored.** When a retryable response carries the header, the
  server-supplied delay wins over the computed backoff — a throttled intake knows when its
  window reopens, and retrying sooner just earns another 429. Both RFC 9110 forms are
  accepted (delta-seconds and HTTP-date). The value is clamped to
  [`maxRetryBackoffMillis`](configuration.md#opt-maxretrybackoffmillis), so a hostile or absurd
  delay cannot park a flusher, and an absent, malformed, or already-elapsed value falls back to
  the exponential schedule.
- **Graceful shutdown** — `appender.stop()` drains and flushes the queue within
  [`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis). After that,
  whatever is still queued is written to the spill buffer (replayed on the next start under
  `spillRecoveryMode=ADOPT`), or counted as dropped and logged at ERROR when spill is off or
  [`spillRecoveryMode`](configuration.md#opt-spillrecoverymode) is `DELETE`/`IGNORE` (the next
  start would not replay them). `close()` itself never makes another HTTP attempt, so it stays
  bounded (roughly `shutdownDrainTimeoutMillis` + 2 s + the spill write).
- **Message size capping** — a single oversized message is truncated to
  [`maxMessageBytes`](configuration.md#opt-maxmessagebytes) on a character boundary before it
  can bloat the queue, the request, or a spill segment.
- **Disk-spill buffer (on by default)** — batches that fail after retries
  (or records the queue would drop on overflow) are written to rotating segment
  files on disk instead of being lost. A background thread re-ships them when the
  endpoint recovers, and segments left by a previous run/crash are replayed on
  startup. The buffer is bounded and free-space-aware (drop-oldest eviction, with the
  evicted records counted and reported), so it never exhausts the filesystem. Both on-disk
  formats are **corruption-tolerant**: a record damaged by an improperly-closed journal is
  skipped and replay resumes from the next intact one. Segments survive an abrupt
  runtime/JVM death (`kill -9`, OOM kill, a crashed worker) and are replayed as soon as the
  next start opens the same [`spillDirectory`](configuration.md#opt-spilldirectory) with the
  same [`spillInstanceId`](configuration.md#opt-spillinstanceid); only intact records are
  re-sent. Replay is **at-least-once** — a segment that fails partway through (or was being
  replayed when the process died) is retried whole, so a record that transits the buffer can be
  delivered twice. See [Disk-spill buffer](#disk-spill-buffer) and
  [Crash recovery](#crash-recovery-runtime--jvm-death) below.
- **Observability** — `BatchingLogShipper.stats()` exposes accepted / dropped /
  sent / failed counts and queue depth; `spillStats()` exposes spilled / dropped /
  evicted (segments and records) / adopted / segment counts — plus `corruptBytesSkipped`
  (bytes discarded recovering a corrupted journal) — when spill is enabled. See
  [troubleshooting.md](troubleshooting.md#programmatic-counters) and the reconciliation in
  [memory-safety.md](memory-safety.md#the-invariant-to-check).

## Disk-spill buffer

A durability safety net that is **on by default**
([`spillEnabled`](configuration.md#opt-spillenabled)). Records flow through the in-memory queue
exactly as normal; they are written to disk **only** when (a) a batch fails after retries are
exhausted, (b) the in-memory queue would otherwise drop a record on overflow, or (c) records are
still queued when the appender stops after
[`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis) — this last case
happens regardless of [`spillOnOverflow`](configuration.md#opt-spillonoverflow), but only with
`spillRecoveryMode=ADOPT` (otherwise those records are counted as dropped). The happy path does
**zero** disk I/O.

### It is on by default — read this before upgrading

`spillEnabled` used to default to `false`. It now defaults to `true`, so **an app that
upgrades the jar without changing its configuration starts writing log payloads to local
storage.** That is a deliberate trade — buffered logs survive an intake outage instead of
being dropped — but it is not free, and there are four consequences worth understanding.

**1. Where it writes when you have not said.** With no
[`spillDirectory`](configuration.md#opt-spilldirectory), the engine derives one per sink in
`BatchingLogShipper.defaultSpillDir`: it reads the JVM's `java.io.tmpdir` (falling back to
`/tmp` only if the JVM reports none), then appends `batch-http-spill` and a filename-safe form
of the sink's name. So a Datadog appender ends up in
`${java.io.tmpdir}/batch-http-spill/Datadog/`, a generic one in something like
`${java.io.tmpdir}/batch-http-spill/Generic_logs.example.com_/`. On a Mule/CloudHub worker
`java.io.tmpdir` is the container's temp directory; on Windows it is `%TEMP%\...`. The
directory is created eagerly at startup, so it exists as soon as the appender does, whether
or not anything is ever written into it.

**2. On many container platforms `/tmp` is memory-backed, and then "disk" buffer means
RAM.** Where the temp directory is a `tmpfs` (a common Kubernetes / container-runtime
default, including some Mule deployment topologies), every byte the spill buffer writes is a
byte of the container's memory — and it counts against the memory limit that gets the
container OOM-killed. On a memory-constrained worker that turns a durability feature into an
out-of-memory risk: the endpoint goes down, the buffer fills with up to
[`maxTotalSpillBytes`](configuration.md#opt-maxtotalspillbytes) of log data, and the pod dies of
the thing that was supposed to protect it. If your `/tmp` is memory-backed, either point
`spillDirectory` at real storage (a mounted volume with a real filesystem behind it) or set
`spillEnabled="false"` and accept drops on failure:

```xml
<BatchHttp name="datadog" vendor="datadog" apiKey="${env:DD_API_KEY}"
           spillDirectory="/var/log/batch-http-spill"/>

<BatchHttp name="datadog" vendor="datadog" apiKey="${env:DD_API_KEY}"
           spillEnabled="false"/>
```

**3. It changes what queue overflow means.** `spillOnOverflow` defaults to `true`, so now
that spill is also on by default, a record the queue would have dropped is **written to disk
instead of being dropped** — [`overflowPolicy`](configuration.md#opt-overflowpolicy) no longer
has the last word on an overloaded app. The practical consequence is a monitoring one: the
`dropped` counter can sit at **zero** while the app is badly overloaded, because everything that
overflowed went to disk. Under the old defaults `dropped` was the overload signal; under these it
is **`spilledRecords`** (and `segmentCount` / `onDiskBytes` growing in the stats heartbeat —
see [`statsIntervalMillis`](configuration.md#opt-statsintervalmillis)). Alert on those.

Which counter to watch follows `spillEnabled`, so decide it per app rather than by habit: with
spill **on** (the default) alert on `spilledRecords`, and with spill **off** alert on `dropped`,
because `spillOnOverflow` has no effect and `overflowPolicy` gets the last word again.

**4. Replay is at-least-once, so duplicates are possible.** A segment that ships partway and
then fails is re-read whole on the next pass, so any record that transits the buffer may be
delivered more than once after a failure. That is the deliberate trade — duplicates over
loss — and it is unchanged by this default; what changed is that it now applies to apps that
never asked for the buffer. See
[Replay is at-least-once, not exactly-once](#replay-is-at-least-once-not-exactly-once) below.

**The staleness trap.** [`spillRecoveryMode`](configuration.md#opt-spillrecoverymode) defaults
to `ADOPT`, which means a spill directory left behind by a previous run is **adopted and
replayed at startup**. That is the right behaviour for a crash a minute ago; it is a trap for a
directory that has been sitting on a reused host or a restored volume for days. Log intakes
reject events whose timestamps are too old — Datadog and New Relic both publish a maximum age
for accepted logs (on the order of hours to a couple of days; take the current window from
[Datadog's](https://docs.datadoghq.com/logs/log_collection/) and
[New Relic's](https://docs.newrelic.com/docs/logs/log-api/introduction-log-api/) own
documentation rather than from here, since these limits change). A backlog older than that
window is replayed, accepted at the HTTP level, and discarded server-side. The engine sees a
success and counts those records in `sentRecords` before deleting the segment, so a stale
backlog can look delivered while nothing of it is searchable in the intake. If a host may
present a stale spill directory at startup, set `spillRecoveryMode="DELETE"` (discard old
segments) or `IGNORE` (leave them untouched) rather than relying on `ADOPT`.

### How the buffer works

Spilled records are stored in rotating segment files. Each segment carries a small
self-describing header naming both the codec and the framing version it was written
with, so a reader always decodes a file correctly even across a format change. A
background replayer re-ships segments once the endpoint is healthy and deletes each on
success; on startup it recovers any segments left by a previous run or crash. The buffer
is bounded by both a total-bytes cap and a filesystem free-space floor — when either is
hit, the **oldest** segment is deleted to make room, so logging can never exhaust the disk.
The records in an evicted segment are counted in `spillStats().droppedRecords` (and
`evictedRecords`) and reported by a coalesced WARN, so the loss is never silent. A segment a
replay worker is reading is never evicted.

### Replay is at-least-once, not exactly-once

A segment has no durable read cursor: if it ships partway and then the endpoint starts
failing — say the first 300 of 500 records are accepted before a 503 — the successful prefix is
not recorded, and the **whole segment is re-read on the next pass**, re-delivering those 300. So
any record that transits the spill buffer may arrive at the endpoint more than once. This is a
deliberate trade-off: a durable per-record cursor would mean an `fsync` per replayed batch on
the recovery path, and for logs a duplicate is a far cheaper failure than a loss. The live
(non-spilled) path is unaffected, and duplicates only arise from a *partially* failed segment,
so they are rare in practice — but if something downstream consumes these logs and must not see
repeats, de-duplicate on a stable field (the preserved event timestamp plus the message, or your
own event id) rather than assuming the buffer suppresses them. Two more sources of duplicates:
a sub-batch delivered just before the process died, in a segment not yet deleted, is sent
again by the next start; and a send interrupted at shutdown may already have reached the
intake, yet its batch is spilled and replayed.

### Segment sizing is fuzzy and never splits a record

Before each record is written, the active segment's size is checked (against an in-memory byte
counter — no `stat` syscall) and a new segment is started if the record wouldn't fit. A record
always lands **whole** in exactly one segment, so a message larger than
[`maxSpillFileBytes`](configuration.md#opt-maxspillfilebytes) produces one oversized segment
rather than a record torn across two files. (The free-space floor probe *is* a syscall, so it is
throttled — re-checked at most once per few MB written, and skipped entirely when
[`minFreeDiskBytes`](configuration.md#opt-minfreediskbytes) is 0 — to keep the spill path cheap
on CPU-throttled workers.)

### Crash-corruption tolerance (both formats)

Both formats frame each record as `SYNC + length + CRC32C + payload` (`JSON` since 0.2.0). If a
runtime crash leaves the journal improperly closed and a record corrupted — a torn tail, a
zero-filled block, or bit-rot in the middle of a segment — the reader detects the bad frame
(failed sync, insane length, or CRC mismatch), **skips exactly the damaged record, and resumes
from the next intact one**, rather than abandoning everything after the damage. The per-frame
CRC is what makes that resync safe for **any** payload: a message whose own bytes happen to
contain the sync marker (an HTTP wire dump, embedded JSON, raw binary) is rejected by the
checksum and can never be mistaken for a frame boundary. Skipped bytes are surfaced via
`spillStats()` (`corruptBytesSkipped`) so recovery is observable — counted for a torn trailing
frame and for a segment with nothing readable left in it, not just interior damage. That last
shape is why the counter earns its keep: zero records recovered *and* zero bytes skipped would
make a wholly destroyed journal indistinguishable from one that never held anything.

The CRC is computed only on the spill path (the abnormal case) and uses the JDK's
hardware-accelerated CRC-32C, so steady-state healthy logging pays nothing. `JSON` segments
written by 0.1.5 and earlier keep the older length-prefixed (v1) framing and are still read.
For those, a crash loses only the torn last record, but damage in the middle ends that
segment; the discarded bytes are counted in `corruptBytesSkipped`. A damaged or missing
segment header no longer silently empties a segment: the reader salvages the intact frames
behind it and counts the rest.

### Spill options

Turn it off, or point it somewhere durable, in `log4j2.xml`:

```xml
<BatchHttp name="newrelic" vendor="newrelic"
           apiKey="${env:NEW_RELIC_API_KEY}"
           spillEnabled="false"/>
```

Defaults and accepted values are in [configuration.md](configuration.md); what each option
means for the buffer:

- [`spillEnabled`](configuration.md#opt-spillenabled) — the disk-spill safety net. `false`
  turns it off, so a batch that fails after retries is dropped and counted instead of buffered.
- [`spillDirectory`](configuration.md#opt-spilldirectory) — base directory for segment files;
  derived from the JVM temp dir when unset (`${java.io.tmpdir}/batch-http-spill/<sink>`,
  `%TEMP%\...` on Windows). Set it explicitly where the temp dir is memory-backed — see
  [It is on by default](#it-is-on-by-default--read-this-before-upgrading) above.
- [`spillInstanceId`](configuration.md#opt-spillinstanceid) — identifier embedded in each
  segment file name (`spill-<instanceId>-<seq>.log`) so two or more apps sharing a spill
  directory never overwrite each other's logs. Unset, it is the appender `name` (the engine
  falls back to the sink name). It must be stable across restarts of the same app and distinct
  per running instance.
- [`maxSpillFileBytes`](configuration.md#opt-maxspillfilebytes) — segment size before rotation
  (fuzzy — a record always lands whole in one segment, even if larger than this). The default is
  a quarter of the default total cap, so the buffer holds four segments: eviction sheds a quarter
  of the backlog at a time, and three closed segments stay replayable while a fourth fills.
- [`maxTotalSpillBytes`](configuration.md#opt-maxtotalspillbytes) — total cap; exceeding it
  evicts the oldest segment. Evicted records are counted in
  `spillStats().droppedRecords`/`evictedRecords` and reported by a coalesced WARN.
- [`minFreeDiskBytes`](configuration.md#opt-minfreediskbytes) — filesystem free-space floor;
  dropping below it evicts the oldest segment. It bounds the impact on the **volume**, not on
  this buffer, so it is deliberately not scaled to `maxTotalSpillBytes` — the spill directory
  usually shares a filesystem with the runtime's own logs and temp files, and this is the margin
  left for them. On a volume with less than this usable, spill degrades to counting drops rather
  than writing; lower it for a small dedicated volume. The probe is a syscall, so it is
  throttled (≈ once per few MB written); set it to 0 to disable it and skip the syscall
  entirely. Evicted records are counted and reported as for `maxTotalSpillBytes`.
- [`spillReplayIntervalMillis`](configuration.md#opt-spillreplayintervalmillis) — how often the
  replayer attempts to drain segments. When segments are already on disk at startup (a previous
  run or a crash), the first pass runs immediately.
- [`spillOnOverflow`](configuration.md#opt-spillonoverflow) — also divert queue-overflow records
  to disk (vs. dropping them).
- [`spillFormat`](configuration.md#opt-spillformat) — on-disk record encoding: `BINARY`
  (compact TLV, all charsets, **corruption-tolerant** sync+CRC framing) or `JSON`
  (human-readable, same corruption-tolerant sync+CRC framing since 0.2.0).
- [`spillRecoveryMode`](configuration.md#opt-spillrecoverymode) — what to do with segments left
  by a previous run: `ADOPT` replays them, `IGNORE` leaves them untouched, `DELETE` discards
  them at startup.
- [`spillStartupReconcile`](configuration.md#opt-spillstartupreconcile) — at startup, evict
  oldest segments to satisfy the total cap and free-space floor before writing new logs.
- [`spillBacklogOrder`](configuration.md#opt-spillbacklogorder) — drain order: `OLDEST_FIRST`,
  `NEWEST_FIRST`, or `DUAL_STREAM` (ship freshest logs and the historical backlog in parallel).
- [`spillReplayThreads`](configuration.md#opt-spillreplaythreads) — parallel replay workers
  (forced to 2 for `DUAL_STREAM`).
- [`spillReplayMaxBytesPerSec`](configuration.md#opt-spillreplaymaxbytespersec) — throttle the
  backlog drain to this many on-disk bytes/sec (0 = unlimited) so replay never starves live
  traffic.

## Crash recovery (runtime / JVM death)

What happens when the Mule runtime or the JVM dies without stopping the appender (`kill -9`,
an OOM kill, a worker crash under load):

- **What survives: spilled records, and only those.** Every record the spill buffer reported as
  spilled had already been handed to the OS when `spill` returned, so a *process* death loses
  none of them. Records that were only in memory at that moment are lost: the in-memory
  queue, the batch a flusher was holding, and a request in flight. The buffer protects what
  reached disk, not what was waiting to.
- **Host crash or power loss (best effort).** Segments are fsynced when they roll, when the
  replayer seals them (once per
  [`spillReplayIntervalMillis`](configuration.md#opt-spillreplayintervalmillis) while an outage
  lasts) and on shutdown, and the spill directory is fsynced when a segment is created so its
  name is durable too (skipped where the OS cannot sync a directory, e.g. Windows). A host
  failure should therefore lose at most about the last interval or segment of spilled data,
  provided the filesystem honours fsync; a segment deleted just before the failure can reappear
  and be replayed again (a duplicate, not a loss). There is deliberately no fsync per record.
- **Restart.** The next start re-takes the per-instance lock. The lock lives on the open file
  descriptor, not on the `.spill-<instanceId>.lock` file, so the OS drops it with the dead
  process; the lock file staying on disk is expected and harmless. The dead process's
  segments are then adopted (`spillRecoveryMode=ADOPT`) and replay starts **immediately**,
  without waiting for the replay interval, and an INFO line says how much was recovered
  (shown only with [`diagnosticsLevel`](configuration.md#opt-diagnosticslevel)`="INFO"` and
  `status="INFO"` or finer; the defaults are `WARN`, so confirm recovery from the
  `adoptedRecords` counter or the heartbeat otherwise — see
  [troubleshooting.md](troubleshooting.md#where-the-messages-go)).
  This needs the **same `spillDirectory` and the same `spillInstanceId`**. The instance id
  defaults to the appender name; for the Generic sink without an explicit id the engine
  default includes the endpoint host, so changing the host strands the old files.
- **Damaged records are skipped and counted, never fatal.** A torn trailing frame from the
  crash, or a record damaged in the middle of a segment, is skipped, and replay resumes at the
  next intact record. The bytes are counted in `corruptBytesSkipped` and reported by a WARN
  per segment. A segment that cannot be read at all (an I/O error) is kept, retried on later
  passes and reported once. It never blocks the segments behind it.
- **At-least-once across a crash.** A sub-batch delivered just before the crash, in a segment
  not yet deleted, is sent again after the restart. A live batch whose request reached the
  intake but whose response never arrived is spilled and re-sent too. De-duplicate
  downstream if that matters (see
  [Replay is at-least-once](#replay-is-at-least-once-not-exactly-once) above).
- **Lock held at startup.** If another live process holds the lock, the segments are left
  untouched and a WARN is logged. That process can be a leaked appender in the same JVM after
  a Mule redeploy, or an NFS lock lease. A second store in the same JVM detects a live holder
  without opening its lock file (on Linux/macOS closing any descriptor of a file releases the
  process's lock on it, which would silently hand the directory to another process). Ownership
  is retried on every replay pass, and the segments are adopted once the holder lets go. Give
  each concurrently running instance its own `spillInstanceId`.
- **Platform limits (outside the appender's control).** Recovery needs the spill directory to
  survive the restart, so it covers **in-place restarts only**. The default
  `${java.io.tmpdir}` survives a Mule runtime/JVM restart on the same host or VM: on-prem, a
  VM, or a CloudHub 1.0 worker restarted in place. It does **not** survive these, because they
  start with an empty `/tmp`:
  - a **CloudHub 1.0** worker replaced by a new worker — which is what a Runtime Manager
    restart did in the 2026-10-12 benchmark (see [benchmark-results.md](benchmark-results.md)),
    so do not count on an application restart from Runtime Manager keeping `/tmp`;
  - a **CloudHub 2.0** / Runtime Fabric / Kubernetes **pod replacement** (redeploy,
    rescheduling, node failure, scale-in);
  - a host reboot that clears `/tmp` (tmpfs or systemd-tmpfiles).

  A container restart inside the same pod keeps only volume-backed paths (for example an
  `emptyDir` mount), not the container's writable layer, so check what your platform mounts at
  `/tmp`. Where the platform offers persistent storage and durability across replacement
  matters, point `spillDirectory` at it.

## Crash-resilience behavior

- **Orphaned files from a previous run** — on startup the store takes a
  `FileLock` on a hidden lock file in the spill directory. If the lock is free, the
  previous owner is gone and any `spill-*.log` files are genuine orphans, handled per
  `spillRecoveryMode` (default `ADOPT` → they are replayed, so logs buffered just
  before the crash still reach the endpoint). If the lock is held, a live sibling
  process owns those files, so this instance leaves them alone — never
  double-shipping or deleting another process's data.
- **Parallel backlog drain** — `DUAL_STREAM` runs two streams at once: one
  ships the newest segments first (so recent logs surface immediately after recovery)
  while the other works through the historical backlog; they meet in the middle.
  `spillReplayMaxBytesPerSec` rate-caps the drain so a large backlog can't saturate
  the intake or starve live traffic.
- **Startup free-space check** — with `spillStartupReconcile` on, a run that
  inherits a large backlog on a nearly-full disk evicts the oldest segments to satisfy
  both `maxTotalSpillBytes` and `minFreeDiskBytes` *before* it writes anything new. The
  records in those segments are counted in `spillStats().droppedRecords`/`evictedRecords`,
  and a one-shot WARN names how many were lost.
- **On-disk format** — `BINARY` is a compact tag-length-value encoding
  (UTF-8 strings, varint lengths) that round-trips every character set — non-Latin
  scripts, emoji, control characters — and is smaller and faster to read/write than
  the JSON alternative. `JSON` remains available (`spillFormat="JSON"`) when you want
  to read the spill files by eye. The per-segment header records which was used, so
  the two can coexist on disk across a config change.

  Each spilled record begins with its event timestamp: the **first field of every
  record** is the original `timestampMillis` (an 8-byte big-endian `int64` in `BINARY`,
  a leading `"ts"` number in `JSON`), so no log ever loses its time — the timestamp is
  carried through the spill and restored verbatim on replay. The **segment file name**,
  by contrast, is *not* a timestamp: `spill-<instanceId>-00000000000000000000.log`
  embeds the owning instance's id (see below) plus a zero-padded **sequence number**
  whose only job is to make lexicographic order equal arrival order (so the replayer can
  drain oldest-first). Newer segments simply have a higher number. Use the file's
  modification time (or decode a record) if you need a wall-clock reference for a
  segment; the per-record timestamps inside are the authoritative event times.

- **Corrupted-journal recovery** — a record damaged by an improperly-closed journal is
  skipped and replay resumes from the next intact one, rather than everything after the
  damage being abandoned. This applies to both formats (`JSON` since 0.2.0), which is what the
  `SYNC + length + CRC32C` framing buys. Legacy v1 `JSON` segments (0.1.5 and earlier) stop
  at mid-segment damage, with the discarded bytes counted. An unreadable segment (I/O error)
  is skipped for that pass and retried later, never blocking the segments behind it. See
  [Crash-corruption tolerance](#crash-corruption-tolerance-both-formats) above for how the
  resync stays safe for arbitrary payloads and how the skipped bytes are reported.

- **Per-instance file naming** (`spillInstanceId`) — every segment name carries an
  instance id, so two or more apps that share one spill directory (two same-vendor apps
  on a host, or an explicitly shared `spillDirectory`) never write the same filename or
  touch each other's segments. Each store lists, replays, evicts, and counts only files
  bearing **its own** id, and takes a per-instance lock (`.spill-<instanceId>.lock`), so
  co-located instances all stay live and independent. The id defaults to the appender
  `name` (engine fallback: the sink name); set it explicitly when several appenders would
  otherwise share a name. It must be **stable across restarts** of the same app (so its
  own orphaned segments are recovered) and **distinct per running instance**. Segment
  files written by a pre-upgrade run under the old id-less name (`spill-<seq>.log`) are
  migrated into the instance's namespace on first startup (honoring `spillRecoveryMode`),
  so upgrading never strands a buffered log.

## See also

- [configuration.md](configuration.md) — defaults and accepted values for every option above.
- [troubleshooting.md](troubleshooting.md) — the WARN/ERROR lines these paths produce, and the
  "no spill files" question.
- [memory-safety.md](memory-safety.md) — proving bounded memory and full drain with the counters.
- [architecture.md](architecture.md) — where the spill path sits in the engine.
- [Docs index](README.md)
