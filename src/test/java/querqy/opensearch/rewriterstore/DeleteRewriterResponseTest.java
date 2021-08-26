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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.opensearch.common.transport.TransportAddress.META_ADDRESS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.opensearch.Version;
import org.opensearch.action.delete.DeleteResponse;
import org.opensearch.action.support.replication.ReplicationResponse;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.shard.ShardId;
import org.opensearch.rest.RestStatus;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class DeleteRewriterResponseTest {

    @Test
    public void testThatStatusIsTakenFromDeleteResponse() {
        final RestStatus status = RestStatus.NOT_FOUND;
        final DeleteResponse deleteResponse = mock(DeleteResponse.class);
        Mockito.when(deleteResponse.status()).thenReturn(status);
        final NodesClearRewriterCacheResponse clearRewriterCacheResponse = mock(NodesClearRewriterCacheResponse.class);

        final DeleteRewriterResponse response = new DeleteRewriterResponse(deleteResponse, clearRewriterCacheResponse);
        assertSame(status, response.status());
        verify(deleteResponse, times(1)).status();
    }

    @Test
    public void testStreamSerialization() throws IOException {

        final DiscoveryNode node1 = new DiscoveryNode("name1", "d1", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);
        final DiscoveryNode node2 = new DiscoveryNode("name2", "d2", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);

        final DeleteResponse deleteResponse1 = new DeleteResponse(new ShardId("idx1", "shard1", 1), ".querqy", "id1",
                11, 2L, 8L, true);
        deleteResponse1.setShardInfo(new ReplicationResponse.ShardInfo(2, 1));

        final NodesClearRewriterCacheResponse clearRewriterCacheResponse1 = new NodesClearRewriterCacheResponse
                (new ClusterName("cluster27"),
                        Arrays.asList(new NodesClearRewriterCacheResponse.NodeResponse(node1),
                                new NodesClearRewriterCacheResponse.NodeResponse(node2)), Collections.emptyList());

        final DeleteRewriterResponse response1 = new DeleteRewriterResponse(deleteResponse1,
                clearRewriterCacheResponse1);

        final BytesStreamOutput output = new BytesStreamOutput();
        response1.writeTo(output);
        output.flush();

        final DeleteRewriterResponse response2 = new DeleteRewriterResponse(output.bytes().streamInput());
        assertEquals(response1.status(), response2.status());

        final DeleteResponse deleteResponse2 = response2.getDeleteResponse();
        assertEquals(deleteResponse1.status(), deleteResponse2.status());
        assertEquals(deleteResponse1.getShardId(), deleteResponse2.getShardId());

        final NodesClearRewriterCacheResponse clearRewriterCacheResponse2 = response2.getClearRewriterCacheResponse();
        assertEquals(clearRewriterCacheResponse1.getNodes(), clearRewriterCacheResponse2.getNodes());

    }


    @SuppressWarnings("unchecked")
    @Test
    public void testToJson() throws IOException {

        final DiscoveryNode node1 = new DiscoveryNode("name1", "d1", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);
        final DiscoveryNode node2 = new DiscoveryNode("name2", "d2", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);

        final DeleteResponse deleteResponse = new DeleteResponse(new ShardId("idx1", "shard1", 1), ".querqy", "id1", 11,
                2L, 8L, true);
        final NodesClearRewriterCacheResponse clearRewriterCacheResponse = new NodesClearRewriterCacheResponse
                (new ClusterName("cluster27"),
                        Arrays.asList(new NodesClearRewriterCacheResponse.NodeResponse(node1),
                                new NodesClearRewriterCacheResponse.NodeResponse(node2)), Collections.emptyList());

        final DeleteRewriterResponse response = new DeleteRewriterResponse(deleteResponse, clearRewriterCacheResponse);

        final Map<String, Object> parsed;
        try (InputStream stream = XContentHelper.toXContent(response, XContentType.JSON, true).streamInput()) {
            parsed = XContentHelper.convertToMap(XContentFactory.xContent(XContentType.JSON), stream, false);
        }

        assertEquals(2, parsed.size());

        final Map<String, Object> clearcacheResult = (Map<String, Object>) parsed.get("clearcache");
        assertNotNull(clearcacheResult);

        final Map<String, Object> nodes = (Map<String, Object>) clearcacheResult.get("nodes");
        assertThat((Map<String, String>) nodes.get("d1"), Matchers.hasEntry("name", "name1"));
        assertThat((Map<String, String>) nodes.get("d2"), Matchers.hasEntry("name", "name2"));

        final Map<String, Object> delete =  (Map<String, Object>) parsed.get("delete");
        assertNotNull(delete);
        assertEquals("deleted", delete.get("result"));

    }

}