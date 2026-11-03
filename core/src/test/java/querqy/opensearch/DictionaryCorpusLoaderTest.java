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
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.indices.InvalidTypeNameException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class DictionaryCorpusLoaderTest extends OpenSearchSingleNodeTestCase {

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

    private void ensureRewriterIndexExists() {
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();
    }

    private void writeTermCorpus(final String id, final String tsv, final boolean gzip) throws Exception {

        final byte[] data = tsv.getBytes(StandardCharsets.UTF_8);
        final Map<String, Object> source = TermCorpusConfigMapping.toLuceneSource(id,
                TermCorpusConfigMapping.FORMAT_TSV_DF, gzip, null, null, data, System.currentTimeMillis());

        client().prepareIndex(QUERQY_INDEX_NAME).setId(TermCorpusConfigMapping.docId(id))
                .setSource(source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    /**
     * In the real plugin, {@code QuerqyCorePlugin#createComponents} calls {@link DictionaryCorpusLoader#setClient}
     * the moment a real {@link org.opensearch.transport.client.Client} is available; tests that construct a loader
     * directly (bypassing that plugin wiring) must do the same themselves.
     */
    private DictionaryCorpusLoader newDictionaryCorpusLoader() {
        final DictionaryCorpusLoader loader = new DictionaryCorpusLoader();
        loader.setClient(client());
        loader.setThreadPool(getInstanceFromNode(org.opensearch.threadpool.ThreadPool.class));
        return loader;
    }

    public void testLoadsAndReturnsSameInstanceWhileChecksumUnchanged() throws Exception {

        ensureRewriterIndexExists();
        writeTermCorpus("d", "haus\t10\n", true);

        final DictionaryCorpusLoader loader = newDictionaryCorpusLoader();
        final DictionaryCorpusLoader.LoadedTermCorpus first = loader.load("d");
        assertTrue(first.termCorpus.exists("haus"));

        // same shared instance on a second load (even though load() always re-checks the live checksum) - proves
        // it wasn't re-fetched/re-parsed since nothing changed
        final DictionaryCorpusLoader.LoadedTermCorpus second = loader.load("d");
        assertSame(first.termCorpus, second.termCorpus);
        assertEquals(first.checksum, second.checksum);
    }

    /**
     * The whole point of dropping "immutable by convention": writing new content under the same id must be picked
     * up, not silently ignored in favor of a stale cached instance.
     */
    public void testReloadsWhenContentChanges() throws Exception {

        ensureRewriterIndexExists();
        writeTermCorpus("d", "haus\t10\n", true);

        final DictionaryCorpusLoader loader = newDictionaryCorpusLoader();
        final DictionaryCorpusLoader.LoadedTermCorpus first = loader.load("d");
        assertTrue(first.termCorpus.exists("haus"));
        assertFalse(first.termCorpus.exists("boot"));

        writeTermCorpus("d", "boot\t5\n", true);

        final DictionaryCorpusLoader.LoadedTermCorpus second = loader.load("d");
        assertNotEquals(first.checksum, second.checksum);
        assertNotSame(first.termCorpus, second.termCorpus);
        assertTrue(second.termCorpus.exists("boot"));
        assertFalse(second.termCorpus.exists("haus"));
    }

    public void testLoadBeforeSetClientFailsClearly() {
        final DictionaryCorpusLoader loader = new DictionaryCorpusLoader();
        final IllegalStateException e = expectThrows(IllegalStateException.class,
                () -> loader.load("d"));
        assertTrue(e.getMessage().contains("setClient"));
    }

    public void testMissingDictionaryFailsClearly() {

        ensureRewriterIndexExists();

        final DictionaryCorpusLoader loader = newDictionaryCorpusLoader();
        final ResourceNotFoundException e = expectThrows(ResourceNotFoundException.class,
                () -> loader.load("does-not-exist"));
        assertTrue(e.getMessage().contains("does-not-exist"));
    }

    public void testWrongDocumentTypeFailsClearly() throws Exception {

        ensureRewriterIndexExists();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "a =>\nSYNONYM: b");
        content.put("config", config);

        // write a *rewriter* document under the id a term_corpus lookup would use
        client().prepareIndex(QUERQY_INDEX_NAME).setId("d")
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();

        final DictionaryCorpusLoader loader = newDictionaryCorpusLoader();
        expectThrows(InvalidTypeNameException.class, () -> loader.load("d"));
    }

    /**
     * The {@code querqy_term_corpus} field mapper (registered via {@code QuerqyCorePlugin.getMappers()|}) validates
     * a {@code term_corpus} document at write time by actually building the corpus it describes - a malformed TSV
     * is rejected here, before {@link DictionaryCorpusLoader} ever sees it (see {@code TermCorpusConfigMappingTest}
     * for coverage of {@code buildTermCorpus}'s own error cases at the unit level).
     */
    public void testWriteTimeValidationRejectsMalformedTsv() {

        ensureRewriterIndexExists();

        final Exception e = expectThrows(Exception.class, () -> writeTermCorpus("d", "no-tab-here", false));
        boolean foundValidationMessage = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("term corpus")) {
                foundValidationMessage = true;
                break;
            }
        }
        assertTrue("Expected a 'term corpus' validation message somewhere in the cause chain of: " + e,
                foundValidationMessage);
    }

    /**
     * Same write-time validation as {@link #testWriteTimeValidationRejectsMalformedTsv()}, but for a {@code
     * tsv_df_cooc} dictionary with a bloom-filter hex value whose length isn't a multiple of 16 (see {@code
     * querqy.BloomFilter.fromHex}) - caught because validation builds a real {@code TsvDfCoocTermCorpus} from the
     * whole file, not by a separate length check (see also {@code TermCorpusConfigMappingTest
     * #testTsvDfCoocRejectsBloomHexWithInvalidLength} for the same check at the unit level).
     */
    public void testWriteTimeValidationRejectsInvalidBloomHexLength() throws Exception {

        ensureRewriterIndexExists();

        final Map<String, Object> source = TermCorpusConfigMapping.toLuceneSource("d",
                TermCorpusConfigMapping.FORMAT_TSV_DF_COOC, false, null, 2,
                "a\t1\t00\n".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        final Exception e = expectThrows(Exception.class, () -> client().prepareIndex(QUERQY_INDEX_NAME)
                .setId(TermCorpusConfigMapping.docId("d")).setSource(source)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get());

        boolean foundValidationMessage = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("multiple of 16")) {
                foundValidationMessage = true;
                break;
            }
        }
        assertTrue("Expected a bloom-hex-length validation message somewhere in the cause chain of: " + e,
                foundValidationMessage);
    }

    /**
     * {@code QuerqyTermCorpusFieldMapper} rejects a write with no {@code checksum} field, the same way it rejects
     * any other malformed content - confirming the mapper, not just {@link TermCorpusConfigMapping#verifyChecksum},
     * is what actually enforces this.
     */
    public void testWriteTimeValidationRejectsMissingChecksum() {

        ensureRewriterIndexExists();

        // built by hand (no "checksum" key), rather than via TermCorpusConfigMapping.toLuceneSource, which always
        // computes one
        final String corpusJson = "{\"format\":\"" + TermCorpusConfigMapping.FORMAT_TSV_DF + "\",\"gzip\":false,"
                + "\"data\":\"" + java.util.Base64.getEncoder().encodeToString(
                        "haus\t10\n".getBytes(StandardCharsets.UTF_8)) + "\"}";

        final Map<String, Object> source = new HashMap<>();
        source.put(TermCorpusConfigMapping.PROP_TYPE, TermCorpusConfigMapping.TYPE_VALUE);
        source.put(TermCorpusConfigMapping.PROP_VERSION, TermCorpusConfigMapping.CURRENT_MAPPING_VERSION);
        source.put(TermCorpusConfigMapping.PROP_NAME, "d");
        source.put(TermCorpusConfigMapping.PROP_CORPUS, corpusJson);

        final Exception e = expectThrows(Exception.class, () -> client().prepareIndex(QUERQY_INDEX_NAME)
                .setId("d").setSource(source).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get());

        boolean foundValidationMessage = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("checksum")) {
                foundValidationMessage = true;
                break;
            }
        }
        assertTrue("Expected a checksum validation message somewhere in the cause chain of: " + e,
                foundValidationMessage);
    }
}
