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

import static querqy.opensearch.rewriterstore.Constants.QUERQY_REWRITER_BASE_ROUTE;

import org.opensearch.action.ActionRequestBuilder;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestStatusToXContentListener;
import org.opensearch.transport.client.OpenSearchClient;
import org.opensearch.transport.client.node.NodeClient;

import java.util.Arrays;
import java.util.List;

public class RestGetRewriterAction extends BaseRestHandler {

    public static final String PARAM_REWRITER_ID = "rewriterId";

    @Override
    public String getName() {
        return "Read Querqy rewriter configurations";
    }

    @Override
    public List<Route> routes() {
        return Arrays.asList(
                new Route(RestRequest.Method.GET, QUERQY_REWRITER_BASE_ROUTE),
                new Route(RestRequest.Method.GET, QUERQY_REWRITER_BASE_ROUTE + "/{rewriterId}"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) {

        final GetRewriterRequestBuilder requestBuilder = createRequestBuilder(request, client);

        return (channel) -> requestBuilder.execute(new RestStatusToXContentListener<>(channel));
    }

    GetRewriterRequestBuilder createRequestBuilder(final RestRequest request, final NodeClient client) {

        final String rewriterIdParam = request.param(PARAM_REWRITER_ID);

        // No rewriter ID: list all rewriters
        final String rewriterId = (rewriterIdParam == null || rewriterIdParam.trim().isEmpty())
                ? null
                : rewriterIdParam.trim();

        return new GetRewriterRequestBuilder(client, GetRewriterAction.INSTANCE, new GetRewriterRequest(rewriterId));
    }


    public static class GetRewriterRequestBuilder
            extends ActionRequestBuilder<GetRewriterRequest, GetRewriterResponse> {

        public GetRewriterRequestBuilder(final OpenSearchClient client, final GetRewriterAction action,
                                        final GetRewriterRequest request) {
            super(client, action, request);
        }

    }
}