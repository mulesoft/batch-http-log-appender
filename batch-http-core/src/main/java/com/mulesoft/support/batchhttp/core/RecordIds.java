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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates the {@link LogRecord#recordId()} the engine gives each accepted record when the sink
 * asks for one. An id is a random 64-bit prefix, drawn once per shipper, followed by a 64-bit
 * sequence number, encoded as 22 characters of unpadded base64url.
 *
 * <p>The prefix keeps ids unique across replicas and restarts without any coordination (two
 * shippers share a prefix with probability about 2<sup>-64</sup>); the sequence makes them unique
 * within one shipper.
 * Uniqueness is all that matters: the id only has to tell a re-sent copy of a record apart from
 * a different record, so it is deliberately not derived from the record's content (two genuinely
 * separate events with identical text in the same millisecond must not be merged).
 */
final class RecordIds {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final long prefix;
    private final AtomicLong sequence = new AtomicLong();

    RecordIds() {
        this(new SecureRandom().nextLong());
    }

    RecordIds(long prefix) {
        this.prefix = prefix;
    }

    String next() {
        long seq = sequence.getAndIncrement();
        byte[] b = new byte[16];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (prefix >>> (56 - 8 * i));
            b[8 + i] = (byte) (seq >>> (56 - 8 * i));
        }
        return ENCODER.encodeToString(b);
    }
}
