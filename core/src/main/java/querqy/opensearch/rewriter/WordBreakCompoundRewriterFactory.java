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
import querqy.opensearch.DictionaryCorpusLoader;
import querqy.opensearch.DismaxSearchEngineRequestAdapter;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.transport.client.Client;
import querqy.opensearch.ConfigUtils;
import querqy.opensearch.OpenSearchRewriterFactory;
import querqy.model.Term;
import querqy.rewrite.QueryRewriter;
import querqy.rewrite.RewriterFactory;
import querqy.rewriter.wordbreak.MorphologicalCompounder;
import querqy.rewriter.wordbreak.MorphologicalWordBreaker;
import querqy.rewriter.wordbreak.Morphology;
import querqy.rewriter.wordbreak.MorphologyProvider;
import querqy.rewriter.wordbreak.OptionalModifierConfig;
import querqy.rewriter.wordbreak.OptionalModifierPosition;
import querqy.rewriter.wordbreak.TermCorpus;
import querqy.rewriter.wordbreak.WordBreakCompoundRewriter;
import querqy.rewrite.SearchEngineRequestAdapter;
import querqy.trie.TrieMap;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class WordBreakCompoundRewriterFactory extends OpenSearchRewriterFactory {

    static final int MAX_EVALUATIONS = 100;

    static final int DEFAULT_MIN_SUGGESTION_FREQ = 1;
    static final int DEFAULT_MIN_BREAK_LENGTH = 3;
    static final int DEFAULT_MAX_DECOMPOUND_EXPANSIONS = 3;
    static final int DEFAULT_MAX_COMPOUND_EXPANSIONS = 3;
    static final boolean DEFAULT_LOWER_CASE_INPUT = false;
    static final boolean DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS = false;
    static final boolean DEFAULT_VERIFY_DECOMPOUND_COLLATION = false;
    static final String DEFAULT_MORPHOLOGY_NAME = "DEFAULT";
    static final String DEFAULT_OPTIONAL_MODIFIER_POSITION = OptionalModifierPosition.NONE.name();
    static final float DEFAULT_OPTIONAL_MODIFIER_BOOST = 1.0f;
    private static final MorphologyProvider MORPHOLOGY_PROVIDER = new MorphologyProvider();

    private String dictionaryField;
    private boolean lowerCaseInput = DEFAULT_LOWER_CASE_INPUT;
    private boolean alwaysAddReverseCompounds = DEFAULT_ALWAYS_ADD_REVERSE_COMPOUNDS;
    private MorphologicalCompounder compounder;
    private MorphologicalWordBreaker wordBreaker;
    private TrieMap<Boolean> reverseCompoundTriggerWords;
    private TrieMap<Boolean> protectedWords;
    private int maxDecompoundExpansions = DEFAULT_MAX_DECOMPOUND_EXPANSIONS;
    private int maxCompoundExpansions = DEFAULT_MAX_COMPOUND_EXPANSIONS;
    private boolean verifyDecompoundCollation = DEFAULT_VERIFY_DECOMPOUND_COLLATION;
    private int minSuggestionFreq = DEFAULT_MIN_SUGGESTION_FREQ;
    private OptionalModifierConfig optionalModifierConfig = OptionalModifierConfig.DISABLED;
    private DictionaryRef dictionaryRef;

    public WordBreakCompoundRewriterFactory(final String rewriterId) {
        super(rewriterId);
    }

    /**
     * A reference to a {@code term_corpus} document in {@code .opensearch-querqy} (see {@link
     * querqy.opensearch.rewriterstore.TermCorpusConfigMapping}), used instead of {@link #dictionaryField} to build
     * a coordinator-safe (TSV-based) {@link TermCorpus} that needs no shard/index access at all.
     */
    private static final class DictionaryRef {
        final String name;
        final String revision;

        DictionaryRef(final String name, final String revision) {
            this.name = name;
            this.revision = revision;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure(final Map<String, Object> config) {

        minSuggestionFreq = ConfigUtils.getArg(config, "minSuggestionFreq", DEFAULT_MIN_SUGGESTION_FREQ);
        final int minBreakLength = ConfigUtils.getArg(config, "minBreakLength", DEFAULT_MIN_BREAK_LENGTH);

        final Optional<String> dictionaryFieldOpt = ConfigUtils.getStringArg(config, "dictionaryField")
                .map(String::trim)
                .filter(s -> !s.isEmpty());
        final Map<String, Object> dictionaryConf = (Map<String, Object>) config.get("dictionary");

        if (dictionaryFieldOpt.isPresent() && dictionaryConf != null) {
            throw new IllegalArgumentException("Specify exactly one of 'dictionaryField' or 'dictionary', not both");
        }
        if (!dictionaryFieldOpt.isPresent() && dictionaryConf == null) {
            throw new IllegalArgumentException(
                    "Missing config: exactly one of 'dictionaryField' or 'dictionary' is required");
        }

        if (dictionaryConf != null) {
            final String name = ConfigUtils.getStringArg(dictionaryConf, "name").map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .orElseThrow(() -> new IllegalArgumentException("Missing config: dictionary.name"));
            final String revision = ConfigUtils.getStringArg(dictionaryConf, "revision").map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .orElseThrow(() -> new IllegalArgumentException("Missing config: dictionary.revision"));
            dictionaryRef = new DictionaryRef(name, revision);
            dictionaryField = null;
        } else {
            dictionaryField = dictionaryFieldOpt.get();
            dictionaryRef = null;
        }

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
        maxCompoundExpansions = ConfigUtils.getArg(compoundConf, "maxExpansions", DEFAULT_MAX_COMPOUND_EXPANSIONS);
        compounder = new MorphologicalCompounder(
                compoundMorphology.orElse(MorphologyProvider.DEFAULT), lowerCaseInput, minSuggestionFreq,
                maxCompoundExpansions);

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

        final String optionalModifierPositionName = ConfigUtils.getStringArg(decompoundConf,
                "optionalModifierPosition", DEFAULT_OPTIONAL_MODIFIER_POSITION);
        final float optionalModifierBoost = ((Number) decompoundConf.getOrDefault(
                "optionalModifierBoost", DEFAULT_OPTIONAL_MODIFIER_BOOST)).floatValue();
        optionalModifierConfig = new OptionalModifierConfig(
                parseOptionalModifierPosition(optionalModifierPositionName), optionalModifierBoost);
    }

    private static OptionalModifierPosition parseOptionalModifierPosition(final String name) {
        try {
            return OptionalModifierPosition.valueOf(name.trim().toUpperCase());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("No such optionalModifierPosition " + name + ". Valid values: "
                    + Arrays.toString(OptionalModifierPosition.values()));
        }
    }

    @Override
    public List<String> validateConfiguration(final Map<String, Object> config) {

        final List<String> errors = new LinkedList<>();

        final Optional<String> dictionaryFieldOpt = ConfigUtils.getStringArg(config, "dictionaryField")
                .map(String::trim).filter(s -> !s.isEmpty());
        final Map<String, Object> dictionaryConf = (Map<String, Object>) config.get("dictionary");

        if (dictionaryFieldOpt.isPresent() && dictionaryConf != null) {
            errors.add("Specify exactly one of 'dictionaryField' or 'dictionary', not both");
        } else if (!dictionaryFieldOpt.isPresent() && dictionaryConf == null) {
            errors.add("Missing config: exactly one of 'dictionaryField' or 'dictionary' is required");
        } else if (dictionaryConf != null) {
            if (!ConfigUtils.getStringArg(dictionaryConf, "name").map(String::trim)
                    .filter(s -> !s.isEmpty()).isPresent()) {
                errors.add("Missing config: dictionary.name");
            }
            if (!ConfigUtils.getStringArg(dictionaryConf, "revision").map(String::trim)
                    .filter(s -> !s.isEmpty()).isPresent()) {
                errors.add("Missing config: dictionary.revision");
            }
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

            final String optionalModifierPositionName = ConfigUtils.getStringArg(decompoundConf,
                    "optionalModifierPosition", DEFAULT_OPTIONAL_MODIFIER_POSITION);
            final float optionalModifierBoost = ((Number) decompoundConf.getOrDefault(
                    "optionalModifierBoost", DEFAULT_OPTIONAL_MODIFIER_BOOST)).floatValue();
            try {
                new OptionalModifierConfig(
                        parseOptionalModifierPosition(optionalModifierPositionName), optionalModifierBoost);
            } catch (final IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
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

    /**
     * When configured with a shard-side {@code dictionaryField} (not {@code dictionary}), {@code createRewriter(...)}
     * (below) needs a real shard-backed {@code IndexSearcher} (via {@code
     * adapter.getSearchExecutionContext().searcher()}) to build its {@link LuceneTermCorpus} - unlike every other
     * built-in rewriter, this one genuinely cannot run on a coordinating node with no shard access at all in that
     * mode. Configured with a {@code dictionary} reference instead, it needs no shard access at all (see {@link
     * #createRewriterFactory(IndexShard, DictionaryCorpusLoader, Client)}), so it is coordinator-safe.
     */
    @Override
    public boolean isSupportedOnCoordinator() {
        return dictionaryRef != null;
    }

    @Override
    public RewriterFactory createRewriterFactory(final IndexShard indexShard) {

        return new RewriterFactory(getRewriterId()) {
            @Override
            public QueryRewriter createRewriter(final SearchEngineRequestAdapter searchEngineRequestAdapter) {

                final DismaxSearchEngineRequestAdapter adapter =
                        (DismaxSearchEngineRequestAdapter) searchEngineRequestAdapter;
                final LuceneTermCorpus corpus = new LuceneTermCorpus(
                        () -> adapter.getSearchExecutionContext().searcher().getTopReaderContext().reader(),
                        dictionaryField);

                return new WordBreakCompoundRewriter(wordBreaker, compounder, corpus,
                        lowerCaseInput, alwaysAddReverseCompounds, reverseCompoundTriggerWords,
                        maxDecompoundExpansions, verifyDecompoundCollation, protectedWords,
                        optionalModifierConfig);
            }

            @Override
            public Set<Term> getGenerableTerms() {
                return QueryRewriter.EMPTY_GENERABLE_TERMS;
            }
        };
    }

    /**
     * Configured with a {@code dictionary} reference, resolves the named/revisioned {@code term_corpus} document
     * via {@code dictionaryCorpusLoader} - once here, not per rewrite - and builds the rewriter directly from it,
     * ignoring {@code indexShard} entirely (so this also works in the classic per-shard {@code querqy} query, not
     * only in the {@code querqy_rewrite} search pipeline). Configured with {@code dictionaryField} instead, falls
     * back to {@link #createRewriterFactory(IndexShard)} unchanged.
     */
    @Override
    public RewriterFactory createRewriterFactory(final IndexShard indexShard,
                                                 final DictionaryCorpusLoader dictionaryCorpusLoader,
                                                 final Client client) {

        if (dictionaryRef == null) {
            return createRewriterFactory(indexShard);
        }

        final TermCorpus corpus = dictionaryCorpusLoader.load(dictionaryRef.name, dictionaryRef.revision, client);

        return new RewriterFactory(getRewriterId()) {
            @Override
            public QueryRewriter createRewriter(final SearchEngineRequestAdapter searchEngineRequestAdapter) {
                return new WordBreakCompoundRewriter(wordBreaker, compounder, corpus,
                        lowerCaseInput, alwaysAddReverseCompounds, reverseCompoundTriggerWords,
                        maxDecompoundExpansions, verifyDecompoundCollation, protectedWords,
                        optionalModifierConfig);
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

    public String getDictionaryName() {
        return dictionaryRef == null ? null : dictionaryRef.name;
    }

    public String getDictionaryRevision() {
        return dictionaryRef == null ? null : dictionaryRef.revision;
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

    public int getMaxCompoundExpansions() {
        return maxCompoundExpansions;
    }

    public boolean isVerifyDecompoundCollation() {
        return verifyDecompoundCollation;
    }

    public OptionalModifierConfig getOptionalModifierConfig() {
        return optionalModifierConfig;
    }

    public MorphologicalCompounder getCompounder() {
        return compounder;
    }

    public MorphologicalWordBreaker getWordBreaker() {
        return wordBreaker;
    }
}
