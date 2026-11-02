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

package querqy.opensearch.rewriterstore;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.QuerqyPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Proves {@code TransportPutRewriterAction} eagerly resolves a {@code word_break_compound} rewriter's {@code
 * dictionaryId} reference before saving it - a missing/broken dictionary is rejected at {@code PUT} time, on this
 * (self-managed, {@code ActionPlugin}) write path, rather than only being discovered the first time a live query
 * tries to use the rewriter.
 */
public class PutWordBreakDictionaryValidationIntegrationTest extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    public void testPutIsRejectedWhenDictionaryDoesNotExist() {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryId", "does-not-exist");
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("word_break_rewriter", content);

        final ExecutionException e = expectThrows(ExecutionException.class,
                () -> client().execute(PutRewriterAction.INSTANCE, request).get());
        assertTrue(e.getCause() instanceof ResourceNotFoundException);
        assertTrue(e.getCause().getMessage().contains("does-not-exist"));

        // the rewriter must not have been saved - the dictionary check runs before the rewriter is indexed
        assertFalse(client().prepareGet(QUERQY_INDEX_NAME, "word_break_rewriter").get().isExists());
    }

    public void testPutSucceedsWhenDictionaryExists() throws Exception {

        writeTermCorpus("compounds", "haus\t10\nboot\t5\n");

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryId", "compounds");
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("word_break_rewriter", content);
        client().execute(PutRewriterAction.INSTANCE, request).get();

        assertTrue(client().prepareGet(QUERQY_INDEX_NAME, "word_break_rewriter").get().isExists());
    }

    public void testPutStillSucceedsForDictionaryFieldConfiguration() throws Exception {

        // unaffected no-op path: a shard-side dictionaryField has nothing to eagerly resolve here
        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryField", "field1");
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("word_break_rewriter", content);
        client().execute(PutRewriterAction.INSTANCE, request).get();

        assertTrue(client().prepareGet(QUERQY_INDEX_NAME, "word_break_rewriter").get().isExists());
    }

    private void writeTermCorpus(final String id, final String tsv) throws Exception {

        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();

        final Map<String, Object> source = TermCorpusConfigMapping.toLuceneSource(id,
                TermCorpusConfigMapping.FORMAT_TSV_DF, true, null, null, tsv.getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        client().prepareIndex(QUERQY_INDEX_NAME).setId(TermCorpusConfigMapping.docId(id))
                .setSource(source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }
}
