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

package querqy.opensearch.query;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.junit.After;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.lucene.LuceneSearchEngineRequestAdapter;
import querqy.lucene.QueryParsingController;
import querqy.model.ExpandedQuery;
import querqy.model.QuerqyQuery;
import querqy.opensearch.QuerqyCorePlugin;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.RewriteChainAndLogging;
import querqy.opensearch.RewriterFactoryLoader;
import querqy.opensearch.RewriterRef;
import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.parser.WhiteSpaceQuerqyParser;
import querqy.rewrite.RewriteChainOutput;
import querqy.rewrite.RewriteLoggingConfig;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Proves {@link QuerqyQueryBuilder}'s in-place pre-rewritten mode end to end: a query carrying an already-rewritten
 * model (set via {@link QuerqyQueryBuilder#setPreRewritten}, exactly as a future {@code querqy_rewrite} search
 * pipeline request processor would after finding this instance via {@code QueryBuilder.visit(QueryBuilderVisitor)})
 * produces identical search results to the normal per-shard parse + rewrite + build path, for the same rewriter.
 * <p>
 * The coordinator-side parse + rewrite step a real request processor would do is inlined here (via {@link
 * RewriterFactoryLoader#nodeLevel}, exactly as that processor will use it) rather than factored out yet, since the
 * processor itself doesn't exist as production code at this point - this test exists to validate the {@link
 * QuerqyQueryBuilder}/{@link QuerqyProcessor} mechanism independently of it.
 */
public class QuerqyQueryBuilderPreRewrittenIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "pre_rewritten_test_index";
    private static final String REWRITER_ID = "pre_rewrite_test_rewriter";

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

    public void testPreRewrittenQueryProducesIdenticalResultsToNormalPerShardPath() throws Exception {

        ensureRewriterIndexExists();
        writeCommonRulesRewriter(REWRITER_ID, "k =>\nSYNONYM: c");
        indexTestDocs();

        // --- baseline: normal per-shard parse + rewrite + build path, unchanged ---
        final QuerqyQueryBuilder normalQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        normalQuery.setRewriters(Collections.singletonList(new Rewriter(REWRITER_ID)));
        normalQuery.setMatchingQuery(new MatchingQuery("a k"));
        normalQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        normalQuery.setMinimumShouldMatch("1");

        final SearchResponse normalResponse = search(normalQuery);
        // sanity check: the rewriter must actually have changed the result set (doc2 "b c" only matches via k->c)
        assertEquals(2L, normalResponse.getHits().getTotalHits().value());

        // --- pre-rewritten path: run parse+rewrite "on the coordinator" ourselves, then set the result on a builder
        // that never calls into the rewrite chain on the shard at all ---
        final RewriterFactoryLoader loader = RewriterFactoryLoader.nodeLevel(Settings.EMPTY, client(), false);
        final RewriteChainAndLogging chainAndLogging = loader.getRewriteChain(
                Collections.singletonList(new RewriterRef(REWRITER_ID, querqy.opensearch.query.RefreshMode.NO)));

        final LuceneSearchEngineRequestAdapter coordinatorAdapter = mock(LuceneSearchEngineRequestAdapter.class);
        when(coordinatorAdapter.getQueryString()).thenReturn("a k");
        when(coordinatorAdapter.isMatchAllQuery("a k")).thenReturn(false);
        when(coordinatorAdapter.needsScores()).thenReturn(true);
        when(coordinatorAdapter.getQueryAnalyzer()).thenReturn(new KeywordAnalyzer());
        when(coordinatorAdapter.getQueryFieldsAndBoostings()).thenReturn(Collections.singletonMap("field1", 1f));
        when(coordinatorAdapter.getGeneratedQueryFieldsAndBoostings()).thenReturn(Collections.emptyMap());
        when(coordinatorAdapter.createQuerqyParser()).thenReturn(Optional.of(new WhiteSpaceQuerqyParser()));
        when(coordinatorAdapter.getRewriteChain()).thenReturn(chainAndLogging.rewriteChain);
        when(coordinatorAdapter.getRewriteLoggingConfig()).thenReturn(RewriteLoggingConfig.off());
        when(coordinatorAdapter.getRequestParams(anyString())).thenReturn(new String[0]);

        final QueryParsingController coordinatorController = new QueryParsingController(coordinatorAdapter);
        final ExpandedQuery parsedInput = coordinatorController.createExpandedQuery();
        final RewriteChainOutput rewriteChainOutput =
                chainAndLogging.rewriteChain.rewrite(parsedInput, coordinatorAdapter);
        final ExpandedQuery rewrittenExpandedQuery = rewriteChainOutput.getExpandedQuery();

        final QuerqyQueryBuilder preRewrittenQuery =
                new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        preRewrittenQuery.setMatchingQuery(new MatchingQuery("a k"));
        preRewrittenQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        preRewrittenQuery.setMinimumShouldMatch("1");
        assertFalse(preRewrittenQuery.isPreRewritten());
        preRewrittenQuery.setPreRewritten((QuerqyQuery<?>) parsedInput.getUserQuery(), rewrittenExpandedQuery);
        assertTrue(preRewrittenQuery.isPreRewritten());

        final SearchResponse preRewrittenResponse = search(preRewrittenQuery);

        assertEquals("A pre-rewritten query must match the exact same documents as the normal per-shard path",
                normalResponse.getHits().getTotalHits().value(), preRewrittenResponse.getHits().getTotalHits().value());
    }

    public void testQueryWithoutPreRewrittenStateIsUnaffected() throws Exception {

        ensureRewriterIndexExists();
        writeCommonRulesRewriter(REWRITER_ID, "k =>\nSYNONYM: c");
        indexTestDocs();

        final QuerqyQueryBuilder normalQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        normalQuery.setRewriters(Collections.singletonList(new Rewriter(REWRITER_ID)));
        normalQuery.setMatchingQuery(new MatchingQuery("a k"));
        normalQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        normalQuery.setMinimumShouldMatch("1");

        assertFalse(normalQuery.isPreRewritten());
        assertEquals(2L, search(normalQuery).getHits().getTotalHits().value());
    }

    private SearchResponse search(final QuerqyQueryBuilder query) throws Exception {
        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(query);
        return client().search(searchRequestBuilder.request()).get();
    }

    private void ensureRewriterIndexExists() throws Exception {
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();
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
