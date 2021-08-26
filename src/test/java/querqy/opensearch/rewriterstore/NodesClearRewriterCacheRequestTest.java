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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;

public class NodesClearRewriterCacheRequestTest {

    @Test
    public void testStreamSerializationWithoutRewriterId() throws IOException {

        final NodesClearRewriterCacheRequest request1 = new NodesClearRewriterCacheRequest(null, "n1", "n2");
        final BytesStreamOutput output = new BytesStreamOutput();
        request1.writeTo(output);
        output.flush();

        final NodesClearRewriterCacheRequest request2 = new NodesClearRewriterCacheRequest(output.bytes().streamInput());
        assertFalse(request1.getRewriterId().isPresent());
        assertFalse(request2.getRewriterId().isPresent());

    }

    @Test
    public void testStreamSerializationWithRewriterId() throws IOException {

        final NodesClearRewriterCacheRequest request1 = new NodesClearRewriterCacheRequest("r1", "n1", "n2");
        final BytesStreamOutput output = new BytesStreamOutput();
        request1.writeTo(output);
        output.flush();

        final NodesClearRewriterCacheRequest request2 = new NodesClearRewriterCacheRequest(output.bytes().streamInput());
        assertEquals(Optional.of("r1"), request1.getRewriterId());
        assertEquals(Optional.of("r1"), request2.getRewriterId());

    }

    @Test
    public void testNodeRequestCreationWithRewriterId() {
        final NodesClearRewriterCacheRequest request = new NodesClearRewriterCacheRequest("r1", "n1", "n2");
        final NodesClearRewriterCacheRequest.NodeRequest nodeRequest = request.newNodeRequest();
        assertEquals(Optional.of("r1"), nodeRequest.getRewriterId());
    }

    @Test
    public void testNodeRequestCreationWithoutRewriterId() {
        final NodesClearRewriterCacheRequest request = new NodesClearRewriterCacheRequest(null, "n1", "n2");
        final NodesClearRewriterCacheRequest.NodeRequest nodeRequest = request.newNodeRequest();
        assertFalse(nodeRequest.getRewriterId().isPresent());
    }

    @Test
    public void testNodeRequestSerializationWithRewriterId() throws IOException {

        final NodesClearRewriterCacheRequest.NodeRequest nodeRequest1 = new NodesClearRewriterCacheRequest
                .NodeRequest("r11");

        final BytesStreamOutput output = new BytesStreamOutput();
        nodeRequest1.writeTo(output);
        output.flush();

        final NodesClearRewriterCacheRequest.NodeRequest nodeRequest2 = new NodesClearRewriterCacheRequest
                .NodeRequest(output.bytes().streamInput());

        assertEquals(nodeRequest1.getRewriterId(), nodeRequest2.getRewriterId());

    }

    @Test
    public void testNodeRequestSerializationWithoutRewriterId() throws IOException {

        final NodesClearRewriterCacheRequest.NodeRequest nodeRequest1 = new NodesClearRewriterCacheRequest
                .NodeRequest();

        final BytesStreamOutput output = new BytesStreamOutput();
        nodeRequest1.writeTo(output);
        output.flush();

        final NodesClearRewriterCacheRequest.NodeRequest nodeRequest2 = new NodesClearRewriterCacheRequest
                .NodeRequest(output.bytes().streamInput());

        assertEquals(nodeRequest1.getRewriterId(), nodeRequest2.getRewriterId());
        assertFalse(nodeRequest2.getRewriterId().isPresent());

    }

}