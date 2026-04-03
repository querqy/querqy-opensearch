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

import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.index.query.AbstractQueryBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class RequestUtilsTest extends OpenSearchTestCase {

    public void testParamToQueryFieldsAndBoostingReturnsEmptyMapForNullParam() {
        final Map<String, Float> qf = RequestUtils.paramToQueryFieldsAndBoosting(null);
        assertNotNull(qf);
        assertTrue(qf.isEmpty());
    }

    public void testParamToQueryFieldsAndBoostingReturnsEmptyMapForEmptyParam() {
        final Map<String, Float> qf = RequestUtils
                .paramToQueryFieldsAndBoosting(Collections.emptyList());
        assertNotNull(qf);
        assertTrue(qf.isEmpty());
    }

    public void testParamToQueryFieldsAndBoostingDoesntAcceptWeightAtBeginning() {
        expectThrows(IllegalArgumentException.class,
                () -> RequestUtils.paramToQueryFieldsAndBoosting(Arrays.asList("f1", "^32")));
    }

    public void testParamToQueryFieldsAndBoostingDoesntHatchAtEnd() {
        expectThrows(IllegalArgumentException.class,
                () -> RequestUtils.paramToQueryFieldsAndBoosting(Arrays.asList("f1", "f2^")));
    }

    public void testParamToQueryFieldsAndBoostingDoesntAcceptDuplicateFieldnameWithWeightOnOne() {
        expectThrows(IllegalArgumentException.class,
                () -> RequestUtils.paramToQueryFieldsAndBoosting(Arrays.asList("f0", "f1", "f1^0.3")));
    }

    public void testParamToQueryFieldsAndBoostingDoesntAcceptDuplicateFieldnameWithWeightOnBoth() {
        expectThrows(IllegalArgumentException.class,
                () -> RequestUtils.paramToQueryFieldsAndBoosting(Arrays.asList("f0", "f1^0.3", "f1^0.3")));
    }

    public void testParamToQueryFieldsAndBoostingDoesntAcceptDuplicateFieldnameWithWeightOnNone() {
        expectThrows(IllegalArgumentException.class,
                () -> RequestUtils.paramToQueryFieldsAndBoosting(Arrays.asList("f0", "f1", "f1")));
    }

    public void testParamToQueryFieldsAndBoostingReturnCorrectFieldsAndWeights() {
        final Map<String, Float> qf = RequestUtils.paramToQueryFieldsAndBoosting(
                Arrays.asList("f0", "f1^0.4", "f2", "f3^20"));
        assertNotNull(qf);
        assertEquals(4, qf.size());
        assertEquals(AbstractQueryBuilder.DEFAULT_BOOST, qf.get("f0"), 0.0001f);
        assertEquals(0.4f, qf.get("f1"), 0.0001f);
        assertEquals(AbstractQueryBuilder.DEFAULT_BOOST, qf.get("f2"), 0.0001f);
        assertEquals(20.0f, qf.get("f3"), 0.0001f);
    }

}