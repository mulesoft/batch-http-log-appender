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

import org.apache.logging.log4j.status.StatusLogger;

/**
 * A {@link Diagnostics} that writes the batching engine's own health messages to Log4j2's
 * internal {@link StatusLogger}.
 *
 * <p><b>Why StatusLogger and not an ordinary {@code Logger}.</b> The engine is itself the
 * thing behind a {@code <BatchHttp>} appender. If its diagnostics went through a normal
 * {@code LogManager.getLogger(...)}, and the application's configuration routes
 * {@code com.mulesoft.support.batchhttp.*} (or the root logger) to this very appender — the
 * common case — every diagnostic event would be re-enqueued into the engine that emitted it,
 * which on a failing endpoint produces more failures, more diagnostics, and an unbounded
 * feedback loop. {@code StatusLogger} is Log4j2's out-of-band channel for components to report
 * their own status; it never flows back through the appender pipeline, so it is recursion-safe
 * by construction. Its output is controlled by the {@code status} attribute on
 * {@code <Configuration status="WARN">} (and visible on the console / status listeners), which
 * is exactly where an operator looks when "logging isn't working."
 *
 * <p>The configured {@code threshold} gates DEBUG/INFO so the per-attempt retry chatter and the
 * stats heartbeat can be turned up only when troubleshooting, while WARN/ERROR (drops, fatal
 * rejections, overflow) always surface at the default {@code status="WARN"}.
 *
 * <p><b>Note on {@code Level}:</b> because this class implements {@link Diagnostics}, the
 * inherited nested type {@code Diagnostics.Level} shadows any {@code import} of Log4j2's own
 * {@code org.apache.logging.log4j.Level} within the class body, so the latter is referenced
 * fully-qualified throughout.
 */
final class StatusLoggerDiagnostics implements Diagnostics {

    private final StatusLogger statusLogger = StatusLogger.getLogger();
    /** Minimum core-level that is emitted; messages below it are dropped. */
    private final org.apache.logging.log4j.Level threshold;

    StatusLoggerDiagnostics(org.apache.logging.log4j.Level threshold) {
        this.threshold = threshold != null ? threshold : org.apache.logging.log4j.Level.WARN;
    }

    @Override
    public boolean isEnabled(Diagnostics.Level level) {
        return map(level).isMoreSpecificThan(threshold);
    }

    @Override
    public void log(Diagnostics.Level level, String message) {
        if (!isEnabled(level)) return;
        try {
            statusLogger.log(map(level), message);
        } catch (RuntimeException ignore) {
            // logging must never break logging
        }
    }

    @Override
    public void log(Diagnostics.Level level, String message, Throwable thrown) {
        if (!isEnabled(level)) return;
        try {
            statusLogger.log(map(level), message, thrown);
        } catch (RuntimeException ignore) {
            // logging must never break logging
        }
    }

    // if/else rather than switch-on-enum: a switch makes javac emit a synthetic $SwitchMap
    // helper class resolved lazily on a logging thread, which can fail under Mule's split
    // classloader (same rationale as BatchingLogShipper.offer).
    private static org.apache.logging.log4j.Level map(Diagnostics.Level level) {
        if (level == Diagnostics.Level.DEBUG) return org.apache.logging.log4j.Level.DEBUG;
        if (level == Diagnostics.Level.INFO) return org.apache.logging.log4j.Level.INFO;
        if (level == Diagnostics.Level.WARN) return org.apache.logging.log4j.Level.WARN;
        return org.apache.logging.log4j.Level.ERROR;
    }
}
