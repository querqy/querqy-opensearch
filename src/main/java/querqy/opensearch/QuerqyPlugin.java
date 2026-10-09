/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2021 Querqy for OpenSearch Contributors
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

package querqy.opensearch;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

import org.opensearch.action.ActionRequest;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import querqy.opensearch.rewriterstore.DeleteRewriterAction;
import querqy.opensearch.rewriterstore.GetRewriterAction;
import querqy.opensearch.rewriterstore.NodesClearRewriterCacheAction;
import querqy.opensearch.rewriterstore.NodesReloadRewriterAction;
import querqy.opensearch.rewriterstore.RestDeleteRewriterAction;
import querqy.opensearch.rewriterstore.RestGetRewriterAction;
import querqy.opensearch.rewriterstore.RestPutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.TransportDeleteRewriterAction;
import querqy.opensearch.rewriterstore.TransportGetRewriterAction;
import querqy.opensearch.rewriterstore.TransportNodesClearRewriterCacheAction;
import querqy.opensearch.rewriterstore.TransportNodesReloadRewriterAction;
import querqy.opensearch.rewriterstore.TransportPutRewriterAction;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * The full Querqy plugin: {@link QuerqyCorePlugin} (the {@code querqy} query, rewriters, index-based config) plus
 * the {@code ActionPlugin} REST/transport rewriter-management API (PUT/GET/DELETE rewriter, cluster-wide reload and
 * cache clear). This is the self-managed-cluster artifact; it is not installable on Amazon OpenSearch Service, which
 * doesn't support {@code ActionPlugin} - use {@code querqy-opensearch-core} there instead.
 */
public class QuerqyPlugin extends QuerqyCorePlugin implements ActionPlugin {

    public QuerqyPlugin(final Settings settings) {
        super(settings);
    }

    @Override
    public List<RestHandler> getRestHandlers(final Settings settings, final RestController restController,
                                             final ClusterSettings clusterSettings,
                                             final IndexScopedSettings indexScopedSettings,
                                             final SettingsFilter settingsFilter,
                                             final IndexNameExpressionResolver indexNameExpressionResolver,
                                             final Supplier<DiscoveryNodes> nodesInCluster) {

        return Arrays.asList(new RestPutRewriterAction(), new RestDeleteRewriterAction(),
                new RestGetRewriterAction());

    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return unmodifiableList(asList(
                new ActionHandler<>(PutRewriterAction.INSTANCE, TransportPutRewriterAction.class),
                new ActionHandler<>(NodesReloadRewriterAction.INSTANCE, TransportNodesReloadRewriterAction.class),
                new ActionHandler<>(DeleteRewriterAction.INSTANCE, TransportDeleteRewriterAction.class),
                new ActionHandler<>(GetRewriterAction.INSTANCE, TransportGetRewriterAction.class),
                new ActionHandler<>(NodesClearRewriterCacheAction.INSTANCE, TransportNodesClearRewriterCacheAction
                        .class)

        ));
    }
}
