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

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.junit.After;
import org.opensearch.action.search.DeleteSearchPipelineRequest;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.search.SearchExtBuilder;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.QuerqyCorePlugin;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.RewriterFactoryLoader;
import querqy.opensearch.infologging.LogPayloadType;
import querqy.opensearch.query.InfoLoggingSpec;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end test combining the {@code querqy_rewrite} request and response processors in one pipeline: PUTs a
 * rewriter, PUTs a pipeline with both, searches through it, and asserts (a) the search results match what the
 * normal per-shard path produces for the same rewriter, and (b) the response carries the expected {@code
 * ext.querqy} debug/decoration data, controlled by the on/off rules described on {@link
 * QuerqyRewriteResponseProcessor}.
 */
public class QuerqyRewriteEndToEndIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "querqy_rewrite_e2e_test_index";
    private static final String PIPELINE_ID = "querqy_rewrite_e2e_test_pipeline";

    /**
     * Unique per test method, not a shared constant: several methods in this class write different rule bodies
     * under "the rewriter", and {@link RewriterFactoryLoader}'s cache (refresh mode {@code NO} by default, the
     * same as a real client query that doesn't request a refresh) does not guarantee a synchronous reload - reusing
     * one id across methods let a stale cached factory from whichever method happened to run first leak into the
     * others, since methods run in a randomized order.
     */
    private String rewriterId() {
        return "querqy_rewrite_e2e_test_rewriter_" + getTestName();
    }

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
            // Ignore - not every test creates the pipeline
        }
    }

    public void testEndToEndPipelineAttachesExtWithRewriteLogAndDecorations() throws Exception {

        setUpRewriterAndDocs("a =>\n"
                + "  SYNONYM: b\n"
                + "  FILTER: d\n"
                + "  DECORATE: simple-flag\n"
                + "  DECORATE(banner): special-offer\n");

        putPipeline("{"
                + "\"request_processors\": [ { \"querqy_rewrite\": {} } ],"
                + "\"response_processors\": [ { \"querqy_rewrite\": { \"info_logging\": { \"type\": \"detail\" } } } ]"
                + "}");

        final SearchResponse baseline = search(querqyQuery(), null);
        final SearchResponse response = search(querqyQuery(), PIPELINE_ID);

        assertEquals("The pipeline path must agree with the normal per-shard path",
                baseline.getHits().getTotalHits().value(), response.getHits().getTotalHits().value());
        assertEquals(2L, response.getHits().getTotalHits().value());

        final QuerqyExtBuilder extBuilder = getQuerqyExt(response);
        final Map<String, Object> payload = extBuilder.getPayload();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> rewriteChain = (List<Map<String, Object>>) payload.get("rewriteChain");
        assertNotNull("detail-level logging must have produced a rewriteChain entry", rewriteChain);
        assertEquals(1, rewriteChain.size());
        assertEquals(rewriterId(), rewriteChain.get(0).get("rewriterId"));
        assertFalse("detail level must include non-empty actions",
                ((List<?>) rewriteChain.get(0).get("actions")).isEmpty());

        @SuppressWarnings("unchecked")
        final Collection<Object> decorations = (Collection<Object>) payload.get("decorations");
        assertNotNull(decorations);
        assertTrue(decorations.contains("simple-flag"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> decorationsByKey = (Map<String, Object>) payload.get("decorationsByKey");
        assertNotNull(decorationsByKey);
        assertEquals(Collections.singletonList("special-offer"), decorationsByKey.get("banner"));
    }

    public void testNoExtAttachedWhenNothingToReport() throws Exception {

        setUpRewriterAndDocs("a =>\n  SYNONYM: b\n");
        putPipeline("{"
                + "\"request_processors\": [ { \"querqy_rewrite\": {} } ],"
                + "\"response_processors\": [ { \"querqy_rewrite\": {} } ]"
                + "}");

        final SearchResponse response = search(querqyQuery(), PIPELINE_ID);
        assertTrue("No info_logging configured anywhere and no DECORATE rule - there is nothing to report, so no "
                        + "ext.querqy section should be attached at all",
                response.getInternalResponse().getSearchExtBuilders().isEmpty());
    }

    public void testPerQueryInfoLoggingOverridesThePipelineDefault() throws Exception {

        setUpRewriterAndDocs("a =>\n  SYNONYM: b\n");

        // pipeline default is "none" - but a query that explicitly asks for "detail" must still get it
        putPipeline("{"
                + "\"request_processors\": [ { \"querqy_rewrite\": {} } ],"
                + "\"response_processors\": [ { \"querqy_rewrite\": {} } ]"
                + "}");

        final QuerqyQueryBuilder queryWithOverride = querqyQuery();
        queryWithOverride.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.DETAIL));

        final SearchResponse response = search(queryWithOverride, PIPELINE_ID);
        final QuerqyExtBuilder extBuilder = getQuerqyExt(response);
        assertNotNull("The query's own info_logging must override the pipeline's quiet default",
                extBuilder.getPayload().get("rewriteChain"));

        // and the reverse: pipeline default "detail" - but a query that explicitly suppresses it must stay quiet
        putPipeline("{"
                + "\"request_processors\": [ { \"querqy_rewrite\": {} } ],"
                + "\"response_processors\": [ { \"querqy_rewrite\": { \"info_logging\": { \"type\": \"detail\" } } } ]"
                + "}");

        final QuerqyQueryBuilder queryWithSuppression = querqyQuery();
        queryWithSuppression.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.NONE));

        final SearchResponse suppressedResponse = search(queryWithSuppression, PIPELINE_ID);
        assertTrue("The query's own info_logging: none must override the pipeline's verbose default",
                suppressedResponse.getInternalResponse().getSearchExtBuilders().isEmpty());
    }

    private static QuerqyExtBuilder getQuerqyExt(final SearchResponse response) {
        final List<SearchExtBuilder> extBuilders = response.getInternalResponse().getSearchExtBuilders();
        assertEquals(1, extBuilders.size());
        assertTrue(extBuilders.get(0) instanceof QuerqyExtBuilder);
        return (QuerqyExtBuilder) extBuilders.get(0);
    }

    private QuerqyQueryBuilder querqyQuery() {
        final QuerqyQueryBuilder query = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        query.setRewriters(Collections.singletonList(new Rewriter(rewriterId())));
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

    private void setUpRewriterAndDocs(final String rules) throws Exception {
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();
        writeCommonRulesRewriter(rules);
        indexTestDocs();
    }

    private void writeCommonRulesRewriter(final String rules) throws Exception {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        client().prepareIndex(QUERQY_INDEX_NAME).setId(rewriterId())
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    private void indexTestDocs() {
        client().admin().indices().prepareCreate(INDEX_NAME).get();
        index("doc1_a_no_d", "field1", "a", "field2", "x");
        index("doc2_a_with_d", "field1", "a d");
        index("doc3_b_with_d", "field1", "b d");
        index("doc4_b_no_d", "field1", "b");
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
