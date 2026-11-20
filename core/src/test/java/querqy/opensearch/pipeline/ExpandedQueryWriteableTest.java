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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import querqy.model.BooleanQuery;
import querqy.model.BoostQuery;
import querqy.model.BoostedPhraseQuery;
import querqy.model.BoostedTerm;
import querqy.model.Clause.Occur;
import querqy.model.DisjunctionMaxQuery;
import querqy.model.ExpandedQuery;
import querqy.model.MatchAllQuery;
import querqy.model.Node;
import querqy.model.ParametrizedRawQuery;
import querqy.model.ParametrizedRawQuery.Part;
import querqy.model.ParsedRawQuery;
import querqy.model.PhraseQuery;
import querqy.model.Query;
import querqy.model.QuerqyQuery;
import querqy.model.StringRawQuery;
import querqy.model.Term;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers every concrete {@code querqy.model} node type a rewrite can produce (per the checklist derived from
 * {@link querqy.model.NodeVisitor}'s overloads plus the subtypes that dispatch through a supertype overload). Uses a
 * hand-rolled structural comparison ({@link #assertSameStructure}) rather than the model classes' own {@code
 * equals()}, since {@code SubQuery}'s own (documented) {@code equals()}/{@code hashCode()} only compare clauses, not
 * {@code occur}/{@code generated} - a regression there would not be caught by {@code assertEquals} alone.
 */
public class ExpandedQueryWriteableTest {

    @Test
    public void testRoundTripsAQueryWithSynonymAndFilter() throws IOException {

        final Query query = new Query(false);
        final DisjunctionMaxQuery dmq = new DisjunctionMaxQuery(query, Occur.SHOULD, false);
        query.addClause(dmq);
        new Term(dmq, "field1", "a", false);
        dmq.addClause(new Term(dmq, "field1", "a", false));
        dmq.addClause(new BoostedTerm(dmq, "field1", "b", 2.5f));

        final Query filter = new Query(false);
        final DisjunctionMaxQuery filterDmq = new DisjunctionMaxQuery(filter, Occur.SHOULD, false);
        filter.addClause(filterDmq);
        filterDmq.addClause(new Term(filterDmq, "category", "shoes", false));

        final ExpandedQuery expandedQuery = new ExpandedQuery(query, Collections.singletonList(filter),
                Collections.singletonList(new BoostQuery(filter, 10f)), Collections.emptyList(),
                Collections.emptyList());

        final ExpandedQuery roundTripped = roundTrip(expandedQuery);

        assertSameStructure(expandedQuery.getUserQuery(), roundTripped.getUserQuery());
        assertEquals(1, roundTripped.getFilterQueries().size());
        assertSameStructure((Node) expandedQuery.getFilterQueries().iterator().next(),
                (Node) roundTripped.getFilterQueries().iterator().next());
        assertEquals(1, roundTripped.getBoostUpQueries().size());
        final BoostQuery originalBoost = expandedQuery.getBoostUpQueries().iterator().next();
        final BoostQuery roundTrippedBoost = roundTripped.getBoostUpQueries().iterator().next();
        assertEquals(originalBoost.getBoost(), roundTrippedBoost.getBoost(), 0f);
        assertSameStructure((Node) originalBoost.getQuery(), (Node) roundTrippedBoost.getQuery());
    }

    @Test
    public void testRoundTripsMatchAllQuery() throws IOException {
        final ExpandedQuery expandedQuery = new ExpandedQuery(new MatchAllQuery());
        final ExpandedQuery roundTripped = roundTrip(expandedQuery);
        assertSameStructure(expandedQuery.getUserQuery(), roundTripped.getUserQuery());
    }

    @Test
    public void testRoundTripsPhraseQueryAndBoostedPhraseQueryAsFilters() throws IOException {

        final Query userQuery = new Query(false);
        new DisjunctionMaxQuery(userQuery, Occur.SHOULD, false);

        final PhraseQuery phraseFilter = new PhraseQuery(null, Occur.MUST, false, "title",
                Arrays.asList("dr", "martens"), 2);
        final BoostedPhraseQuery boostedPhraseFilter = new BoostedPhraseQuery(null, Occur.SHOULD, "title",
                Arrays.asList("air", "wair"), 0, 3f);

        final ExpandedQuery expandedQuery = new ExpandedQuery(userQuery, Arrays.asList(phraseFilter,
                boostedPhraseFilter), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        final ExpandedQuery roundTripped = roundTrip(expandedQuery);

        final List<QuerqyQuery<?>> filters = (List<QuerqyQuery<?>>) roundTripped.getFilterQueries();
        assertEquals(2, filters.size());
        assertSameStructure((Node) phraseFilter, (Node) filters.get(0));
        assertSameStructure((Node) boostedPhraseFilter, (Node) filters.get(1));
        assertEquals(3f, ((BoostedPhraseQuery) filters.get(1)).getBoost(), 0f);
    }

    @Test
    public void testRoundTripsStringRawQueryAndParametrizedRawQueryAsFilters() throws IOException {

        final Query userQuery = new Query(false);
        new DisjunctionMaxQuery(userQuery, Occur.SHOULD, false);

        final StringRawQuery stringRaw = new StringRawQuery(null, "{\"term\":{\"category\":\"shoes\"}}",
                Occur.MUST, false);
        final ParametrizedRawQuery parametrizedRaw = new ParametrizedRawQuery(null,
                Arrays.asList(new Part("field:", Part.Type.QUERY_PART), new Part("value1", Part.Type.PARAMETER)),
                Occur.MUST, true);

        final ExpandedQuery expandedQuery = new ExpandedQuery(userQuery, Arrays.asList(stringRaw, parametrizedRaw),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        final ExpandedQuery roundTripped = roundTrip(expandedQuery);

        final List<QuerqyQuery<?>> filters = (List<QuerqyQuery<?>>) roundTripped.getFilterQueries();
        assertEquals(2, filters.size());

        final StringRawQuery rtStringRaw = (StringRawQuery) filters.get(0);
        assertEquals(stringRaw.getQueryString(), rtStringRaw.getQueryString());
        assertEquals(stringRaw.getOccur(), rtStringRaw.getOccur());
        assertEquals(stringRaw.isGenerated(), rtStringRaw.isGenerated());

        final ParametrizedRawQuery rtParametrizedRaw = (ParametrizedRawQuery) filters.get(1);
        assertEquals(parametrizedRaw.getParts(), rtParametrizedRaw.getParts());
        assertEquals(parametrizedRaw.getOccur(), rtParametrizedRaw.getOccur());
        assertEquals(parametrizedRaw.isGenerated(), rtParametrizedRaw.isGenerated());
    }

    @Test
    public void testParsedRawQueryIsExplicitlyRejected() {
        final ParsedRawQuery<Object> parsedRawQuery = new ParsedRawQuery<Object>(null, Occur.MUST, false,
                new Object()) {
            @Override
            public querqy.model.RawQuery clone(final querqy.model.BooleanParent newParent) {
                return this;
            }

            @Override
            public querqy.model.RawQuery clone(final querqy.model.BooleanParent newParent, final boolean generated) {
                return this;
            }
        };

        final Query userQuery = new Query(false);
        new DisjunctionMaxQuery(userQuery, Occur.SHOULD, false);
        final ExpandedQuery expandedQuery = new ExpandedQuery(userQuery,
                Collections.singletonList(parsedRawQuery), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        final BytesStreamOutput output = new BytesStreamOutput();
        final UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> ExpandedQueryWriteable.writeExpandedQuery(output, expandedQuery));
        assertTrue(e.getMessage().contains("ParsedRawQuery"));
    }

    @Test
    public void testFutureFormatVersionIsRejectedClearly() throws IOException {

        final BytesStreamOutput output = new BytesStreamOutput();
        output.writeVInt(ExpandedQueryWriteable.CURRENT_VERSION + 1);
        output.flush();

        final StreamInput input = output.bytes().streamInput();
        final IOException e = assertThrows(IOException.class,
                () -> ExpandedQueryWriteable.readExpandedQuery(input));
        assertTrue(e.getMessage().contains("version"));
    }

    private static ExpandedQuery roundTrip(final ExpandedQuery expandedQuery) throws IOException {
        final BytesStreamOutput output = new BytesStreamOutput();
        ExpandedQueryWriteable.writeExpandedQuery(output, expandedQuery);
        output.flush();
        return ExpandedQueryWriteable.readExpandedQuery(output.bytes().streamInput());
    }

    /**
     * Structural comparison that - unlike {@code SubQuery.equals()}/{@code hashCode()} - also checks {@code occur}
     * and {@code generated} (see this class's javadoc).
     */
    private static void assertSameStructure(final Node expected, final Node actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals("generated flag for " + expected.getClass().getSimpleName(),
                expected.isGenerated(), actual.isGenerated());

        if (expected instanceof querqy.model.Clause) {
            assertEquals("occur for " + expected.getClass().getSimpleName(),
                    ((querqy.model.Clause<?>) expected).getOccur(), ((querqy.model.Clause<?>) actual).getOccur());
        }

        if (expected instanceof Term) {
            final Term expectedTerm = (Term) expected;
            final Term actualTerm = (Term) actual;
            assertEquals(expectedTerm.getField(), actualTerm.getField());
            assertEquals(expectedTerm.getValue().toString(), actualTerm.getValue().toString());
            if (expected instanceof BoostedTerm) {
                assertEquals(((BoostedTerm) expected).getBoost(), ((BoostedTerm) actual).getBoost(), 0f);
            }
            return;
        }

        if (expected instanceof PhraseQuery) {
            final PhraseQuery expectedPq = (PhraseQuery) expected;
            final PhraseQuery actualPq = (PhraseQuery) actual;
            assertEquals(expectedPq.getField(), actualPq.getField());
            assertEquals(expectedPq.getTerms(), actualPq.getTerms());
            assertEquals(expectedPq.getSlop(), actualPq.getSlop());
            if (expected instanceof BoostedPhraseQuery) {
                assertEquals(((BoostedPhraseQuery) expected).getBoost(), ((BoostedPhraseQuery) actual).getBoost(), 0f);
            }
            return;
        }

        if (expected instanceof DisjunctionMaxQuery) {
            assertEquals(((DisjunctionMaxQuery) expected).getTieBreaker(),
                    ((DisjunctionMaxQuery) actual).getTieBreaker());
        }

        if (expected instanceof querqy.model.SubQuery) {
            final List<?> expectedClauses = ((querqy.model.SubQuery<?, ?>) expected).getClauses();
            final List<?> actualClauses = ((querqy.model.SubQuery<?, ?>) actual).getClauses();
            assertEquals(expectedClauses.size(), actualClauses.size());
            for (int i = 0; i < expectedClauses.size(); i++) {
                assertSameStructure((Node) expectedClauses.get(i), (Node) actualClauses.get(i));
            }
        }
    }
}
