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
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.indices.IndicesService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import querqy.opensearch.RewriterShardContexts;

import java.io.IOException;
import java.util.List;

public class TransportNodesReloadRewriterAction extends TransportNodesAction<NodesReloadRewriterRequest,
        NodesReloadRewriterResponse, NodesReloadRewriterRequest.NodeRequest, NodesReloadRewriterResponse.NodeResponse> {

    protected RewriterShardContexts rewriterShardContexts;
    protected Client client;
    protected IndicesService indexServices;

    @Inject
    public TransportNodesReloadRewriterAction(final ThreadPool threadPool, final ClusterService clusterService,
                                              final TransportService transportService,
                                              final ActionFilters actionFilters,
                                              final IndicesService indexServices,
                                              final Client client,
                                              final RewriterShardContexts rewriterShardContexts) {

        super(NodesReloadRewriterAction.NAME, threadPool, clusterService, transportService, actionFilters,
                NodesReloadRewriterRequest::new, NodesReloadRewriterRequest.NodeRequest::new,
                ThreadPool.Names.MANAGEMENT, NodesReloadRewriterResponse.NodeResponse.class);
        this.rewriterShardContexts = rewriterShardContexts;
        this.client = client;
        this.indexServices = indexServices;
    }

    @Override
    protected NodesReloadRewriterResponse newResponse(final NodesReloadRewriterRequest request,
                                                      final List<NodesReloadRewriterResponse.NodeResponse> nodeResponses,
                                                      final List<FailedNodeException> failures) {
        return new NodesReloadRewriterResponse(clusterService.getClusterName(), nodeResponses, failures);
    }

    @Override
    protected NodesReloadRewriterRequest.NodeRequest newNodeRequest(final NodesReloadRewriterRequest request) {
        return request.newNodeRequest();
    }

    @Override
    protected NodesReloadRewriterResponse.NodeResponse newNodeResponse(final StreamInput in) throws IOException {
        return new NodesReloadRewriterResponse.NodeResponse(in);
    }

    @Override
    protected NodesReloadRewriterResponse.NodeResponse nodeOperation(
            final NodesReloadRewriterRequest.NodeRequest request) {
        try {
            rewriterShardContexts.reloadRewriter(request.getRewriterId());
            return new NodesReloadRewriterResponse.NodeResponse(clusterService.localNode(), null);
        } catch (final Exception e) {
            return new NodesReloadRewriterResponse.NodeResponse(clusterService.localNode(), e);
        }
    }


}
