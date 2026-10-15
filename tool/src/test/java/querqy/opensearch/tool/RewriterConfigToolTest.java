/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Querqy for OpenSearch Contributors
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

package querqy.opensearch.tool;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the local (no-network) validation path only - the same
 * {@code OpenSearchRewriterFactory.loadInstance(...).validateConfiguration(...)} check used by
 * {@code PutRewriterRequest.validate()} and the write-time field mapper. HTTP interaction
 * ({@link HttpQuerqyIndexManager}) is exercised manually/in integration, not here.
 */
public class RewriterConfigToolTest {

    @Test
    public void testValidateRejectsUnresolvableClass() {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", "not.a.real.RewriterFactory");

        assertThatThrownBy(() -> RewriterConfigTool.validate("my_rewriter", content))
                .isInstanceOf(RewriterConfigTool.ValidationFailedException.class)
                .hasMessageContaining("class");
    }

    @Test
    public void testValidateRejectsClassNotOnAllowList() {

        // a real class, but not registered via META-INF/services/querqy.opensearch.OpenSearchRewriterFactory
        final Map<String, Object> content = new HashMap<>();
        content.put("class", "java.lang.String");

        assertThatThrownBy(() -> RewriterConfigTool.validate("my_rewriter", content))
                .isInstanceOf(RewriterConfigTool.ValidationFailedException.class);
    }

    @Test
    public void testValidateRejectsMissingClass() {

        final Map<String, Object> content = new HashMap<>();

        assertThatThrownBy(() -> RewriterConfigTool.validate("my_rewriter", content))
                .isInstanceOf(RewriterConfigTool.ValidationFailedException.class);
    }

    @Test
    public void testValidationFailedExceptionCarriesErrors() {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", "not.a.real.RewriterFactory");

        try {
            RewriterConfigTool.validate("my_rewriter", content);
        } catch (final RewriterConfigTool.ValidationFailedException e) {
            assertThat(e.errors).isNotEmpty();
            return;
        }
        throw new AssertionError("Expected ValidationFailedException");
    }
}
