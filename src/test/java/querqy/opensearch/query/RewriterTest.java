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

package querqy.opensearch.query;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.opensearch.common.xcontent.XContentHelper.createParser;
import static org.opensearch.common.xcontent.XContentHelper.toXContent;
import static org.opensearch.common.xcontent.XContentType.JSON;
import static org.junit.Assert.assertEquals;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentParser;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RewriterTest {

    @Test
    public void testStreamSerializationWithParams() throws IOException {

        final Rewriter rewriter1 = new Rewriter("rewriter_1");

        final Map<String, Object> params = new HashMap<>();

        final Map<String, Object> criteria = new HashMap<>();
        criteria.put("sort", "prio desc");
        criteria.put("limit", 1);
        params.put("criteria", criteria);
        rewriter1.setParams(params);

        final BytesStreamOutput output = new BytesStreamOutput();
        rewriter1.writeTo(output);
        output.flush();

        final Rewriter rewriter2 = new Rewriter(output.bytes().streamInput());
        assertEquals(rewriter1, rewriter2);
        assertEquals(rewriter1.hashCode(), rewriter2.hashCode());
        assertEquals(rewriter1.getName(), rewriter2.getName());
        assertEquals(rewriter1.getParams(), rewriter2.getParams());

    }

    @Test
    public void testStreamSerializationWithoutParams() throws IOException {

        final Rewriter rewriter1 = new Rewriter("rewriter_1");

        final BytesStreamOutput output = new BytesStreamOutput();
        rewriter1.writeTo(output);
        output.flush();

        final Rewriter rewriter2 = new Rewriter(output.bytes().streamInput());
        assertEquals(rewriter1, rewriter2);
        assertEquals(rewriter1.hashCode(), rewriter2.hashCode());
        assertEquals(rewriter1.getName(), rewriter2.getName());
        assertEquals(rewriter1.getParams(), rewriter2.getParams());

    }

    @Test
    public void testToJsonWithParams() throws IOException {

        final Rewriter rewriter1 = new Rewriter("some_rewriter");

        final Map<String, Object> params = new HashMap<>();

        final Map<String, Object> criteria = new HashMap<>();
        criteria.put("sort", "prio desc");
        criteria.put("limit", 1);
        params.put("criteria", criteria);
        rewriter1.setParams(params);

        assertFalse(rewriter1.isFragment());

        final XContentParser parser = createParser(null, null, toXContent(rewriter1, JSON, true), JSON);
        parser.nextToken();

        final Rewriter rewriter2 = Rewriter.PARSER.parse(parser, null);

        assertEquals(rewriter1, rewriter2);
        assertEquals(rewriter1.hashCode(), rewriter2.hashCode());
        assertEquals(rewriter1.getName(), rewriter2.getName());
        assertEquals(rewriter1.getParams(), rewriter2.getParams());

    }

    @Test
    public void testToFragmentWithoutParams() {

        final Rewriter rewriter1 = new Rewriter("some_rewriter");
        assertTrue(rewriter1.isFragment());

    }
}