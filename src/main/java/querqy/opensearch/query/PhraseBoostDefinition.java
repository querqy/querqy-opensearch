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

import static querqy.opensearch.query.RequestUtils.paramToQueryFieldsAndBoosting;

import org.opensearch.core.ParseField;
import org.opensearch.core.common.io.stream.NamedWriteable;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ObjectParser;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import querqy.lucene.PhraseBoosting;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class PhraseBoostDefinition implements NamedWriteable, ToXContent {

    static final ObjectParser<PhraseBoostDefinition, Void> PARSER = new ObjectParser<>(
            "phrase_boost_definition", PhraseBoostDefinition::new);

    private static final ParseField FIELD_SLOP = new ParseField("slop");
    private static final ParseField FIELD_FIELDS = new ParseField("fields");

    static {
        PARSER.declareInt(PhraseBoostDefinition::setSlop, FIELD_SLOP);
        PARSER.declareStringArray(PhraseBoostDefinition::setFields, FIELD_FIELDS);
    }


    private int slop = 0;
    private Map<String, Float> queryFieldsAndBoostings;

    public PhraseBoostDefinition() {}

    public PhraseBoostDefinition(final int slop, final List<String> fields) {
        setSlop(slop);
        setFields(fields);
    }

    public PhraseBoostDefinition(final int slop, final String... fields) {
        setSlop(slop);
        setFields(Arrays.asList(fields));
    }

    public PhraseBoostDefinition(final StreamInput in) throws IOException {
        slop = in.readInt();
        final int numFields = in.readInt();
        queryFieldsAndBoostings = new HashMap<>(numFields);
        for (int i = 0; i < numFields; i++) {
            queryFieldsAndBoostings.put(in.readString(), in.readFloat());
        }
    }


    public void setSlop(final int slop) {
        this.slop = slop;
    }

    public PhraseBoostDefinition slop(final int slop) {
        setSlop(slop);
        return this;
    }

    public void setFields(final List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Query fields must not be null or empty");
        }
        this.queryFieldsAndBoostings = paramToQueryFieldsAndBoosting(fields);
    }

    public PhraseBoostDefinition fields(final String... fields) {
        if (fields == null) {
            throw new IllegalArgumentException("Query fields must not be null");
        }
        setFields(Arrays.asList(fields));
        return this;
    }

    public List<PhraseBoosting.PhraseBoostFieldParams> toPhraseBoostFieldParams(final PhraseBoosting.NGramType nGramType) {
        return queryFieldsAndBoostings.entrySet().stream()
                .map(entry -> new PhraseBoosting.PhraseBoostFieldParams(entry.getKey(), nGramType, slop, entry.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public String getWriteableName() {
        return "phraseBoostDefinition";
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeInt(slop);
        out.writeInt(queryFieldsAndBoostings.size());
        for (Map.Entry<String, Float> entry : queryFieldsAndBoostings.entrySet()) {
            out.writeString(entry.getKey());
            out.writeFloat(entry.getValue());
        }
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {

        builder.startObject();
        builder.field(FIELD_SLOP.getPreferredName(), slop);

        if (queryFieldsAndBoostings != null && !queryFieldsAndBoostings.isEmpty()) {
            builder.startArray(FIELD_FIELDS.getPreferredName());
            for (final Map.Entry<String, Float> fieldEntry : queryFieldsAndBoostings.entrySet()) {
                builder.value(fieldEntry.getKey() + "^" + fieldEntry.getValue());
            }
            builder.endArray();
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
        if (!(o instanceof PhraseBoostDefinition)) return false;
        final PhraseBoostDefinition that = (PhraseBoostDefinition) o;
        return slop == that.slop &&
                Objects.equals(queryFieldsAndBoostings, that.queryFieldsAndBoostings);
    }

    @Override
    public int hashCode() {

        return Objects.hash(slop, queryFieldsAndBoostings);
    }
}
