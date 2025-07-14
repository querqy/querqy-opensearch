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

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class Generated implements NamedWriteable, ToXContent {

    public static final String NAME = "generated";


    public static final ObjectParser<Generated, Void> PARSER = new ObjectParser<>(NAME, Generated::new);
    private static final ParseField FIELD_QUERY_FIELDS = new ParseField("query_fields");
    private static final ParseField FIELD_FIELD_BOOST_FACTOR = new ParseField("field_boost_factor");

    static {
        PARSER.declareStringArray(Generated::setQueryFieldsAndBoostings, FIELD_QUERY_FIELDS);
        PARSER.declareFloat(Generated::setFieldBoostFactor, FIELD_FIELD_BOOST_FACTOR);
    }

    private Map<String, Float> queryFieldsAndBoostings = null;
    private Float fieldBoostFactor = null;

    public Generated() {}

    public Generated(final List<String> queryFieldsAndBoostings) {
        setQueryFieldsAndBoostings(queryFieldsAndBoostings);
    }

    public Generated(final StreamInput in) throws IOException {

        final int numGeneratedFields = in.readInt();
        if (numGeneratedFields > 0) {
            queryFieldsAndBoostings = new HashMap<>(numGeneratedFields);
            for (int i = 0; i < numGeneratedFields; i++) {
                queryFieldsAndBoostings.put(in.readString(), in.readFloat());
            }
        }
        fieldBoostFactor = in.readOptionalFloat();

    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        final int numFields = queryFieldsAndBoostings == null ? 0 : queryFieldsAndBoostings.size();
        out.writeInt(numFields);
        if (numFields > 0) {
            for (final Map.Entry<String, Float> entry : queryFieldsAndBoostings.entrySet()) {
                out.writeString(entry.getKey());
                out.writeFloat(entry.getValue());
            }
        }
        out.writeOptionalFloat(fieldBoostFactor);
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {

        builder.startObject();

        if (queryFieldsAndBoostings != null && !queryFieldsAndBoostings.isEmpty()) {
            builder.startArray(FIELD_QUERY_FIELDS.getPreferredName());
            for (final Map.Entry<String, Float> fieldEntry : queryFieldsAndBoostings.entrySet()) {
                final float boost = fieldEntry.getValue();
                if (boost == 1f) {
                    builder.value(fieldEntry.getKey());
                } else {
                    builder.value(fieldEntry.getKey() + "^" + boost);
                }
            }
            builder.endArray();
        }

        if (fieldBoostFactor != null) {
            builder.field(FIELD_FIELD_BOOST_FACTOR.getPreferredName(), fieldBoostFactor);
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
        if (!(o instanceof Generated)) return false;
        final Generated generated = (Generated) o;
        return Objects.equals(queryFieldsAndBoostings, generated.queryFieldsAndBoostings) &&
                Objects.equals(fieldBoostFactor, generated.fieldBoostFactor);
    }

    @Override
    public int hashCode() {

        return Objects.hash(queryFieldsAndBoostings, fieldBoostFactor);
    }

    public void setQueryFieldsAndBoostings(final List<String> queryFieldsAndBoostings) {
        this.queryFieldsAndBoostings = paramToQueryFieldsAndBoosting(queryFieldsAndBoostings);
    }

    public Map<String, Float> getQueryFieldsAndBoostings() {
        return queryFieldsAndBoostings == null ? Collections.emptyMap() : queryFieldsAndBoostings;
    }

    public Optional<Float> getFieldBoostFactor() {
        return Optional.ofNullable(fieldBoostFactor);
    }

    public void setFieldBoostFactor(final Float fieldBoostFactor) {
        this.fieldBoostFactor = fieldBoostFactor;
    }
}
