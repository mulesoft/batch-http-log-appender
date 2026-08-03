/*
 * Copyright (c) 2026, Salesforce, Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mulesoft.support.batchhttp.core.spill;

import com.mulesoft.support.batchhttp.core.BatchConfig;
import com.mulesoft.support.batchhttp.core.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers two {@link SpillReplayer} behaviours the resilience suite leaves untested:
 * the {@link BatchConfig.SpillBacklogOrder#NEWEST_FIRST} drain order (freshest segments
 * surface first during a large backlog) and the byte/sec {@link BatchConfig#spillReplayMaxBytesPerSec
 * rate cap} that keeps backlog drain from starving live traffic.
 */
class SpillReplayOrderRateTest {

    private static final long BIG = Long.MAX_VALUE / 4;

    private static SpillStore.FreeSpaceProbe plenty() {
        return d -> BIG;
    }

    /** Records whose message encodes the sequence index, so arrival order can be asserted. */
    private static List<LogRecord> recs(int from, int count, int msgPadding) {
        String pad = "x".repeat(Math.max(0, msgPadding));
        List<LogRecord> out = new ArrayList<>(count);
        for (int i = from; i < from + count; i++) {
            out.add(LogRecord.builder()
                    .timestampMillis(1000 + i).level("INFO").loggerName("l").threadName("t")
                    .message("idx=" + i + " " + pad).build());
        }
        return out;
    }

    private static int indexOf(LogRecord r) {
        String m = r.message();               // "idx=<n> xxxx..."
        int sp = m.indexOf(' ');
        return Integer.parseInt(m.substring(4, sp < 0 ? m.length() : sp));
    }

    // ---- 2.5 NEWEST_FIRST drain order ----

    @Test
    void newestFirstDrainsFreshestSegmentsBeforeOldest(@TempDir Path dir) throws Exception {
        // Small segments => several of them; records 0..299 land oldest-first across segments.
        try (SpillStore producer = new SpillStore(dir, 200, BIG, 0L, plenty())) {
            producer.spill(recs(0, 300, 0));
        }

        SpillStore store = new SpillStore(dir, 200, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);

        List<LogRecord> shipped = new CopyOnWriteArrayList<>();
        SpillReplayer.BatchShipper collect = batch -> { shipped.addAll(batch); return true; };

        // Single worker + NEWEST_FIRST => deterministic: claim segments from the newest end.
        SpillReplayer replayer = new SpillReplayer(store, collect, 50, 25, 2000, "test",
                BatchConfig.SpillBacklogOrder.NEWEST_FIRST, 1, 0L);
        replayer.replayOnce();

        assertEquals(300, shipped.size(), "every record must still drain exactly once");
        // Segment-granular reversal: whole segments drain newest-first, but records WITHIN a
        // segment keep their write order. So the first record shipped is the newest segment's
        // first record (a high index) and the last shipped is the oldest segment's last record
        // (a low index) — the first index is strictly greater than the last (oldest-first is the
        // reverse). We assert at segment granularity, not per-record, on purpose.
        assertTrue(indexOf(shipped.get(0)) > indexOf(shipped.get(shipped.size() - 1)),
                "NEWEST_FIRST must ship a newer segment before the oldest; first=" + indexOf(shipped.get(0))
                        + " last=" + indexOf(shipped.get(shipped.size() - 1)));
        // The very oldest record (index 0) leads the oldest segment, which drains last — so it
        // must land deep in the second half of the delivery order, never near the front.
        assertTrue(shipped.stream().map(SpillReplayOrderRateTest::indexOf).toList().indexOf(0) > 150,
                "the oldest record must drain late under NEWEST_FIRST, not lead");

        replayer.close();
        store.close();
    }

    // ---- 2.6 replay rate cap ----

    @Test
    void rateCapPacesTheDrainButStillDeliversEverything(@TempDir Path dir) throws Exception {
        // ~120-byte payloads * 500 = ~60 KB of backlog, well above the 1-second burst allowance.
        try (SpillStore producer = new SpillStore(dir, 1 << 16, BIG, 0L, plenty())) {
            producer.spill(recs(0, 500, 100));
        }

        SpillStore store = new SpillStore(dir, 1 << 16, BIG, 0L, plenty(),
                SpillCodec.BINARY, BatchConfig.SpillRecoveryMode.ADOPT, false);
        long backlogBytes = store.totalBytes();
        assertTrue(backlogBytes > 40_000, "precondition: a backlog worth pacing, was " + backlogBytes);

        // Cap at roughly a third of the backlog per second. The limiter allows a 1s burst
        // (== cap) up front, so the remaining ~2/3 must be paced over time.
        long cap = backlogBytes / 3;

        AtomicLong shipped = new AtomicLong();
        SpillReplayer.BatchShipper count = batch -> { shipped.addAndGet(batch.size()); return true; };

        SpillReplayer replayer = new SpillReplayer(store, count, 50, 50, 5000, "test",
                BatchConfig.SpillBacklogOrder.OLDEST_FIRST, 1, cap);

        long startNanos = System.nanoTime();
        replayer.replayOnce();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertEquals(500, shipped.get(), "the rate cap must never lose records, only slow them");
        // Unlimited, this backlog drains in single-digit ms. Pacing ~2/3 of it at `cap`/s takes
        // well over a second; assert a floor far below the theoretical time but far above 0 so
        // the check is robust on a loaded CI box.
        assertTrue(elapsedMs >= 300,
                "rate cap must pace the drain; drained " + backlogBytes + " bytes at " + cap
                        + " B/s in only " + elapsedMs + "ms");

        replayer.close();
        store.close();
    }
}
