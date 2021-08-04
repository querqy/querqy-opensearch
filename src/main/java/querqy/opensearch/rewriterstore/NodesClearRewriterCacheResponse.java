package querqy.opensearch.rewriterstore;

import org.opensearch.action.FailedNodeException;
import org.opensearch.action.support.nodes.BaseNodeResponse;
import org.opensearch.action.support.nodes.BaseNodesResponse;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ToXContentObject;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class NodesClearRewriterCacheResponse extends BaseNodesResponse<NodesClearRewriterCacheResponse.NodeResponse>
        implements ToXContentObject {


    public NodesClearRewriterCacheResponse(final ClusterName clusterName,
                                       final List<NodeResponse> responses,
                                       final List<FailedNodeException> failures) {
        super(clusterName, responses, failures);
    }

    public NodesClearRewriterCacheResponse(final StreamInput in) throws IOException {
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
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
        builder.startObject();
        builder.startObject("nodes");
        for (final NodeResponse node : getNodes()) {
            builder.startObject(node.getNode().getId());
            node.toXContent(builder, params);
            builder.endObject();
        }
        builder.endObject();
        builder.endObject();
        return builder;
    }



    public static class NodeResponse extends BaseNodeResponse
            implements ToXContentObject {

        public NodeResponse(final StreamInput in) throws IOException {
            super(in);
        }

        public NodeResponse(final DiscoveryNode node) {
            super(node);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            return Objects.equals(getNode(), ((NodeResponse) o).getNode());

        }

        @Override
        public int hashCode() {
            return Objects.hash(getNode());
        }

        static NodeResponse readNodeResponse(final StreamInput in) throws IOException {
            return new NodeResponse(in);
        }

        @Override
        public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
            return builder.field("name", getNode().getName());
        }

        @Override
        public boolean isFragment() {
            return true;
        }
    }
}
