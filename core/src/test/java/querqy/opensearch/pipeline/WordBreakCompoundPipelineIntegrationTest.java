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
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.QuerqyCorePlugin;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Proves the goal of this change end-to-end: a {@code word_break_compound} rewriter configured with a {@code
 * dictionary} reference (a {@code term_corpus} document, not a shard-side {@code dictionaryField}) works from the
 * {@code querqy_rewrite} search pipeline, which runs on the coordinator with no shard/index access at all - the one
 * thing the rewriter could not do before this change (see {@code RewriterFactoryLoaderTest
 * #testWordBreakCompoundFailsClearlyOnTheNodeLevelLoader}).
 */
public class WordBreakCompoundPipelineIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "word_break_pipeline_test_index";
    private static final String PIPELINE_ID = "word_break_pipeline_test_pipeline";
    private static final String REWRITER_ID = "word_break_pipeline_test_rewriter";
    private static final String DICTIONARY_NAME = "test-compounds";
    private static final String DICTIONARY_REVISION = "r1";

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

    public void testCompoundIsDecompoundedThroughThePipelineUsingTheTsvDictionary() throws Exception {

        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();

        writeTermCorpus(DICTIONARY_NAME, DICTIONARY_REVISION, "haus\t50\nboot\t50\n");
        writeWordBreakCompoundRewriter();

        client().admin().indices().prepareCreate(INDEX_NAME).get();
        index("doc_split_form", "field1", "haus boot");
        index("doc_unrelated", "field1", "something else entirely");
        client().admin().indices().prepareRefresh(INDEX_NAME).get();

        // sanity check: nothing contains the literal compound "hausboot" as a token
        final SearchResponse literalBaseline = client().prepareSearch(INDEX_NAME)
                .setQuery(org.opensearch.index.query.QueryBuilders.matchQuery("field1", "hausboot"))
                .get();
        assertEquals(0L, literalBaseline.getHits().getTotalHits().value());

        client().admin().cluster().putSearchPipeline(new PutSearchPipelineRequest(PIPELINE_ID,
                new BytesArray("{\"request_processors\": [ { \"querqy_rewrite\": {} } ] }"), MediaTypeRegistry.JSON))
                .get();

        final QuerqyQueryBuilder query = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        query.setRewriters(Collections.singletonList(new Rewriter(REWRITER_ID)));
        query.setMatchingQuery(new MatchingQuery("hausboot"));
        query.setQueryFieldsAndBoostings(Arrays.asList("field1"));

        final SearchRequest request = client().prepareSearch(INDEX_NAME).setQuery(query).request();
        request.pipeline(PIPELINE_ID);
        final SearchResponse response = client().search(request).get();

        assertTrue("Decompounding 'hausboot' into 'haus'+'boot' (via the TSV dictionary loaded with no shard/index "
                        + "access) must match the document containing the split form",
                response.getHits().getTotalHits().value() >= 1L);

        boolean foundSplitDoc = false;
        for (final org.opensearch.search.SearchHit hit : response.getHits().getHits()) {
            if ("doc_split_form".equals(hit.getId())) {
                foundSplitDoc = true;
            }
        }
        assertTrue("Expected doc_split_form among the hits", foundSplitDoc);
    }

    private void writeWordBreakCompoundRewriter() throws Exception {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        final Map<String, Object> dictionary = new HashMap<>();
        dictionary.put("name", DICTIONARY_NAME);
        dictionary.put("revision", DICTIONARY_REVISION);
        config.put("dictionary", dictionary);
        content.put("config", config);

        client().prepareIndex(QUERQY_INDEX_NAME).setId(REWRITER_ID)
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    private void writeTermCorpus(final String name, final String revision, final String tsv) throws Exception {

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(tsv.getBytes(StandardCharsets.UTF_8));
        }

        final Map<String, Object> source = TermCorpusConfigMapping.toLuceneSource(name, revision,
                TermCorpusConfigMapping.FORMAT_TSV_DF, true, null, null, bos.toByteArray(),
                System.currentTimeMillis());

        client().prepareIndex(QUERQY_INDEX_NAME).setId(TermCorpusConfigMapping.docId(name, revision))
                .setSource(source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
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
