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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.search.BooleanQuery;
import org.junit.Test;
import org.opensearch.common.io.stream.BytesStreamOutput;
import querqy.lucene.LuceneQueries;
import querqy.lucene.LuceneSearchEngineRequestAdapter;
import querqy.lucene.QueryParsingController;
import querqy.model.ExpandedQuery;
import querqy.model.QuerqyQuery;
import querqy.parser.WhiteSpaceQuerqyParser;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriteChainOutput;
import querqy.rewrite.RewriteLoggingConfig;
import querqy.rewrite.RewriterFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * End-to-end proof, via the real public API (no same-package tricks needed, unlike this test's earlier
 * incarnation): a Common Rules rewrite can be run ONCE (parse + rewrite only), its unrewritten user query and
 * rewritten {@link ExpandedQuery} both serialized via {@link ExpandedQueryWriteable}, shipped elsewhere, and
 * rebuilt into Lucene queries there via {@code QueryParsingController.buildFromRewrittenQuery} - with the exact
 * same result as running everything (parse + rewrite + build) in one place, the way {@code
 * QueryParsingController.process()} does today for the per-shard path.
 */
public class RewrittenModelRebuildTest {

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

        // sanity check this test is actually exercising SYNONYM+UP+FILTER, not a no-op
        assertEquals("Expected the UP rule to have produced exactly one boost-up query",
                1, rewrittenExpandedQuery.getBoostUpQueries().size());

        // --- the wire: serialize both the original and rewritten query, deserialize into fresh object graphs ---
        final QuerqyQuery<?> roundTrippedOriginalUserQuery = roundTripQuerqyQuery(parsedInput.getUserQuery());
        final ExpandedQuery roundTrippedRewrittenQuery = roundTripExpandedQuery(rewrittenExpandedQuery);

        // --- "shard": build Lucene queries from each, via two independent controllers and the real public API ---
        final LuceneQueries originalResult = new QueryParsingController(requestAdapter)
                .buildFromRewrittenQuery(parsedInput.getUserQuery(), rewrittenExpandedQuery);
        final LuceneQueries roundTrippedResult = new QueryParsingController(requestAdapter)
                .buildFromRewrittenQuery(roundTrippedOriginalUserQuery, roundTrippedRewrittenQuery);

        // the UP boost is folded into mainQuery itself here (addQuerqyBoostQueriesToMainQuery() is true in this
        // adapter), so its effect is asserted via mainQuery equality below, not via a separate querqyBoostQueries
        // list (which stays null in this configuration - see QueryParsingController's boost-assembly branch)
        assertEquals("Main query built from the round-tripped model must equal the one built directly",
                originalResult.mainQuery, roundTrippedResult.mainQuery);
        assertEquals(originalResult.mainQuery.toString(), roundTrippedResult.mainQuery.toString());
        assertEquals(null, originalResult.querqyBoostQueries);
        assertEquals(null, roundTrippedResult.querqyBoostQueries);

        // this is what a hybrid query's filter(...) pushdown would consume - must also survive the round trip
        assertEquals(1, originalResult.filterQueries.size());
        assertEquals("The FILTER query built from the round-tripped model must equal the original",
                originalResult.filterQueries, roundTrippedResult.filterQueries);
    }

    private static QuerqyQuery<?> roundTripQuerqyQuery(final QuerqyQuery<?> query) throws IOException {
        final BytesStreamOutput output = new BytesStreamOutput();
        ExpandedQueryWriteable.writeQuerqyQuery(output, query);
        output.flush();
        return ExpandedQueryWriteable.readQuerqyQuery(output.bytes().streamInput());
    }

    private static ExpandedQuery roundTripExpandedQuery(final ExpandedQuery expandedQuery) throws IOException {
        final BytesStreamOutput output = new BytesStreamOutput();
        ExpandedQueryWriteable.writeExpandedQuery(output, expandedQuery);
        output.flush();
        return ExpandedQueryWriteable.readExpandedQuery(output.bytes().streamInput());
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
