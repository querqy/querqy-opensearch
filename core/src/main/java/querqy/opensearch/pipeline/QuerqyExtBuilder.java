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

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.search.SearchExtBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code querqy_rewrite} response processor's {@code ext.querqy} payload: rewrite-chain debug/log data
 * (reusing {@link querqy.rewrite.logging.RewriteChainLog}'s own field names verbatim - {@code rewriteChain},
 * {@code rewriterId}, {@code actions}, ... - matching the modern {@code debug.querqy.rewrite} convention used
 * elsewhere in the Querqy ecosystem) plus Common Rules DECORATE output, serialized generically as a plain
 * {@code Map<String, Object>} rather than a hand-built typed {@code Writeable} - there is no fixed schema to
 * maintain here since the wrapped data's own shape already is the schema.
 */
public class QuerqyExtBuilder extends SearchExtBuilder {

    public static final String NAME = "querqy";

    private final Map<String, Object> payload;

    public QuerqyExtBuilder(final Map<String, Object> payload) {
        this.payload = payload;
    }

    @SuppressWarnings("unchecked")
    public QuerqyExtBuilder(final StreamInput in) throws IOException {
        this.payload = (Map<String, Object>) in.readGenericValue();
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeGenericValue(payload);
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
        return builder.field(NAME, payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload);
    }

    @Override
    public boolean equals(final Object obj) {
        return (obj instanceof QuerqyExtBuilder) && Objects.equals(payload, ((QuerqyExtBuilder) obj).payload);
    }
}
