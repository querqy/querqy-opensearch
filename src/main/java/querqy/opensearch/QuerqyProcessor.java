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

package querqy.opensearch;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.opensearch.index.query.QueryShardContext;
import querqy.lucene.rewrite.infologging.InfoLogging;
import querqy.lucene.rewrite.infologging.Sink;
import querqy.opensearch.infologging.LogPayloadType;
import querqy.opensearch.infologging.SingleSinkInfoLogging;
import querqy.opensearch.query.InfoLoggingSpec;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.lucene.LuceneQueries;
import querqy.lucene.LuceneSearchEngineRequestAdapter;
import querqy.lucene.QueryParsingController;
import querqy.rewrite.RewriteChain;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


public class QuerqyProcessor {

    private static final RewriteChain EMPTY_REWRITE_CHAIN = new RewriteChain(Collections.emptyList());

    private RewriterShardContexts rewriterShardContexts;
    private Sink infoLoggingSink;

    public QuerqyProcessor(final RewriterShardContexts rewriterShardContexts, final Sink infoLoggingSink) {
        this.rewriterShardContexts = rewriterShardContexts;
        this.infoLoggingSink = infoLoggingSink;
    }

    public Query parseQuery(final QuerqyQueryBuilder queryBuilder, final QueryShardContext context)
            throws LuceneSearchEngineRequestAdapter.SyntaxException {

        final List<Rewriter> rewriters = queryBuilder.getRewriters();

        final RewriteChain rewriteChain;
        final Set<String> rewritersEnabledForLogging;
        if (rewriters == null || rewriters.isEmpty()) {

            rewriteChain = EMPTY_REWRITE_CHAIN;
            rewritersEnabledForLogging = Collections.emptySet();

        } else {

            final RewriteChainAndLogging rewriteChainAndLogging = rewriterShardContexts.getRewriteChain(
                    rewriters.stream().map(Rewriter::getName).collect(Collectors.toList()), context);

            rewriteChain = rewriteChainAndLogging.rewriteChain;
            final InfoLoggingSpec infoLoggingSpec = queryBuilder.getInfoLoggingSpec();

            if ((infoLoggingSpec != null) && (infoLoggingSpec.getPayloadType() != LogPayloadType.NONE)
                    && !infoLoggingSpec.isLogged()) {

                infoLoggingSpec.setLogged(true);
                rewritersEnabledForLogging = rewriteChainAndLogging.rewritersEnabledForLogging;

            } else {
                rewritersEnabledForLogging = Collections.emptySet();
            }

        }

        final InfoLogging infoLogging = rewritersEnabledForLogging.isEmpty()
                ? null : new SingleSinkInfoLogging(infoLoggingSink, rewritersEnabledForLogging);

        final DismaxSearchEngineRequestAdapter requestAdapter =
                new DismaxSearchEngineRequestAdapter(queryBuilder, rewriteChain, context, infoLogging);

        final QueryParsingController controller = new QueryParsingController(requestAdapter);
        final LuceneQueries queries = controller.process();


//        // TODO: make decos part of the general Querqy object model
//        final Set<Object> decorations = (Set<Object>) requestAdapter.getContext().get(DecorateInstruction.CONTEXT_KEY);

        if ((queries.querqyBoostQueries == null || queries.querqyBoostQueries.isEmpty())
                && (queries.filterQueries == null || queries.filterQueries.isEmpty())
                && queries.mainQuery instanceof BooleanQuery) {

            // TODO: we should do this when building the query
            final List<BooleanClause> clauses = ((BooleanQuery) queries.mainQuery).clauses();
            if (clauses.size() == 1) {
                final BooleanClause onlyClause = clauses.get(0);
                if (onlyClause.isScoring()) {
                    return onlyClause.getQuery();
                }
            }
        }

        final BooleanQuery.Builder builder = new BooleanQuery.Builder();

        builder.add(queries.mainQuery, BooleanClause.Occur.MUST);
        if (queries.querqyBoostQueries != null) {
            for (final Query query : queries.querqyBoostQueries) {
                builder.add(query, BooleanClause.Occur.SHOULD);
            }
        }

        appendFilterQueries(queries, builder);

        final BooleanQuery query = builder.build();
        if (infoLogging != null) {
            infoLogging.endOfRequest(requestAdapter);
        }
        return query;

    }


    void appendFilterQueries(final LuceneQueries queries, final BooleanQuery.Builder builder) {

        if (queries.filterQueries != null) {

            for (final Query query : queries.filterQueries) {

                if (query instanceof BooleanQuery) {

                    final BooleanQuery bq = (BooleanQuery) query;

                    final List<BooleanClause> clauses = bq.clauses();
                    final int minimumNumberShouldMatch = bq.getMinimumNumberShouldMatch();

                    if (clauses.size() < 2 || clauses.size() <= minimumNumberShouldMatch ||
                            clauses.stream().allMatch(booleanClause ->
                                    BooleanClause.Occur.MUST_NOT.equals(booleanClause.getOccur()))) {

                        for (final BooleanClause clause : clauses) {
                            if (clause.getOccur() == BooleanClause.Occur.MUST_NOT) {
                                builder.add(clause.getQuery(), BooleanClause.Occur.MUST_NOT);
                            } else {
                                builder.add(clause.getQuery(), BooleanClause.Occur.FILTER);
                            }
                        }
                    } else {
                        builder.add(query, BooleanClause.Occur.FILTER);
                    }
                } else {
                    builder.add(query, BooleanClause.Occur.FILTER);
                }

            }
        }
    }

}
