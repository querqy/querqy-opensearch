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

import org.opensearch.Version;
import org.opensearch.action.FailedNodeException;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.ByteBufferStreamInput;
import org.opensearch.common.io.stream.DataOutputStreamOutput;
import org.opensearch.common.transport.TransportAddress;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

public class NodesReloadRewriterResponseTest {

    @Test
    public void testWriteToReadFromStream() throws IOException {

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStreamOutput dos = new DataOutputStreamOutput(new DataOutputStream(bos));

        NullPointerException npe = new NullPointerException("test");
        npe.fillInStackTrace();
        NodesReloadRewriterResponse response = new NodesReloadRewriterResponse(
                        new ClusterName("c1"),
                        Arrays.asList(
                                new NodesReloadRewriterResponse.NodeResponse(
                                    new DiscoveryNode("n1",
                                            new TransportAddress(TransportAddress.META_ADDRESS, 9234),
                                            Version.CURRENT), npe),
                                new NodesReloadRewriterResponse.NodeResponse(
                                        new DiscoveryNode("n2",
                                                new TransportAddress(TransportAddress.META_ADDRESS, 9235),
                                                Version.CURRENT), null)

                        ), Collections.singletonList(new FailedNodeException("n3", "node 3 down",
                new SocketException())));

        response.writeTo(dos);
        dos.flush();
        dos.close();

        final ByteBufferStreamInput byteInput = new ByteBufferStreamInput(ByteBuffer.wrap(bos.toByteArray()));
        final NodesReloadRewriterResponse response1 = new NodesReloadRewriterResponse(byteInput);
        assertEquals(response, response1);


    }

}