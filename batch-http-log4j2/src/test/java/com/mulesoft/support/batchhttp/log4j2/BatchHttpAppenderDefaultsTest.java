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

import com.mulesoft.support.batchhttp.core.BatchConfig;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@code @PluginAttribute} defaults are a second, independent copy of the engine's defaults:
 * the factory always passes a value to the builder, so whatever Log4j2 substitutes for an absent
 * attribute wins and the builder's own default is never consulted on this path. A number changed
 * in {@link BatchConfig} alone would therefore silently not reach any {@code log4j2.xml}-driven
 * app. These tests read the annotations and hold the two copies to the same values.
 */
class BatchHttpAppenderDefaultsTest {

    @Test
    void pluginAttributeDefaultsAreTheDocumentedOnes() {
        assertEquals(200, attribute("maxBatchRecords").defaultInt(), "default records-per-batch flush trigger");
        assertTrue(attribute("gzip").defaultBoolean(), "request bodies are gzipped unless compression is turned off");
        assertTrue(attribute("spillEnabled").defaultBoolean(),
                "the disk-spill safety net is on unless the config turns it off");
        assertEquals(32L * 1024 * 1024, attribute("maxTotalSpillBytes").defaultLong(),
                "default total spill cap");
    }

    /**
     * {@code includeTimestamp} exists so the demo apps can hand the timestamp back to the vendor
     * and make ingest lag measurable. That is an opt-in: an app upgrading the library without
     * touching its {@code log4j2.xml} must keep getting Datadog's {@code date} and New Relic's
     * {@code timestamp}, so the absent-attribute default has to be true.
     */
    @Test
    void suppressingTheVendorTimestampIsOptIn() {
        assertTrue(attribute("includeTimestamp").defaultBoolean(),
                "omitting includeTimestamp must leave the vendor timestamp emitted, as before");
    }

    @Test
    void pluginAttributeDefaultsAgreeWithTheEngineDefaults() {
        BatchConfig engineDefaults = BatchConfig.builder().build();
        assertEquals(engineDefaults.maxBatchRecords, attribute("maxBatchRecords").defaultInt(),
                "maxBatchRecords default differs between the plugin attribute and BatchConfig");
        assertEquals(engineDefaults.gzip, attribute("gzip").defaultBoolean(),
                "gzip default differs between the plugin attribute and BatchConfig");
        assertEquals(engineDefaults.spillEnabled, attribute("spillEnabled").defaultBoolean(),
                "spillEnabled default differs between the plugin attribute and BatchConfig");
        assertEquals(engineDefaults.maxTotalSpillBytes, attribute("maxTotalSpillBytes").defaultLong(),
                "maxTotalSpillBytes default differs between the plugin attribute and BatchConfig");
        assertEquals(engineDefaults.maxSpillFileBytes, attribute("maxSpillFileBytes").defaultLong(),
                "maxSpillFileBytes default differs between the plugin attribute and BatchConfig");
    }

    /** The {@code @PluginAttribute} declared for {@code name} on the appender's plugin factory. */
    private static PluginAttribute attribute(String name) {
        for (Method m : BatchHttpAppender.class.getDeclaredMethods()) {
            if (!"createAppender".equals(m.getName())) continue;
            for (Parameter p : m.getParameters()) {
                PluginAttribute a = p.getAnnotation(PluginAttribute.class);
                if (a != null && name.equals(a.value())) return a;
            }
        }
        return fail("no @PluginAttribute(\"" + name + "\") on BatchHttpAppender.createAppender");
    }
}
