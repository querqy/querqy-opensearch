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
import org.opensearch.action.search.DeleteSearchPipelineRequest;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.IndexNotFoundException;
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
 * Proves {@link RewriterCacheSweeper} closes the gap described in its own javadoc: on a core-only install (no
 * {@code ActionPlugin}, so no push-based reload at all - modeled here by {@link QuerqyCorePlugin} directly, with a
 * rewriter document written straight to the index), a rewriter deleted out-of-band is eventually evicted from both
 * the per-shard cache ({@link RewriterShardContexts}) and the {@code querqy_rewrite} pipeline's cache ({@link
 * PipelineRewriterCacheHandle}) by one sweep - with no further query traffic against that rewriter needed to
 * trigger it, unlike {@link RewriterFactoryLoader#maybeRefreshAsync}. The sweep is invoked directly (not via its
 * own schedule) against the node's real, live {@link RewriterShardContexts}/{@link PipelineRewriterCacheHandle}
 * instances, so the test is deterministic rather than racing a background timer.
 */
public class RewriterCacheSweeperIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "sweeper_test_index";
    private static final String PIPELINE_ID = "sweeper_test_pipeline";
    private static final String REWRITER_ID = "sweeper_test_rewriter";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyCorePlugin.class);
    }

    @After
    public void cleanUp() {
        try {
            client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
        } catch (final IndexNotFoundException e) {
            // Ignore
        }
        try {
            client().admin().cluster().deleteSearchPipeline(new DeleteSearchPipelineRequest(PIPELINE_ID)).get();
        } catch (final Exception e) {
            // Ignore
        }
    }

    public void testSweepEvictsDeletedRewriterFromShardCacheWithoutQueryTraffic() throws Exception {

        ensureRewriterIndexExists();
        indexTestDocs();
        writeRewriter(REWRITER_ID, "k =>\nSYNONYM: z");

        assertHitCount(1L); // warms the per-shard cache

        final RewriterShardContexts shardContexts = getInstanceFromNode(RewriterShardContexts.class);
        assertTrue(shardContexts.cachedRewriterIds().contains(REWRITER_ID));

        client().prepareDelete(QUERQY_INDEX_NAME, REWRITER_ID).get(); // bypasses any push notification

        newSweeper().sweep(client());

        assertBusy(() -> assertFalse(shardContexts.cachedRewriterIds().contains(REWRITER_ID)));
    }

    public void testSweepEvictsDeletedRewriterFromPipelineCacheWithoutQueryTraffic() throws Exception {

        ensureRewriterIndexExists();
        indexTestDocs();
        putPipeline();
        writeRewriter(REWRITER_ID, "k =>\nSYNONYM: z");

        assertHitCountViaPipeline(1L); // warms the pipeline's cache

        final PipelineRewriterCacheHandle pipelineHandle = getInstanceFromNode(PipelineRewriterCacheHandle.class);
        assertTrue(pipelineHandle.cachedRewriterIds().contains(REWRITER_ID));

        client().prepareDelete(QUERQY_INDEX_NAME, REWRITER_ID).get();

        newSweeper().sweep(client());

        assertBusy(() -> assertFalse(pipelineHandle.cachedRewriterIds().contains(REWRITER_ID)));
    }

    public void testSweepLeavesAnUnchangedRewriterQueryable() throws Exception {

        ensureRewriterIndexExists();
        indexTestDocs();
        writeRewriter(REWRITER_ID, "k =>\nSYNONYM: z");

        assertHitCount(1L);

        newSweeper().sweep(client());

        assertBusy(() -> assertHitCount(1L));
    }

    private RewriterCacheSweeper newSweeper() {
        return new RewriterCacheSweeper(getInstanceFromNode(RewriterShardContexts.class),
                getInstanceFromNode(PipelineRewriterCacheHandle.class));
    }

    private void ensureRewriterIndexExists() throws Exception {
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();
    }

    private void putPipeline() throws Exception {
        client().admin().cluster().putSearchPipeline(new PutSearchPipelineRequest(PIPELINE_ID,
                new BytesArray("{\"request_processors\": [ { \"querqy_rewrite\": {} } ] }"), MediaTypeRegistry.JSON))
                .get();
    }

    private void writeRewriter(final String rewriterId, final String rules) throws Exception {

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

    private QuerqyQueryBuilder rewriterQuery() {
        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter(REWRITER_ID)));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");
        return querqyQuery;
    }

    private SearchResponse search() throws Exception {
        final SearchRequestBuilder builder = client().prepareSearch(INDEX_NAME).setQuery(rewriterQuery());
        return client().search(builder.request()).get();
    }

    private SearchResponse searchViaPipeline() throws Exception {
        final SearchRequest request = client().prepareSearch(INDEX_NAME).setQuery(rewriterQuery()).request();
        request.pipeline(PIPELINE_ID);
        return client().search(request).get();
    }

    private void assertHitCount(final long expectedHits) throws Exception {
        assertEquals(expectedHits, search().getHits().getTotalHits().value());
    }

    private void assertHitCountViaPipeline(final long expectedHits) throws Exception {
        assertEquals(expectedHits, searchViaPipeline().getHits().getTotalHits().value());
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
