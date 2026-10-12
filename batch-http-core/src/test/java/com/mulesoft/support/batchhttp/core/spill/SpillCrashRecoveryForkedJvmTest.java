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
import com.mulesoft.support.batchhttp.core.BatchingLogShipper;
import com.mulesoft.support.batchhttp.core.Diagnostics;
import com.mulesoft.support.batchhttp.core.LogRecord;
import com.mulesoft.support.batchhttp.core.sink.GenericNdjsonSink;
import com.mulesoft.support.batchhttp.core.sink.LogSink;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The crash this buffer exists for, end to end and with a real process death. A child JVM
 * runs the real engine against an intake that is down, so every record spills, and then dies
 * through {@link Runtime#halt} with no {@code close()}: no shutdown hooks, no seal, no fsync,
 * no lock release, the same as {@code kill -9}, an OOM kill or a crashed Mule worker. The
 * test then damages what the dead process left behind the way a crash can (a record corrupted
 * in the middle of a segment, the open segment torn mid-frame) and starts a fresh engine on
 * the same directory and instance id against a now healthy intake.
 *
 * <p>It proves: a hard kill loses nothing the engine had reported as spilled; the dead
 * process's lock file does not block adoption; replay starts without waiting for
 * {@code spillReplayInterval}; every intact record is delivered exactly once; the damaged ones
 * are skipped and counted to the byte; and the engine neither hangs nor crashes. Run for both
 * on-disk formats.
 */
class SpillCrashRecoveryForkedJvmTest {

    private static final int N = 200;
    private static final String INSTANCE_ID = "crash-test";
    private static final Pattern MESSAGE = Pattern.compile("crash-rec-(\\d{5})");

    private HttpServer server;
    private int port;
    private volatile int status = 503;
    private final Map<String, AtomicInteger> delivered = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/intake", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                int code = status;
                if (code >= 200 && code < 300) {
                    Matcher m = MESSAGE.matcher(new String(body, StandardCharsets.UTF_8));
                    while (m.find()) {
                        delivered.computeIfAbsent(m.group(), k -> new AtomicInteger()).incrementAndGet();
                    }
                }
                exchange.sendResponseHeaders(code, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------------
    // the process that dies
    // ------------------------------------------------------------------

    /**
     * Entry point of the child JVM. Arguments: spill directory, intake URL, record count,
     * spill format. Exits only through {@link Runtime#halt}: 137 once every record is on disk,
     * 3 if that did not happen in time.
     */
    public static final class CrashingChild {
        public static void main(String[] args) throws Exception {
            Path dir = Path.of(args[0]);
            String url = args[1];
            int n = Integer.parseInt(args[2]);
            BatchConfig.SpillFormat format = BatchConfig.SpillFormat.valueOf(args[3]);

            // An hour-long replay interval: the child never seals its active segment, so the
            // segment open at the moment of death is exactly what a crash leaves behind.
            BatchingLogShipper shipper = new BatchingLogShipper(sink(url),
                    config(dir, format, Duration.ofHours(1), 0, Diagnostics.NOOP));
            for (int i = 0; i < n; i++) {
                shipper.offer(rec(i));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (shipper.spillStats().spilledRecords < n) {
                if (System.nanoTime() > deadline) {
                    System.out.println("TIMEOUT " + shipper.stats() + " " + shipper.spillStats());
                    System.out.flush();
                    Runtime.getRuntime().halt(3);
                }
                Thread.sleep(10);
            }
            System.out.println("SPILLED " + shipper.spillStats().spilledRecords);
            System.out.flush();
            Runtime.getRuntime().halt(137); // no close(): no seal, no fsync, no lock release
        }
    }

    static LogSink sink(String url) {
        return GenericNdjsonSink.builder()
                .url(url)
                .framing(GenericNdjsonSink.Framing.NDJSON)
                .maxRecords(100)
                .build();
    }

    static BatchConfig config(Path dir, BatchConfig.SpillFormat format, Duration replayInterval,
                              int maxRetries, Diagnostics diag) {
        return BatchConfig.builder()
                .maxBatchRecords(10)
                .lingerTime(Duration.ofMillis(20))
                .maxRetries(maxRetries)
                .initialRetryBackoff(Duration.ofMillis(1))
                .requestTimeout(Duration.ofSeconds(2))
                .gzip(false)
                .queueCapacity(10_000)
                .spillEnabled(true)
                .spillDirectory(dir)
                .spillInstanceId(INSTANCE_ID)
                .maxSpillFileBytes(2048)              // several small segments
                .maxTotalSpillBytes(64L * 1024 * 1024)
                .minFreeDiskBytes(0)
                .spillReplayInterval(replayInterval)
                .spillFormat(format)
                .diagnostics(diag)
                .build();
    }

    static LogRecord rec(int i) {
        return LogRecord.builder()
                .timestampMillis(1_700_000_000_000L + i)
                .level("INFO").loggerName("crash").threadName("main")
                .message(msg(i)).build();
    }

    static String msg(int i) {
        return String.format("crash-rec-%05d", i);
    }

    // ------------------------------------------------------------------
    // the test
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(BatchConfig.SpillFormat.class)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void intactRecordsSurviveAHardKillAndReplayExactlyOnceAfterRestart(BatchConfig.SpillFormat format,
                                                                       @TempDir Path dir) throws Exception {
        String url = "http://127.0.0.1:" + port + "/intake";

        // 1. A real JVM spills everything (intake answers 503) and is killed without close().
        status = 503;
        String output = runCrashingChild(dir, url, format);
        assertTrue(output.contains("SPILLED " + N), "child must report every record spilled: " + output);
        assertTrue(Files.exists(dir.resolve(".spill-" + INSTANCE_ID + ".lock")),
                "the dead process's lock file stays on disk — it must not block the restart");
        assertTrue(delivered.isEmpty(), "nothing may reach the intake while it is down");

        List<Path> segments = segments(dir);
        assertTrue(segments.size() >= 3, "precondition: several segments, had " + segments.size());
        List<List<Frame>> frames = new ArrayList<>();
        Set<String> onDisk = new HashSet<>();
        int total = 0;
        for (Path seg : segments) {
            List<Frame> fs = frames(seg, format);
            frames.add(fs);
            total += fs.size();
            for (Frame f : fs) onDisk.add(f.msg);
        }
        assertEquals(N, total, "Runtime.halt must lose nothing the engine had already spilled");
        assertEquals(allMessages(), onDisk, "every record is on disk exactly once");

        // 2. Crash damage: a record corrupted in the middle of a closed segment...
        long expectedSkipped = 0;
        List<Frame> second = frames.get(1);
        Frame corrupted = second.get(second.size() / 2);
        flipBytes(segments.get(1), corrupted.offset + SpillFrameV2.HEADER_LEN + corrupted.len / 2, 3);
        expectedSkipped += SpillFrameV2.HEADER_LEN + corrupted.len;
        // ...and the segment that was open at the crash torn in the middle of its last frame.
        Path open = segments.get(segments.size() - 1);
        List<Frame> openFrames = frames.get(frames.size() - 1);
        Frame torn = openFrames.get(openFrames.size() - 1);
        long cut = torn.offset + SpillFrameV2.HEADER_LEN + torn.len / 2;
        try (FileChannel ch = FileChannel.open(open, StandardOpenOption.WRITE)) {
            ch.truncate(cut);
        }
        expectedSkipped += cut - torn.offset;

        Set<String> expected = allMessages();
        expected.remove(corrupted.msg);
        expected.remove(torn.msg);

        // 3. Restart on the same directory and instance id, intake healthy. The 30 s replay
        //    interval is far longer than the wait below, so delivery proves the first pass runs
        //    at startup rather than one interval later.
        status = 200;
        CapturingDiagnostics diag = new CapturingDiagnostics();
        BatchingLogShipper shipper = new BatchingLogShipper(sink(url),
                config(dir, format, Duration.ofSeconds(30), 1, diag));
        try {
            waitFor(() -> delivered.keySet().containsAll(expected), 8_000);
            Thread.sleep(300); // room for a late duplicate to show up
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(delivered.keySet());
            Set<String> unexpected = new TreeSet<>(delivered.keySet());
            unexpected.removeAll(expected);
            assertEquals(Set.of(), missing, "every intact record must be delivered (" + delivered.size() + " were)");
            assertEquals(Set.of(), unexpected, "only intact records may be delivered");
            delivered.forEach((m, c) -> assertEquals(1, c.get(), m + " delivered twice from the spill"));
            assertFalse(delivered.containsKey(corrupted.msg), "the corrupted record must not be delivered garbled");
            assertFalse(delivered.containsKey(torn.msg), "the torn record must not be delivered");

            waitFor(() -> segments(dir).isEmpty(), 3_000);
            assertEquals(List.of(), segments(dir), "replayed segments are deleted");

            SpillStore.SpillStats sp = shipper.spillStats();
            assertEquals(expectedSkipped, sp.corruptBytesSkipped,
                    "the damaged bytes are counted exactly: one corrupted frame plus the torn tail");
            assertEquals(N - 2, sp.adoptedRecords, "adoption counts only the intact records it found");
            assertEquals(N - 2, shipper.stats().sentRecords);
            assertTrue(diag.any(Diagnostics.Level.INFO, "recovered " + segments.size() + " spill segment(s)"),
                    "adoption must be reported: " + diag.entries);
            assertTrue(diag.any(Diagnostics.Level.WARN, "corrupt byte(s) skipped"),
                    "segments with skipped damage must be reported: " + diag.entries);
        } finally {
            assertTimeoutPreemptively(Duration.ofSeconds(10), shipper::close, "close() must not hang");
        }
    }

    /** Fork the child on the test classpath and wait for it to die. Returns its output. */
    private static String runCrashingChild(Path dir, String url, BatchConfig.SpillFormat format) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String surefireCp = System.getProperty("surefire.test.class.path");
        // Under surefire's default manifest-only jar, java.class.path is the booter jar, whose
        // manifest Class-Path carries the whole test classpath; that works for the child too.
        String cp = surefireCp != null && !surefireCp.isBlank() ? surefireCp : System.getProperty("java.class.path");
        Process p = new ProcessBuilder(javaBin, "-Xmx64m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC",
                "-cp", cp, CrashingChild.class.getName(), dir.toString(), url, String.valueOf(N), format.name())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(out);
            } catch (Exception ignore) {
                // the process is gone; whatever was read is the output
            }
        });
        drainer.setDaemon(true);
        drainer.start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("child JVM did not die within 20 s: " + out);
        }
        drainer.join(2_000);
        String output;
        synchronized (out) {
            output = out.toString(StandardCharsets.UTF_8);
        }
        assertEquals(137, p.exitValue(), "child must die through Runtime.halt(137): " + output);
        return output;
    }

    // ------------------------------------------------------------------
    // a test-local frame walker, independent of SpillReader
    // ------------------------------------------------------------------

    private static final class Frame {
        final int offset, len;
        final String msg;
        Frame(int offset, int len, String msg) { this.offset = offset; this.len = len; this.msg = msg; }
    }

    /** Parse a segment the child wrote: a 6-byte header, then whole v2 frames to the end of the file. */
    private static List<Frame> frames(Path seg, BatchConfig.SpillFormat format) throws Exception {
        byte[] b = Files.readAllBytes(seg);
        byte[] header = Arrays.copyOf(b, SpillSegmentHeader.LENGTH);
        assertTrue(SpillSegmentHeader.isValid(header), "segment header must be intact: " + seg);
        assertEquals(SpillSegmentHeader.VERSION_V2, SpillSegmentHeader.version(header),
                format + " segments use the sync+CRC framing");
        SpillCodec codec = SpillCodec.forId(SpillSegmentHeader.codecId(header));
        assertSame(SpillCodec.forFormat(format), codec);
        List<Frame> out = new ArrayList<>();
        int pos = SpillSegmentHeader.LENGTH;
        while (pos < b.length) {
            assertTrue(SpillFrameV2.isSyncAt(b, pos), "frame boundary expected at " + pos + " in " + seg);
            int len = SpillFrameV2.readIntBE(b, pos + 4);
            byte[] payload = Arrays.copyOfRange(b, pos + SpillFrameV2.HEADER_LEN, pos + SpillFrameV2.HEADER_LEN + len);
            assertEquals(SpillFrameV2.readIntBE(b, pos + 8), SpillFrameV2.crc32c(payload), "intact CRC");
            out.add(new Frame(pos, len, codec.decode(payload).message()));
            pos += SpillFrameV2.HEADER_LEN + len;
        }
        assertEquals(b.length, pos, "a halt after the spill returned leaves only whole frames");
        assertFalse(out.isEmpty(), "every segment holds at least one record: " + seg);
        return out;
    }

    private static void flipBytes(Path file, long at, int count) throws Exception {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(count);
            ch.read(buf, at);
            for (int i = 0; i < count; i++) buf.put(i, (byte) (buf.get(i) ^ 0x5A));
            buf.rewind();
            ch.write(buf, at);
        }
    }

    private static List<Path> segments(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("spill-" + INSTANCE_ID + "-\\d+\\.log"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> allMessages() {
        Set<String> all = new HashSet<>();
        for (int i = 0; i < N; i++) all.add(msg(i));
        return all;
    }

    private static void waitFor(BooleanSupplier cond, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
    }

    private static final class CapturingDiagnostics implements Diagnostics {
        static final class Entry {
            final Level level;
            final String message;
            Entry(Level level, String message) { this.level = level; this.message = message; }
            @Override public String toString() { return level + " " + message; }
        }
        final List<Entry> entries = new CopyOnWriteArrayList<>();

        @Override public boolean isEnabled(Level level) { return true; }
        @Override public void log(Level level, String message) { entries.add(new Entry(level, message)); }
        @Override public void log(Level level, String message, Throwable thrown) { entries.add(new Entry(level, message)); }

        boolean any(Level level, String substring) {
            return entries.stream().anyMatch(e -> e.level == level && e.message.contains(substring));
        }
    }
}
