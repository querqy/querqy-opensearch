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

import static org.opensearch.core.common.transport.TransportAddress.META_ADDRESS;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.opensearch.Version;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.replication.ReplicationResponse;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.rest.RestStatus;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class PutRewriterResponseTest {

    @Test
    public void testThatStatusIsTakenFromIndexResponse() {
        final RestStatus status = RestStatus.CREATED;
        final IndexResponse indexResponse = mock(IndexResponse.class);
        when(indexResponse.status()).thenReturn(status);
        final NodesReloadRewriterResponse reloadRewriterResponse = mock(NodesReloadRewriterResponse.class);

        final PutRewriterResponse response = new PutRewriterResponse(indexResponse, reloadRewriterResponse);
        assertSame(status, response.status());
        verify(indexResponse, times(1)).status();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testToJson() throws IOException {

        final IndexResponse indexResponse = new IndexResponse(new ShardId("idx1", "shard1", 1), "id1", 11,
                2L, 8L, true);

        final DiscoveryNode node1 = new DiscoveryNode("name1", "d1", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);
        final DiscoveryNode node2 = new DiscoveryNode("name2", "d2", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);

        final NodesReloadRewriterResponse reloadRewriterResponse = new NodesReloadRewriterResponse(
                new ClusterName("cluster27"), Arrays.asList(new NodesReloadRewriterResponse.NodeResponse(node1, null),
                        new NodesReloadRewriterResponse.NodeResponse(node2, null)), Collections.emptyList());

        final PutRewriterResponse response = new PutRewriterResponse(indexResponse, reloadRewriterResponse);

        final Map<String, Object> parsed;
        try (InputStream stream = XContentHelper.toXContent(response, XContentType.JSON, true).streamInput()) {
            parsed = XContentHelper.convertToMap(XContentType.JSON.xContent(), stream, false);
        }

        assertEquals(2, parsed.size());

        final Map<String, Object> reloaded = (Map<String, Object>) parsed.get("reloaded");
        assertNotNull(reloaded);

        final Map<String, Object> nodes = (Map<String, Object>) reloaded.get("nodes");
        assertThat((Map<String, String>) nodes.get("d1"), Matchers.hasEntry("name", "name1"));
        assertThat((Map<String, String>) nodes.get("d2"), Matchers.hasEntry("name", "name2"));

        final Map<String, Object> put = (Map<String, Object>) parsed.get("put");
        assertNotNull(put);
        assertEquals("created", put.get("result"));

    }

    @Test
    public void testStreamSerialization() throws IOException {

        final IndexResponse indexResponse = new IndexResponse(new ShardId("idx1", "shard1", 1), "id1", 11, 2L,
                8L, true);

        indexResponse.setShardInfo(new ReplicationResponse.ShardInfo(4, 4));

        final DiscoveryNode node1 = new DiscoveryNode("name1", "d1", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);
        final DiscoveryNode node2 = new DiscoveryNode("name2", "d2", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);

        final NodesReloadRewriterResponse reloadRewriterResponse = new NodesReloadRewriterResponse(
                new ClusterName("cluster27"), Arrays.asList(new NodesReloadRewriterResponse.NodeResponse(node1, null),
                new NodesReloadRewriterResponse.NodeResponse(node2, null)), Collections.emptyList());

        final PutRewriterResponse response1 = new PutRewriterResponse(indexResponse, reloadRewriterResponse);


        final BytesStreamOutput output = new BytesStreamOutput();
        response1.writeTo(output);
        output.flush();

        final PutRewriterResponse response2 = new PutRewriterResponse(output.bytes().streamInput());

        assertEquals(response1.status(), response2.status());

        final IndexResponse indexResponse1 = response1.getIndexResponse();
        final IndexResponse indexResponse2 = response2.getIndexResponse();
        assertEquals(indexResponse1.getShardId(), indexResponse2.getShardId());
        assertEquals(indexResponse1.getSeqNo(), indexResponse2.getSeqNo());

        final NodesReloadRewriterResponse reloadResponse1 = response1.getReloadResponse();
        final NodesReloadRewriterResponse reloadResponse2 = response2.getReloadResponse();
        assertEquals(reloadResponse1.getNodes(), reloadResponse2.getNodes());
    }
}