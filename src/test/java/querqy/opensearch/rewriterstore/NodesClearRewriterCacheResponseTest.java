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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertEquals;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class NodesClearRewriterCacheResponseTest {

    @SuppressWarnings("unchecked")
    @Test
    public void testJsonSerialization() throws Exception {

        final DiscoveryNode node1 = new DiscoveryNode("name1", "d1", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);
        final DiscoveryNode node2 = new DiscoveryNode("name2", "d2", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);


        final NodesClearRewriterCacheResponse response = new NodesClearRewriterCacheResponse
                (new ClusterName("cluster27"),
                        Arrays.asList(new NodesClearRewriterCacheResponse.NodeResponse(node1),
                        new NodesClearRewriterCacheResponse.NodeResponse(node2)), Collections.emptyList());

        final Map<String, Object> parsed;
        try (InputStream stream = XContentHelper.toXContent(response, XContentType.JSON, true).streamInput()) {
            parsed = XContentHelper.convertToMap(XContentType.JSON.xContent(), stream, false);
        }

        final Map<String, Object> nodes = (Map<String, Object>) parsed.get("nodes");
        assertThat((Map<String, String>) nodes.get("d1"), Matchers.hasEntry("name", "name1"));
        assertThat((Map<String, String>) nodes.get("d2"), Matchers.hasEntry("name", "name2"));

    }

    @Test
    public void testStreamSerialization() throws IOException {
        final DiscoveryNode node1 = new DiscoveryNode("name1", "d1", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);
        final DiscoveryNode node2 = new DiscoveryNode("name2", "d2", new TransportAddress(META_ADDRESS, 0),
                Collections.emptyMap(), Collections.emptySet(), Version.CURRENT);


        final NodesClearRewriterCacheResponse response1 = new NodesClearRewriterCacheResponse(
                new ClusterName("cluster27"),
                Arrays.asList(new NodesClearRewriterCacheResponse.NodeResponse(node1),
                        new NodesClearRewriterCacheResponse.NodeResponse(node2)), Collections.emptyList());

        final BytesStreamOutput output = new BytesStreamOutput();
        response1.writeTo(output);
        output.flush();


        final NodesClearRewriterCacheResponse response2 = new NodesClearRewriterCacheResponse(output.bytes()
                .streamInput());

        assertEquals(response1.getNodes(), response2.getNodes());


    }

}