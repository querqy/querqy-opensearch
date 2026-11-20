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

package querqy.opensearch.pipeline;

import static org.opensearch.index.query.QueryBuilders.boolQuery;
import static org.opensearch.index.query.QueryBuilders.matchAllQuery;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.junit.After;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.QuerqyCorePlugin;
import querqy.opensearch.QuerqyProcessor;
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
import java.util.concurrent.ExecutionException;

/**
 * End-to-end test for {@link QuerqyRewriteRequestProcessor}: PUTs a {@code querqy_rewrite} search pipeline, runs
 * searches through it, and asserts both the rewrite itself (a SYNONYM rule) and the filter-query pushdown (a FILTER
 * rule) took effect - including when the {@code querqy} clause is nested inside a {@link BoolQueryBuilder}, the
 * dependency-free stand-in this repo uses for a hybrid query's multi-leg structure (see {@code
 * QuerqyRewriteRequestProcessor}'s javadoc and this plan's testing note: real {@code HybridQueryBuilder} validation
 * needs a neural-search-enabled cluster, which isn't available as a test dependency).
 */
public class QuerqyRewriteRequestProcessorIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "querqy_rewrite_processor_test_index";
    private static final String REWRITER_ID = "querqy_rewrite_processor_test_rewriter";
    private static final String PIPELINE_ID = "querqy_rewrite_test_pipeline";

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
            client().admin().cluster().deleteSearchPipeline(
                    new org.opensearch.action.search.DeleteSearchPipelineRequest(PIPELINE_ID)).get();
        } catch (final Exception e) {
            // Ignore - not every test creates the pipeline
        }
    }

    public void testFilterQueryFromRewriteIsPushedOntoTopLevelQuery() throws Exception {

        setUpRewriterAndDocs();
        putPipeline("{ \"request_processors\": [ { \"querqy_rewrite\": {} } ] }");

        // this rewriter runs SYNONYM+FILTER either way (per-shard, normally, or once on the coordinator via the
        // pipeline) - the two code paths must agree on the result
        assertEquals("Sanity check: the normal per-shard path must apply SYNONYM+FILTER and leave only the docs "
                        + "that both match (literally or via synonym) and contain \"d\"",
                2L, search(querqyQuery(), null).getHits().getTotalHits().value());

        final SearchResponse response = search(querqyQuery(), PIPELINE_ID);
        assertEquals("The querqy_rewrite pipeline path must agree with the normal per-shard path",
                2L, response.getHits().getTotalHits().value());
    }

    public void testFilterQueryReachesEveryLegOfANestedBoolQuery() throws Exception {

        setUpRewriterAndDocs();
        putPipeline("{ \"request_processors\": [ { \"querqy_rewrite\": {} } ] }");

        // stand-in for a hybrid query: the querqy clause lives inside one leg of a bool query, alongside an
        // unrelated match_all leg - querqy_rewrite must still find it (via visit()) and the filter it produces must
        // still apply to the *whole* top-level query, not just the querqy clause's own leg
        final BoolQueryBuilder topLevelQuery = boolQuery()
                .should(querqyQuery())
                .should(matchAllQuery());

        final SearchResponse withoutPipeline = search(topLevelQuery, null);
        assertEquals("Sanity check: match_all alone must match every doc", 6L,
                withoutPipeline.getHits().getTotalHits().value());

        final SearchResponse withPipeline = search(topLevelQuery, PIPELINE_ID);
        assertEquals("The pushed-down filter must restrict the whole bool query (both legs), not just the "
                        + "querqy clause's own subtree - leaving only docs that actually contain \"d\"",
                3L, withPipeline.getHits().getTotalHits().value());
    }

    public void testIgnoreMissingControlsWhetherAQuerylessRequestFails() throws Exception {

        indexTestDocs();
        putPipeline("{ \"request_processors\": [ { \"querqy_rewrite\": {} } ] }");

        final SearchRequestBuilder builder = client().prepareSearch(INDEX_NAME).setQuery(matchAllQuery());
        final SearchRequest request = builder.request();
        request.pipeline(PIPELINE_ID);

        final ExecutionException e = expectThrows(ExecutionException.class, () -> client().search(request).get());
        assertTrue(e.getCause().getMessage().contains("no 'querqy' query found"));

        putPipeline("{ \"request_processors\": [ { \"querqy_rewrite\": { \"ignore_missing\": true } } ] }");
        final SearchRequest requestIgnoreMissing = client().prepareSearch(INDEX_NAME).setQuery(matchAllQuery())
                .request();
        requestIgnoreMissing.pipeline(PIPELINE_ID);
        assertEquals(6L, client().search(requestIgnoreMissing).get().getHits().getTotalHits().value());
    }

    public void testShardDependentRewriterFailsClearlyThroughThePipeline() throws Exception {

        ensureRewriterIndexExists();
        writeWordBreakCompoundRewriter("word_break_test_rewriter");
        indexTestDocs();
        putPipeline("{ \"request_processors\": [ { \"querqy_rewrite\": {} } ] }");

        final QuerqyQueryBuilder query = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        query.setRewriters(Collections.singletonList(new Rewriter("word_break_test_rewriter")));
        query.setMatchingQuery(new MatchingQuery("a"));
        query.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));

        final SearchRequest request = client().prepareSearch(INDEX_NAME).setQuery(query).request();
        request.pipeline(PIPELINE_ID);

        final ExecutionException e = expectThrows(ExecutionException.class, () -> client().search(request).get());
        assertTrue(e.getCause().getMessage().contains("cannot be used with a coordinator-side rewrite"));
    }

    private QuerqyQueryBuilder querqyQuery() {
        final QuerqyQueryBuilder query = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        query.setRewriters(Collections.singletonList(new Rewriter(REWRITER_ID)));
        query.setMatchingQuery(new MatchingQuery("a"));
        query.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        query.setMinimumShouldMatch("1");
        return query;
    }

    private SearchResponse search(final org.opensearch.index.query.QueryBuilder query, final String pipelineId)
            throws Exception {
        final SearchRequest request = client().prepareSearch(INDEX_NAME).setQuery(query).request();
        if (pipelineId != null) {
            request.pipeline(pipelineId);
        }
        return client().search(request).get();
    }

    private void putPipeline(final String json) throws Exception {
        client().admin().cluster().putSearchPipeline(
                new PutSearchPipelineRequest(PIPELINE_ID, new BytesArray(json), MediaTypeRegistry.JSON)).get();
    }

    private void setUpRewriterAndDocs() throws Exception {
        ensureRewriterIndexExists();
        writeCommonRulesRewriter(REWRITER_ID, "a =>\n  SYNONYM: b\n  FILTER: d\n");
        indexTestDocs();
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

    private void writeWordBreakCompoundRewriter(final String rewriterId) throws Exception {
        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryField", "field1");
        content.put("config", config);

        client().prepareIndex(QUERQY_INDEX_NAME).setId(rewriterId)
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    /**
     * Docs chosen so that (query "a", SYNONYM a-&gt;b, FILTER: d) can distinguish: matches-only-via-literal-"a",
     * matches-only-via-synonym-"b", and whether each also contains "d" (the FILTER term) - plus two docs that match
     * neither "a" nor "b" at all (only {@code match_all}), one of which also contains "d".
     */
    private void indexTestDocs() {
        client().admin().indices().prepareCreate(INDEX_NAME).get();
        index("doc1_a_no_d", "field1", "a", "field2", "x");
        index("doc2_a_with_d", "field1", "a d");
        index("doc3_b_with_d", "field1", "b d");
        index("doc4_b_no_d", "field1", "b");
        index("doc5_neither_with_d", "field1", "zzz d");
        index("doc6_neither_no_d", "field1", "zzz");
    }

    private void index(final String id, final Object... kv) {
        final Map<String, Object> doc = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            doc.put((String) kv[i], kv[i + 1]);
        }
        client().prepareIndex(INDEX_NAME).setId(id).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource(doc).get();
    }
}
