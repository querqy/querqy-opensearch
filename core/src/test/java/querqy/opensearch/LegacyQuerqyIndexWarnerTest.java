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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static querqy.opensearch.rewriterstore.Constants.LEGACY_QUERQY_INDEX_NAME;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.gateway.GatewayService;
import org.opensearch.test.MockLogAppender;
import org.opensearch.test.OpenSearchTestCase;

public class LegacyQuerqyIndexWarnerTest extends OpenSearchTestCase {

    public void testWarnsOnceWhenLegacyIndexExists() throws Exception {

        final ClusterService clusterService = mock(ClusterService.class);
        final LegacyQuerqyIndexWarner warner = new LegacyQuerqyIndexWarner(clusterService);

        try (MockLogAppender appender = MockLogAppender.createForLoggers(
                LogManager.getLogger(LegacyQuerqyIndexWarner.class))) {
            final WarnCount warnings = new WarnCount();
            appender.addExpectation(warnings);

            warner.clusterChanged(event(true, false, LEGACY_QUERQY_INDEX_NAME, QUERQY_INDEX_NAME));

            assertEquals(1, warnings.count);
            assertTrue(warnings.lastMessage, warnings.lastMessage.contains(LEGACY_QUERQY_INDEX_NAME));
            assertTrue(warnings.lastMessage, warnings.lastMessage.contains(QUERQY_INDEX_NAME));
        }

        verify(clusterService).removeListener(warner);
    }

    public void testStaysQuietAndUnregistersWhenNoLegacyIndex() throws Exception {

        final ClusterService clusterService = mock(ClusterService.class);
        final LegacyQuerqyIndexWarner warner = new LegacyQuerqyIndexWarner(clusterService);

        try (MockLogAppender appender = MockLogAppender.createForLoggers(
                LogManager.getLogger(LegacyQuerqyIndexWarner.class))) {
            final WarnCount warnings = new WarnCount();
            appender.addExpectation(warnings);

            warner.clusterChanged(event(true, false, QUERQY_INDEX_NAME));

            assertEquals(0, warnings.count);
        }

        verify(clusterService).removeListener(warner);
    }

    public void testWaitsWhileNotClusterManager() {

        final ClusterService clusterService = mock(ClusterService.class);
        final LegacyQuerqyIndexWarner warner = new LegacyQuerqyIndexWarner(clusterService);

        warner.clusterChanged(event(false, false, LEGACY_QUERQY_INDEX_NAME));

        verify(clusterService, never()).removeListener(warner);
    }

    public void testWaitsUntilStateIsRecovered() {

        final ClusterService clusterService = mock(ClusterService.class);
        final LegacyQuerqyIndexWarner warner = new LegacyQuerqyIndexWarner(clusterService);

        warner.clusterChanged(event(true, true));

        verify(clusterService, never()).removeListener(warner);
    }

    /**
     * Counts WARN events from {@link LegacyQuerqyIndexWarner}'s logger. (MockLogAppender's own Seen/Unseen
     * expectations prefix the expected logger name with {@code org.opensearch.}, so they never match a logger
     * outside that package.)
     */
    private static final class WarnCount implements MockLogAppender.LoggingExpectation {

        int count = 0;
        String lastMessage;

        @Override
        public void match(final LogEvent event) {
            if (event.getLevel() == Level.WARN
                    && LegacyQuerqyIndexWarner.class.getName().equals(event.getLoggerName())) {
                count++;
                lastMessage = event.getMessage().getFormattedMessage();
            }
        }

        @Override
        public void assertMatched() {
        }
    }

    private static ClusterChangedEvent event(final boolean localIsClusterManager, final boolean notRecovered,
                                             final String... indexNames) {

        final DiscoveryNode local = new DiscoveryNode("local", buildNewFakeTransportAddress(), Version.CURRENT);
        final DiscoveryNode other = new DiscoveryNode("other", buildNewFakeTransportAddress(), Version.CURRENT);
        final DiscoveryNodes nodes = DiscoveryNodes.builder().add(local).add(other).localNodeId(local.getId())
                .clusterManagerNodeId(localIsClusterManager ? local.getId() : other.getId()).build();

        final Metadata.Builder metadata = Metadata.builder();
        for (final String indexName : indexNames) {
            metadata.put(IndexMetadata.builder(indexName).settings(settings(Version.CURRENT))
                    .numberOfShards(1).numberOfReplicas(0));
        }

        final ClusterBlocks.Builder blocks = ClusterBlocks.builder();
        if (notRecovered) {
            blocks.addGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK);
        }

        final ClusterState state = ClusterState.builder(ClusterName.DEFAULT).nodes(nodes).metadata(metadata)
                .blocks(blocks).build();
        final ClusterState previous = ClusterState.builder(ClusterName.DEFAULT).nodes(nodes).build();
        return new ClusterChangedEvent("test", state, previous);
    }
}
