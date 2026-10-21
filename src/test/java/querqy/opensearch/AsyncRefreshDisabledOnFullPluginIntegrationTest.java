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
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * On the full ({@code ActionPlugin}) plugin, push-based reload already keeps every node's cache fresh
 * immediately after every write made through the REST/transport API, so {@code QuerqyPlugin} turns off
 * {@code core}'s async refresh-after-write check entirely (see
 * {@link RewriterShardContexts#disableAsyncRefresh()}) - both to avoid redundant background work and, more
 * importantly, an unguarded race where a delayed background refresh could overwrite a cache entry a concurrent
 * push-based reload had just installed. This proves that disablement actually takes effect: a config change
 * written directly to the index (bypassing the REST API, so no push notification fires) must NOT be picked up,
 * even past the (deliberately very short) {@code refresh_after_write} window - unlike on a core-only install
 * (see {@code RewriterConfigFormatIntegrationTest#testThatConfigChangeIsPickedUpAsynchronouslyAfterRefreshInterval}
 * in the {@code core} module, which proves the opposite for {@link QuerqyCorePlugin}).
 */
public class AsyncRefreshDisabledOnFullPluginIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "test_index";
    private static final String REFRESH_AFTER_WRITE = "200ms";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
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

    public void testThatOutOfBandConfigChangeIsNotPickedUpAsynchronously() throws Exception {

        indexTestDocs();

        putRewriter("direct_write_rewriter", "k =>\nSYNONYM: z"); // "z" matches neither doc
        assertHitCount(1L); // cold-cache load, served through the push-based path, caches the factory

        // bypass the REST/transport API entirely - a raw write like this never triggers a push notification, so
        // the only mechanism that could pick this change up is async refresh
        writeRewriterDirectlyToIndex("direct_write_rewriter", "k =>\nSYNONYM: c"); // now "c" would also match "a c"

        // maybeRefreshAsync only *triggers* a background refresh when a stale entry is queried - that triggering
        // query itself still returns the old (stale) value; the refreshed value only becomes visible on a later
        // query, once the background GET has completed. So proving async refresh is OFF needs repeated queries
        // spaced across several refresh windows, each expected to keep seeing the OLD result throughout - on a
        // core-only install (where async refresh is enabled, see the mirrored core-module test referenced in the
        // class javadoc) one of these iterations would flip to 2 once a background refresh completes; on the full
        // plugin it must never flip, since there is no background mechanism to trigger at all.
        for (int i = 0; i < 5; i++) {
            Thread.sleep(300); // > REFRESH_AFTER_WRITE, so a background refresh would have had time to both run
                                // and be observed by this iteration's query, if async refresh were active
            assertHitCount(1L);
        }
    }

    private void putRewriter(final String rewriterId, final String rules) throws Exception {
        final PutRewriterRequest request = new PutRewriterRequest(rewriterId, commonRulesContent(rules));
        client().execute(PutRewriterAction.INSTANCE, request).get();
    }

    private void writeRewriterDirectlyToIndex(final String rewriterId, final String rules) throws Exception {
        client().prepareIndex(QUERQY_INDEX_NAME).setId(rewriterId)
                .setSource(RewriterConfigMapping.toLuceneSource(commonRulesContent(rules), System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    private Map<String, Object> commonRulesContent(final String rules) {
        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);
        return content;
    }

    private void assertHitCount(final long expectedHits) throws Exception {

        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter("direct_write_rewriter")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(java.util.Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        final SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(expectedHits, response.getHits().getTotalHits().value());
    }

    /**
     * Only doc1 matches via the literal "a" clause alone (doc2 has no "a"); whether doc2 also matches hinges
     * entirely on whatever "k" rewrites to actually matching doc2's content.
     */
    private void indexTestDocs() {
        client().admin().indices().prepareCreate(INDEX_NAME).get();
        client().prepareIndex(INDEX_NAME).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "a b", "field2", "a c").get();
        client().prepareIndex(INDEX_NAME).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "b c").get();
    }
}
