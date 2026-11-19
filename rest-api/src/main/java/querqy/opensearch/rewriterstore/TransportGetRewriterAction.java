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

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.Client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TransportGetRewriterAction extends HandledTransportAction<GetRewriterRequest, GetRewriterResponse> {

    private static final Logger LOGGER = LogManager.getLogger(TransportGetRewriterAction.class);

    /**
     * The maximum number of rewriters that will be returned when rewriters are listed. This is the default value of
     * the index.max_result_window setting, which a search request cannot exceed anyway.
     */
    static final int MAX_LISTED_REWRITERS = 10000;

    private final Client client;

    @Inject
    public TransportGetRewriterAction(final TransportService transportService, final ActionFilters actionFilters,
                                      final Client client) {
        super(GetRewriterAction.NAME, false, transportService, actionFilters, GetRewriterRequest::new);
        this.client = client;
    }

    @Override
    protected void doExecute(final Task task, final GetRewriterRequest request,
                             final ActionListener<GetRewriterResponse> listener) {

        final String rewriterId = request.getRewriterId();

        if (rewriterId == null) {
            listRewriters(listener);
        } else {
            getRewriter(rewriterId, listener);
        }

    }

    protected void getRewriter(final String rewriterId, final ActionListener<GetRewriterResponse> listener) {

        client.prepareGet(QUERQY_INDEX_NAME, rewriterId).execute(new ActionListener<GetResponse>() {

            @Override
            public void onResponse(final GetResponse getResponse) {

                final Map<String, Object> source = getResponse.getSource();

                if (source == null || !"rewriter".equals(source.get(RewriterConfigMapping.PROP_TYPE))) {
                    listener.onFailure(rewriterNotFound(rewriterId));
                    return;
                }

                try {
                    listener.onResponse(new GetRewriterResponse(RewriterInfo.fromSource(rewriterId, source, true)));
                } catch (final Exception e) {
                    listener.onFailure(e);
                }

            }

            @Override
            public void onFailure(final Exception e) {
                if (isIndexNotFound(e)) {
                    listener.onFailure(rewriterNotFound(rewriterId));
                } else {
                    listener.onFailure(e);
                }
            }
        });

    }

    protected void listRewriters(final ActionListener<GetRewriterResponse> listener) {

        client.prepareSearch(QUERQY_INDEX_NAME)
                // Don't fail if the rewriter index hasn't been created yet
                .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                .setQuery(QueryBuilders.termQuery(RewriterConfigMapping.PROP_TYPE, "rewriter"))
                .setSize(MAX_LISTED_REWRITERS)
                .execute(new ActionListener<SearchResponse>() {

                    @Override
                    public void onResponse(final SearchResponse searchResponse) {

                        try {

                            final SearchHit[] hits = searchResponse.getHits().getHits();

                            if (hits.length == MAX_LISTED_REWRITERS) {
                                LOGGER.warn("Listing not more than {} rewriters", MAX_LISTED_REWRITERS);
                            }

                            final List<RewriterInfo> rewriters = new ArrayList<>(hits.length);
                            for (final SearchHit hit : hits) {
                                // Configs can be very large - we only return them for a single rewriter
                                rewriters.add(RewriterInfo.fromSource(hit.getId(), hit.getSourceAsMap(), false));
                            }
                            rewriters.sort(Comparator.comparing(RewriterInfo::getRewriterId));

                            listener.onResponse(new GetRewriterResponse(rewriters));

                        } catch (final Exception e) {
                            listener.onFailure(e);
                        }

                    }

                    @Override
                    public void onFailure(final Exception e) {
                        if (isIndexNotFound(e)) {
                            listener.onResponse(new GetRewriterResponse(Collections.emptyList()));
                        } else {
                            listener.onFailure(e);
                        }
                    }
                });

    }

    private static ResourceNotFoundException rewriterNotFound(final String rewriterId) {
        return new ResourceNotFoundException("Rewriter not found: " + rewriterId);
    }

    private static boolean isIndexNotFound(final Exception e) {
        return (e instanceof IndexNotFoundException) || (e.getCause() instanceof IndexNotFoundException);
    }

}