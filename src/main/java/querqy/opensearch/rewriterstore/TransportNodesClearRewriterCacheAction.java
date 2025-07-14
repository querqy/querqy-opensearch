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

import org.opensearch.action.FailedNodeException;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.nodes.TransportNodesAction;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.indices.IndicesService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import querqy.opensearch.RewriterShardContexts;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class TransportNodesClearRewriterCacheAction extends TransportNodesAction<NodesClearRewriterCacheRequest,
        NodesClearRewriterCacheResponse, NodesClearRewriterCacheRequest.NodeRequest, NodesClearRewriterCacheResponse.NodeResponse> {

    protected RewriterShardContexts rewriterShardContexts;


    @Inject
    public TransportNodesClearRewriterCacheAction(final ThreadPool threadPool, final ClusterService clusterService,
                                              final TransportService transportService,
                                              final ActionFilters actionFilters,
                                              final IndicesService indexServices,
                                              final Client client,
                                              final RewriterShardContexts rewriterShardContexts) {

        super(NodesClearRewriterCacheAction.NAME, threadPool, clusterService, transportService, actionFilters,
                NodesClearRewriterCacheRequest::new, NodesClearRewriterCacheRequest.NodeRequest::new,
                ThreadPool.Names.MANAGEMENT, NodesClearRewriterCacheResponse.NodeResponse.class);
        this.rewriterShardContexts = rewriterShardContexts;
    }


    @Override
    protected NodesClearRewriterCacheResponse newResponse(final NodesClearRewriterCacheRequest request,
                                                          final List<NodesClearRewriterCacheResponse.NodeResponse>
                                                                  nodeResponses,
                                                          final List<FailedNodeException> failures) {
        return new NodesClearRewriterCacheResponse(clusterService.getClusterName(), nodeResponses, failures);
    }

    @Override
    protected NodesClearRewriterCacheRequest.NodeRequest newNodeRequest(final NodesClearRewriterCacheRequest request) {
        return request.newNodeRequest();
    }

    @Override
    protected NodesClearRewriterCacheResponse.NodeResponse newNodeResponse(final StreamInput in) throws IOException {
        return new NodesClearRewriterCacheResponse.NodeResponse(in);
    }

    @Override
    protected NodesClearRewriterCacheResponse.NodeResponse nodeOperation(
            final NodesClearRewriterCacheRequest.NodeRequest request) {

        final Optional<String> rewriterId = request.getRewriterId();
        if (rewriterId.isPresent()) {
            rewriterId.ifPresent(rewriterShardContexts::clearRewriter);
        } else {
            rewriterShardContexts.clearRewriters();
        }

        return new NodesClearRewriterCacheResponse.NodeResponse(clusterService.localNode());


    }
}
