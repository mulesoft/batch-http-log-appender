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
 * Turns an arbitrary string into a filesystem-safe token by replacing every character
 * outside {@code [A-Za-z0-9._-]} with {@code '_'}. Shared by the pieces that build spill
 * path/filename components from a sink name or instance id, so the whitelist lives in one
 * place. The empty-input fallback is intentionally left to each caller: a sink-name
 * directory wants a non-empty default, while an instance id preserves "" to select the
 * legacy segment naming.
 */
public final class FilenameSafe {

    private FilenameSafe() {}

    /** Replace every non-{@code [A-Za-z0-9._-]} character with {@code '_'}; empty input yields "". */
    public static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
