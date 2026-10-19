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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import querqy.opensearch.rewriterstore.Constants;
import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The HTTP equivalent of the node-local index management {@code TransportPutRewriterAction} does via
 * {@code IndicesAdminClient} - needed here because this tool has no such client, only a REST connection to the
 * cluster. Reuses {@code QuerqyIndexManager}'s pure computation (the mapping resource, the missing-properties
 * check) so both paths stay consistent with the same field names and mapping shape.
 */
final class HttpQuerqyIndexManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX_PATH = "/" + Constants.QUERQY_INDEX_NAME;

    private final RestClient client;
    private final RequestSigner signer;

    HttpQuerqyIndexManager(final RestClient client) {
        this(client, RequestSigner.NONE);
    }

    HttpQuerqyIndexManager(final RestClient client, final RequestSigner signer) {
        this.client = client;
        this.signer = signer;
    }

    void ensureIndexExists(final int numReplicas) throws IOException {
        if (indexExists()) {
            migrateMappingIfNeeded();
        } else {
            createIndex(numReplicas);
        }
    }

    private boolean indexExists() throws IOException {
        // the client never turns a 404 into a ResponseException for a HEAD request - just check the status code
        final Response response = performRequest(new Request("HEAD", INDEX_PATH));
        return response.getStatusLine().getStatusCode() == 200;
    }

    private void createIndex(final int numReplicas) throws IOException {
        final String body = "{\"settings\":{\"number_of_shards\":" + Constants.QUERQY_INDEX_NUM_SHARDS
                + ",\"number_of_replicas\":" + numReplicas + "},\"mappings\":"
                + QuerqyIndexManager.readMappingResource() + "}";
        final Request request = new Request("PUT", INDEX_PATH);
        request.setJsonEntity(body);
        performRequest(request);
    }

    @SuppressWarnings("unchecked")
    private void migrateMappingIfNeeded() throws IOException {

        final Request getMapping = new Request("GET", INDEX_PATH + "/_mapping");
        final Map<String, Object> body = MAPPER.readValue(readBody(performRequest(getMapping)), Map.class);
        final Map<String, Object> indexBody = (Map<String, Object>) body.get(Constants.QUERQY_INDEX_NAME);
        final Map<String, Object> mappings = (Map<String, Object>) indexBody.get("mappings");
        final Map<String, Object> properties = (Map<String, Object>) mappings.getOrDefault("properties",
                Collections.emptyMap());

        final Map<String, String> missingProperties = QuerqyIndexManager.computeMissingProperties(properties);
        if (missingProperties.isEmpty()) {
            return;
        }

        final Request putMapping = new Request("PUT", INDEX_PATH + "/_mapping");
        putMapping.setJsonEntity(QuerqyIndexManager.buildPutMappingBody(missingProperties));
        performRequest(putMapping);
    }

    void putRewriter(final String rewriterId, final Map<String, Object> source) throws IOException {
        final Request request = new Request("PUT", INDEX_PATH + "/_doc/" + rewriterId);
        request.addParameter("refresh", "true");
        request.setJsonEntity(MAPPER.writeValueAsString(source));
        performRequest(request);
    }

    /**
     * @return The {@link RewriterInfo} for the given rewriter, or {@code null} if it doesn't exist.
     */
    @SuppressWarnings("unchecked")
    RewriterInfo getRewriter(final String rewriterId) throws IOException {

        final Request request = new Request("GET", INDEX_PATH + "/_doc/" + rewriterId);
        request.addParameter("ignore", "404"); // a missing document is a normal outcome here, not an error

        final Map<String, Object> doc = MAPPER.readValue(readBody(performRequest(request)), Map.class);
        if (!Boolean.TRUE.equals(doc.get("found"))) {
            return null;
        }

        final Map<String, Object> source = (Map<String, Object>) doc.get("_source");
        return RewriterInfo.fromSource(rewriterId, source, true);
    }

    /**
     * @return Every stored rewriter, sorted by ID, without their config (matching the REST API's listing
     * behavior - configs can be large). An empty list if the index doesn't exist yet.
     */
    @SuppressWarnings("unchecked")
    List<RewriterInfo> listRewriters() throws IOException {

        final Request request = new Request("POST", INDEX_PATH + "/_search");
        request.addParameter("ignore", "404"); // a missing index means "no rewriters yet", not an error
        request.setJsonEntity("{\"query\":{\"term\":{\"type\":\"rewriter\"}},\"size\":10000}");

        final Map<String, Object> body = MAPPER.readValue(readBody(performRequest(request)), Map.class);
        final Map<String, Object> hitsWrapper = (Map<String, Object>) body.get("hits");
        if (hitsWrapper == null) {
            return Collections.emptyList(); // missing-index response body has no "hits" wrapper at all
        }

        final List<Map<String, Object>> hits = (List<Map<String, Object>>) hitsWrapper.get("hits");
        final List<RewriterInfo> rewriters = new ArrayList<>(hits.size());
        for (final Map<String, Object> hit : hits) {
            rewriters.add(RewriterInfo.fromSource((String) hit.get("_id"),
                    (Map<String, Object>) hit.get("_source"), false));
        }
        rewriters.sort(Comparator.comparing(RewriterInfo::getRewriterId));
        return rewriters;
    }

    /**
     * @return {@code true} if a rewriter document existed and was deleted, {@code false} if it was already absent.
     */
    boolean deleteRewriter(final String rewriterId) throws IOException {

        final Request request = new Request("DELETE", INDEX_PATH + "/_doc/" + rewriterId);
        request.addParameter("refresh", "true");
        request.addParameter("ignore", "404"); // deleting an already-absent document is not an error

        final Map<String, Object> body = MAPPER.readValue(readBody(performRequest(request)), Map.class);
        return "deleted".equals(body.get("result"));
    }

    private Response performRequest(final Request request) throws IOException {
        signer.sign(request);
        return client.performRequest(request);
    }

    private static String readBody(final Response response) throws IOException {
        try {
            return EntityUtils.toString(response.getEntity());
        } catch (final org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Could not read response body", e);
        }
    }
}
