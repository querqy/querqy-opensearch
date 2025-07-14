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

import org.opensearch.core.ParseField;
import org.opensearch.core.common.io.stream.NamedWriteable;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ObjectParser;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import querqy.lucene.PhraseBoosting.NGramType;
import querqy.lucene.PhraseBoosting.PhraseBoostFieldParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PhraseBoosts implements NamedWriteable, ToXContent {

    public static final ObjectParser<PhraseBoosts, Void> PARSER = new ObjectParser<>("phrase_boosts", PhraseBoosts::new);

    private static final ParseField FIELD_TIE_BREAKER = new ParseField("tie_breaker");
    private static final ParseField FIELD_FULL = new ParseField("full");
    private static final ParseField FIELD_BIGRAM = new ParseField("bigram");
    private static final ParseField FIELD_TRIGRAM = new ParseField("trigram");

    static {
        PARSER.declareFloat(PhraseBoosts::setTieBreaker, FIELD_TIE_BREAKER);
        PARSER.declareObject(PhraseBoosts::setFull, PhraseBoostDefinition.PARSER, FIELD_FULL);
        PARSER.declareObject(PhraseBoosts::setBigram, PhraseBoostDefinition.PARSER, FIELD_BIGRAM);
        PARSER.declareObject(PhraseBoosts::setTrigram, PhraseBoostDefinition.PARSER, FIELD_TRIGRAM);

    }

    private float tie = 0f;
    private PhraseBoostDefinition full;
    private PhraseBoostDefinition bigram;
    private PhraseBoostDefinition trigram;


    public PhraseBoosts() {}

    public PhraseBoosts(final StreamInput in) throws IOException {
        tie = in.readFloat();
        full = in.readOptionalWriteable(PhraseBoostDefinition::new);
        bigram = in.readOptionalWriteable(PhraseBoostDefinition::new);
        trigram = in.readOptionalWriteable(PhraseBoostDefinition::new);
    }

    public List<PhraseBoostFieldParams> toPhraseBoostFieldParams() {

        final List<PhraseBoostFieldParams> params = new ArrayList<>();

        if (full != null) {
            params.addAll(full.toPhraseBoostFieldParams(NGramType.PHRASE));
        }
        if (bigram != null) {
            params.addAll(bigram.toPhraseBoostFieldParams(NGramType.BI_GRAM));
        }

        if (trigram != null) {
            params.addAll(trigram.toPhraseBoostFieldParams(NGramType.TRI_GRAM));
        }

        return params;

    }

    public PhraseBoosts full(final PhraseBoostDefinition full) {
        setFull(full);
        return this;
    }

    public PhraseBoosts bigram(final PhraseBoostDefinition bigram) {
        setBigram(bigram);
        return this;
    }

    public PhraseBoosts trigram(final PhraseBoostDefinition trigram) {
        setTrigram(trigram);
        return this;
    }

    public PhraseBoosts tieBreaker(final float tie) {
        setTieBreaker(tie);
        return this;
    }

    public void setTieBreaker(final float tie) {
        this.tie = tie;
    }

    public void setFull(final PhraseBoostDefinition full) {
        this.full = full;
    }

    public void setBigram(final PhraseBoostDefinition bigram) {
        this.bigram = bigram;
    }

    public void setTrigram(final PhraseBoostDefinition trigram) {
        this.trigram = trigram;
    }


    public float getTieBreaker() {
        return tie;
    }

    public PhraseBoostDefinition getFull() {
        return full;
    }

    public PhraseBoostDefinition getBigram() {
        return bigram;
    }

    public PhraseBoostDefinition getTrigram() {
        return trigram;
    }

    @Override
    public String getWriteableName() {
        return "phraseBoosts";
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeFloat(tie);
        out.writeOptionalWriteable(full);
        out.writeOptionalWriteable(bigram);
        out.writeOptionalWriteable(trigram);

    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {

        builder.startObject();

        builder.field(FIELD_TIE_BREAKER.getPreferredName(), tie);

        if (full != null) {
            builder.field(FIELD_FULL.getPreferredName(), full, params);
        }

        if (bigram != null) {
            builder.field(FIELD_BIGRAM.getPreferredName(), bigram, params);
        }

        if (trigram != null) {
            builder.field(FIELD_TRIGRAM.getPreferredName(), trigram, params);
        }

        builder.endObject();

        return builder;
    }

    @Override
    public boolean isFragment() {
        return false;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof PhraseBoosts)) return false;
        final PhraseBoosts that = (PhraseBoosts) o;
        return Float.compare(that.tie, tie) == 0 &&
                Objects.equals(full, that.full) &&
                Objects.equals(bigram, that.bigram) &&
                Objects.equals(trigram, that.trigram);
    }

    @Override
    public int hashCode() {

        return Objects.hash(tie, full, bigram, trigram);
    }
}
