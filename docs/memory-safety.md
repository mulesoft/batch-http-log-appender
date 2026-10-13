# Validating memory safety and full drain

The appender holds events in a bounded in-memory queue and (optionally) on disk. This page shows
how to prove out the two failure modes worth checking, without a profiler, using the counters the
engine already exposes (listed in [troubleshooting.md](troubleshooting.md#programmatic-counters)):

1. memory does not grow without bound under sustained load or a downed endpoint;
2. every accepted record is eventually shipped — nothing is silently stranded in the queue or in
   a spill segment.

On this page:

- [The invariant to check](#the-invariant-to-check)
- [1. No unbounded memory growth](#1-no-unbounded-memory-growth)
- [2. Full drain after processing](#2-full-drain-after-processing)

## The invariant to check

`BatchingLogShipper.stats()` exposes `accepted`, `dropped`, `sentRecords`, and `queueDepth`;
`spillStats()` exposes `spilledRecords`, `droppedRecords`, and `segmentCount`. After a **clean
shutdown against a healthy endpoint** the engine should satisfy:

```text
accepted == sentRecords + dropped + spillDropped   # conservation of records
queueDepth == 0                                    # the in-memory queue fully drained
segmentCount == 0                                  # the spill buffer fully drained
```

where `spillDropped` is `spillStats().droppedRecords` (0 when spill is disabled).

### Where a record can end up

Each term on the right is a place a record can legitimately end up, and **every one is counted,
never silent**:

- `sentRecords` — delivered to the endpoint (live or via spill replay).
- `dropped` — records the engine shed or could not send:
  - shed by the overflow policy when spill is off (or `spillOnOverflow=false`);
  - a terminal 4xx the engine won't retry;
  - a document a bulk intake (Elasticsearch) refused for good inside a `200` response;
  - records still queued at shutdown with spill off (or with a `DELETE`/`IGNORE` recovery mode,
    or past the shutdown spill budget);
  - a batch whose serialization threw (a sink bug).
- `spillDropped` — records the disk buffer lost:
  - a record it refused: buffer full (cap or free-space floor with nothing left to evict,
    including overflow records diverted to a full buffer), an I/O error, or the store already
    closed — including at shutdown;
  - **plus every record inside a segment it evicted as oldest**, counted at eviction time
    (`evictedRecords` is that part). Bounded-buffer back-pressure, by design.

`dropped` and `spillDropped` never overlap: a record the spill refuses is counted only in
`spillDropped`, never also in `dropped`.

### Reading the result

If `accepted` exceeds that sum, records leaked. `queueDepth` is 0 after every `close()`: records
still queued at shutdown are spilled (or, with spill off or a non-`ADOPT` recovery mode, counted
in `dropped`).

| Situation | How the books close | Read it as |
| --- | --- | --- |
| Clean shutdown, healthy endpoint | `accepted == sentRecords + dropped + spillDropped`, `segmentCount == 0` | Full drain |
| `close()` returned, endpoint **up** | `segmentCount` stays above zero | Incomplete drain |
| Endpoint **down** at shutdown | `accepted == sentRecords + dropped + spillDropped + (records left in spill segments)` | Expected: those records replay on the next start (note 1) |
| Endpoint flapped mid-segment | Sum can come out **above** `accepted` | Duplicate delivery, not a leak (note 2) |
| Restart adopted a previous run's backlog | `accepted + adoptedRecords == sentRecords + dropped + spillDropped` (plus whatever is still on disk) | Surplus is recovered records, not a leak (note 3) |

1. A non-zero `segmentCount` at shutdown with the endpoint down is expected and correct; see the
   caveat at the end of [Full drain after processing](#2-full-drain-after-processing).
2. The equality is stated for a **healthy** endpoint for a reason: spill replay is at-least-once,
   so if the endpoint flapped mid-segment, the re-shipped prefix is counted in `sentRecords`
   again. Read a surplus as duplicate delivery (expected, see
   [Disk-spill buffer](resilience.md#replay-is-at-least-once-not-exactly-once)), and only a
   *shortfall* as a leak. A segment evicted after a partly successful replay pass counts its
   already-delivered prefix as dropped too, which is another source of surplus, never a
   shortfall.
3. See [The counters are per process](#the-counters-are-per-process) below.

### The counters are per process

After a restart that adopted segments from a previous run or a crash, those records are not in
this run's `accepted`. Replaying them adds to `sentRecords`, and evicting them adds to
`spillDropped`, so the sum can exceed `accepted`; read that surplus as recovered records, not a
leak. `spillStats().adoptedRecords` is the number of intact records adopted at startup, which is
why the check with a backlog becomes the adopted-backlog row in the table above.

Two things are outside the equality:

- records destroyed by on-disk corruption are reported in bytes (`corruptBytesSkipped`), not as
  records;
- segments discarded by `spillRecoveryMode=DELETE` are not counted as drops: they were a previous
  run's records. They are reported in an INFO line and in `evictedSegments`.

## 1. No unbounded memory growth

The queue is hard-capped at [`queueCapacity`](configuration.md#opt-queuecapacity) and overflow is
governed by [`overflowPolicy`](configuration.md#opt-overflowpolicy) — it cannot grow past the cap
by construction. To prove it under load:

```bash
# Run the JVM with a tight, fixed heap and a soak workload. If the queue or spill
# bookkeeping leaked, the bounded cap would be exceeded and you'd see OOM / steady
# old-gen growth across GCs rather than a sawtooth that returns to baseline.
java -Xmx128m -Xms128m -Xlog:gc -jar your-app.jar
```

- Watch `queueDepth` stay at or below `queueCapacity` throughout (it should oscillate, not climb
  monotonically).
- With the endpoint **down** and spill enabled, memory must still stay flat: failed batches move
  to **disk**, not an unbounded in-memory retry list. `segmentCount` grows on disk (capped by
  [`maxTotalSpillBytes`](configuration.md#opt-maxtotalspillbytes) /
  [`minFreeDiskBytes`](configuration.md#opt-minfreediskbytes), oldest-evicted), while heap stays
  bounded.
- For a definitive check, take two heap histograms (`jmap -histo:live <pid>`) a few minutes apart
  under steady load and confirm `LogRecord` / `byte[]` counts are not trending up.

## 2. Full drain after processing

`close()` (which Log4j2 calls on `appender.stop()`) does four things, in order:

1. lets the flushers drain and send within
   [`shutdownDrainTimeoutMillis`](configuration.md#opt-shutdowndraintimeoutmillis);
2. stops the replayer;
3. spills whatever is still queued;
4. seals (and fsyncs) the spill store.

To verify nothing is stranded:

```java
// In a test or a tiny main(), against a mock intake that records what it receives:
for (int i = 0; i < N; i++) shipper.offer(record(i));
shipper.close();                       // blocks until drained or the drain timeout

var s = shipper.stats();
assert s.accepted == s.sentRecords + s.dropped;   // conservation of records
assert s.queueDepth == 0;                          // in-memory queue empty
var sp = shipper.spillStats();
assert sp == null || sp.segmentCount == 0;         // spill buffer empty (healthy endpoint)
```

This is exactly what the end-to-end tests assert; run them with `mvn test`.

For the **outage → recovery** path, drive the "Watch it work" steps in the batch demo READMEs (for
example `demo/mule/datadog-batch-http/README.md`). Spill files accumulate while the endpoint is
down; then `ls` on the spill directory shows it empty once a healthy endpoint lets the background
replayer drain every segment.

> [!NOTE]
> One caveat to design for, not a leak: if `close()` cannot reach a downed endpoint within
> `shutdownDrainTimeoutMillis`, undelivered records — the failed batches *and* whatever is still
> queued — are spilled to disk (not lost) and replayed on the **next** startup
> (`spillRecoveryMode=ADOPT`). "Full drain" there means across the restart, not within the single
> process: `segmentCount > 0` at shutdown with a dead endpoint is correct durability behavior, not
> a stranded-record bug.

Two related edge cases:

- a send interrupted at shutdown may already have reached the intake, so that batch can arrive
  twice after the replay (at-least-once);
- a record logged after `stop()` begins is refused by `offer()` (it returns false) and is not
  counted in `accepted`.

## See also

- [resilience.md](resilience.md) — the spill, shutdown and crash-recovery behaviour these checks
  exercise.
- [troubleshooting.md](troubleshooting.md) — the heartbeat and status messages that report the
  same counters.
- [building.md](building.md#verify-the-build) — running the tests that assert these invariants.
- [configuration.md](configuration.md) — every `<BatchHttp>` option.

---

[Docs index](README.md) · [Configuration reference](configuration.md) ·
[Resilience](resilience.md) · [Troubleshooting](troubleshooting.md) · [Building](building.md)
