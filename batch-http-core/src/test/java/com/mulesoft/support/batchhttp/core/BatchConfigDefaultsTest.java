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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the batching defaults an embedding gets when it configures nothing. These four are
 * documented in the README's attribute tables and set the flush trigger, the wire encoding,
 * and whether the engine touches the local filesystem at all for every deployment that leaves
 * them unset, so a change here is a behaviour change for every such app — it must be
 * deliberate, not a side effect of editing the builder.
 */
class BatchConfigDefaultsTest {

    @Test
    void unconfiguredBuilderYieldsTheDocumentedDefaults() {
        BatchConfig config = BatchConfig.builder().build();
        assertEquals(200, config.maxBatchRecords, "default records-per-batch flush trigger");
        assertTrue(config.gzip, "request bodies are gzipped unless compression is turned off");
    }

    /**
     * Spill on by default means an unconfigured embedding writes log payloads to local storage
     * on a failure path, and 32 MB is how much of it the buffer may occupy. Both are documented
     * behaviour a user inherits on upgrade, so both are pinned here.
     */
    @Test
    void diskSpillIsOnByDefaultAndBoundedAtThirtyTwoMegabytes() {
        BatchConfig config = BatchConfig.builder().build();
        assertTrue(config.spillEnabled, "the disk-spill safety net is on unless it is turned off");
        assertEquals(32L * 1024 * 1024, config.maxTotalSpillBytes, "default total spill cap");
    }
}
