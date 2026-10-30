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
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;
import querqy.rewrite.RewriterFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Proves the central claim behind {@link RewriterFactoryLoader#nodeLevel(Settings, Client, boolean,
 * DictionaryCorpusLoader)}: loading
 * and building a {@link RewriterFactory} from a rewriter-config document needs nothing shard-specific at all -
 * no {@code IndexService}, no {@code IndexShard}, not even an index with any local shard on this node. This is
 * the prerequisite a coordinator-side consumer (e.g. a future search pipeline processor, which runs with no
 * shard access whatsoever) needs: unlike {@link RewriterShardContext} (keyed by {@code ShardId}, resolved via
 * {@code IndicesService}), {@link RewriterFactoryLoader#nodeLevel} only ever needs a {@link
 * org.opensearch.transport.client.Client}.
 */
public class RewriterFactoryLoaderTest extends OpenSearchSingleNodeTestCase {

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

    private void ensureRewriterIndexExists() throws Exception {
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();
    }

    /**
     * In the real plugin, {@code QuerqyCorePlugin#createComponents} calls {@link DictionaryCorpusLoader#setClient}
     * the moment a real {@link org.opensearch.transport.client.Client} is available; tests that construct a
     * {@link RewriterFactoryLoader} directly (bypassing that plugin wiring) must do the same themselves.
     */
    private DictionaryCorpusLoader newDictionaryCorpusLoader() {
        final DictionaryCorpusLoader loader = new DictionaryCorpusLoader();
        loader.setClient(client());
        return loader;
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

    /**
     * No index named in this test ever has any document indexed into it (other than {@code .opensearch-querqy}
     * itself, which the loader only ever accesses via plain document GETs, never via a shard reference) - so
     * there is no "target" {@code IndexShard} anywhere this loader could have reached for even if it wanted to.
     */
    public void testLoadsAndBuildsRewriterFactoryWithNoShardAccessAtAll() throws Exception {

        ensureRewriterIndexExists();
        writeCommonRulesRewriter("node_level_rewriter", "k =>\nSYNONYM: c");

        final RewriterFactoryLoader loader = RewriterFactoryLoader.nodeLevel(Settings.EMPTY, client(), false,
                newDictionaryCorpusLoader());

        final RewriterFactoryAndLogging loaded = loader.loadFactory("node_level_rewriter", false);

        assertNotNull(loaded);
        final RewriterFactory factory = loaded.rewriterFactory;
        assertNotNull(factory);
        assertEquals("node_level_rewriter", factory.getRewriterId());

        // cached on the second call too, still with no shard involved
        final RewriterFactoryAndLogging loadedAgain = loader.loadFactory("node_level_rewriter", false);
        assertSame(factory, loadedAgain.rewriterFactory);
    }

    public void testUnknownRewriterFailsClearlyWithNoShardAccess() throws Exception {

        ensureRewriterIndexExists();

        final RewriterFactoryLoader loader = RewriterFactoryLoader.nodeLevel(Settings.EMPTY, client(), false,
                newDictionaryCorpusLoader());

        final ResourceNotFoundException e = expectThrows(ResourceNotFoundException.class,
                () -> loader.loadFactory("does_not_exist", false));
        assertEquals("Rewriter not found: does_not_exist", e.getMessage());
    }

    /**
     * {@code nodeLevel(...)} (unlike the per-shard constructor {@link RewriterShardContext} uses) enforces {@link
     * OpenSearchRewriterFactory#isSupportedOnCoordinator()} - word-break-compound genuinely needs a real shard-
     * backed index reader to run its rewrite (not just to build its factory), so it must fail clearly here rather
     * than fail confusingly later when the rewrite itself runs with no shard behind it.
     */
    public void testWordBreakCompoundFailsClearlyOnTheNodeLevelLoader() throws Exception {

        ensureRewriterIndexExists();
        writeWordBreakCompoundRewriter("word_break_rewriter");

        final RewriterFactoryLoader loader = RewriterFactoryLoader.nodeLevel(Settings.EMPTY, client(), false,
                newDictionaryCorpusLoader());

        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> loader.loadFactory("word_break_rewriter", false));
        assertTrue(e.getMessage().contains("word_break_rewriter"));
        assertTrue(e.getMessage().contains("WordBreakCompoundRewriterFactory"));
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
     * Unlike {@link #testWordBreakCompoundFailsClearlyOnTheNodeLevelLoader()}, a word-break-compound rewriter
     * configured with a {@code dictionary} reference (a {@code term_corpus} document) instead of a shard-side
     * {@code dictionaryField} needs no shard access at all, so it must succeed on the node-level loader.
     */
    public void testWordBreakCompoundWithDictionaryReferenceSucceedsOnTheNodeLevelLoader() throws Exception {

        ensureRewriterIndexExists();
        writeTermCorpus("compounds", "r1", "haus\t10\nboot\t5\n");
        writeWordBreakCompoundRewriterWithDictionary("word_break_rewriter", "compounds", "r1");

        final RewriterFactoryLoader loader = RewriterFactoryLoader.nodeLevel(Settings.EMPTY, client(), false,
                newDictionaryCorpusLoader());

        final RewriterFactoryAndLogging loaded = loader.loadFactory("word_break_rewriter", false);

        assertNotNull(loaded);
        assertNotNull(loaded.rewriterFactory);
        assertEquals("word_break_rewriter", loaded.rewriterFactory.getRewriterId());
    }

    private void writeWordBreakCompoundRewriterWithDictionary(final String rewriterId, final String dictionaryName,
                                                              final String dictionaryRevision) throws Exception {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        final Map<String, Object> dictionary = new HashMap<>();
        dictionary.put("name", dictionaryName);
        dictionary.put("revision", dictionaryRevision);
        config.put("dictionary", dictionary);
        content.put("config", config);

        client().prepareIndex(QUERQY_INDEX_NAME).setId(rewriterId)
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
}
