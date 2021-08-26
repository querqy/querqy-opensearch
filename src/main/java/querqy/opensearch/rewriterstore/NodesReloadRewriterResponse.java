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

import org.opensearch.OpenSearchException;
import org.opensearch.action.FailedNodeException;
import org.opensearch.action.support.nodes.BaseNodeResponse;
import org.opensearch.action.support.nodes.BaseNodesResponse;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.Strings;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.ToXContentObject;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class NodesReloadRewriterResponse extends BaseNodesResponse<NodesReloadRewriterResponse.NodeResponse>
        implements ToXContentObject {


    public NodesReloadRewriterResponse(final ClusterName clusterName,
                                       final List<NodeResponse> responses,
                                       final List<FailedNodeException> failures) {
        super(clusterName, responses, failures);
    }

    public NodesReloadRewriterResponse(final StreamInput in) throws IOException {
        super(in);
    }

    @Override
    protected List<NodeResponse> readNodesFrom(final StreamInput in) throws IOException {
        return in.readList(NodeResponse::readNodeResponse);
    }

    @Override
    protected void writeNodesTo(final StreamOutput out, final List<NodeResponse> nodes) throws IOException {
        out.writeCollection(nodes);
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final ToXContent.Params params) throws IOException {
        builder.startObject();
        builder.startObject("nodes");
        for (final NodeResponse node : getNodes()) {
            builder.startObject(node.getNode().getId());
            builder.field("name", node.getNode().getName());
            final Exception e = node.reloadException();
            if (e != null) {
                builder.startObject("reload_exception");
                OpenSearchException.generateThrowableXContent(builder, params, e);
                builder.endObject();
            }
            builder.endObject();
        }
        builder.endObject();
        builder.endObject();
        return builder;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof NodesReloadRewriterResponse)) {
            return false;
        }
        final NodesReloadRewriterResponse other = (NodesReloadRewriterResponse) obj;
        // We only count failures as they don't implement equals():
        final List<FailedNodeException> thisFailures = failures();
        final List<FailedNodeException> thatFailures = other.failures();
        if (thisFailures == null && thatFailures != null) {
            return false;
        }
        if (thisFailures != null) {
            if (thatFailures == null) {
                return false;
            }
            if (thisFailures.size() != thatFailures.size()) {
                return false;
            }
        }

        return Objects.equals(getClusterName(), other.getClusterName())
                && Objects.equals(getNodes(), other.getNodes());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getNodes());
    }

    @Override
    public String toString() {
        try {
            final XContentBuilder builder = XContentFactory.jsonBuilder().prettyPrint();
            toXContent(builder, EMPTY_PARAMS);
            return Strings.toString(builder);
        } catch (final IOException e) {
            return "{ \"error\" : \"" + e.getMessage() + "\"}";
        }
    }

    public static class NodeResponse extends BaseNodeResponse {

        private final Exception reloadException;

        public NodeResponse(final StreamInput in) throws IOException {
            super(in);
            reloadException = in.readBoolean() ? in.readException() : null;
        }

        public NodeResponse(final DiscoveryNode node, final Exception reloadException) {
            super(node);
            this.reloadException = reloadException;
        }

        public Exception reloadException() {
            return this.reloadException;
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            super.writeTo(out);
            if (reloadException != null) {
                out.writeBoolean(true);
                out.writeException(reloadException);
            } else {
                out.writeBoolean(false);
            }
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final NodeResponse that = (NodeResponse) o;
            // We cannot rely on the Exception to implement equals(), users of NodesReloadRewriterResponse will
            // be interested just in the message anyway
            if (reloadException == null) {
                return that.reloadException == null;
            } else if (that.reloadException == null) {
                return false;
            }
            return Objects.equals(reloadException.getMessage(), that.reloadException.getMessage());
        }

        @Override
        public int hashCode() {
            return reloadException != null && reloadException.getMessage() != null
                    ? reloadException.getMessage().hashCode() : 0;
        }

        static NodeResponse readNodeResponse(final StreamInput in) throws IOException {
            return new NodeResponse(in);
        }
    }
}
