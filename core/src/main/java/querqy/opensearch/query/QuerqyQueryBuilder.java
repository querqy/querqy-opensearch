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

package querqy.opensearch.query;

import static querqy.opensearch.query.RequestUtils.fieldBoostModelToString;
import static querqy.opensearch.query.RequestUtils.paramToFieldBoostModel;
import static querqy.opensearch.query.RequestUtils.paramToQueryFieldsAndBoosting;

import org.apache.lucene.search.Query;
import org.opensearch.core.ParseField;
import org.opensearch.core.common.ParsingException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ObjectParser;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.AbstractQueryBuilder;
import org.opensearch.index.query.QueryShardContext;
import querqy.model.ExpandedQuery;
import querqy.model.QuerqyQuery;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.pipeline.ExpandedQueryWriteable;
import querqy.lucene.LuceneSearchEngineRequestAdapter;
import querqy.lucene.rewrite.SearchFieldsAndBoosting.FieldBoostModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class QuerqyQueryBuilder extends AbstractQueryBuilder<QuerqyQueryBuilder> {

    public static final String NAME = "querqy";

    private static final ParseField FIELD_MATCHING_QUERY = new ParseField("matching_query");
    private static final ParseField FIELD_BOOSTING_QUERIES = new ParseField("boosting_queries");
    private static final ParseField FIELD_GENERATED = new ParseField("generated");

    private static final ParseField FIELD_TIE_BREAKER = new ParseField("tie_breaker");
    private static final ParseField FIELD_MINIMUM_SHOULD_MATCH = new ParseField("minimum_should_match");
    private static final ParseField FIELD_QUERY_FIELDS = new ParseField("query_fields");
    private static final ParseField FIELD_FIELD_BOOST_MODEL = new ParseField("field_boost_model");

    private static final ParseField FIELD_REWRITERS = new ParseField("rewriters");
    private static final ParseField FIELD_INFO_LOGGING = new ParseField("info_logging");
    private static final ParseField FIELD_REFRESH_CONFIG = new ParseField("refresh_config");

    private static final ObjectParser<QuerqyQueryBuilder, Void> PARSER = new ObjectParser<>(NAME,
            QuerqyQueryBuilder::new);

    static {
        declareStandardFields(PARSER);
        PARSER.declareFloat(QuerqyQueryBuilder::setTieBreaker, FIELD_TIE_BREAKER);
        PARSER.declareString(QuerqyQueryBuilder::setMinimumShouldMatch, FIELD_MINIMUM_SHOULD_MATCH);
        PARSER.declareString(QuerqyQueryBuilder::setFieldBoostModel, FIELD_FIELD_BOOST_MODEL);
        PARSER.declareStringArray(QuerqyQueryBuilder::setQueryFieldsAndBoostings, FIELD_QUERY_FIELDS);
        PARSER.declareObjectArray(QuerqyQueryBuilder::setRewriters, Rewriter.PARSER, FIELD_REWRITERS);
        PARSER.declareObject(QuerqyQueryBuilder::setGenerated, Generated.PARSER, FIELD_GENERATED);
        PARSER.declareObject(QuerqyQueryBuilder::setMatchingQuery, MatchingQuery.PARSER, FIELD_MATCHING_QUERY);
        PARSER.declareObject(QuerqyQueryBuilder::setBoostingQueries, BoostingQueries.PARSER, FIELD_BOOSTING_QUERIES);
        PARSER.declareObject(QuerqyQueryBuilder::setInfoLoggingSpec, InfoLoggingSpec.PARSER, FIELD_INFO_LOGGING);
        PARSER.declareString((qb, s) -> qb.setRefreshConfig(RefreshMode.fromString(s)), FIELD_REFRESH_CONFIG);
    }


    private Float tieBreaker;
    private String minimumShouldMatch;
    private Map<String, Float> queryFieldsAndBoostings;
    private List<String> queryFields;
    private FieldBoostModel fieldBoostModel = null;

    private Generated generated = null;
    private BoostingQueries boostingQueries = null;
    private MatchingQuery matchingQuery = null;

    private List<Rewriter> rewriters = Collections.emptyList();

    private QuerqyProcessor querqyProcessor;

    private InfoLoggingSpec infoLoggingSpec;

    /** The query-wide default {@link RefreshMode}; {@code null} (the wire-absent case) means {@link RefreshMode#NO}. */
    private RefreshMode refreshConfig;

    /**
     * Non-null only when a search pipeline's {@code querqy_rewrite} request processor has already run the full
     * parse + rewrite chain for this query on the coordinating node (see {@link #setPreRewritten}) - in which case
     * {@link #doToQuery} runs only the shard-local Lucene query-building stage against this already-rewritten model
     * instead of doing the full per-shard parse + rewrite + build it does by default. Deliberately not wired into
     * {@link #PARSER}/{@link XContentParser} parsing anywhere in this class: a client can never supply a
     * pre-rewritten model directly in a query body and bypass the rewrite chain - the only way these two fields are
     * ever populated is a search pipeline processor calling {@link #setPreRewritten} programmatically after finding
     * this exact (mutable, in-place) instance via {@code QueryBuilder.visit(QueryBuilderVisitor)}.
     */
    private QuerqyQuery<?> originalUserQuery;

    /** @see #originalUserQuery */
    private ExpandedQuery rewrittenExpandedQuery;

    public QuerqyQueryBuilder() {
        super();
    }


    public QuerqyQueryBuilder(final QuerqyProcessor querqyProcessor) {
        super();
        this.querqyProcessor = querqyProcessor;
    }

    public QuerqyQueryBuilder(final StreamInput in, final QuerqyProcessor querqyProcessor) throws IOException {
        super(in);
        this.querqyProcessor = querqyProcessor;

        matchingQuery = new MatchingQuery(in);
        boostingQueries = in.readOptionalWriteable(BoostingQueries::new);
        generated = in.readOptionalWriteable(Generated::new);

        queryFields = in.readStringList();
        setQueryFieldsAndBoostings(queryFields);
        minimumShouldMatch = in.readOptionalString();
        tieBreaker = in.readOptionalFloat();

        final String strFieldBoostModel = in.readOptionalString();
        fieldBoostModel = strFieldBoostModel == null
                ? null : FieldBoostModel.valueOf(strFieldBoostModel);

        final int numRewriters = in.readInt();
        rewriters = new ArrayList<>(numRewriters);
        for (int i = 0; i < numRewriters; i++) {
            rewriters.add(new Rewriter(in));
        }
        infoLoggingSpec = in.readOptionalWriteable(InfoLoggingSpec::new);

        final String strRefreshConfig = in.readOptionalString();
        refreshConfig = strRefreshConfig == null ? null : RefreshMode.valueOf(strRefreshConfig);

        if (in.readBoolean()) {
            originalUserQuery = ExpandedQueryWriteable.readQuerqyQuery(in);
            rewrittenExpandedQuery = ExpandedQueryWriteable.readExpandedQuery(in);
        }
    }

    @Override
    protected void doWriteTo(final StreamOutput out) throws IOException {
        matchingQuery.writeTo(out);
        out.writeOptionalWriteable(boostingQueries);
        out.writeOptionalWriteable(generated);

        out.writeStringCollection(queryFields);
        out.writeOptionalString(minimumShouldMatch);
        out.writeOptionalFloat(tieBreaker);
        out.writeOptionalString(fieldBoostModel == null ? null : fieldBoostModel.name());
        out.writeInt(rewriters.size());
        for (final Rewriter rewriter : rewriters) {
            rewriter.writeTo(out);
        }
        out.writeOptionalWriteable(infoLoggingSpec);
        out.writeOptionalString(refreshConfig == null ? null : refreshConfig.name());

        final boolean hasPreRewritten = rewrittenExpandedQuery != null;
        out.writeBoolean(hasPreRewritten);
        if (hasPreRewritten) {
            ExpandedQueryWriteable.writeQuerqyQuery(out, originalUserQuery);
            ExpandedQueryWriteable.writeExpandedQuery(out, rewrittenExpandedQuery);
        }
    }

    @Override
    protected void doXContent(final XContentBuilder builder, final Params params) throws IOException {
        builder.startObject(NAME);
        printBoostAndQueryName(builder);

        builder.field(FIELD_MATCHING_QUERY.getPreferredName(), matchingQuery);
        if (boostingQueries != null) {
            builder.field(FIELD_BOOSTING_QUERIES.getPreferredName(), boostingQueries);
        }

        if (generated != null) {
            builder.field(FIELD_GENERATED.getPreferredName(), generated);
        }

        builder.field(FIELD_QUERY_FIELDS.getPreferredName(), queryFields);

        if (minimumShouldMatch != null) {
            builder.field(FIELD_MINIMUM_SHOULD_MATCH.getPreferredName(), minimumShouldMatch);
        }

        if (tieBreaker != null) {
            builder.field(FIELD_TIE_BREAKER.getPreferredName(), tieBreaker);
        }

        if (fieldBoostModel != null) {
            final Optional<String> strFieldBoostModel = fieldBoostModelToString(fieldBoostModel);
            if (strFieldBoostModel.isPresent()) {
                builder.field(FIELD_FIELD_BOOST_MODEL.getPreferredName(), strFieldBoostModel.get());
            }
        }

        if (rewriters != null) {

            builder.startArray(FIELD_REWRITERS.getPreferredName());
            for (final Rewriter rewriter : rewriters) {
                rewriter.toXContent(builder, params);
            }

            builder.endArray();
        }

        if (infoLoggingSpec != null) {
            builder.field(FIELD_INFO_LOGGING.getPreferredName(), infoLoggingSpec);
        }

        if (refreshConfig != null) {
            builder.field(FIELD_REFRESH_CONFIG.getPreferredName(), refreshConfig.name());
        }

        builder.endObject();
    }

    public static QuerqyQueryBuilder fromXContent(final XContentParser parser, final QuerqyProcessor querqyProcessor) {

        final QuerqyQueryBuilder builder;
        try {
            builder = PARSER.apply(parser, null);
        } catch (final IllegalArgumentException e) {
            throw new ParsingException(parser.getTokenLocation(), e.getMessage(), e);
        }

        if (builder.matchingQuery == null) {
            throw new ParsingException(parser.getTokenLocation(), "[querqy] requires a matching_query, none specified");
        }
        if (builder.matchingQuery.getQueryString() == null) {
            throw new ParsingException(parser.getTokenLocation(),
                    "[querqy] requires a query, none specified");
        }
        if (builder.queryFields == null) {
            throw new ParsingException(parser.getTokenLocation(),
                    "[querqy] requires query_fields, none specified");
        }
        builder.setQuerqyProcessor(querqyProcessor);
        return builder;
    }

    @Override
    protected Query doToQuery(final QueryShardContext context) throws IOException {
        try {
            if (rewrittenExpandedQuery != null) {
                return querqyProcessor.buildFromRewrittenQuery(this, originalUserQuery, rewrittenExpandedQuery,
                        context);
            }
            return querqyProcessor.parseQuery(this, context);
        } catch (final LuceneSearchEngineRequestAdapter.SyntaxException e) {
            throw new IOException(e);
        }
    }

    /**
     * Indicates whether some other {@link org.opensearch.index.query.QueryBuilder} object of the same type is
     * "equal to" this one.
     *
     * @param other Check equality with that other QuerqyQueryBuilder
     */
    @Override
    protected boolean doEquals(final QuerqyQueryBuilder other) {
        return (this.matchingQuery == other.matchingQuery
                || (this.matchingQuery != null && this.matchingQuery.equals(other.matchingQuery)))
                && Objects.equals(this.queryFields, other.queryFields)
                && Objects.equals(this.generated, other.generated)
                && Objects.equals(this.minimumShouldMatch, other.minimumShouldMatch)
                && Objects.equals(this.rewriters, other.rewriters)
                && Objects.equals(this.tieBreaker, other.tieBreaker)
                && Objects.equals(this.fieldBoostModel, other.fieldBoostModel)
                && Objects.equals(this.boostingQueries, other.boostingQueries)
                && Objects.equals(this.infoLoggingSpec, other.infoLoggingSpec)
                && Objects.equals(this.refreshConfig, other.refreshConfig)
                ;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(matchingQuery, queryFields, generated, minimumShouldMatch,
                rewriters, tieBreaker, fieldBoostModel, boostingQueries, infoLoggingSpec, refreshConfig);
    }

    /**
     * Returns the name of the writeable object
     */
    @Override
    public String getWriteableName() {
        return NAME;
    }

    public void setQuerqyProcessor(final QuerqyProcessor querqyProcessor) {
        this.querqyProcessor = Objects.requireNonNull(querqyProcessor);
    }

    public MatchingQuery getMatchingQuery() {
        return matchingQuery;
    }

    public void setMatchingQuery(final MatchingQuery matchingQuery) {
        this.matchingQuery = matchingQuery;
    }

    public BoostingQueries getBoostingQueries() {
        return boostingQueries;
    }

    public void setBoostingQueries(final BoostingQueries boostingQueries) {
        this.boostingQueries = boostingQueries;
    }

    public Optional<Generated> getGenerated() {
        return Optional.ofNullable(generated);
    }

    public void setGenerated(final Generated generated) {
        this.generated = generated;
    }

    public void setQueryFieldsAndBoostings(final List<String> queryFieldsAndBoostings) {
        if (queryFieldsAndBoostings == null) {
            throw new IllegalArgumentException("Query fields must not be null");
        }
        this.queryFieldsAndBoostings = paramToQueryFieldsAndBoosting(queryFieldsAndBoostings);
        this.queryFields = queryFieldsAndBoostings;
    }

    public void setRewriters(final List<Rewriter> rewriters) {
        this.rewriters = rewriters == null ? Collections.emptyList() : rewriters;
    }

    public List<Rewriter> getRewriters() {
        return rewriters;
    }

    public Map<String, Float> getQueryFieldsAndBoostings() {
        return queryFieldsAndBoostings;
    }

    public void setTieBreaker(final float tie) {
        this.tieBreaker = tie;
    }

    public Optional<Float> getTieBreaker() {
        return Optional.ofNullable(tieBreaker);
    }

    public String getMinimumShouldMatch() {
        return minimumShouldMatch;
    }

    public void setMinimumShouldMatch(final String minimumShouldMatch) {
        this.minimumShouldMatch = minimumShouldMatch;
    }

    QuerqyQueryBuilder minimumShouldMatch(final String minimumShouldMatch) {
        setMinimumShouldMatch(minimumShouldMatch);
        return this;
    }

    public Optional<FieldBoostModel> getFieldBoostModel() {
        return Optional.ofNullable(fieldBoostModel);
    }

    public void setFieldBoostModel(final String fieldBoostModel) {
        setFieldBoostModel(paramToFieldBoostModel(fieldBoostModel));
    }

    public void setFieldBoostModel(final FieldBoostModel fieldFieldBoostModel) {
        this.fieldBoostModel = fieldFieldBoostModel;
    }

    public InfoLoggingSpec getInfoLoggingSpec() {
        return infoLoggingSpec;
    }

    public void setInfoLoggingSpec(final InfoLoggingSpec infoLoggingSpec) {
        this.infoLoggingSpec = infoLoggingSpec;
    }

    /**
     * @return The query-wide default {@link RefreshMode}, or {@code null} if the query didn't set one (treat as
     * {@link RefreshMode#NO}). A rewriter entry's own {@link Rewriter#getRefreshMode()}, when present, overrides
     * this for that rewriter specifically.
     */
    public RefreshMode getRefreshConfig() {
        return refreshConfig;
    }

    public void setRefreshConfig(final RefreshMode refreshConfig) {
        this.refreshConfig = refreshConfig;
    }

    /**
     * @return The effective {@link RefreshMode} for the given rewriter: its own override if set, otherwise the
     * query's default ({@link #getRefreshConfig()}), otherwise {@link RefreshMode#NO}.
     */
    /**
     * Called only by a search pipeline's {@code querqy_rewrite} request processor, on an instance it already found
     * via {@code QueryBuilder.visit(QueryBuilderVisitor)} (i.e. the same, live object sitting in the request's
     * query tree - this mutates it in place rather than the processor replacing it with a new object). See the
     * {@code rewrittenExpandedQuery} field's javadoc for why this is deliberately unreachable from the query DSL.
     *
     * @param originalUserQuery The unrewritten user query (before the rewrite chain ran) - see {@code
     *                          QueryParsingController#buildFromRewrittenQuery}'s javadoc for why this must be
     *                          preserved alongside the rewritten model.
     * @param rewrittenExpandedQuery The result of running the rewrite chain against {@code originalUserQuery}.
     */
    public void setPreRewritten(final QuerqyQuery<?> originalUserQuery, final ExpandedQuery rewrittenExpandedQuery) {
        this.originalUserQuery = Objects.requireNonNull(originalUserQuery);
        this.rewrittenExpandedQuery = Objects.requireNonNull(rewrittenExpandedQuery);
    }

    /** @return Whether {@link #setPreRewritten} has already been called on this instance. */
    public boolean isPreRewritten() {
        return rewrittenExpandedQuery != null;
    }

    public RefreshMode effectiveRefreshMode(final Rewriter rewriter) {
        if (rewriter.getRefreshMode() != null) {
            return rewriter.getRefreshMode();
        }
        return refreshConfig != null ? refreshConfig : RefreshMode.NO;
    }
}