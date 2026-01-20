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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_LOWER_CASE_INPUT;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_MAX_COMBINE_LENGTH;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_MIN_BREAK_LENGTH;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_MIN_SUGGESTION_FREQ;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.DEFAULT_VERIFY_DECOMPOUND_COLLATION;
import static querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory.MAX_CHANGES;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.search.spell.WordBreakSpellChecker;
import org.apache.lucene.search.IndexSearcher;
import org.opensearch.index.query.QueryShardContext;
import querqy.opensearch.DismaxSearchEngineRequestAdapter;
import org.opensearch.index.shard.IndexShard;
import org.hamcrest.Matchers;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.test.OpenSearchTestCase;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.index.TermsEnum;
import querqy.lucene.contrib.rewrite.wordbreak.LuceneCompounder;
import querqy.lucene.contrib.rewrite.wordbreak.MorphologicalWordBreaker;
import querqy.lucene.contrib.rewrite.wordbreak.WordBreakCompoundRewriter;
import querqy.rewrite.RewriterFactory;
import querqy.trie.TrieMap;
import static org.mockito.Mockito.withSettings;
import static org.mockito.ArgumentMatchers.any;

@SuppressForbidden(reason = "Using reflection to mock top level reader context")
public class WordBreakCompoundRewriterFactoryTest extends OpenSearchTestCase {

    public void testConfigureRequiresDictionaryField() throws Exception {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        expectThrows(IllegalArgumentException.class, () -> factory.configure(Collections.emptyMap()));
    }

    public void testConfigureRequiresNonEmptyDictionaryField() throws Exception {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        expectThrows(IllegalArgumentException.class,
                () -> factory.configure(Collections.singletonMap("dictionaryField", " ")));
    }

    public void testConfigureRequiresDictionaryFieldOnly() throws Exception {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(Collections.singletonMap("dictionaryField", "f1"));
    }

    public void testValidateRequiresDictionaryField() {

        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        final List<String> errors1 = factory.validateConfiguration(Collections.emptyMap());
        assertEquals(1, errors1.size());
        assertTrue(errors1.get(0).contains("dictionaryField"));

        final List<String> errors2 = factory.validateConfiguration(Collections.singletonMap("dictionaryField", ""));
        assertEquals(1, errors2.size());
        assertTrue(errors2.get(0).contains("dictionaryField"));

    }

    public void testValidateRequiresDictionaryFieldOnly() {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        final List<String> errors = factory.validateConfiguration(Collections.singletonMap("dictionaryField", "f1"));
        assertTrue(errors == null || errors.isEmpty());
    }

    public void testValidateRefusesInvalidMorphology() {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        final Map<String, Object> config = new HashMap<>();
        config.put("dictionaryField", "f1");
        config.put("morphology", "IDIOLECT");

        final List<String> errors = factory.validateConfiguration(config);
        assertThat(errors, Matchers.contains("Unknown morphology: IDIOLECT"));
    }

    public void testThatDefaultConfigurationIsApplied() throws Exception {

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

        final LeafReader indexReader = mock(LeafReader.class, withSettings().useConstructor());
        when(indexReader.maxDoc()).thenReturn(1);

        final LeafReaderContext topReaderContext = indexReader.getContext();

        // This is horrible, but there seems to be no way to mock top level IndexReaderContext
        final Field field = IndexReaderContext.class.getDeclaredField("isTopLevel");
        field.setAccessible(true);
        field.setBoolean(topReaderContext, true);
        field.setAccessible(false);

        Terms mockTerms = mock(Terms.class);
        TermsEnum mockTermsEnum = mock(TermsEnum.class);
        when(indexReader.terms(eq("f1"))).thenReturn(mockTerms);
        when(mockTerms.iterator()).thenReturn(mockTermsEnum);

        final Map<BytesRef, Integer> freqMap = new HashMap<>();
        freqMap.put(new BytesRef("def"), 20);

        final BytesRef[] currentTerm = new BytesRef[1];

        org.mockito.Mockito.doAnswer(invocation -> {
            BytesRef term = invocation.getArgument(0);
            currentTerm[0] = term;
            return freqMap.containsKey(term);
        }).when(mockTermsEnum).seekExact(any(BytesRef.class));

        when(mockTermsEnum.docFreq()).thenAnswer(invocation -> freqMap.get(currentTerm[0]));

        wordBreaker.breakWord("abcdef", indexReader, 2, true);
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("def")));
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("abc")));

        // min break length is 3:
        verify(mockTermsEnum, times(0)).seekExact(eq(new BytesRef("ab")));
        // this will not be called by DEFAULT morphology:
        verify(mockTermsEnum, times(0)).seekExact(eq(new BytesRef("cdef")));
        verify(mockTermsEnum, times(0)).seekExact(eq(new BytesRef("abce")));

    }

    public void testThatConfigurationIsApplied() throws Exception {

        final Map<String, Object> config = new HashMap<>();
        config.put("minSuggestionFreq", 11);
        config.put("maxCombineLength", 22);
        config.put("minBreakLength", 1);
        config.put("dictionaryField", "f2");
        config.put("lowerCaseInput", !DEFAULT_LOWER_CASE_INPUT);
        config.put("alwaysAddReverseCompounds", !DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS);
        config.put("reverseCompoundTriggerWords", Arrays.asList("für", "aus"));
        config.put("protectedWords", Arrays.asList("blumen"));
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

        final LeafReader indexReader = mock(LeafReader.class, withSettings().useConstructor());
        when(indexReader.maxDoc()).thenReturn(1);

        final LeafReaderContext topReaderContext = indexReader.getContext();

        final Field field = IndexReaderContext.class.getDeclaredField("isTopLevel");
        field.setAccessible(true);
        field.setBoolean(topReaderContext, true);
        field.setAccessible(false);

        Terms mockTerms = mock(Terms.class);
        TermsEnum mockTermsEnum = mock(TermsEnum.class);
        when(indexReader.terms(eq("f2"))).thenReturn(mockTerms);
        when(mockTerms.iterator()).thenReturn(mockTermsEnum);

        final Map<BytesRef, Integer> freqMap = new HashMap<>();
        freqMap.put(new BytesRef("de"), 20);

        final BytesRef[] currentTerm = new BytesRef[1];

        org.mockito.Mockito.doAnswer(invocation -> {
            BytesRef term = invocation.getArgument(0);
            currentTerm[0] = term;
            return freqMap.containsKey(term);
        }).when(mockTermsEnum).seekExact(any(BytesRef.class));

        when(mockTermsEnum.docFreq()).thenAnswer(invocation -> freqMap.get(currentTerm[0]));

        wordBreaker.breakWord("abcde", indexReader, 2, true);
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("e")));
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("de")));
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("cde")));
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("bcde")));
        // this will be generated by GERMAN morphology:
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("abce")));

    }

    public void testThatDecompoundMorphologyIsApplied() throws Exception {
        final Map<String, Object> config = new HashMap<>();
        config.put("minSuggestionFreq", 11);
        config.put("maxCombineLength", 22);
        config.put("minBreakLength", 1);
        config.put("dictionaryField", "f2");
        config.put("lowerCaseInput", !DEFAULT_LOWER_CASE_INPUT);
        config.put("alwaysAddReverseCompounds", !DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS);
        config.put("reverseCompoundTriggerWords", Arrays.asList("für", "aus"));
        config.put("protectedWords", Arrays.asList("blumen"));
        Map<String, Object> decompoundConf = new HashMap<>();
        config.put("decompound", decompoundConf);
        decompoundConf.put("verifyCollation", !DEFAULT_VERIFY_DECOMPOUND_COLLATION);
        decompoundConf.put("maxExpansions", 87);
        decompoundConf.put("morphology", "GERMAN");
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(config);
        final MorphologicalWordBreaker wordBreaker = factory.getWordBreaker();
        assertNotNull(wordBreaker);
        final LeafReader indexReader = mock(LeafReader.class, withSettings().useConstructor());
        when(indexReader.maxDoc()).thenReturn(1);

        final LeafReaderContext topReaderContext = indexReader.getContext();

        final Field field = IndexReaderContext.class.getDeclaredField("isTopLevel");
        field.setAccessible(true);
        field.setBoolean(topReaderContext, true);
        field.setAccessible(false);

        Terms mockTerms = mock(Terms.class);
        TermsEnum mockTermsEnum = mock(TermsEnum.class);
        when(indexReader.terms(eq("f2"))).thenReturn(mockTerms);
        when(mockTerms.iterator()).thenReturn(mockTermsEnum);

        final Map<BytesRef, Integer> freqMap = new HashMap<>();
        freqMap.put(new BytesRef("de"), 20);

        final BytesRef[] currentTerm = new BytesRef[1];

        org.mockito.Mockito.doAnswer(invocation -> {
            BytesRef term = invocation.getArgument(0);
            currentTerm[0] = term;
            return freqMap.containsKey(term);
        }).when(mockTermsEnum).seekExact(any(BytesRef.class));

        when(mockTermsEnum.docFreq()).thenAnswer(invocation -> freqMap.get(currentTerm[0]));

        wordBreaker.breakWord("abcde", indexReader, 2, true);
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("e")));
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("de")));
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("cde")));
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("bcde")));
        // this will be generated by GERMAN morphology:
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("abce")));
    }

    public void testThatCompoundMorphologyIsApplied() throws Exception {
        final Map<String, Object> config = new HashMap<>();
        config.put("minSuggestionFreq", 11);
        config.put("maxCombineLength", 22);
        config.put("minBreakLength", 1);
        config.put("dictionaryField", "f2");
        config.put("lowerCaseInput", !DEFAULT_LOWER_CASE_INPUT);
        config.put("alwaysAddReverseCompounds", !DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS);
        config.put("reverseCompoundTriggerWords", Arrays.asList("für", "aus"));
        config.put("protectedWords", Arrays.asList("blumen"));
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
        final LeafReader indexReader = mock(LeafReader.class, withSettings().useConstructor());

        final LeafReaderContext topReaderContext = indexReader.getContext();

        final Field field = IndexReaderContext.class.getDeclaredField("isTopLevel");
        field.setAccessible(true);
        field.setBoolean(topReaderContext, true);
        field.setAccessible(false);

        Terms mockTerms = mock(Terms.class);
        TermsEnum mockTermsEnum = mock(TermsEnum.class);
        when(indexReader.terms(eq("f2"))).thenReturn(mockTerms);
        when(mockTerms.iterator()).thenReturn(mockTermsEnum);

        compounder.combine(new querqy.model.Term[] {
                new querqy.model.Term(null, "ab"), new querqy.model.Term(null, "de") }, indexReader, false);
        // this will be generated by GERMAN morphology:
        verify(mockTermsEnum, times(1)).seekExact(eq(new BytesRef("absde")));
    }

    public void testCreateRewriter() throws Exception {
        final WordBreakCompoundRewriterFactory factory = new WordBreakCompoundRewriterFactory("r1");
        factory.configure(Collections.singletonMap("dictionaryField", "f1"));
        final IndexShard indexShard = mock(IndexShard.class);
        final LeafReader indexReader = mock(LeafReader.class, withSettings().useConstructor());

        final LeafReaderContext topReaderContext = indexReader.getContext();

        // This is horrible, but there seems to be no way to mock top level
        // IndexReaderContext
        final Field field = IndexReaderContext.class.getDeclaredField("isTopLevel");
        field.setAccessible(true);
        field.setBoolean(topReaderContext, true);
        field.setAccessible(false);

        final QueryShardContext searchExecutionContext = mock(QueryShardContext.class);
        final IndexSearcher searcher = mock(IndexSearcher.class);
        when(searchExecutionContext.searcher()).thenReturn(searcher);
        when(searcher.getTopReaderContext()).thenReturn(topReaderContext);
        final DismaxSearchEngineRequestAdapter searchEngineRequestAdapter = mock(
                DismaxSearchEngineRequestAdapter.class);
        when(searchEngineRequestAdapter.getSearchExecutionContext()).thenReturn(searchExecutionContext);
        final RewriterFactory rewriterFactory = factory.createRewriterFactory(indexShard);
        assertTrue(
                rewriterFactory.createRewriter(null, searchEngineRequestAdapter) instanceof WordBreakCompoundRewriter);
    }
}
