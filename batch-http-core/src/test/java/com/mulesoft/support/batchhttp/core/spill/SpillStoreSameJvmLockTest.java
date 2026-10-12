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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A second store with the same id in the same JVM (a leaked appender after a Mule redeploy, a
 * Log4j2 reconfiguration) must not weaken the first store's directory lock. On POSIX systems
 * closing any descriptor of a file drops every lock the process holds on it, so a store that
 * merely probed the held lock file (open, OverlappingFileLockException, close) used to free it
 * for every other process while this JVM still reported it valid. Checked from a separate
 * process, because only another process can see that the OS lock is gone.
 */
class SpillStoreSameJvmLockTest {

    private static final String ID = "same-jvm";

    /** Child JVM: try the lock file given as argv[0]; print HELD if another process holds it, FREE otherwise. */
    public static final class LockProbe {
        public static void main(String[] args) throws Exception {
            try (FileChannel ch = FileChannel.open(Path.of(args[0]),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = ch.tryLock();
                System.out.println(lock == null ? "HELD" : "FREE");
                if (lock != null) lock.release();
            }
            System.out.flush();
        }
    }

    private static SpillStore store(Path dir) throws Exception {
        return new SpillStore(dir, 1 << 20, 1 << 24, 0L, null, SpillCodec.BINARY,
                BatchConfig.SpillRecoveryMode.ADOPT, false, ID);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aSecondSameIdStoreInTheJvmNeverReleasesTheHoldersOsLock(@TempDir Path dir) throws Exception {
        Path lockFile = dir.resolve(".spill-" + ID + ".lock");
        SpillStore holder = store(dir);
        SpillStore second = null;
        try {
            assertTrue(holder.ownsDirectory());
            assertEquals("HELD", probe(lockFile), "precondition: another process sees the holder's lock");

            second = store(dir);
            assertFalse(second.ownsDirectory(), "the holder is alive, so the second store must not own the directory");
            for (int i = 0; i < 3; i++) {
                assertFalse(second.tryReacquireOwnership(), "the holder still has the lock");
            }
            assertTrue(holder.ownsDirectory());
            assertEquals("HELD", probe(lockFile),
                    "constructing a same-id store and retrying ownership must not drop the holder's OS lock");

            // Once the holder lets go, the second store takes over, and its lock is real too.
            holder.close();
            assertTrue(second.tryReacquireOwnership(), "the lock is free after the holder closes");
            assertEquals("HELD", probe(lockFile), "the new owner's lock is visible to other processes");
        } finally {
            holder.close();
            if (second != null) second.close();
        }
        assertEquals("FREE", probe(lockFile), "closing the last store releases the lock");
        // Closed stores leave no JVM-wide claim behind: a fresh store owns the directory at once.
        try (SpillStore fresh = store(dir)) {
            assertTrue(fresh.ownsDirectory());
        }
    }

    /** Fork {@link LockProbe} on the test classpath and return what it printed. */
    private static String probe(Path lockFile) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String surefireCp = System.getProperty("surefire.test.class.path");
        String cp = surefireCp != null && !surefireCp.isBlank() ? surefireCp : System.getProperty("java.class.path");
        Process p = new ProcessBuilder(javaBin, "-Xmx32m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC",
                "-cp", cp, LockProbe.class.getName(), lockFile.toString())
                .redirectErrorStream(true)
                .start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("lock probe did not finish: " + out);
        }
        assertEquals(0, p.exitValue(), "lock probe failed: " + out);
        return out;
    }
}
