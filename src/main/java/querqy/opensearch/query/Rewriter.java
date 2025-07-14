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

import org.opensearch.core.common.io.stream.NamedWriteable;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ContextParser;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public class Rewriter implements NamedWriteable, ToXContent {

    public static final String NAME = "rewriter";

    public static ContextParser<Void, Rewriter> PARSER = new RewriterParser();

    private String name = null;
    private Map<String, Object> params = null;

    public Rewriter(final StreamInput in) throws IOException {
        name = in.readString();
        final boolean hasParams = in.readBoolean();
        if (hasParams) {
            params = in.readMap();
        }
    }

    public Rewriter(final String name) {
        this(name, null);
    }

    public Rewriter(final String name, final Map<String, Object> params) {
        if (name == null) {
            throw new IllegalArgumentException("Missing rewriter name");
        }
        this.name = name;
        this.params = params;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeString(name);
        if (params != null) {
            out.writeBoolean(true);
            out.writeMap(params);
        } else {
            out.writeBoolean(false);
        }
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
        if (this.params != null) {
            builder.startObject();
            builder.field("name", name);
            if (this.params != null && !this.params.isEmpty()) {
                builder.field("params", this.params);
            }
            builder.endObject();
        } else {
            builder.value(name);
        }
        return builder;
    }

    @Override
    public boolean isFragment() {
        return params == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rewriter)) return false;
        Rewriter rewriter = (Rewriter) o;
        return Objects.equals(name, rewriter.name) &&
                Objects.equals(params, rewriter.params);
    }

    @Override
    public int hashCode() {

        return Objects.hash(name, params);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public static class RewriterParser implements ContextParser<Void, Rewriter> {

        @Override
        @SuppressWarnings("unchecked")
        public Rewriter parse(final XContentParser parser, final Void context) throws IOException {
            final XContentParser.Token token = parser.currentToken();
            if (token == XContentParser.Token.START_OBJECT) {

                final Map<String, Object> definition = parser.map();
                return new Rewriter((String) definition.get("name"), (Map<String, Object>) definition.get("params"));

            } else if (token == XContentParser.Token.VALUE_STRING) {
                return new Rewriter(parser.text(), null);
            } else {
                throw new IOException("Unexpected token type: " + token);
            }

        }
    }
}
