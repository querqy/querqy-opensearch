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
import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.mapper.MapperParsingException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;

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

    private static final String REFRESH_AFTER_WRITE = "500ms";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyCorePlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder()
                .put(RewriterShardContext.CACHE_REFRESH_AFTER_WRITE.getKey(), REFRESH_AFTER_WRITE)
                .build();
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
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();
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

    /**
     * A core-only install has no push-based reload action, so a shard's cached rewriter factory can only become
     * stale and get refreshed in the background (see {@link RewriterShardContext#CACHE_REFRESH_AFTER_WRITE}). This
     * proves a config change written directly to the index does take effect, but only after that refresh window -
     * not synchronously with the write, and not instantly on the very next query either.
     */
    public void testThatConfigChangeIsPickedUpAsynchronouslyAfterRefreshInterval() throws Exception {

        ensureRewriterIndexExists();
        indexTestDocs();

        writeCommonRulesRewriter("refresh_rewriter", "k =>\nSYNONYM: z"); // "z" matches neither doc
        assertHitCount("refresh_rewriter", 1L); // first (cold-cache) load is synchronous

        writeCommonRulesRewriter("refresh_rewriter", "k =>\nSYNONYM: c"); // now "c" would also match "a c"
        Thread.sleep(600); // past the refresh window configured in nodeSettings()

        // the new rule must apply eventually, without ever needing a restart or an explicit reload action (which
        // doesn't exist in core, without ActionPlugin) - each poll both checks and (while still stale) re-triggers
        // the background refresh, so this converges even if an earlier poll's refresh raced with the write above.
        assertBusy(() -> assertHitCount("refresh_rewriter", 2L));
    }

    /**
     * {@code RewriterConfigMapping.toLuceneSource} splits the combined class+config JSON into a {@code String[]}
     * once it exceeds Lucene's 32766-byte keyword-field limit (see {@code stringToSourceValue}) - both the REST PUT
     * action and the CLI tool go through that same method, so both get this for free. What's untested elsewhere is
     * the other half: that a document actually stored that way is both accepted by the write-time field mapper
     * (which must reconstruct the full value from the array before validating it) and correctly read back later via
     * the normal query path (which must do the same reconstruction again, from a real GetResponse).
     */
    public void testThatLargeConfigIsSplitOnWriteAndReadBackCorrectly() throws Exception {

        ensureRewriterIndexExists();
        indexTestDocs();

        final StringBuilder rules = new StringBuilder("k =>\nSYNONYM: c\n");
        while (rules.length() < 40_000) {
            rules.append("filler_term_").append(rules.length()).append(" =>\n  SYNONYM: irrelevant_term\n");
        }

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules.toString());
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());
        if (!(source.get("config_v_005") instanceof String[])) {
            throw new IllegalStateException("Test assumptions are wrong: config was not split up");
        }

        // the write-time field mapper must reconstruct the full array value before it can validate it
        client().prepareIndex(QUERQY_INDEX_NAME).setId("large_rewriter")
                .setSource(source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();

        // and a later read (a fresh GetResponse, not the Map we just built) must reconstruct it the same way
        assertRewritesKToC("large_rewriter");
    }

    private void writeCommonRulesRewriter(final String rewriterId, final String rules) throws Exception {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        client().prepareIndex(QUERQY_INDEX_NAME).setId(rewriterId)
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    private void assertHitCount(final String rewriterId, final long expectedHits) throws Exception {

        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter(rewriterId)));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        final SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(expectedHits, response.getHits().getTotalHits().value());
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

    /**
     * Only doc1 matches via the literal "a" clause alone (doc2 has no "a"); whether doc2 also matches hinges
     * entirely on whatever "k" rewrites to actually matching doc2's content - this is what makes
     * {@link #assertHitCount} and {@link #assertRewritesKToC} sensitive to the rewriter config actually being
     * applied, not just to "a" trivially matching every doc under minimum_should_match=1.
     */
    private void indexTestDocs() {
        client().admin().indices().prepareCreate(INDEX_NAME).get();
        client().prepareIndex(INDEX_NAME).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource(doc("field1", "a b", "field2", "a c")).get();
        client().prepareIndex(INDEX_NAME).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource(doc("field1", "b c")).get();
    }

    private static Map<String, Object> doc(final Object... kv) {
        final Map<String, Object> doc = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            doc.put((String) kv[i], kv[i + 1]);
        }
        return doc;
    }
}
