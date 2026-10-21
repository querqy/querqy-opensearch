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

package querqy.lucene;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.junit.Test;
import querqy.model.ExpandedQuery;
import querqy.opensearch.searchpipelinespike.SpikeQuerqyModelCodec;
import querqy.parser.WhiteSpaceQuerqyParser;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriteChainOutput;
import querqy.rewrite.RewriteLoggingConfig;
import querqy.rewrite.RewriterFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase-0 "decisive serialization spike" (briefing section 5): can a Common Rules rewrite be run ONCE,
 * its result (a {@code querqy.model.ExpandedQuery}) serialized, shipped elsewhere, and rebuilt into Lucene
 * queries there via the existing {@code LuceneQueryBuilder} stage - with the same result as running the
 * whole thing (parse + rewrite + build) in one place, as {@link QueryParsingController#process()} does
 * today?
 * <p>
 * This package ({@code querqy.lucene}) is chosen deliberately so this test can read {@link
 * QueryParsingController}'s {@code protected} {@code builder} field directly (same package, cross-module,
 * classpath-mode - no JPMS module boundary here), since {@code transformUserQuery(QuerqyQuery, LuceneQueryBuilder)}
 * takes that builder as an explicit parameter rather than using it implicitly.
 * <p>
 * <b>Finding:</b> yes, for the node types this spike covers (see {@link SpikeQuerqyModelCodec}) - a rewrite
 * producing a SYNONYM-expanded main query term and an UP-boosted query round-trips through serialization
 * with the resulting Lucene queries identical ({@code equals()} and {@code toString()}) to the
 * no-round-trip baseline. See {@link SpikeQuerqyModelCodec}'s javadoc for what a production serializer
 * would still need to cover beyond this.
 */
public class CutAtQuerqyModelSpikeTest {

    private static final String RULES =
            "a =>\n" +
            "  SYNONYM: b\n" +
            "  UP(10): c\n" +
            "  FILTER: d\n";

    @Test
    public void testRewrittenModelSurvivesSerializationAndRebuildsToTheSameLuceneQuery() throws Exception {

        final RewriterFactory rewriterFactory = buildCommonRulesRewriterFactory();
        final RewriteChain rewriteChain = new RewriteChain(Collections.singletonList(rewriterFactory));

        final LuceneSearchEngineRequestAdapter requestAdapter = requestAdapter(rewriteChain);

        // --- "coordinator": parse + rewrite only ---
        final QueryParsingController coordinatorController = new QueryParsingController(requestAdapter);
        final ExpandedQuery parsedInput = coordinatorController.createExpandedQuery();
        final RewriteChainOutput rewriteChainOutput = rewriteChain.rewrite(parsedInput, requestAdapter);
        final ExpandedQuery rewrittenExpandedQuery = rewriteChainOutput.getExpandedQuery();

        // sanity check this spike is actually exercising SYNONYM+UP, not a no-op
        assertEquals("Expected the UP rule to have produced exactly one boost-up query",
                1, rewrittenExpandedQuery.getBoostUpQueries().size());

        // --- the wire: serialize, then deserialize into a brand new, independent object graph ---
        final byte[] bytes = SpikeQuerqyModelCodec.serialize(rewrittenExpandedQuery);
        final ExpandedQuery roundTripped = SpikeQuerqyModelCodec.deserialize(bytes);

        // --- "shard": build Lucene queries from each, via two independent controller instances ---
        final Query originalMainQuery = buildMainQuery(requestAdapter, rewrittenExpandedQuery);
        final Query roundTrippedMainQuery = buildMainQuery(requestAdapter, roundTripped);

        assertEquals("Main query built from the round-tripped model must equal the one built directly",
                originalMainQuery, roundTrippedMainQuery);
        assertEquals(originalMainQuery.toString(), roundTrippedMainQuery.toString());

        final List<Query> originalBoosts = buildAdditiveBoostQueries(requestAdapter, rewrittenExpandedQuery);
        final List<Query> roundTrippedBoosts = buildAdditiveBoostQueries(requestAdapter, roundTripped);

        assertEquals(1, originalBoosts.size());
        assertEquals("The UP-boost query built from the round-tripped model must equal the original",
                originalBoosts, roundTrippedBoosts);

        // this is what a hybrid query's filter(...) pushdown would consume - must also survive the round trip
        final List<Query> originalFilters = buildFilterQueries(requestAdapter, rewrittenExpandedQuery);
        final List<Query> roundTrippedFilters = buildFilterQueries(requestAdapter, roundTripped);

        assertEquals(1, originalFilters.size());
        assertEquals("The FILTER query built from the round-tripped model must equal the original",
                originalFilters, roundTrippedFilters);
    }

    private static List<Query> buildFilterQueries(final LuceneSearchEngineRequestAdapter requestAdapter,
                                                   final ExpandedQuery expandedQuery) throws Exception {
        final QueryParsingController controller = new QueryParsingController(requestAdapter);
        return controller.transformFilterQueries(expandedQuery.getFilterQueries());
    }

    private static Query buildMainQuery(final LuceneSearchEngineRequestAdapter requestAdapter,
                                        final ExpandedQuery expandedQuery) {
        final QueryParsingController controller = new QueryParsingController(requestAdapter);
        return controller.transformUserQuery(expandedQuery.getUserQuery(), controller.builder);
    }

    private static List<Query> buildAdditiveBoostQueries(final LuceneSearchEngineRequestAdapter requestAdapter,
                                                          final ExpandedQuery expandedQuery) throws Exception {
        final QueryParsingController controller = new QueryParsingController(requestAdapter);
        return controller.getAdditiveQuerqyBoostQueries(expandedQuery);
    }

    private static RewriterFactory buildCommonRulesRewriterFactory() throws Exception {
        final querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory factory =
                new querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory("spike_common_rules");

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", RULES);
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());

        factory.configure(config);
        return factory.createRewriterFactory(null);
    }

    private static LuceneSearchEngineRequestAdapter requestAdapter(final RewriteChain rewriteChain) {

        final LuceneSearchEngineRequestAdapter requestAdapter = mock(LuceneSearchEngineRequestAdapter.class);
        when(requestAdapter.getQueryString()).thenReturn("a");
        when(requestAdapter.isMatchAllQuery("a")).thenReturn(false);
        when(requestAdapter.needsScores()).thenReturn(true);
        when(requestAdapter.getQueryAnalyzer()).thenReturn(new KeywordAnalyzer());
        when(requestAdapter.getQueryFieldsAndBoostings()).thenReturn(Collections.singletonMap("field1", 1f));
        when(requestAdapter.getGeneratedQueryFieldsAndBoostings()).thenReturn(Collections.emptyMap());
        when(requestAdapter.createQuerqyParser()).thenReturn(Optional.of(new WhiteSpaceQuerqyParser()));
        when(requestAdapter.getRewriteChain()).thenReturn(rewriteChain);
        when(requestAdapter.getRewriteLoggingConfig()).thenReturn(RewriteLoggingConfig.off());
        when(requestAdapter.getRequestParams(anyString())).thenReturn(new String[0]);
        when(requestAdapter.addQuerqyBoostQueriesToMainQuery()).thenReturn(true);
        when(requestAdapter.useFieldBoostingInQuerqyBoostQueries()).thenReturn(true);
        when(requestAdapter.applyMinimumShouldMatch(any(BooleanQuery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(requestAdapter.isDebugQuery()).thenReturn(false);

        return requestAdapter;
    }
}
