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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import querqy.json.JsonProvider;
import querqy.lucene.LuceneSearchEngineRequestAdapter;
import querqy.lucene.QuerySimilarityScoring;
import querqy.lucene.rewrite.SearchFieldsAndBoosting.FieldBoostModel;
import querqy.lucene.rewrite.cache.TermQueryCache;
import querqy.lucene.rewrite.infologging.InfoLoggingContext;
import querqy.model.QuerqyQuery;
import querqy.model.RawQuery;
import querqy.opensearch.json.XContentJsonProvider;
import querqy.opensearch.query.BoostingQueries;
import querqy.opensearch.query.Generated;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.query.RewrittenQueries;
import querqy.parser.QuerqyParser;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriteLoggingConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A {@link LuceneSearchEngineRequestAdapter} for running parse + rewrite ONLY, on the coordinating node, with no
 * shard access of any kind - used by the {@code querqy_rewrite} search pipeline request processor to call {@code
 * QueryParsingController.createExpandedQuery()} and a {@link RewriteChain}'s {@code rewrite(...)}.
 * <p>
 * Deliberately distinct from {@code DismaxSearchEngineRequestAdapter} (the per-shard adapter, which requires a real
 * {@code QueryShardContext}): every method this adapter actually needs for parse + rewrite is pure data, delegated
 * straight to the {@link QuerqyQueryBuilder} (the same way the per-shard adapter does it) - but the methods that
 * belong to the later, shard-only Lucene query-building stage (field analysis, raw-query resolution, request-level
 * boost queries, minimum-should-match, rank queries) throw a clear {@link UnsupportedOperationException} instead of
 * guessing at shard-dependent behavior with no shard to back it. {@link #getQueryAnalyzer()} is the one exception -
 * {@code QueryParsingController}'s constructor calls it unconditionally even though parse + rewrite never tokenizes
 * anything, so it returns a harmless placeholder that is built but never exercised.
 * <p>
 * Always builds an active, full-detail {@link RewriteLoggingConfig} covering every rewriter in use, independent of
 * the query's own {@code info_logging} field and of any rewriter's stored sink configuration - those gate the
 * separate, older {@code Log4jSink} delivery mechanism; this adapter's whole purpose is a different, new delivery
 * path (the {@code querqy_rewrite} response processor's {@code ext} output), so {@link #getRewriteLoggingConfig()}
 * always captures the data and lets the caller decide whether to use it (see {@code RewriterFactoryLoader}/{@code
 * DismaxSearchEngineRequestAdapter#createRewriteLoggingConfig} for why relying on the per-shard derivation would
 * silently produce nothing unless every rewriter had also been tagged for log4j).
 */
public class CoordinatorSearchEngineRequestAdapter implements LuceneSearchEngineRequestAdapter {

    private final QuerqyQueryBuilder queryBuilder;
    private final RewriteChain rewriteChain;
    private final Map<String, Object> context = new HashMap<>();
    private final RewriteLoggingConfig rewriteLoggingConfig;

    public CoordinatorSearchEngineRequestAdapter(final QuerqyQueryBuilder queryBuilder,
                                                 final RewriteChain rewriteChain) {
        this.queryBuilder = queryBuilder;
        this.rewriteChain = rewriteChain;
        this.rewriteLoggingConfig = RewriteLoggingConfig.builder()
                .isActive(true)
                .hasDetails(true)
                .includedRewriters(queryBuilder.getRewriters().stream()
                        .map(Rewriter::getName)
                        .collect(Collectors.toSet()))
                .build();
    }

    @Override
    public String getQueryString() {
        return queryBuilder.getMatchingQuery().getQueryString();
    }

    @Override
    public boolean isMatchAllQuery(final String queryString) {
        return false;
    }

    @Override
    public boolean needsScores() {
        return true;
    }

    /**
     * Never actually exercised: parse + rewrite never tokenizes anything, but {@code QueryParsingController}'s
     * constructor calls this unconditionally to build fields it only uses during Lucene query building (which this
     * adapter's caller never invokes - see this class's javadoc).
     */
    @Override
    public Analyzer getQueryAnalyzer() {
        return new KeywordAnalyzer();
    }

    @Override
    public Optional<TermQueryCache> getTermQueryCache() {
        return Optional.empty();
    }

    @Override
    public boolean addQuerqyBoostQueriesToMainQuery() {
        return true;
    }

    @Override
    public Optional<QuerySimilarityScoring> getUserQuerySimilarityScoring() {
        return queryBuilder.getMatchingQuery().getSimilarityScoring();
    }

    @Override
    public Optional<QuerySimilarityScoring> getBoostQuerySimilarityScoring() {
        final BoostingQueries boostingQueries = queryBuilder.getBoostingQueries();
        if (boostingQueries == null) {
            return Optional.empty();
        }
        return boostingQueries.getRewrittenQueries().map(RewrittenQueries::getSimilarityScoring);
    }

    @Override
    public Map<String, Float> getQueryFieldsAndBoostings() {
        return queryBuilder.getQueryFieldsAndBoostings();
    }

    @Override
    public Map<String, Float> getGeneratedQueryFieldsAndBoostings() {
        return queryBuilder.getGenerated().map(Generated::getQueryFieldsAndBoostings).orElse(Collections.emptyMap());
    }

    @Override
    public Optional<QuerqyParser> createQuerqyParser() {
        return Optional.empty();
    }

    @Override
    public boolean useFieldBoostingInQuerqyBoostQueries() {
        final BoostingQueries boostingQueries = queryBuilder.getBoostingQueries();
        if (boostingQueries == null) {
            return true;
        }
        return boostingQueries.getRewrittenQueries().map(RewrittenQueries::isUseFieldBoosts).orElse(true);
    }

    @Override
    public Optional<Float> getTiebreaker() {
        return queryBuilder.getTieBreaker();
    }

    @Override
    public Optional<Float> getMultiMatchTiebreaker() {
        return Optional.empty();
    }

    @Override
    public Query applyMinimumShouldMatch(final BooleanQuery query) {
        throw luceneBuildingStageOnly("applyMinimumShouldMatch");
    }

    @Override
    public Optional<Float> getUserQueryWeight() {
        return queryBuilder.getMatchingQuery().getWeight();
    }

    @Override
    public Optional<Float> getGeneratedFieldBoost() {
        return queryBuilder.getGenerated().flatMap(Generated::getFieldBoostFactor);
    }

    @Override
    public Optional<Float> getPositiveQuerqyBoostWeight() {
        final BoostingQueries boostingQueries = queryBuilder.getBoostingQueries();
        if (boostingQueries == null) {
            return Optional.empty();
        }
        return boostingQueries.getRewrittenQueries().map(RewrittenQueries::getPositiveWeight);
    }

    @Override
    public Optional<Float> getNegativeQuerqyBoostWeight() {
        final BoostingQueries boostingQueries = queryBuilder.getBoostingQueries();
        if (boostingQueries == null) {
            return Optional.empty();
        }
        return boostingQueries.getRewrittenQueries().map(RewrittenQueries::getNegativeWeight);
    }

    @Override
    public java.util.List<Query> getAdditiveBoosts(final QuerqyQuery<?> userQuery) {
        throw luceneBuildingStageOnly("getAdditiveBoosts");
    }

    @Override
    public java.util.List<Query> getMultiplicativeBoosts(final QuerqyQuery<?> userQuery) {
        throw luceneBuildingStageOnly("getMultiplicativeBoosts");
    }

    @Override
    public Optional<Query> parseRankQuery() {
        throw luceneBuildingStageOnly("parseRankQuery");
    }

    @Override
    public Query rawQueryToQuery(final RawQuery rawQuery) {
        throw luceneBuildingStageOnly("rawQueryToQuery");
    }

    @Override
    public Optional<FieldBoostModel> getFieldBoostModel() {
        return queryBuilder.getFieldBoostModel();
    }

    /**
     * Always empty: decoration/rewrite-log data is captured directly from this adapter's {@link #getContext()} and
     * from the rewrite call's own {@code RewriteChainOutput.getRewriteLog()} - the older {@code Sink}/{@code
     * InfoLogging} mechanism (log4j delivery) is not used here at all.
     */
    @Override
    public Optional<InfoLoggingContext> getInfoLoggingContext() {
        return Optional.empty();
    }

    @Override
    public RewriteChain getRewriteChain() {
        return rewriteChain;
    }

    @Override
    public Map<String, Object> getContext() {
        return context;
    }

    @Override
    public Optional<String> getRequestParam(final String name) {
        return getParam(name);
    }

    <T> Optional<T> getParam(final String name) {

        final String[] parts = name.split("\\.");
        if (parts.length < 3 || !"querqy".equals(parts[0])) {
            return Optional.empty();
        }

        final String rewriterId = parts[1];
        for (final Rewriter rewriter : queryBuilder.getRewriters()) {
            if (rewriterId.equals(rewriter.getName())) {
                return getRewriterParam(rewriter, Arrays.copyOfRange(parts, 2, parts.length));
            }
        }

        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    <T> Optional<T> getRewriterParam(final Rewriter rewriter, final String[] path) {

        final Map<String, Object> params = rewriter.getParams();
        if (params == null) {
            return Optional.empty();
        }

        Map<String, Object> current = params;
        final int len = path.length - 1;
        for (int i = 0; i < len; i++) {
            current = (Map<String, Object>) current.get(path[i]);
            if (current == null) {
                return Optional.empty();
            }
        }

        return Optional.ofNullable((T) current.get(path[len]));
    }

    @Override
    @SuppressWarnings("unchecked")
    public String[] getRequestParams(final String name) {
        final String[] parts = name.split("\\.");
        if (parts.length < 3 || !"querqy".equals(parts[0])) {
            return new String[0];
        }

        final String rewriterId = parts[1];
        for (final Rewriter rewriter : queryBuilder.getRewriters()) {
            if (rewriterId.equals(rewriter.getName())) {
                final Map<String, Object> params = rewriter.getParams();
                if (params == null) {
                    return new String[0];
                }

                Map<String, Object> current = params;
                final int len = parts.length - 1;
                for (int i = 2; i < len; i++) {
                    current = (Map<String, Object>) current.get(parts[i]);
                    if (current == null) {
                        return new String[0];
                    }
                }
                final Object obj = current.get(parts[len]);
                if (obj == null) {
                    return new String[0];
                }
                if (obj instanceof String) {
                    return new String[] {obj.toString()};
                } else {
                    return (String[]) obj;
                }
            }
        }

        return new String[0];
    }

    @Override
    public Optional<Boolean> getBooleanRequestParam(final String name) {
        return getParam(name);
    }

    @Override
    public Optional<Integer> getIntegerRequestParam(final String name) {
        return getParam(name);
    }

    @Override
    public Optional<Float> getFloatRequestParam(final String name) {
        return getParam(name);
    }

    @Override
    public Optional<Double> getDoubleRequestParam(final String name) {
        return getParam(name);
    }

    @Override
    public boolean isDebugQuery() {
        return false;
    }

    @Override
    public RewriteLoggingConfig getRewriteLoggingConfig() {
        return rewriteLoggingConfig;
    }

    @Override
    public JsonProvider getJsonProvider() {
        return XContentJsonProvider.INSTANCE;
    }

    private static UnsupportedOperationException luceneBuildingStageOnly(final String methodName) {
        return new UnsupportedOperationException(methodName + " belongs to the shard-side Lucene query-building "
                + "stage (QueryParsingController.buildFromRewrittenQuery), not to coordinator-side parse + rewrite "
                + "(CoordinatorSearchEngineRequestAdapter only supports createExpandedQuery()/RewriteChain.rewrite(...))");
    }
}
