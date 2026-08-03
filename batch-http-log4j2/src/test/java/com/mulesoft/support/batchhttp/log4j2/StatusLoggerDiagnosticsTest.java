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
package com.mulesoft.support.batchhttp.log4j2;

import com.mulesoft.support.batchhttp.core.Diagnostics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Log4j2-side diagnostics adapter: messages flow to the {@link StatusLogger}
 * (Log4j2's recursion-safe, out-of-band status channel — never back through the appender),
 * and the configured threshold gates DEBUG/INFO while always passing WARN/ERROR.
 */
class StatusLoggerDiagnosticsTest {

    /** Records every StatusLogger event so the test can assert on level + message. */
    private static final class CapturingListener implements StatusListener {
        final List<StatusData> events = new CopyOnWriteArrayList<>();
        @Override public void log(StatusData data) { events.add(data); }
        @Override public Level getStatusLevel() { return Level.ALL; } // capture everything; the adapter does the gating
        @Override public void close() { }

        boolean any(Level level, String substring) {
            return events.stream().anyMatch(d ->
                    d.getLevel() == level && d.getMessage().getFormattedMessage().contains(substring));
        }
    }

    private CapturingListener listener;

    @BeforeEach
    void setUp() {
        listener = new CapturingListener();
        StatusLogger.getLogger().registerListener(listener);
    }

    @AfterEach
    void tearDown() {
        StatusLogger.getLogger().removeListener(listener);
    }

    @Test
    void warnThresholdSuppressesInfoAndDebugButPassesWarnAndError() {
        Diagnostics diag = new StatusLoggerDiagnostics(Level.WARN);

        assertFalse(diag.isEnabled(Diagnostics.Level.DEBUG), "DEBUG must be gated at WARN threshold");
        assertFalse(diag.isEnabled(Diagnostics.Level.INFO), "INFO must be gated at WARN threshold");
        assertTrue(diag.isEnabled(Diagnostics.Level.WARN), "WARN must pass at WARN threshold");
        assertTrue(diag.isEnabled(Diagnostics.Level.ERROR), "ERROR must pass at WARN threshold");

        diag.log(Diagnostics.Level.DEBUG, "debug-XYZ");
        diag.log(Diagnostics.Level.INFO, "info-XYZ");
        diag.log(Diagnostics.Level.WARN, "warn-XYZ");
        diag.log(Diagnostics.Level.ERROR, "error-XYZ");

        assertFalse(listener.any(Level.DEBUG, "debug-XYZ"), "DEBUG must not reach the StatusLogger");
        assertFalse(listener.any(Level.INFO, "info-XYZ"), "INFO must not reach the StatusLogger");
        assertTrue(listener.any(Level.WARN, "warn-XYZ"), "WARN must reach the StatusLogger");
        assertTrue(listener.any(Level.ERROR, "error-XYZ"), "ERROR must reach the StatusLogger");
    }

    @Test
    void infoThresholdPassesInfoAndHeartbeat() {
        Diagnostics diag = new StatusLoggerDiagnostics(Level.INFO);

        assertTrue(diag.isEnabled(Diagnostics.Level.INFO), "INFO must pass at INFO threshold");
        assertFalse(diag.isEnabled(Diagnostics.Level.DEBUG), "DEBUG must still be gated at INFO threshold");

        diag.log(Diagnostics.Level.INFO, "batch-http[x] stats accepted=5 sentRecords=5");
        assertTrue(listener.any(Level.INFO, "stats"),
                "an INFO heartbeat must reach the StatusLogger when threshold is INFO");
    }

    @Test
    void nullThresholdDefaultsToWarn() {
        Diagnostics diag = new StatusLoggerDiagnostics(null);
        assertTrue(diag.isEnabled(Diagnostics.Level.WARN));
        assertFalse(diag.isEnabled(Diagnostics.Level.INFO));
    }

    /** Guard against accidentally introducing a level we don't map. */
    @Test
    void everyCoreLevelMaps() {
        Diagnostics diag = new StatusLoggerDiagnostics(Level.ALL);
        for (Diagnostics.Level l : EnumSet.allOf(Diagnostics.Level.class)) {
            assertDoesNotThrow(() -> diag.log(l, "probe-" + l));
        }
    }
}
