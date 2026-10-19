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

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecordIdsTest {

    @Test
    void idsAre22UrlSafeCharactersAndUnique() {
        RecordIds ids = new RecordIds();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            String id = ids.next();
            assertEquals(22, id.length(), id);
            assertTrue(id.matches("[A-Za-z0-9_-]{22}"), id);
            assertTrue(seen.add(id), "duplicate id " + id);
        }
    }

    @Test
    void idEncodesPrefixThenSequence() {
        RecordIds ids = new RecordIds(1L);
        assertEquals("AAAAAAAAAAEAAAAAAAAAAA", ids.next()); // prefix 1, sequence 0
        assertEquals("AAAAAAAAAAEAAAAAAAAAAQ", ids.next()); // prefix 1, sequence 1
    }

    @Test
    void twoGeneratorsDoNotShareIds() {
        RecordIds a = new RecordIds(), b = new RecordIds();
        assertNotEquals(a.next(), b.next(), "a random prefix per shipper keeps replicas apart");
    }
}
