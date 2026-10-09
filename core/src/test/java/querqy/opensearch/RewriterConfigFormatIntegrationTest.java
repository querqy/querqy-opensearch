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

package querqy.opensearch;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.junit.After;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.mapper.MapperParsingException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Proves that {@link QuerqyCorePlugin} works standalone, with no {@code ActionPlugin} installed - the only way to
 * write a rewriter config is a raw document write, exactly as it would be on a core-only/AWS install. Covers two
 * things not exercised by the other rewriter-store integration tests (which all go through the REST
 * {@code PutRewriterAction}, only available in the full plugin module):
 * <ul>
 *     <li>write-time validation of the combined class+config field ({@code config_v_005}), enforced even for a raw
 *     document write;</li>
 *     <li>that documents written in an older format (mapping version 4: separate {@code class}/{@code config_v_003}
 *     fields) are still read correctly alongside documents in the new (version 5) combined-field format.</li>
 * </ul>
 */
public class RewriterConfigFormatIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "test_index";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyCorePlugin.class);
    }

    @After
    public void deleteRewriterIndex() {
        try {
            client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
        } catch (final IndexNotFoundException e) {
            // Ignore
        }
    }

    /**
     * Creates the rewriter index with the current mapping (including the custom config_v_005 field type) via the
     * plain document/index-admin API - no ActionPlugin/PutRewriterAction involved, exactly as a core-only install
     * would have to do it.
     */
    private void ensureRewriterIndexExists() throws Exception {
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME).setMapping(readMappingResource()).get();
    }

    private static String readMappingResource() throws IOException {
        try (InputStream in = RewriterConfigFormatIntegrationTest.class.getClassLoader()
                .getResourceAsStream("querqy-mapping.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void testThatRawWriteWithInvalidConfigIsRejected() throws Exception {

        ensureRewriterIndexExists();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", DummyOpenSearchRewriterFactory.class.getName());
        content.put("config", Collections.singletonMap("error", "this config is deliberately invalid"));

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());

        final Exception e = expectThrows(Exception.class, () ->
                client().prepareIndex(QUERQY_INDEX_NAME).setId("invalid")
                        .setSource(source)
                        .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                        .get());

        Throwable cause = e;
        while (cause.getCause() != null && !(cause instanceof MapperParsingException)) {
            cause = cause.getCause();
        }
        assertTrue("Expected a MapperParsingException in the cause chain but got: " + e,
                cause instanceof MapperParsingException || e.getMessage().contains("invalid"));
    }

    public void testThatRawWriteWithValidConfigIsAccepted() throws Exception {

        ensureRewriterIndexExists();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", DummyOpenSearchRewriterFactory.class.getName());
        content.put("config", Collections.emptyMap());

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());

        client().prepareIndex(QUERQY_INDEX_NAME).setId("valid")
                .setSource(source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    public void testThatV4AndV5FormatDocumentsAreBothReadable() throws Exception {

        ensureRewriterIndexExists();

        indexTestDocs();

        // a document in the old (mapping version 4) format: class and config in separate fields
        final Map<String, Object> v4Source = new HashMap<>();
        v4Source.put(RewriterConfigMapping.PROP_TYPE, "rewriter");
        v4Source.put(RewriterConfigMapping.PROP_VERSION, 4);
        v4Source.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        v4Source.put("config_v_003", "{\"ignoreCase\":true,\"querqyParser\":\""
                + querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName()
                + "\",\"rules\":\"k =>\\nSYNONYM: c\"}");

        client().prepareIndex(QUERQY_INDEX_NAME).setId("v4_rewriter")
                .setSource(v4Source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();

        assertRewritesKToC("v4_rewriter");

        // the same rewriter, saved in the current (mapping version 5) combined-field format
        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c");
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        final Map<String, Object> v5Source = RewriterConfigMapping.toLuceneSource(content,
                System.currentTimeMillis());

        client().prepareIndex(QUERQY_INDEX_NAME).setId("v5_rewriter")
                .setSource(v5Source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();

        assertRewritesKToC("v5_rewriter");
    }

    private void assertRewritesKToC(final String rewriterId) throws Exception {

        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter(rewriterId)));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        final SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals("rewriter " + rewriterId + " did not apply the k -> SYNONYM: c rule as expected",
                2L, response.getHits().getTotalHits().value());
    }

    private void indexTestDocs() {
        client().admin().indices().prepareCreate(INDEX_NAME).get();
        client().prepareIndex(INDEX_NAME).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource(doc("field1", "a k")).get();
        client().prepareIndex(INDEX_NAME).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource(doc("field1", "a c")).get();
    }

    private static Map<String, Object> doc(final Object... kv) {
        final Map<String, Object> doc = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            doc.put((String) kv[i], kv[i + 1]);
        }
        return doc;
    }
}
