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
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;

import java.nio.charset.StandardCharsets;
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

    /**
     * Proves the sweep's dependency-checksum check (not just its rewriter-doc check): a {@code word_break_compound}
     * rewriter's dictionary is updated out-of-band (same id, new content, so a new checksum) - with no change
     * whatsoever to the rewriter's own document - and the sweep still notices and rebuilds it, with no query
     * traffic in between.
     */
    public void testSweepPicksUpDictionaryContentChangeWithoutQueryTraffic() throws Exception {

        final String dictionaryId = "sweeper_test_dictionary";
        final String wordBreakRewriterId = "sweeper_test_word_break_rewriter";

        ensureRewriterIndexExists();
        indexTestDocs();
        client().prepareIndex(INDEX_NAME).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "haus boot").get(); // the split form "hausboot" must decompound to match
        writeTermCorpus(dictionaryId, "haus\t50\nboot\t50\n");
        writeWordBreakCompoundRewriter(wordBreakRewriterId, dictionaryId);

        assertDecompoundsHausboot(wordBreakRewriterId, true); // warms the cache, resolving the dictionary

        // same id, different content - "boot" is no longer a known term, so "hausboot" can no longer be
        // decompounded into "haus"+"boot"
        writeTermCorpus(dictionaryId, "haus\t50\n");

        newSweeper().sweep(client());

        assertBusy(() -> assertDecompoundsHausboot(wordBreakRewriterId, false));
    }

    /**
     * Nothing in {@code .opensearch-querqy} prevents a {@code term_corpus} dictionary document from being written
     * to the same id as an existing rewriter. Before this was fixed, the sweep's rebuild attempt in this situation
     * threw {@code InvalidTypeNameException}, caught by a generic catch that only logged and kept the stale cache
     * entry in place - repeating every sweep cycle forever. Proves the cache entry is evicted instead.
     */
    public void testSweepEvictsRewriterFromShardCacheWhenItsIdNowHoldsADifferentDocumentType() throws Exception {

        ensureRewriterIndexExists();
        indexTestDocs();
        writeRewriter(REWRITER_ID, "k =>\nSYNONYM: z");

        assertHitCount(1L); // warms the per-shard cache

        final RewriterShardContexts shardContexts = getInstanceFromNode(RewriterShardContexts.class);
        assertTrue(shardContexts.cachedRewriterIds().contains(REWRITER_ID));

        writeTermCorpus(REWRITER_ID, "haus\t10\n"); // same id, a completely different document type

        newSweeper().sweep(client());

        assertBusy(() -> assertFalse(shardContexts.cachedRewriterIds().contains(REWRITER_ID)));
    }

    private void writeTermCorpus(final String id, final String tsv) throws Exception {
        final Map<String, Object> source = TermCorpusConfigMapping.toLuceneSource(id,
                TermCorpusConfigMapping.FORMAT_TSV_DF, true, null, null, tsv.getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());
        client().prepareIndex(QUERQY_INDEX_NAME).setId(TermCorpusConfigMapping.docId(id))
                .setSource(source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    private void writeWordBreakCompoundRewriter(final String rewriterId, final String dictionaryId)
            throws Exception {
        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryId", dictionaryId);
        content.put("config", config);
        client().prepareIndex(QUERQY_INDEX_NAME).setId(rewriterId)
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    /** Asserts whether "hausboot" decompounds into "haus"+"boot" and so matches a doc containing the split form. */
    private void assertDecompoundsHausboot(final String rewriterId, final boolean expectDecompound) throws Exception {
        final QuerqyQueryBuilder query = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        query.setRewriters(Collections.singletonList(new Rewriter(rewriterId)));
        query.setMatchingQuery(new MatchingQuery("hausboot"));
        query.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        final SearchResponse response = client().search(client().prepareSearch(INDEX_NAME).setQuery(query)
                .request()).get();
        final long hits = response.getHits().getTotalHits().value();
        if (expectDecompound) {
            assertTrue("Expected 'hausboot' to decompound and match the split-form doc", hits >= 1L);
        } else {
            assertEquals("Expected 'hausboot' to no longer decompound into 'haus'+'boot'", 0L, hits);
        }
    }

    private RewriterCacheSweeper newSweeper() {
        final RewriterCacheSweeper sweeper = new RewriterCacheSweeper(getInstanceFromNode(RewriterShardContexts.class),
                getInstanceFromNode(PipelineRewriterCacheHandle.class));
        sweeper.setThreadPool(getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class));
        return sweeper;
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
