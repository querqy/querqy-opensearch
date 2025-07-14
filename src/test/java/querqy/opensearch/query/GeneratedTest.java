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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertNotNull;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class GeneratedTest {

    @Test
    public void testStreamSerialization() throws IOException {
        final Generated generated1 = new Generated(Arrays.asList("field1^20.1", "field2^3", "field3"));
        generated1.setFieldBoostFactor(0.8f);
        final BytesStreamOutput output = new BytesStreamOutput();
        generated1.writeTo(output);
        output.flush();

        final Generated generated2 = new Generated(output.bytes().streamInput());
        assertEquals(generated1, generated2);

        // do not trust equals
        assertEquals(generated1.getFieldBoostFactor(), generated2.getFieldBoostFactor());
        assertEquals(generated2.getQueryFieldsAndBoostings(), generated2.getQueryFieldsAndBoostings());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testToJson() throws IOException {
        final Generated generated = new Generated(Arrays.asList("field1^20.1", "field2^3", "field3"));
        generated.setFieldBoostFactor(0.8f);

        final Map<String, Object> parsed;
        try (InputStream stream = XContentHelper.toXContent(generated, XContentType.JSON, true).streamInput()) {
            parsed = XContentHelper.convertToMap(XContentType.JSON.xContent(), stream, false);
        }

        assertNotNull(parsed);
        final List<String> queryFields = (List<String>) parsed.get("query_fields");
        assertThat(queryFields, Matchers.containsInAnyOrder("field1^20.1", "field2^3.0", "field3"));

        final Double fieldBoost = (Double) parsed.get("field_boost_factor");
        assertNotNull(fieldBoost);
        assertEquals(0.8, fieldBoost, 0.000001f);

    }

}