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

package querqy.opensearch.searchpipelinespike;

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.search.SearchExtBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * Phase-0 spike only (see the briefing's "Response ext spike"): a minimal, dedicated {@link SearchExtBuilder}
 * standing in for what Querqy's eventual decoration/rewriter-debug output would look like, to answer a single
 * question - can a {@code SearchResponseProcessor} add arbitrary data to a {@code SearchResponse}'s {@code ext}
 * section at all, and does it survive back to the client? Deliberately test-scoped, not production code.
 */
public class SpikeExtBuilder extends SearchExtBuilder {

    public static final String NAME = "querqy_spike_ext";

    private final String payload;

    public SpikeExtBuilder(final String payload) {
        this.payload = payload;
    }

    public SpikeExtBuilder(final StreamInput in) throws IOException {
        this.payload = in.readString();
    }

    public String getPayload() {
        return payload;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeString(payload);
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
        return (obj instanceof SpikeExtBuilder) && Objects.equals(payload, ((SpikeExtBuilder) obj).payload);
    }
}
