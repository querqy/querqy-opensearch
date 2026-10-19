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

package querqy.opensearch.tool;

import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import querqy.opensearch.rewriterstore.RewriterInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link HttpQuerqyIndexManager}'s new get/list/delete methods against a stubbed {@link RestClient} - a test
 * coverage gap deliberately left open for the original put/ensureIndexExists methods (manual/end-to-end verification
 * only); not repeated here for three more methods.
 */
public class HttpQuerqyIndexManagerTest {

    /**
     * Stubs {@code client.performRequest(any())} to return a response with the given JSON body. Note: the response
     * must be built (via {@link #mock(Class)}/{@code when(...)}) as a separate statement *before* this call, not
     * inline as an argument to an outer {@code when(...).thenReturn(...)} - nesting stubbing calls like that
     * corrupts Mockito's internal stubbing state (UnfinishedStubbingException).
     */
    private static RestClient clientReturning(final String json) throws Exception {
        final Response response = mock(Response.class);
        final StringEntity entity = new StringEntity(json);
        when(response.getEntity()).thenReturn(entity);

        final RestClient client = mock(RestClient.class);
        when(client.performRequest(any(Request.class))).thenReturn(response);
        return client;
    }

    @Test
    public void testGetRewriterReturnsNullWhenNotFound() throws Exception {

        final RestClient client = clientReturning(
                "{\"_index\":\".opensearch-querqy\",\"_id\":\"r1\",\"found\":false}");

        final RewriterInfo info = new HttpQuerqyIndexManager(client).getRewriter("r1");

        assertThat(info).isNull();
    }

    @Test
    public void testGetRewriterReturnsInfoWhenFound() throws Exception {

        final RestClient client = clientReturning(
                "{\"_index\":\".opensearch-querqy\",\"_id\":\"r1\",\"found\":true,\"_source\":{"
                        + "\"type\":\"rewriter\",\"version\":5,"
                        + "\"config_v_005\":\"{\\\"class\\\":\\\"querqy.opensearch.rewriter."
                        + "SimpleCommonRulesRewriterFactory\\\",\\\"config\\\":{\\\"rules\\\":\\\"a =>\\\\nSYNONYM: "
                        + "b\\\"}}\"}}");

        final RewriterInfo info = new HttpQuerqyIndexManager(client).getRewriter("r1");

        assertThat(info).isNotNull();
        assertThat(info.getRewriterId()).isEqualTo("r1");
        assertThat(info.getRewriterClassName())
                .isEqualTo("querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory");
        assertThat(info.getConfig()).containsEntry("rules", "a =>\nSYNONYM: b");
    }

    @Test
    public void testGetRewriterRequestShapeAndIgnore404() throws Exception {

        final RestClient client = clientReturning("{\"found\":false}");

        new HttpQuerqyIndexManager(client).getRewriter("r1");

        verify(client).performRequest(argThat(request ->
                "GET".equals(request.getMethod())
                        && "/.opensearch-querqy/_doc/r1".equals(request.getEndpoint())
                        && "404".equals(request.getParameters().get("ignore"))));
    }

    @Test
    public void testListRewritersReturnsEmptyListWhenIndexMissing() throws Exception {

        // OpenSearch's search-on-missing-index response body carries no "hits" wrapper at all
        final RestClient client = clientReturning("{\"error\":{\"type\":\"index_not_found_exception\"}}");

        final List<RewriterInfo> rewriters = new HttpQuerqyIndexManager(client).listRewriters();

        assertThat(rewriters).isEmpty();
    }

    @Test
    public void testListRewritersReturnsSortedInfosWithoutConfig() throws Exception {

        final RestClient client = clientReturning(
                "{\"hits\":{\"total\":{\"value\":2},\"hits\":["
                        + "{\"_id\":\"zz_rewriter\",\"_source\":{\"type\":\"rewriter\",\"version\":5,"
                        + "\"config_v_005\":\"{\\\"class\\\":\\\"querqy.opensearch.rewriter."
                        + "SimpleCommonRulesRewriterFactory\\\",\\\"config\\\":{\\\"rules\\\":\\\"a\\\"}}\"}},"
                        + "{\"_id\":\"aa_rewriter\",\"_source\":{\"type\":\"rewriter\",\"version\":5,"
                        + "\"config_v_005\":\"{\\\"class\\\":\\\"querqy.opensearch.rewriter."
                        + "SimpleCommonRulesRewriterFactory\\\",\\\"config\\\":{\\\"rules\\\":\\\"b\\\"}}\"}}"
                        + "]}}");

        final List<RewriterInfo> rewriters = new HttpQuerqyIndexManager(client).listRewriters();

        assertThat(rewriters).hasSize(2);
        assertThat(rewriters.get(0).getRewriterId()).isEqualTo("aa_rewriter"); // sorted
        assertThat(rewriters.get(1).getRewriterId()).isEqualTo("zz_rewriter");
        assertThat(rewriters.get(0).getConfig()).isNull(); // includeConfig=false for listing
    }

    @Test
    public void testListRewritersRequestShape() throws Exception {

        final RestClient client = clientReturning("{\"hits\":{\"hits\":[]}}");

        new HttpQuerqyIndexManager(client).listRewriters();

        verify(client).performRequest(argThat(request ->
                "POST".equals(request.getMethod())
                        && "/.opensearch-querqy/_search".equals(request.getEndpoint())
                        && "404".equals(request.getParameters().get("ignore"))));
    }

    @Test
    public void testDeleteRewriterReturnsTrueWhenDeleted() throws Exception {

        final RestClient client = clientReturning("{\"_id\":\"r1\",\"result\":\"deleted\"}");

        assertThat(new HttpQuerqyIndexManager(client).deleteRewriter("r1")).isTrue();
    }

    @Test
    public void testDeleteRewriterReturnsFalseWhenAlreadyAbsent() throws Exception {

        final RestClient client = clientReturning("{\"_id\":\"r1\",\"result\":\"not_found\"}");

        assertThat(new HttpQuerqyIndexManager(client).deleteRewriter("r1")).isFalse();
    }

    @Test
    public void testDeleteRewriterRequestShape() throws Exception {

        final RestClient client = clientReturning("{\"result\":\"deleted\"}");

        new HttpQuerqyIndexManager(client).deleteRewriter("r1");

        verify(client).performRequest(argThat(request ->
                "DELETE".equals(request.getMethod())
                        && "/.opensearch-querqy/_doc/r1".equals(request.getEndpoint())
                        && "404".equals(request.getParameters().get("ignore"))
                        && "true".equals(request.getParameters().get("refresh"))));
    }

    @Test
    public void testRequestSignerIsInvokedBeforeEveryRequest() throws Exception {

        final RestClient client = clientReturning("{\"found\":false}");
        final RequestSigner signer = mock(RequestSigner.class);

        new HttpQuerqyIndexManager(client, signer).getRewriter("r1");

        verify(signer).sign(any(Request.class));
    }
}
