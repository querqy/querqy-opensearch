/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2021 Querqy for OpenSearch Contributors
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

import static org.opensearch.core.action.ActionListener.wrap;
import static querqy.opensearch.rewriterstore.Constants.DEFAULT_QUERQY_INDEX_NUM_REPLICAS;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;
import static querqy.opensearch.rewriterstore.Constants.SETTINGS_QUERQY_INDEX_NUM_REPLICAS;
import static querqy.opensearch.rewriterstore.PutRewriterAction.NAME;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.core.action.ActionListener;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.index.IndexAction;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.cluster.service.ClusterService;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.IndicesAdminClient;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

public class TransportPutRewriterAction extends HandledTransportAction<PutRewriterRequest, PutRewriterResponse> {

    private static final Logger LOGGER = LogManager.getLogger(TransportPutRewriterAction.class);

    private final Client client;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final Settings settings;
    private boolean mappingsVersionChecked = false;

    @Inject
    public TransportPutRewriterAction(final TransportService transportService, final ActionFilters actionFilters,
                                      final ClusterService clusterService, final ThreadPool threadPool,
                                      final Client client, final Settings settings)
    {
        super(NAME, false, transportService, actionFilters, PutRewriterRequest::new);
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.client = client;
        this.settings = settings;
    }

    @Override
    protected void doExecute(final Task task, final PutRewriterRequest request,
                             final ActionListener<PutRewriterResponse> listener) {

        final IndicesAdminClient indicesClient = client.admin().indices();

        indicesClient.prepareGetMappings(QUERQY_INDEX_NAME).execute(new ActionListener<GetMappingsResponse>() {

            @Override
            @SuppressWarnings("unchecked")
            public void onResponse(final GetMappingsResponse getMappingsResponse) {
                final Map<String, MappingMetadata> mappings = getMappingsResponse.getMappings();

                if (!mappingsVersionChecked) {

                    final Map<String, Object> properties = (Map<String, Object>) mappings.get(QUERQY_INDEX_NAME)
                            .getSourceAsMap().get("properties");
                    try {
                        updateMappings(indicesClient, properties);
                        mappingsVersionChecked = true;
                    } catch (final Exception e) {
                        listener.onFailure(e);
                        return;
                    }

                }
                try {
                    saveRewriter(task, request, listener);
                } catch (final IOException e) {
                    listener.onFailure(e);
                }

            }

            @Override
            public void onFailure(final Exception e) {
                if ((e instanceof IndexNotFoundException) || (e.getCause() instanceof IndexNotFoundException)) {

                    indicesClient.create(buildCreateQuerqyIndexRequest(indicesClient),
                            new ActionListener<CreateIndexResponse>() {

                                @Override
                                public void onResponse(final CreateIndexResponse createIndexResponse) {
                                    LOGGER.info("Created index {}", QUERQY_INDEX_NAME);
                                    mappingsVersionChecked = true;
                                    try {
                                        saveRewriter(task, request, listener);
                                    } catch (final IOException e) {
                                        listener.onFailure(e);
                                    }
                                }

                                @Override
                                public void onFailure(final Exception e) {
                                    listener.onFailure(e);
                                }
                            });

                } else {
                    listener.onFailure(e);
                }
            }
        });

    }

    /**
     * <p>Adds the properties that are missing in the mappings of the rewriter index, migrating the mappings of any
     * earlier mapping version to {@link RewriterConfigMapping#CURRENT_MAPPING_VERSION}.</p>
     *
     * @param indicesClient The client for index operations
     * @param existingProperties The properties that are currently mapped in the rewriter index
     * @throws ExecutionException if updating the mappings fails
     * @throws InterruptedException if updating the mappings is interrupted
     */
    protected void updateMappings(final IndicesAdminClient indicesClient, final Map<String, Object> existingProperties)
            throws ExecutionException, InterruptedException {

        final RewriterConfigMapping mapping = RewriterConfigMapping.CURRENT;

        // property name -> mapping definition of that property
        final Map<String, String> missingProperties = new LinkedHashMap<>();

        if (!existingProperties.containsKey(mapping.getInfoLoggingProperty())) {
            missingProperties.put(mapping.getInfoLoggingProperty(),
                    "      \"" + mapping.getInfoLoggingProperty() + "\": {\n" +
                    "        \"properties\": {\n" +
                    "          \"sinks\": {\"type\" : \"keyword\" }\n" +
                    "        }\n" +
                    "      }");
        }

        if (!existingProperties.containsKey(mapping.getConfigStringProperty())) {
            missingProperties.put(mapping.getConfigStringProperty(),
                    "      \"" + mapping.getConfigStringProperty() + "\": {\n" +
                    "        \"type\" : \"keyword\",\n" +
                    "        \"doc_values\": false,\n" +
                    "        \"index\": false\n" +
                    "      }");
        }

        if (!existingProperties.containsKey(RewriterConfigMapping.PROP_REVISION)) {
            missingProperties.put(RewriterConfigMapping.PROP_REVISION,
                    "      \"" + RewriterConfigMapping.PROP_REVISION + "\": {\"type\" : \"keyword\" }");
        }

        if (!existingProperties.containsKey(RewriterConfigMapping.PROP_SAVED_AT)) {
            missingProperties.put(RewriterConfigMapping.PROP_SAVED_AT,
                    "      \"" + RewriterConfigMapping.PROP_SAVED_AT + "\": {\"type\" : \"date\" }");
        }

        if (missingProperties.isEmpty()) {
            return;
        }

        final PutMappingRequest request = new PutMappingRequest(QUERQY_INDEX_NAME).source(
                "{\n" +
                        "    \"properties\": {\n" +
                        String.join(",\n", missingProperties.values()) + "\n" +
                        "    }\n" +
                        "}", XContentType.JSON
        );

        if (!indicesClient.putMapping(request).get().isAcknowledged()) {
            throw new IllegalStateException("Adding properties " + missingProperties.keySet()
                    + " to mappings not acknowledged");
        }

        LOGGER.info("Added properties {} to index {}", missingProperties.keySet(), QUERQY_INDEX_NAME);

    }

    protected CreateIndexRequest buildCreateQuerqyIndexRequest(final IndicesAdminClient indicesClient) {

        final CreateIndexRequestBuilder createIndexRequestBuilder = indicesClient.prepareCreate(QUERQY_INDEX_NAME);
        final int numReplicas = settings.getAsInt(SETTINGS_QUERQY_INDEX_NUM_REPLICAS, DEFAULT_QUERQY_INDEX_NUM_REPLICAS);
        return  createIndexRequestBuilder.setMapping(readUtf8Resource("querqy-mapping.json"))
                .setSettings(Settings.builder().put("number_of_replicas", numReplicas))
                .request();
    }


    protected void saveRewriter(final Task task, final PutRewriterRequest request,
                                final ActionListener<PutRewriterResponse> listener) throws IOException {
        final IndexRequest indexRequest = buildIndexRequest(task, request);
        client.execute(IndexAction.INSTANCE, indexRequest,

                new ActionListener<IndexResponse>() {
                    @Override
                    public void onResponse(final IndexResponse indexResponse) {
                        LOGGER.info("Saved rewriter {}", request.getRewriterId());
                        client.execute(NodesReloadRewriterAction.INSTANCE,
                                new NodesReloadRewriterRequest(request.getRewriterId()),
                                wrap(
                                        (reloadResponse) -> listener
                                                .onResponse(new PutRewriterResponse(indexResponse, reloadResponse)),
                                        listener::onFailure
                                ));
                    }

                    @Override
                    public void onFailure(final Exception e) {
                        LOGGER.error("Could not save rewriter " + request.getRewriterId(), e);
                        listener.onFailure(e);
                    }
                })
        ;
    }

    private IndexRequest buildIndexRequest(final Task parentTask, final PutRewriterRequest request) throws IOException {

        final IndexRequest indexRequest = client.prepareIndex(QUERQY_INDEX_NAME).setId(request.getRewriterId())
                .setCreate(false)
                .setSource(RewriterConfigMapping.toLuceneSource(request.getContent(),
                        threadPool.absoluteTimeInMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).request();
        indexRequest.setParentTask(clusterService.localNode().getId(), parentTask.getId());
        return indexRequest;
    }


    private static String readUtf8Resource(final String name) {
        final Scanner scanner = new Scanner(TransportPutRewriterAction.class.getClassLoader().getResourceAsStream(name),
                StandardCharsets.UTF_8).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }



}