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
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.search.DeleteSearchPipelineRequest;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.DeleteRewriterAction;
import querqy.opensearch.rewriterstore.DeleteRewriterRequest;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Proves the fix for the gap described in {@code PipelineRewriterCacheHandle}'s javadoc: on the full ({@code
 * ActionPlugin}) plugin, {@code PUT}/{@code DELETE} of a rewriter via the REST/transport API now reaches the
 * {@code querqy_rewrite} search pipeline's cache immediately - not just the classic per-shard {@code querqy} query's
 * cache (already proven by {@link AsyncRefreshDisabledOnFullPluginIntegrationTest}) - with no sleep and no
 * {@code RefreshMode} override needed. {@code CACHE_REFRESH_AFTER_WRITE} is set very long here specifically so
 * that if the push-based path were broken, these tests would deterministically see stale results rather than
 * flakily passing by racing the background async-refresh fallback.
 */
public class PipelineRewriterCacheInvalidationIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "pipeline_cache_invalidation_test_index";
    private static final String PIPELINE_ID = "pipeline_cache_invalidation_test_pipeline";
    private static final String REWRITER_ID = "pipeline_cache_invalidation_test_rewriter";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder()
                .put(RewriterShardContext.CACHE_REFRESH_AFTER_WRITE.getKey(), "10m")
                .build();
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

    public void testPutReachesThePipelineImmediately() throws Exception {

        indexTestDocs();
        putPipeline();
        putRewriter("k =>\nSYNONYM: z"); // "z" matches neither doc

        assertHitCountViaPipeline(1L); // cold-cache load through the pipeline, warms its cache

        // REST PUT of a changed config - on return, the pipeline's cache must already be updated, synchronously,
        // with no sleep and no RefreshMode override: CACHE_REFRESH_AFTER_WRITE is 10 minutes, so this could only
        // pass via the push-based path this test is proving, not the async fallback.
        putRewriter("k =>\nSYNONYM: c"); // now "c" also matches "a c"

        assertHitCountViaPipeline(2L);
    }

    public void testDeleteReachesThePipelineImmediately() throws Exception {

        indexTestDocs();
        putPipeline();
        putRewriter("k =>\nSYNONYM: z");

        assertHitCountViaPipeline(1L); // warms the pipeline's cache

        client().execute(DeleteRewriterAction.INSTANCE, new DeleteRewriterRequest(REWRITER_ID)).get();

        // the very next pipeline query must fail clearly - not keep serving the deleted rewriter's stale, cached
        // behavior - proving the DELETE evicted the pipeline's cache entry, not just the classic path's. The
        // search pipeline framework wraps the processor's exception in (two levels of) SearchPipelineProcessingException,
        // so the ResourceNotFoundException RewriterFactoryLoader actually throws is found by walking the cause
        // chain, not checking getCause() directly.
        final ExecutionException e = expectThrows(ExecutionException.class, () -> searchViaPipeline());
        assertTrue("Expected a ResourceNotFoundException somewhere in the cause chain of: " + e,
                causeChainContains(e, ResourceNotFoundException.class));
    }

    private static boolean causeChainContains(final Throwable top, final Class<? extends Throwable> type) {
        for (Throwable cause = top; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private void putPipeline() throws Exception {
        client().admin().cluster().putSearchPipeline(new PutSearchPipelineRequest(PIPELINE_ID,
                new BytesArray("{\"request_processors\": [ { \"querqy_rewrite\": {} } ] }"), MediaTypeRegistry.JSON))
                .get();
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

    private SearchResponse searchViaPipeline() throws Exception {

        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter(REWRITER_ID)));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequest request = client().prepareSearch(INDEX_NAME).setQuery(querqyQuery).request();
        request.pipeline(PIPELINE_ID);
        return client().search(request).get();
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
