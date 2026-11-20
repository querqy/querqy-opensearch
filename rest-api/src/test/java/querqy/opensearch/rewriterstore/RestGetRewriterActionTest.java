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

package querqy.opensearch.rewriterstore;

import static org.mockito.Mockito.mock;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_REWRITER_BASE_ROUTE;
import static querqy.opensearch.rewriterstore.RestGetRewriterAction.PARAM_REWRITER_ID;

import org.opensearch.rest.RestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.rest.FakeRestRequest;
import org.opensearch.transport.client.node.NodeClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RestGetRewriterActionTest extends OpenSearchTestCase {

    public void testThatRewriterIdIsParsed() {

        final GetRewriterRequest request = createRequest(Collections.singletonMap(PARAM_REWRITER_ID, "rewriter1"));
        assertEquals("rewriter1", request.getRewriterId());
    }

    public void testThatRewriterIdIsTrimmed() {

        final GetRewriterRequest request = createRequest(Collections.singletonMap(PARAM_REWRITER_ID, " rewriter1 "));
        assertEquals("rewriter1", request.getRewriterId());
    }

    public void testThatMissingRewriterIdRequestsAllRewriters() {

        final GetRewriterRequest request = createRequest(Collections.emptyMap());
        assertNull(request.getRewriterId());
    }

    public void testThatEmptyRewriterIdRequestsAllRewriters() {

        final GetRewriterRequest request = createRequest(Collections.singletonMap(PARAM_REWRITER_ID, " "));
        assertNull(request.getRewriterId());
    }

    public void testRoutes() {

        final List<RestHandler.Route> routes = new RestGetRewriterAction().routes();
        assertEquals(2, routes.size());

        for (final RestHandler.Route route : routes) {
            assertEquals(RestRequest.Method.GET, route.getMethod());
        }

        assertEquals(QUERQY_REWRITER_BASE_ROUTE, routes.get(0).getPath());
        assertEquals(QUERQY_REWRITER_BASE_ROUTE + "/{rewriterId}", routes.get(1).getPath());
    }

    private static GetRewriterRequest createRequest(final Map<String, String> params) {

        final NodeClient client = mock(NodeClient.class);
        final FakeRestRequest restRequest = new FakeRestRequest.Builder(null)
                .withParams(new HashMap<>(params)).build();

        return new RestGetRewriterAction().createRequestBuilder(restRequest, client).request();
    }

}