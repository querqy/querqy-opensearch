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

public class RewrittenQueries implements NamedWriteable, ToXContent {

    public static final String NAME = "rewritten_queries_boosts";

    static final ObjectParser<RewrittenQueries, Void> PARSER = new ObjectParser<>(
            NAME, RewrittenQueries::new);

    private static final ParseField FIELD_USE_FIELD_BOOST = new ParseField("use_field_boost");
    private static final ParseField FIELD_NEGATIVE_WEIGHT = new ParseField("negative_query_weight");
    private static final ParseField FIELD_POSITIVE_WEIGHT = new ParseField("positive_query_weight");
    private static final ParseField FIELD_SIMILARITY_SCORING = new ParseField("similarity_scoring");

    static {
        PARSER.declareBoolean(RewrittenQueries::setUseFieldBoosts, FIELD_USE_FIELD_BOOST);
        PARSER.declareFloat(RewrittenQueries::setNegativeWeight, FIELD_NEGATIVE_WEIGHT);
        PARSER.declareFloat(RewrittenQueries::setPositiveWeight, FIELD_POSITIVE_WEIGHT);
        PARSER.declareString(RewrittenQueries::setSimilarityScoring, FIELD_SIMILARITY_SCORING);
    }


    private boolean useFieldBoosts = true;
    private float positiveWeight = 1f;
    private float negativeWeight = 1f;
    private QuerySimilarityScoring similarityScoring = null;


    public RewrittenQueries() {}

    public RewrittenQueries(final StreamInput in) throws IOException {
        useFieldBoosts = in.readBoolean();
        positiveWeight = in.readFloat();
        negativeWeight = in.readFloat();
        final String strSimilarityScoring = in.readOptionalString();
        similarityScoring = strSimilarityScoring == null
                ? null : QuerySimilarityScoring.valueOf(strSimilarityScoring);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeBoolean(useFieldBoosts);
        out.writeFloat(positiveWeight);
        out.writeFloat(negativeWeight);
        out.writeOptionalString(similarityScoring != null ? similarityScoring.name() : null);
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {

        builder.startObject();

        builder.field(FIELD_USE_FIELD_BOOST.getPreferredName(), useFieldBoosts);
        builder.field(FIELD_NEGATIVE_WEIGHT.getPreferredName(), negativeWeight);
        builder.field(FIELD_POSITIVE_WEIGHT.getPreferredName(), positiveWeight);
        final Optional<String> scoringOpt = querySimilarityScoringToString(similarityScoring);
        if (scoringOpt.isPresent()) {
            builder.field(FIELD_SIMILARITY_SCORING.getPreferredName(), scoringOpt.get());
        }

        builder.endObject();

        return builder;

    }

    @Override
    public boolean isFragment() {
        return false;
    }

    public boolean isUseFieldBoosts() {
        return useFieldBoosts;
    }

    public void setUseFieldBoosts(boolean useFieldBoosts) {
        this.useFieldBoosts = useFieldBoosts;
    }

    public float getPositiveWeight() {
        return positiveWeight;
    }

    public void setPositiveWeight(float positiveWeight) {
        this.positiveWeight = positiveWeight;
    }

    public float getNegativeWeight() {
        return negativeWeight;
    }

    public void setNegativeWeight(float negativeWeight) {
        this.negativeWeight = negativeWeight;
    }

    public QuerySimilarityScoring getSimilarityScoring() {
        return similarityScoring;
    }

    public void setSimilarityScoring(final String boostQuerySimilarityScoring) {

        this.similarityScoring = paramToQuerySimilarityScoring(boostQuerySimilarityScoring,
                FIELD_SIMILARITY_SCORING);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof RewrittenQueries)) return false;
        final RewrittenQueries that = (RewrittenQueries) o;
        return useFieldBoosts == that.useFieldBoosts &&
                Float.compare(that.positiveWeight, positiveWeight) == 0 &&
                Float.compare(that.negativeWeight, negativeWeight) == 0 &&
                similarityScoring == that.similarityScoring;
    }

    @Override
    public int hashCode() {

        return Objects.hash(useFieldBoosts, positiveWeight, negativeWeight, similarityScoring);
    }
}
