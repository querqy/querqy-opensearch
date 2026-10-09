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

import java.io.IOException;
import java.util.Collections;
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

    HttpQuerqyIndexManager(final RestClient client) {
        this.client = client;
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
        final Response response = client.performRequest(new Request("HEAD", INDEX_PATH));
        return response.getStatusLine().getStatusCode() == 200;
    }

    private void createIndex(final int numReplicas) throws IOException {
        final String body = "{\"settings\":{\"number_of_replicas\":" + numReplicas + "},\"mappings\":"
                + QuerqyIndexManager.readMappingResource() + "}";
        final Request request = new Request("PUT", INDEX_PATH);
        request.setJsonEntity(body);
        client.performRequest(request);
    }

    @SuppressWarnings("unchecked")
    private void migrateMappingIfNeeded() throws IOException {

        final Request getMapping = new Request("GET", INDEX_PATH + "/_mapping");
        final String responseBody;
        try {
            responseBody = EntityUtils.toString(client.performRequest(getMapping).getEntity());
        } catch (final org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Could not read mapping response", e);
        }

        final Map<String, Object> body = MAPPER.readValue(responseBody, Map.class);
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
        client.performRequest(putMapping);
    }

    void putRewriter(final String rewriterId, final Map<String, Object> source) throws IOException {
        final Request request = new Request("PUT", INDEX_PATH + "/_doc/" + rewriterId);
        request.addParameter("refresh", "true");
        request.setJsonEntity(MAPPER.writeValueAsString(source));
        client.performRequest(request);
    }
}
