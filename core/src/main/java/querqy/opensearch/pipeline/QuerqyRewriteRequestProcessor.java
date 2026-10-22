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

import org.apache.lucene.search.BooleanClause;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilderVisitor;
import org.opensearch.ingest.ConfigurationUtils;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchRequestProcessor;
import org.opensearch.search.pipeline.StatefulSearchRequestProcessor;
import org.opensearch.transport.client.Client;
import querqy.model.ExpandedQuery;
import querqy.model.MatchAllQuery;
import querqy.model.QuerqyQuery;
import querqy.lucene.QueryParsingController;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.RewriteChainAndLogging;
import querqy.opensearch.RewriterFactoryLoader;
import querqy.opensearch.RewriterRef;
import querqy.opensearch.query.InfoLoggingSpec;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriteChainOutput;
import querqy.rewriter.commonrules.model.DecorateInstruction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the full Querqy parse + rewrite chain once, on the coordinating node, for every {@code querqy} query
 * anywhere in the request (including nested inside compound/hybrid queries - see {@link #findQuerqyQueries}),
 * instead of each shard doing it independently. Mutates each found {@link QuerqyQueryBuilder} in place (see
 * {@link QuerqyQueryBuilder#setPreRewritten}) rather than replacing it, and pushes any filter queries the rewrite
 * produced onto the top-level query via its own polymorphic {@link QueryBuilder#filter(QueryBuilder)} - which
 * reaches every leg of a hybrid query, not just the one the {@code querqy} clause lives in (see
 * {@code buildFilterWrapper}).
 * <p>
 * Safe to mutate the found builders in place: this all happens before the request is dispatched to any shard,
 * and a request processor must finish all its mutation before returning - see the design notes this processor's
 * implementation plan recorded on OpenSearch's shard request cache and local-shard dispatch for why that's the
 * one real constraint this imposes.
 */
public class QuerqyRewriteRequestProcessor extends AbstractProcessor implements StatefulSearchRequestProcessor {

    public static final String TYPE = "querqy_rewrite";

    /** Key under which this processor stashes a {@code List<QuerqyRewriteCapture>} for the response processor. */
    public static final String PIPELINE_CONTEXT_ATTRIBUTE = "querqy_rewrite.captures";

    private final RewriterFactoryLoader loader;
    private final QuerqyProcessor querqyProcessor;
    private final boolean ignoreMissing;

    QuerqyRewriteRequestProcessor(final String tag, final String description, final boolean ignoreFailure,
                                  final RewriterFactoryLoader loader, final QuerqyProcessor querqyProcessor,
                                  final boolean ignoreMissing) {
        super(tag, description, ignoreFailure);
        this.loader = loader;
        this.querqyProcessor = querqyProcessor;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public SearchRequest processRequest(final SearchRequest request, final PipelineProcessingContext requestContext)
            throws Exception {

        final SearchSourceBuilder source = request.source();
        if (source == null || source.query() == null) {
            if (ignoreMissing) {
                return request;
            }
            throw new IllegalArgumentException("querqy_rewrite: search request has no query to rewrite "
                    + "(set ignore_missing: true to let requests without one pass through unchanged)");
        }

        final List<QuerqyQueryBuilder> found = findQuerqyQueries(source.query());
        if (found.isEmpty()) {
            if (ignoreMissing) {
                return request;
            }
            throw new IllegalArgumentException("querqy_rewrite: no 'querqy' query found anywhere in this request "
                    + "(set ignore_missing: true to let requests without one pass through unchanged)");
        }

        final List<QuerqyRewriteCapture> captures = new ArrayList<>(found.size());
        QueryBuilder topLevelQuery = source.query();

        for (final QuerqyQueryBuilder queryBuilder : found) {

            final List<Rewriter> rewriters = queryBuilder.getRewriters();
            if (rewriters == null || rewriters.isEmpty()) {
                continue;
            }

            final List<RewriterRef> refs = rewriters.stream()
                    .map(r -> new RewriterRef(r.getName(), queryBuilder.effectiveRefreshMode(r)))
                    .collect(Collectors.toList());

            // a shard-dependent rewriter (e.g. word-break-compound) throws IllegalArgumentException here -
            // deliberately left to propagate, per the "fail clearly" choice for this case
            final RewriteChainAndLogging chainAndLogging = loader.getRewriteChain(refs);
            final RewriteChain rewriteChain = chainAndLogging.rewriteChain;

            final CoordinatorSearchEngineRequestAdapter adapter =
                    new CoordinatorSearchEngineRequestAdapter(queryBuilder, rewriteChain);

            final QueryParsingController controller = new QueryParsingController(adapter);
            final ExpandedQuery parsedInput = controller.createExpandedQuery();

            final RewriteChainOutput output = rewriteChain.rewrite(parsedInput, adapter);
            final ExpandedQuery rewritten = output.getExpandedQuery();

            queryBuilder.setPreRewritten(parsedInput.getUserQuery(), rewritten);

            if (rewritten.getFilterQueries() != null && !rewritten.getFilterQueries().isEmpty()) {
                topLevelQuery = topLevelQuery.filter(buildFilterWrapper(queryBuilder, rewritten.getFilterQueries()));
            }

            final InfoLoggingSpec infoLoggingSpec = queryBuilder.getInfoLoggingSpec();

            captures.add(new QuerqyRewriteCapture(
                    output.getRewriteLog().orElse(null),
                    castDecorations(adapter.getContext().get(DecorateInstruction.DECORATION_CONTEXT_KEY)),
                    castDecorationsMap(adapter.getContext().get(DecorateInstruction.DECORATION_CONTEXT_MAP_KEY)),
                    infoLoggingSpec == null ? null : infoLoggingSpec.getPayloadType()));
        }

        source.query(topLevelQuery);
        requestContext.setAttribute(PIPELINE_CONTEXT_ATTRIBUTE, captures);

        return request;
    }

    /**
     * Wraps the filter queries produced by one rewritten {@code querqy} clause into a pre-rewritten {@link
     * QuerqyQueryBuilder} (a placeholder {@link MatchAllQuery} "main" query plus those filters), so a single {@link
     * QueryBuilder#filter(QueryBuilder)} call on the top-level query can push it onto every leg of a hybrid query,
     * or append it in place for a bool query, or wrap a plain query - all via the existing polymorphic {@code
     * filter(...)} dispatch, with no hybrid-specific code here at all. Any {@link QueryBuilder} handed to {@code
     * filter(...)} is converted to its Lucene form later, per shard, via its own {@code toQuery(...)} - so wrapping
     * already-rewritten filter content in another {@code QuerqyQueryBuilder} needs no new translator between the
     * querqy-model and OpenSearch query worlds.
     * <p>
     * Copies {@code originatingQuery}'s own {@code query_fields} (not the processor's, and not some combination
     * across every {@code querqy} clause found in the request) - a field-less {@link querqy.model.Term} inside a
     * filter query (e.g. a plain {@code FILTER: foo} common-rules rule) is only resolved against query fields at
     * the shard-side Lucene-building stage, using whichever {@code QuerqyQueryBuilder} instance it is attached to;
     * using the right instance's own fields is what makes that resolution correct. Built fresh per originating
     * query (rather than one combined wrapper for the whole request) precisely so each stays correct even when two
     * {@code querqy} clauses in the same request use different query fields.
     */
    private QuerqyQueryBuilder buildFilterWrapper(final QuerqyQueryBuilder originatingQuery,
                                                  final Collection<QuerqyQuery<?>> filterQueries) {

        final QuerqyQueryBuilder wrapper = new QuerqyQueryBuilder(querqyProcessor);
        // never parsed in pre-rewritten mode - exists only to satisfy QueryParsingController's constructor,
        // which unconditionally validates a non-empty query string
        wrapper.setMatchingQuery(new MatchingQuery("*"));
        wrapper.setQueryFieldsAndBoostings(originatingQuery.getQueryFieldsAndBoostings().entrySet().stream()
                .map(e -> e.getKey() + "^" + e.getValue())
                .collect(Collectors.toList()));
        originatingQuery.getFieldBoostModel().ifPresent(wrapper::setFieldBoostModel);

        final MatchAllQuery placeholderMainQuery = new MatchAllQuery();
        final ExpandedQuery filterExpandedQuery = new ExpandedQuery(placeholderMainQuery, filterQueries,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        wrapper.setPreRewritten(placeholderMainQuery, filterExpandedQuery);
        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> castDecorations(final Object raw) {
        return (Set<Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Object>> castDecorationsMap(final Object raw) {
        return (Map<String, List<Object>>) raw;
    }

    /**
     * Finds every live {@link QuerqyQueryBuilder} reference reachable from {@code query}, at any nesting depth
     * (inside a bool query, a hybrid query, or any other compound query type), via {@link QueryBuilder
     * #visit(QueryBuilderVisitor)} - a generic, dependency-free mechanism that hands back the actual object sitting
     * in the request's query tree rather than a copy, which is exactly what lets {@link #processRequest} mutate it
     * in place.
     */
    static List<QuerqyQueryBuilder> findQuerqyQueries(final QueryBuilder query) {
        final List<QuerqyQueryBuilder> found = new ArrayList<>();
        query.visit(new QueryBuilderVisitor() {
            @Override
            public void accept(final QueryBuilder queryBuilder) {
                if (queryBuilder instanceof QuerqyQueryBuilder) {
                    found.add((QuerqyQueryBuilder) queryBuilder);
                }
            }

            @Override
            public QueryBuilderVisitor getChildVisitor(final BooleanClause.Occur occur) {
                return this;
            }
        });
        return found;
    }

    public static final class Factory implements Processor.Factory<SearchRequestProcessor> {

        private final RewriterFactoryLoader loader;
        private final QuerqyProcessor querqyProcessor;

        public Factory(final Client client, final Settings settings, final QuerqyProcessor querqyProcessor) {
            this.loader = RewriterFactoryLoader.nodeLevel(settings, client, true);
            this.querqyProcessor = querqyProcessor;
        }

        @Override
        public SearchRequestProcessor create(
                final Map<String, Processor.Factory<SearchRequestProcessor>> processorFactories,
                final String tag, final String description, final boolean ignoreFailure,
                final Map<String, Object> config, final Processor.PipelineContext pipelineContext) {

            final boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config,
                    "ignore_missing", false);

            return new QuerqyRewriteRequestProcessor(tag, description, ignoreFailure, loader, querqyProcessor,
                    ignoreMissing);
        }
    }
}
