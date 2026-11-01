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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * On the full ({@code ActionPlugin}) plugin, push-based reload already keeps every node's cache fresh immediately
 * after every write made through the REST/transport API, so {@code QuerqyPlugin} disables {@link
 * RewriterCacheSweeper} entirely (see {@link RewriterCacheSweeper#disable()}) - the periodic sweep exists only to
 * catch changes a core-only install (no {@code ActionPlugin}) can't push-notify about, and would otherwise be a
 * source of an unguarded race against the push-based path, same reasoning as {@link
 * AsyncRefreshDisabledOnFullPluginIntegrationTest} for the async refresh-after-write check. This proves the
 * disablement actually takes effect: a rewriter deleted directly from the index (bypassing the REST API, so no
 * push notification fires) must NOT be evicted even past several {@code SWEEP_INTERVAL} windows - unlike on a
 * core-only install (see {@code RewriterCacheSweeperIntegrationTest} in the {@code core} module, which proves the
 * sweep runs there).
 */
public class RewriterCacheSweeperDisabledOnFullPluginIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "sweeper_disabled_test_index";
    private static final String SWEEP_INTERVAL = "200ms";
    private static final String REWRITER_ID = "sweeper_disabled_test_rewriter";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder()
                .put(RewriterCacheSweeper.SWEEP_INTERVAL.getKey(), SWEEP_INTERVAL)
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

    public void testThatOutOfBandDeleteIsNotPickedUpBySweep() throws Exception {

        indexTestDocs();

        putRewriter("k =>\nSYNONYM: z"); // "z" matches neither doc
        assertHitCount(1L); // cold-cache load through the push-based path, warms the cache

        // bypass the REST/transport API entirely - a raw delete like this never triggers a push notification, so
        // the only mechanism that could pick this up is the periodic sweep
        client().prepareDelete(QUERQY_INDEX_NAME, REWRITER_ID).get();

        // if the sweep were active despite disable(), it would have evicted the cache entry within one or two
        // SWEEP_INTERVAL windows, and every subsequent query would fail with ResourceNotFoundException instead of
        // continuing to serve the stale cached rewriter - spaced across several windows so a delayed sweep can't
        // pass this test by accident
        for (int i = 0; i < 5; i++) {
            Thread.sleep(300);
            assertHitCount(1L);
        }
    }

    private void putRewriter(final String rules) throws Exception {
        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest(REWRITER_ID, content)).get();
    }

    private void assertHitCount(final long expectedHits) throws Exception {

        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter(REWRITER_ID)));
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
