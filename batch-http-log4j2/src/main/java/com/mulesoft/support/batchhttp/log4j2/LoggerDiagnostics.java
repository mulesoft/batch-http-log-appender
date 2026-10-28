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

import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;

/**
 * A {@link Diagnostics} that writes every message to the {@link StatusLoggerDiagnostics} it
 * wraps and, while the appender is started, also to an ordinary Log4j2 logger named
 * {@code com.mulesoft.support.batchhttp.diagnostics.<appender name>}.
 *
 * <p><b>Why.</b> StatusLogger output goes only to the process console. Where the platform does
 * not show the console (CloudHub 2.0 Runtime Manager logs, for example), an operator never sees
 * the stats heartbeat or the drop lines. A named logger is an ordinary event: it follows the
 * normal logger hierarchy, so whatever appenders the root logger has — including ones the
 * platform attaches after deployment — receive it.
 *
 * <p><b>Two independent gates.</b> The StatusLogger copy is gated by {@code diagnosticsLevel}
 * (and {@code <Configuration status>}); the logger copy only by the logger's own level, so it is
 * switched off with {@code level="OFF"} and turned up with {@code level="DEBUG"} like any other
 * category, for every appender through the parent name or for one through its child. A message is
 * built when either gate wants it.
 *
 * <p><b>Why this cannot loop.</b> The usual objection to an ordinary logger (see
 * {@link StatusLoggerDiagnostics}) is that the appender is often on the root logger, so a
 * diagnostic event would be shipped by the engine that emitted it. Two guards break that:
 * {@link BatchHttpAppender#append} discards events from any logger under
 * {@link #PREFIX} — every appender's, not just its own — so they never enter a queue; and a per-thread flag skips the logger leg if a diagnostic is
 * raised while one is already being written on the same thread.
 *
 * <p><b>Why only while started.</b> The factory runs, and the engine emits its spill-recovery
 * and TLS lines, while the {@link LoggerContext} still holds the previous configuration (or the
 * default one at first start). Logging there would reach the wrong appenders or a stopping
 * configuration, so those lines go to StatusLogger only. The same applies to the final stats
 * line at shutdown.
 */
final class LoggerDiagnostics implements Diagnostics {

    /** Parent of every appender's diagnostics logger. */
    static final String PREFIX = "com.mulesoft.support.batchhttp.diagnostics";

    private static final ThreadLocal<Boolean> WRITING = new ThreadLocal<>();

    private final StatusLoggerDiagnostics status;
    private final Configuration configuration;
    private final String loggerName;
    private volatile boolean live;
    private volatile Logger logger;

    LoggerDiagnostics(StatusLoggerDiagnostics status, Configuration configuration, String appenderName) {
        this.status = status;
        this.configuration = configuration;
        this.loggerName = PREFIX + "." + appenderName;
    }

    String loggerName() { return loggerName; }

    /** True for this appender's diagnostics logger, another appender's, or the parent. */
    static boolean isDiagnosticsLogger(String name) {
        return name != null && name.startsWith(PREFIX)
                && (name.length() == PREFIX.length() || name.charAt(PREFIX.length()) == '.');
    }

    /** Called from the appender's start/stop; before start and after stop only StatusLogger is written. */
    void live(boolean live) { this.live = live; }

    @Override
    public boolean isEnabled(Diagnostics.Level level) {
        return status.isEnabled(level) || loggerEnabled(level);
    }

    @Override
    public void log(Diagnostics.Level level, String message) {
        status.log(level, message);
        toLogger(level, message, null);
    }

    @Override
    public void log(Diagnostics.Level level, String message, Throwable thrown) {
        status.log(level, message, thrown);
        toLogger(level, message, thrown);
    }

    private boolean loggerEnabled(Diagnostics.Level level) {
        if (!live) return false;
        try {
            Logger l = resolve();
            return l != null && l.isEnabled(map(level));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void toLogger(Diagnostics.Level level, String message, Throwable thrown) {
        if (!live || WRITING.get() != null) return;
        WRITING.set(Boolean.TRUE);
        try {
            Logger l = resolve();
            if (l == null) return;
            org.apache.logging.log4j.Level lvl = map(level);
            if (!l.isEnabled(lvl)) return;
            if (thrown == null) l.log(lvl, message);
            else l.log(lvl, message, thrown);
        } catch (RuntimeException ignore) {
            // logging must never break logging
        } finally {
            WRITING.remove();
        }
    }

    private Logger resolve() {
        Logger l = logger;
        if (l == null) {
            LoggerContext ctx = configuration != null ? configuration.getLoggerContext() : null;
            if (ctx == null) return null;
            l = ctx.getLogger(loggerName);
            logger = l;
        }
        return l;
    }

    // if/else rather than switch-on-enum (same reason as StatusLoggerDiagnostics.map).
    private static org.apache.logging.log4j.Level map(Diagnostics.Level level) {
        if (level == Diagnostics.Level.DEBUG) return org.apache.logging.log4j.Level.DEBUG;
        if (level == Diagnostics.Level.INFO) return org.apache.logging.log4j.Level.INFO;
        if (level == Diagnostics.Level.WARN) return org.apache.logging.log4j.Level.WARN;
        return org.apache.logging.log4j.Level.ERROR;
    }
}
