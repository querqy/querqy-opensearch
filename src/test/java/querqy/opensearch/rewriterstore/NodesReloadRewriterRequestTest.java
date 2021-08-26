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

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.junit.Test;

import java.io.IOException;

public class NodesReloadRewriterRequestTest {

    @Test
    public void testStreamSerialization() throws IOException {

        final NodesReloadRewriterRequest request1 = new NodesReloadRewriterRequest("r1", "n1", "n2");
        final BytesStreamOutput output = new BytesStreamOutput();
        request1.writeTo(output);
        output.flush();

        final NodesReloadRewriterRequest request2 = new NodesReloadRewriterRequest(output.bytes().streamInput());
        assertEquals("r1", request1.getRewriterId());
        assertEquals("r1", request2.getRewriterId());

    }
}