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
package com.mulesoft.support.batchhttp.core;

/**
 * A tiny logging seam the batching engine uses to surface its own health —
 * dropped batches, fatal rejections, queue overflow, spill init failure, and an
 * optional periodic stats heartbeat — without taking a dependency on any logging
 * framework (the core jar is deliberately zero-dependency).
 *
 * <p><b>Why a seam rather than a logger.</b> When this engine is driven by a Log4j2
 * appender, writing diagnostics through an ordinary {@code Logger} would risk an
 * infinite loop: a diagnostic event routed back to the very appender that emitted it
 * (e.g. the app's root logger ships everything, including
 * {@code com.mulesoft.support.batchhttp.*}, to this appender). The Log4j2 module
 * therefore installs a {@link Diagnostics} backed by Log4j2's internal
 * {@code StatusLogger}, which writes to Log4j2's status output and never re-enters the
 * appender pipeline. Plain embeddings can use {@link #systemLogger(String)} (JDK
 * {@link System.Logger}); when nothing is installed the engine is silent ({@link #NOOP}).
 *
 * <p>Implementations must be thread-safe and must never throw: logging must never break
 * logging.
 */
public interface Diagnostics {

    /** Severity of a diagnostic message, mapped onto the host logger's own levels. */
    enum Level { DEBUG, INFO, WARN, ERROR }

    /**
     * Whether messages at {@code level} would be emitted. Callers guard the
     * construction of higher-volume (DEBUG) messages with this so the string is never
     * built when the level is suppressed.
     */
    boolean isEnabled(Level level);

    /** Emit a diagnostic message at {@code level}. Must not throw. */
    void log(Level level, String message);

    /** Emit a diagnostic message with an associated throwable. Must not throw. */
    default void log(Level level, String message, Throwable thrown) {
        log(level, message);
    }

    /**
     * Compact, log-safe rendering of an exception for a diagnostic line:
     * {@code "ConnectException: Connection refused"}, or just {@code "EOFException"} when
     * the throwable carries no message. Shared by the transport and the batching engine so
     * their error output stays in one format.
     */
    static String describe(Throwable e) {
        String type = e.getClass().getSimpleName();
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? type : type + ": " + msg;
    }

    /** A diagnostics sink that discards everything — the default when none is installed. */
    Diagnostics NOOP = new Diagnostics() {
        @Override public boolean isEnabled(Level level) { return false; }
        @Override public void log(Level level, String message) { /* discard */ }
        @Override public void log(Level level, String message, Throwable thrown) { /* discard */ }
    };

    /**
     * A {@link Diagnostics} backed by the JDK {@link System.Logger}, for plain (non-Log4j2)
     * embeddings. Routing/level is controlled by whatever {@code System.Logger} provider is
     * on the classpath (java.util.logging by default).
     */
    static Diagnostics systemLogger(String name) {
        return new SystemLoggerDiagnostics(name);
    }

    /** {@link Diagnostics} over {@link System.Logger}. */
    final class SystemLoggerDiagnostics implements Diagnostics {
        private final System.Logger logger;

        SystemLoggerDiagnostics(String name) {
            this.logger = System.getLogger(name);
        }

        @Override
        public boolean isEnabled(Level level) {
            return logger.isLoggable(map(level));
        }

        @Override
        public void log(Level level, String message) {
            logger.log(map(level), message);
        }

        @Override
        public void log(Level level, String message, Throwable thrown) {
            logger.log(map(level), message, thrown);
        }

        // if/else rather than switch-on-enum: a switch makes javac emit a synthetic
        // $SwitchMap helper class resolved lazily on a logging thread, which can fail
        // under a split classloader (see the same note in BatchingLogShipper.offer).
        private static System.Logger.Level map(Level level) {
            if (level == Level.DEBUG) return System.Logger.Level.DEBUG;
            if (level == Level.INFO) return System.Logger.Level.INFO;
            if (level == Level.WARN) return System.Logger.Level.WARNING;
            return System.Logger.Level.ERROR;
        }
    }
}
