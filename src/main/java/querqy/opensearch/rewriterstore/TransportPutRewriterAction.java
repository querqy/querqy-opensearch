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
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NUM_SHARDS;
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
import querqy.opensearch.DictionaryCorpusLoader;
import querqy.opensearch.OpenSearchRewriterFactory;
import querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class TransportPutRewriterAction extends HandledTransportAction<PutRewriterRequest, PutRewriterResponse> {

    private static final Logger LOGGER = LogManager.getLogger(TransportPutRewriterAction.class);

    private final Client client;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final Settings settings;
    private final DictionaryCorpusLoader dictionaryCorpusLoader;
    private boolean mappingsVersionChecked = false;

    @Inject
    public TransportPutRewriterAction(final TransportService transportService, final ActionFilters actionFilters,
                                      final ClusterService clusterService, final ThreadPool threadPool,
                                      final Client client, final Settings settings,
                                      final DictionaryCorpusLoader dictionaryCorpusLoader)
    {
        super(NAME, false, transportService, actionFilters, PutRewriterRequest::new);
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.client = client;
        this.settings = settings;
        this.dictionaryCorpusLoader = dictionaryCorpusLoader;
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
                dispatchTrySaveRewriter(task, request, listener);

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
                                    dispatchTrySaveRewriter(task, request, listener);
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

        final Map<String, String> missingProperties = QuerqyIndexManager.computeMissingProperties(existingProperties);

        if (missingProperties.isEmpty()) {
            return;
        }

        final PutMappingRequest request = new PutMappingRequest(QUERQY_INDEX_NAME)
                .source(QuerqyIndexManager.buildPutMappingBody(missingProperties), XContentType.JSON);

        if (!indicesClient.putMapping(request).get().isAcknowledged()) {
            throw new IllegalStateException("Adding properties " + missingProperties.keySet()
                    + " to mappings not acknowledged");
        }

        LOGGER.info("Added properties {} to index {}", missingProperties.keySet(), QUERQY_INDEX_NAME);

    }

    protected CreateIndexRequest buildCreateQuerqyIndexRequest(final IndicesAdminClient indicesClient) {

        final CreateIndexRequestBuilder createIndexRequestBuilder = indicesClient.prepareCreate(QUERQY_INDEX_NAME);
        final int numReplicas = settings.getAsInt(SETTINGS_QUERQY_INDEX_NUM_REPLICAS, DEFAULT_QUERQY_INDEX_NUM_REPLICAS);
        return  createIndexRequestBuilder.setMapping(QuerqyIndexManager.readMappingResource())
                .setSettings(Settings.builder()
                        .put("number_of_shards", QUERQY_INDEX_NUM_SHARDS)
                        .put("number_of_replicas", numReplicas))
                .request();
    }


    /**
     * Runs {@link #trySaveRewriter} on {@link ThreadPool#generic()} rather than directly on the calling thread,
     * which at both call sites is a callback thread already inside another blocking {@code Client} round trip
     * ({@code prepareGetMappings}/{@code updateMappings}'s blocking {@code putMapping(...).get()}, or {@code
     * indicesClient.create(...)}'s own response callback). {@link #validateDictionaryReferenceIfPresent} adds a
     * second blocking round trip ({@link DictionaryCorpusLoader#load}); nesting that inside a callback already
     * occupying a slot on a small internal pool (e.g. {@code management}) risks exactly the self-starvation
     * deadlock this caused when first written as a direct call - that callback thread blocks waiting for the
     * dictionary {@code GET} to complete, while the thread pool has no free thread left to run the completion
     * callback that would unblock it. Dispatching to {@code generic} lets the original callback thread return
     * immediately (freeing its pool slot) before anything here blocks.
     */
    private void dispatchTrySaveRewriter(final Task task, final PutRewriterRequest request,
                                         final ActionListener<PutRewriterResponse> listener) {
        threadPool.generic().execute(() -> trySaveRewriter(task, request, listener));
    }

    /**
     * Runs {@link #validateDictionaryReferenceIfPresent} before {@link #saveRewriter}, failing the request via
     * {@code listener} instead of saving if either throws - consistent with the rest of this action's "fail
     * clearly before writing" validation, rather than letting a word-break-compound rewriter with a missing or
     * broken {@code dictionary} reference save successfully only to fail later, confusingly, the first time a
     * live query tries to use it. Always called via {@link #dispatchTrySaveRewriter} - never directly from a
     * {@code Client} callback thread, see there for why.
     */
    private void trySaveRewriter(final Task task, final PutRewriterRequest request,
                                 final ActionListener<PutRewriterResponse> listener) {
        try {
            validateDictionaryReferenceIfPresent(request);
            saveRewriter(task, request, listener);
        } catch (final Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * For a {@link WordBreakCompoundRewriterFactory} configured with a {@code dictionary} reference (as opposed to
     * a shard-side {@code dictionaryField}), eagerly resolves the referenced {@code term_corpus} document here -
     * before the rewriter config is ever saved - via the same {@link DictionaryCorpusLoader} the {@code
     * querqy_rewrite} search pipeline and the classic per-shard query both already share, so a missing or broken
     * dictionary is rejected at write time instead of only being discovered when a live query first tries to use
     * the rewriter. Also warms the shared cache as a side effect. A no-op for every other configuration
     * (shard-side {@code dictionaryField}, or any other rewriter class entirely).
     *
     * <p>Only available on this action - the self-managed, {@code ActionPlugin} REST API - since it needs a
     * blocking {@code Client} round trip; the AWS/core-only write path (a raw {@code PUT} to {@code
     * .opensearch-querqy}, validated only by {@code QuerqyTermCorpusFieldMapper}/{@code
     * QuerqyRewriterConfigFieldMapper}) cannot do this at write time, since those field mappers run during
     * document parsing/translog replay, where blocking network I/O is unsafe. See {@link
     * #dispatchTrySaveRewriter} for why this blocking call must never run directly on a {@code Client} callback
     * thread either.</p>
     */
    private void validateDictionaryReferenceIfPresent(final PutRewriterRequest request) throws IOException {

        final Map<String, Object> luceneSource = RewriterConfigMapping.toLuceneSource(request.getContent(),
                threadPool.absoluteTimeInMillis());
        final OpenSearchRewriterFactory factory = OpenSearchRewriterFactory.loadConfiguredInstance(
                new LoadRewriterConfig(request.getRewriterId(), luceneSource));

        if (!(factory instanceof WordBreakCompoundRewriterFactory)) {
            return;
        }

        final WordBreakCompoundRewriterFactory wordBreakFactory = (WordBreakCompoundRewriterFactory) factory;
        final String name = wordBreakFactory.getDictionaryName();
        final String revision = wordBreakFactory.getDictionaryRevision();
        if (name != null && revision != null) {
            dictionaryCorpusLoader.load(name, revision);
        }
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

}