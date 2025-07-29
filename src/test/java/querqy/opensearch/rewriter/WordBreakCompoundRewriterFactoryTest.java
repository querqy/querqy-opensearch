/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package querqy.opensearch.rewriter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_LOWER_CASE_INPUT;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_MAX_COMBINE_LENGTH;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_MIN_BREAK_LENGTH;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_MIN_SUGGESTION_FREQ;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_VERIFY_DECOMPOUND_COLLATION;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.MAX_CHANGES;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.spell.WordBreakSpellChecker;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.opensearch.index.query.QueryShardContext;
import querqy.opensearch.DismaxSearchEngineRequestAdapter;
import org.opensearch.index.shard.IndexShard;
import org.hamcrest.Matchers;
import org.junit.Test;
import querqy.lucene.contrib.rewrite.wordbreak.LuceneCompounder;
import querqy.lucene.contrib.rewrite.wordbreak.MorphologicalWordBreaker;
import querqy.lucene.contrib.rewrite.wordbreak.WordBreakCompoundRewriter;
import querqy.rewrite.RewriterFactory;
import querqy.trie.TrieMap;


public class WordBreakCompoundRewriterFactoryTest extends LuceneTestCase {

    @Test(expected = IllegalArgumentException.class)
    public void testConfigureRequiresDictionaryField() {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(Collections.emptyMap());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigureRequiresNonEmptyDictionaryField() {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(Collections.singletonMap("dictionaryField", " "));
    }


    @Test
    public void testConfigureRequiresDictionaryFieldOnly() {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(Collections.singletonMap("dictionaryField", "f1"));
    }


    @Test
    public void testValidateRequiresDictionaryField() {

        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        final List<String> errors1 = factory.validateConfiguration(Collections.emptyMap());
        assertEquals(1, errors1.size());
        assertTrue(errors1.getFirst().contains("dictionaryField"));

        final List<String> errors2 = factory.validateConfiguration(Collections.singletonMap("dictionaryField", ""));
        assertEquals(1, errors2.size());
        assertTrue(errors2.getFirst().contains("dictionaryField"));

    }


    @Test
    public void testValidateRequiresDictionaryFieldOnly() {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        final List<String> errors = factory.validateConfiguration(Collections.singletonMap("dictionaryField", "f1"));
        assertTrue(errors == null || errors.isEmpty());
    }

    @Test
    public void testValidateRefusesInvalidMorphology() {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryField", "f1");
        config.put("morphology", "IDIOLECT");

        final List<String> errors = factory.validateConfiguration(config);
        assertThat(errors, Matchers.contains("Unknown morphology: IDIOLECT"));
    }

    @Test
    public void testThatDefaultConfigurationIsApplied() {

        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(Collections.singletonMap("dictionaryField", "f1"));
        final WordBreakSpellChecker spellChecker = factory.getSpellChecker();
        assertNotNull(spellChecker);
        assertEquals(MAX_CHANGES, spellChecker.getMaxChanges());
        assertEquals(DEFAULT_MAX_COMBINE_LENGTH, spellChecker.getMaxCombineWordLength());
        assertEquals(DEFAULT_MIN_SUGGESTION_FREQ, spellChecker.getMinSuggestionFrequency());
        assertEquals(DEFAULT_MIN_BREAK_LENGTH, spellChecker.getMinBreakWordLength());
        assertEquals(DEFAULT_LOWER_CASE_INPUT, factory.isLowerCaseInput());
        assertEquals(DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS, factory.isAlwaysAddReverseCompounds());
        assertEquals(DEFAULT_VERIFY_DECOMPOUND_COLLATION, factory.isVerifyDecompoundCollation());

        assertEquals("f1", factory.getDictionaryField());

        assertNotNull(factory.getCompounder());

        final MorphologicalWordBreaker wordBreaker = factory.getWordBreaker();
        assertNotNull(wordBreaker);

    }


    @Test
    public void testThatConfigurationIsApplied() {

        final Map<String, Object> config = new HashMap<>();
        config.put("minSuggestionFreq", 11);
        config.put("maxCombineLength", 22);
        config.put("minBreakLength", 1);
        config.put("dictionaryField", "f2");
        config.put("lowerCaseInput", !DEFAULT_LOWER_CASE_INPUT);
        config.put("alwaysAddReverseCompounds", !DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS);
        config.put("reverseCompoundTriggerWords", Arrays.asList("für", "aus"));
        config.put("protectedWords", List.of("blumen"));
        config.put("morphology", "GERMAN");

        Map<String, Object> decompoundConf = new HashMap<>();
        config.put("decompound", decompoundConf);

        decompoundConf.put("verifyCollation", !DEFAULT_VERIFY_DECOMPOUND_COLLATION);
        decompoundConf.put("maxExpansions", 87);


        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(config);


        final WordBreakSpellChecker spellChecker = factory.getSpellChecker();
        assertNotNull(spellChecker);

        assertEquals(22, spellChecker.getMaxCombineWordLength());
        assertEquals(11, spellChecker.getMinSuggestionFrequency());
        assertEquals(1, spellChecker.getMinBreakWordLength());
        assertEquals(87, factory.getMaxDecompoundExpansions());

        assertNotEquals(DEFAULT_LOWER_CASE_INPUT, factory.isLowerCaseInput());
        assertNotEquals(DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS, factory.isAlwaysAddReverseCompounds());
        assertNotEquals(DEFAULT_VERIFY_DECOMPOUND_COLLATION, factory.isVerifyDecompoundCollation());

        assertEquals("f2", factory.getDictionaryField());

        final TrieMap<Boolean> words = factory.getReverseCompoundTriggerWords();
        assertNotNull(words);
        assertTrue(words.get("für").getStateForCompleteSequence().isFinal());
        assertTrue(words.get("aus").getStateForCompleteSequence().isFinal());

        final TrieMap<Boolean> protectedWords = factory.getProtectedWords();
        assertNotNull(protectedWords);
        assertTrue(protectedWords.get("blumen").getStateForCompleteSequence().isFinal());

        final MorphologicalWordBreaker wordBreaker = factory.getWordBreaker();
        assertNotNull(wordBreaker);

    }

    @Test
    public void testThatDecompoundMorphologyIsApplied() throws Exception  {
        final Map<String, Object> config = new HashMap<>();
        config.put("minSuggestionFreq", 2);
        config.put("maxCombineLength", 22);
        config.put("minBreakLength", 1);
        config.put("dictionaryField", "f2");
        config.put("lowerCaseInput", !DEFAULT_LOWER_CASE_INPUT);
        config.put("alwaysAddReverseCompounds", !DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS);
        config.put("reverseCompoundTriggerWords", Arrays.asList("für", "aus"));
        config.put("protectedWords", List.of("blumen"));
        Map<String, Object> decompoundConf = new HashMap<>();
        config.put("decompound", decompoundConf);
        decompoundConf.put("verifyCollation", !DEFAULT_VERIFY_DECOMPOUND_COLLATION);
        decompoundConf.put("maxExpansions", 87);
        decompoundConf.put("morphology", "GERMAN");
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(config);
        final MorphologicalWordBreaker wordBreaker = factory.getWordBreaker();
        assertNotNull(wordBreaker);

        final Analyzer analyzer = new WhitespaceAnalyzer();
        final Directory directory = newDirectory();
        final RandomIndexWriter indexWriter = new RandomIndexWriter(random(), directory, analyzer);

        addNumDocsWithTextField("f2", "arbeit jacke", indexWriter, 12);

        indexWriter.close();

        try (final IndexReader indexReader = DirectoryReader.open(directory)) {
            final List<CharSequence[]> broken = wordBreaker.breakWord("arbeitsjacke", indexReader, 2, true);
            assertNotNull(broken);
            assertEquals(1, broken.size());
            final CharSequence[] result = broken.getFirst();
            assertNotNull(result);
            assertEquals("arbeit", result[0].toString());
            assertEquals("jacke", result[1].toString());
        } finally {
            try {
                directory.close();
            } catch (final IOException e) {
                //
            }
        }

    }

    @Test
    public void testThatCompoundMorphologyIsApplied() throws Exception  {
        final Map<String, Object> config = new HashMap<>();
        config.put("minSuggestionFreq", 11);
        config.put("maxCombineLength", 5);
        config.put("minBreakLength", 1);
        config.put("dictionaryField", "f2");
        config.put("lowerCaseInput", !DEFAULT_LOWER_CASE_INPUT);
        config.put("alwaysAddReverseCompounds", !DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS);
        config.put("reverseCompoundTriggerWords", Arrays.asList("für", "aus"));
        config.put("protectedWords", List.of("blumen"));
        final Map<String, Object> decompoundConf = new HashMap<>();
        config.put("decompound", decompoundConf);
        decompoundConf.put("verifyCollation", !DEFAULT_VERIFY_DECOMPOUND_COLLATION);
        decompoundConf.put("maxExpansions", 87);
        final Map<String, Object> compoundConf = new HashMap<>();
        config.put("compound", compoundConf);
        compoundConf.put("morphology", "GERMAN");
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(config);
        final LuceneCompounder compounder = factory.getCompounder();
        final MorphologicalWordBreaker wordBreaker = factory.getWordBreaker();
        assertNotNull(wordBreaker);

        final Analyzer analyzer = new WhitespaceAnalyzer();
        final Directory directory = newDirectory();
        final RandomIndexWriter indexWriter = new RandomIndexWriter(random(), directory, analyzer);

        addNumDocsWithTextField("f2", "absde", indexWriter, 12);

        indexWriter.close();

        try (final IndexReader indexReader = DirectoryReader.open(directory)) {
            final List<LuceneCompounder.CompoundTerm> combined = compounder.combine(new querqy.model.Term[]{
                    new querqy.model.Term(null, "ab"), new querqy.model.Term(null, "de")}, indexReader, false);
            // this will be generated by GERMAN morphology:
            assertNotNull(combined);
            assertEquals(1, combined.size());
            assertEquals("absde", combined.getFirst().value.toString());

        }  finally {
            try {
                directory.close();
            } catch (final IOException e) {
                //
            }
        }
    }

    @Test
    public void testCreateRewriter() throws Exception {


        final Analyzer analyzer = new WhitespaceAnalyzer();
        final Directory directory = newDirectory();
        final RandomIndexWriter indexWriter = new RandomIndexWriter(random(), directory, analyzer);
        indexWriter.close();

        try (final IndexReader indexReader = DirectoryReader.open(directory)) {
            final QueryShardContext searchExecutionContext = mock(QueryShardContext.class);
            final IndexShard indexShard = mock(IndexShard.class);

            final IndexSearcher searcher = mock(IndexSearcher.class);
            when(searchExecutionContext.searcher()).thenReturn(searcher);

            when(searcher.getTopReaderContext()).thenReturn(indexReader.getContext());

            final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
            factory.configure(Collections.singletonMap("dictionaryField", "f1"));

            final DismaxSearchEngineRequestAdapter searchEngineRequestAdapter =
                    mock(DismaxSearchEngineRequestAdapter.class);

            when(searchEngineRequestAdapter.getSearchExecutionContext()).thenReturn(searchExecutionContext);
            final RewriterFactory rewriterFactory = factory.createRewriterFactory(indexShard);
            assertTrue(rewriterFactory.createRewriter(null, searchEngineRequestAdapter) instanceof
                    WordBreakCompoundRewriter);


        } finally {
            try {
                directory.close();
            } catch (final IOException e) {
                //
            }
        }






    }

    public static void addNumDocsWithTextField(final String fieldname, final String value,
                                               final RandomIndexWriter indexWriter, final int num) throws IOException {
        indexWriter.addDocuments(IntStream.range(0, num).mapToObj(i -> {

            final Document doc = new Document();
            doc.add(LuceneTestCase.newTextField(fieldname, value, org.apache.lucene.document.Field.Store.YES));
            return doc;

        }).collect(Collectors.toList()));

    }
}
