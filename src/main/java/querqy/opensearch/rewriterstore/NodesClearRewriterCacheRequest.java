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

package querqy.opensearch.rewriterstore;

import org.opensearch.action.support.nodes.BaseNodeRequest;
import org.opensearch.action.support.nodes.BaseNodesRequest;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.Optional;

public class NodesClearRewriterCacheRequest extends BaseNodesRequest<NodesClearRewriterCacheRequest> {

    private final String rewriterId;

    public NodesClearRewriterCacheRequest(final StreamInput in) throws IOException {
        super(in);
        rewriterId = in.readOptionalString();
    }

    public NodesClearRewriterCacheRequest() {
        super((String[]) null);
        rewriterId = null;
    }

    public NodesClearRewriterCacheRequest(final String rewriterId, final String... nodesIds) {
        super(nodesIds);
        this.rewriterId = rewriterId;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(rewriterId);
    }

    public Optional<String> getRewriterId() {
        return Optional.ofNullable(rewriterId);
    }

    public NodeRequest newNodeRequest() {
        return new NodeRequest(rewriterId);
    }

    public static class NodeRequest extends BaseNodeRequest {

        final String rewriterId;

        public NodeRequest(final StreamInput in) throws IOException {
            super(in);
            rewriterId = in.readOptionalString();
        }

        public NodeRequest() {
            rewriterId = null;
        }

        public NodeRequest(final String rewriterId) {
            this.rewriterId = rewriterId;
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeOptionalString(rewriterId);
        }

        public Optional<String> getRewriterId() {
            return Optional.ofNullable(rewriterId);
        }

    }
}
