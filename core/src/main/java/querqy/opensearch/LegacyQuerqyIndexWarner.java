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

package querqy.opensearch;

import static querqy.opensearch.rewriterstore.Constants.LEGACY_QUERQY_INDEX_NAME;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.gateway.GatewayService;

/**
 * Logs a one-time warning if the cluster still has the rewriter index under its pre-2.0 name ({@value
 * querqy.opensearch.rewriterstore.Constants#LEGACY_QUERQY_INDEX_NAME}). Since version 2.0, rewriters and dictionaries
 * are only read from {@value querqy.opensearch.rewriterstore.Constants#QUERQY_INDEX_NAME} and nothing is migrated
 * automatically (see querqy-opensearch#208), so without this warning, rewriters that were only ever saved to the old
 * index would just seem to have disappeared.
 *
 * <p>Checks the first recovered cluster state seen while this node is the elected cluster manager (so the warning is
 * logged once per cluster, not once per node), then unregisters itself. Nothing is read from, written to, or deleted
 * from the legacy index.</p>
 */
public final class LegacyQuerqyIndexWarner implements ClusterStateListener {

    private static final Logger LOGGER = LogManager.getLogger(LegacyQuerqyIndexWarner.class);

    static final String WARNING = "Found the index [" + LEGACY_QUERQY_INDEX_NAME + "], which held Querqy rewriter "
            + "configurations before Querqy for OpenSearch 2.0. It is no longer read: rewriters and dictionaries are "
            + "now stored in [" + QUERQY_INDEX_NAME + "]. Save your rewriters (and dictionaries) again - via the REST "
            + "API or the config CLI - and then delete [" + LEGACY_QUERQY_INDEX_NAME + "].";

    private final ClusterService clusterService;

    LegacyQuerqyIndexWarner(final ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    static void register(final ClusterService clusterService) {
        clusterService.addListener(new LegacyQuerqyIndexWarner(clusterService));
    }

    @Override
    public void clusterChanged(final ClusterChangedEvent event) {

        if (!event.localNodeClusterManager()) {
            return;
        }

        final ClusterState state = event.state();
        if (state.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            return; // index metadata not recovered yet - can't tell whether the legacy index exists
        }

        clusterService.removeListener(this);

        if (state.metadata().hasIndex(LEGACY_QUERQY_INDEX_NAME)) {
            LOGGER.warn(WARNING);
        }
    }
}
