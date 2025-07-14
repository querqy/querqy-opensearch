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

package querqy.opensearch.query;

import static querqy.opensearch.query.RequestUtils.paramToQuerySimilarityScoring;
import static querqy.opensearch.query.RequestUtils.querySimilarityScoringToString;

import org.opensearch.core.ParseField;
import org.opensearch.core.common.io.stream.NamedWriteable;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ObjectParser;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import querqy.lucene.QuerySimilarityScoring;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public class MatchingQuery implements NamedWriteable, ToXContent {

    public static final String NAME = "matching_query";

    public static final ObjectParser<MatchingQuery, Void> PARSER = new ObjectParser<>(NAME, MatchingQuery::new);

    private static final ParseField FIELD_QUERY = new ParseField("query");
    private static final ParseField FIELD_SIMILARITY_SCORING = new ParseField("similarity_scoring");
    private static final ParseField FIELD_WEIGHT = new ParseField("weight");

    static {
        PARSER.declareString(MatchingQuery::setQueryString, FIELD_QUERY);
        PARSER.declareFloat(MatchingQuery::setWeight, FIELD_WEIGHT);
        PARSER.declareString(MatchingQuery::setSimilarityScoring, FIELD_SIMILARITY_SCORING);
    }

    private Float weight = null;
    private String queryString = null;
    private QuerySimilarityScoring similarityScoring;

    public MatchingQuery() {}
    public MatchingQuery(final String queryString) {
        this.queryString = queryString;
    }

    public MatchingQuery(final String queryString, final String similarityScoring) {
        this.queryString = queryString;
        setSimilarityScoring(similarityScoring);
    }

    public MatchingQuery(final StreamInput in) throws IOException {
        queryString = in.readString();
        final String strSimilarityScoring = in.readOptionalString();
        similarityScoring = strSimilarityScoring == null
                ? null : QuerySimilarityScoring.valueOf(strSimilarityScoring);
        weight = in.readOptionalFloat();
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeString(queryString);
        out.writeOptionalString(similarityScoring == null ? null : similarityScoring.name());
        out.writeOptionalFloat(weight);

    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
        builder.startObject();

        builder.field(FIELD_QUERY.getPreferredName(), queryString);

        final Optional<String> optSimilarityScoring = querySimilarityScoringToString(similarityScoring);
        if (optSimilarityScoring.isPresent()) {
            builder.field(FIELD_SIMILARITY_SCORING.getPreferredName(), optSimilarityScoring.get());
        }

        if (weight != null) {
            builder.field(FIELD_WEIGHT.getPreferredName(), weight);
        }

        builder.endObject();
        return builder;
    }

    @Override
    public boolean isFragment() {
        return false;
    }

    public Optional<Float> getWeight() {
        return Optional.ofNullable(weight);
    }

    public void setWeight(final float weight) {
        this.weight = weight;
    }

    public String getQueryString() {
        return queryString;
    }

    public void setQueryString(final String queryString) {

        if (queryString == null) {
            throw new IllegalArgumentException(FIELD_QUERY.getPreferredName() + " must not be null");
        }

        final String trimmed = queryString.trim();
        if (trimmed.length() == 0) {
            throw new IllegalArgumentException(FIELD_QUERY.getPreferredName() + " must not be empty");
        }
        this.queryString = trimmed;
    }

    public Optional<QuerySimilarityScoring> getSimilarityScoring() {
        return Optional.ofNullable(similarityScoring);
    }

    public void setSimilarityScoring(final String similarityScoring) {
        setSimilarityScoring(paramToQuerySimilarityScoring(similarityScoring, FIELD_SIMILARITY_SCORING));
    }

    public void setSimilarityScoring(final QuerySimilarityScoring similarityScoring) {
        this.similarityScoring = similarityScoring;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof MatchingQuery)) return false;
        final MatchingQuery that = (MatchingQuery) o;
        return Objects.equals(weight, that.weight) &&
                Objects.equals(queryString, that.queryString) &&
                similarityScoring == that.similarityScoring;
    }

    @Override
    public int hashCode() {

        return Objects.hash(weight, queryString, similarityScoring);
    }
}
