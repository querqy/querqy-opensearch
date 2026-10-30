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
import querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory;
import querqy.opensearch.rewriterstore.RewriterInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the local (no-network) validation path - the same
 * {@code OpenSearchRewriterFactory.loadInstance(...).validateConfiguration(...)} check used by
 * {@code PutRewriterRequest.validate()} and the write-time field mapper - plus {@link
 * RewriterConfigTool#findRewritersReferencingDictionary} against a mocked {@link HttpQuerqyIndexManager} (wire-level
 * request/response shapes for {@link HttpQuerqyIndexManager} itself are covered in {@code
 * HttpQuerqyIndexManagerTest}, not here). The rest of {@code RewriterConfigTool}'s HTTP interaction is exercised
 * manually/in integration.
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

    private static RewriterInfo wordBreakListing(final String id) {
        return new RewriterInfo(id, WordBreakCompoundRewriterFactory.class.getName(), null, "hash", null, null, null);
    }

    private static RewriterInfo wordBreakWithDictionary(final String id, final String dictionaryName,
                                                        final String dictionaryRevision) {
        final Map<String, Object> dictionary = new HashMap<>();
        dictionary.put("name", dictionaryName);
        dictionary.put("revision", dictionaryRevision);
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionary", dictionary);
        return new RewriterInfo(id, WordBreakCompoundRewriterFactory.class.getName(), null, "hash", null, config,
                null);
    }

    @Test
    public void testFindRewritersReferencingDictionaryMatchesByNameAndRevision() throws Exception {

        final HttpQuerqyIndexManager indexManager = mock(HttpQuerqyIndexManager.class);

        when(indexManager.listRewriters()).thenReturn(List.of(
                wordBreakListing("wb_match"),
                wordBreakListing("wb_no_match"),
                new RewriterInfo("common_rules", "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory",
                        null, "hash", null, null, null)));
        when(indexManager.getRewriter("wb_match")).thenReturn(wordBreakWithDictionary("wb_match", "d", "r1"));
        when(indexManager.getRewriter("wb_no_match")).thenReturn(wordBreakWithDictionary("wb_no_match", "other",
                "r1"));

        final List<String> referencing = RewriterConfigTool.findRewritersReferencingDictionary(indexManager, "d",
                "r1");

        assertThat(referencing).containsExactly("wb_match");
        // a non-word-break rewriter's full config is never even fetched - listRewriters() already told us enough
        verify(indexManager, never()).getRewriter("common_rules");
    }

    @Test
    public void testFindRewritersReferencingDictionaryReturnsEmptyWhenNoneMatch() throws Exception {

        final HttpQuerqyIndexManager indexManager = mock(HttpQuerqyIndexManager.class);

        when(indexManager.listRewriters()).thenReturn(List.of(wordBreakListing("wb1")));
        when(indexManager.getRewriter("wb1")).thenReturn(wordBreakWithDictionary("wb1", "other", "r1"));

        assertThat(RewriterConfigTool.findRewritersReferencingDictionary(indexManager, "d", "r1")).isEmpty();
    }

    @Test
    public void testFindRewritersReferencingDictionaryIgnoresDictionaryFieldConfigurations() throws Exception {

        final HttpQuerqyIndexManager indexManager = mock(HttpQuerqyIndexManager.class);
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryField", "f1"); // no "dictionary" block at all

        when(indexManager.listRewriters()).thenReturn(List.of(wordBreakListing("wb1")));
        when(indexManager.getRewriter("wb1")).thenReturn(new RewriterInfo("wb1",
                WordBreakCompoundRewriterFactory.class.getName(), null, "hash", null, config, null));

        assertThat(RewriterConfigTool.findRewritersReferencingDictionary(indexManager, "d", "r1")).isEmpty();
    }
}
