/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2021 Querqy for OpenSearch Contributors
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

package querqy.opensearch.rewriter;

import querqy.lucene.LuceneTermCorpus;
import querqy.opensearch.DismaxSearchEngineRequestAdapter;
import org.opensearch.index.shard.IndexShard;
import querqy.opensearch.ConfigUtils;
import querqy.opensearch.OpenSearchRewriterFactory;
import querqy.model.ExpandedQuery;
import querqy.model.Term;
import querqy.rewrite.QueryRewriter;
import querqy.rewrite.RewriterFactory;
import querqy.rewriter.wordbreak.MorphologicalCompounder;
import querqy.rewriter.wordbreak.MorphologicalWordBreaker;
import querqy.rewriter.wordbreak.Morphology;
import querqy.rewriter.wordbreak.MorphologyProvider;
import querqy.rewriter.wordbreak.WordBreakCompoundRewriter;
import querqy.rewrite.SearchEngineRequestAdapter;
import querqy.trie.TrieMap;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class WordBreakCompoundRewriterFactory extends OpenSearchRewriterFactory {

    static final int MAX_EVALUATIONS = 100;

    static final int DEFAULT_MIN_SUGGESTION_FREQ = 1;
    static final int DEFAULT_MAX_COMBINE_LENGTH = 30;
    static final int DEFAULT_MIN_BREAK_LENGTH = 3;
    static final int DEFAULT_MAX_DECOMPOUND_EXPANSIONS = 3;
    static final boolean DEFAULT_LOWER_CASE_INPUT = false;
    static final boolean DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS = false;
    static final boolean DEFAULT_VERIFY_DECOMPOUND_COLLATION = false;
    static final String DEFAULT_MORPHOLOGY_NAME = "DEFAULT";
    private static final MorphologyProvider MORPHOLOGY_PROVIDER = new MorphologyProvider();

    private String dictionaryField;
    private boolean lowerCaseInput = DEFAULT_LOWER_CASE_INPUT;
    private boolean alwaysAddReverseCompounds = DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS;
    private MorphologicalCompounder compounder;
    private MorphologicalWordBreaker wordBreaker;
    private TrieMap<Boolean> reverseCompoundTriggerWords;
    private TrieMap<Boolean> protectedWords;
    private int maxDecompoundExpansions = DEFAULT_MAX_DECOMPOUND_EXPANSIONS;
    private boolean verifyDecompoundCollation = DEFAULT_VERIFY_DECOMPOUND_COLLATION;
    private int minSuggestionFreq = DEFAULT_MIN_SUGGESTION_FREQ;

    public WordBreakCompoundRewriterFactory(final String rewriterId) {
        super(rewriterId);
    }

    @Override
    public void configure(final Map<String, Object> config) {

        minSuggestionFreq = ConfigUtils.getArg(config, "minSuggestionFreq", DEFAULT_MIN_SUGGESTION_FREQ);
        final int minBreakLength = ConfigUtils.getArg(config, "minBreakLength", DEFAULT_MIN_BREAK_LENGTH);
        dictionaryField = ConfigUtils.getStringArg(config, "dictionaryField")
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("Missing config:  dictionaryField"));
        lowerCaseInput = ConfigUtils.getArg(config, "lowerCaseInput", DEFAULT_LOWER_CASE_INPUT);
        alwaysAddReverseCompounds = ConfigUtils.getArg(config, "alwaysAddReverseCompounds",
                DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS);

        final String defaultMorphologyName = ConfigUtils.getStringArg(config, "morphology", DEFAULT_MORPHOLOGY_NAME);

        Map<String, Object> compoundConf = (Map<String, Object>) config.get("compound");
        if (compoundConf == null) {
            compoundConf = Collections.emptyMap();
        }
        final String compoundMorphologyName = ConfigUtils.getStringArg(compoundConf, "morphology",
                defaultMorphologyName);
        final Optional<Morphology> compoundMorphology = MORPHOLOGY_PROVIDER.get(compoundMorphologyName);
        compounder = new MorphologicalCompounder(
                compoundMorphology.orElse(MorphologyProvider.DEFAULT), lowerCaseInput, minSuggestionFreq);

        Map<String, Object> decompoundConf = (Map<String, Object>) config.get("decompound");
        if (decompoundConf == null) {
            decompoundConf = Collections.emptyMap();
        }
        final String decompoundMorphologyName = ConfigUtils.getStringArg(decompoundConf, "morphology",
                defaultMorphologyName);
        final Optional<Morphology> decompoundMorphology = MORPHOLOGY_PROVIDER.get(decompoundMorphologyName);
        wordBreaker = new MorphologicalWordBreaker(
                decompoundMorphology.orElse(MorphologyProvider.DEFAULT), lowerCaseInput,
                minSuggestionFreq, minBreakLength, MAX_EVALUATIONS);

        reverseCompoundTriggerWords = ConfigUtils.getTrieSetArg(config, "reverseCompoundTriggerWords");
        protectedWords = ConfigUtils.getTrieSetArg(config, "protectedWords");
        maxDecompoundExpansions = ConfigUtils.getArg(decompoundConf, "maxExpansions",
                DEFAULT_MAX_DECOMPOUND_EXPANSIONS);
        verifyDecompoundCollation = ConfigUtils.getArg(decompoundConf, "verifyCollation",
                DEFAULT_VERIFY_DECOMPOUND_COLLATION);
    }

    @Override
    public List<String> validateConfiguration(final Map<String, Object> config) {

        final List<String> errors = new LinkedList<>();
        final Optional<String> optValue = ConfigUtils.getStringArg(config, "dictionaryField").map(String::trim)
                .filter(s -> !s.isEmpty());
        if (!optValue.isPresent()) {
            errors.add("Missing config:  dictionaryField");
        }

        ConfigUtils.getStringArg(config, "morphology").ifPresent(morphologyName -> {
            if (!MORPHOLOGY_PROVIDER.exists(morphologyName)) {
                errors.add("Unknown morphology: " + morphologyName);
            }
        });

        final Map<String, Object> decompoundConf = (Map<String, Object>) config.get("decompound");
        if (decompoundConf != null) {
            ConfigUtils.getStringArg(decompoundConf, "morphology").ifPresent(morphologyName -> {
                if (!MORPHOLOGY_PROVIDER.exists(morphologyName)) {
                    errors.add("Unknown decompound morphology: " + morphologyName);
                }
            });
        }
        final Map<String, Object> compoundConf = (Map<String, Object>) config.get("compound");
        if (compoundConf != null) {
            ConfigUtils.getStringArg(compoundConf, "morphology").ifPresent(morphologyName -> {
                if (!MORPHOLOGY_PROVIDER.exists(morphologyName)) {
                    errors.add("Unknown compound morphology: " + morphologyName);
                }
            });
        }

        return errors;
    }

    @Override
    public RewriterFactory createRewriterFactory(final IndexShard indexShard) {

        return new RewriterFactory(getRewriterId()) {
            @Override
            public QueryRewriter createRewriter(final ExpandedQuery input,
                                                final SearchEngineRequestAdapter searchEngineRequestAdapter) {

                final DismaxSearchEngineRequestAdapter adapter =
                        (DismaxSearchEngineRequestAdapter) searchEngineRequestAdapter;
                final LuceneTermCorpus corpus = new LuceneTermCorpus(
                        () -> adapter.getSearchExecutionContext().searcher().getTopReaderContext().reader(),
                        dictionaryField);

                return new WordBreakCompoundRewriter(wordBreaker, compounder, corpus,
                        lowerCaseInput, alwaysAddReverseCompounds, reverseCompoundTriggerWords,
                        maxDecompoundExpansions, verifyDecompoundCollation, protectedWords);
            }

            @Override
            public Set<Term> getGenerableTerms() {
                return QueryRewriter.EMPTY_GENERABLE_TERMS;
            }
        };
    }

    public String getDictionaryField() {
        return dictionaryField;
    }

    public boolean isLowerCaseInput() {
        return lowerCaseInput;
    }

    public boolean isAlwaysAddReverseCompounds() {
        return alwaysAddReverseCompounds;
    }

    public TrieMap<Boolean> getReverseCompoundTriggerWords() {
        return reverseCompoundTriggerWords;
    }

    public TrieMap<Boolean> getProtectedWords() {
        return protectedWords;
    }

    public int getMaxDecompoundExpansions() {
        return maxDecompoundExpansions;
    }

    public boolean isVerifyDecompoundCollation() {
        return verifyDecompoundCollation;
    }

    public MorphologicalCompounder getCompounder() {
        return compounder;
    }

    public MorphologicalWordBreaker getWordBreaker() {
        return wordBreaker;
    }
}
