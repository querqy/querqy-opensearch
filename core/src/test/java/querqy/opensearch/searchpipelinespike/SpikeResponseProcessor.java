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

package querqy.opensearch.searchpipelinespike;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.search.SearchExtBuilder;
import org.opensearch.search.aggregations.Aggregations;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.ingest.ConfigurationUtils;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;
import org.opensearch.search.profile.SearchProfileShardResults;

import java.util.Collections;
import java.util.Map;

/**
 * Phase-0 spike only: rebuilds the {@link SearchResponse} with a {@link SpikeExtBuilder} added to its ext
 * section - standing in for where Querqy's eventual DECORATE/rewriter-debug output would go. Reconstruction
 * pattern (copy every section across into a new {@code InternalSearchResponse}/{@code SearchResponse}) mirrors
 * core's own {@code org.opensearch.search.pipeline.common.helpers.SearchResponseUtil#replaceHits}, which has
 * no equivalent "add an ext builder" helper - there's no in-place mutator for this on the existing objects.
 */
public class SpikeResponseProcessor extends AbstractProcessor implements SearchResponseProcessor {

    public static final String TYPE = "querqy_spike_add_ext";

    private final String payload;

    SpikeResponseProcessor(final String tag, final String description, final boolean ignoreFailure,
                           final String payload) {
        super(tag, description, ignoreFailure);
        this.payload = payload;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public SearchResponse processResponse(final SearchRequest request, final SearchResponse response) {

        final SpikeExtBuilder extBuilder = new SpikeExtBuilder(payload);

        final SearchResponseSections sections;
        if (response.getAggregations() == null || response.getAggregations() instanceof InternalAggregations) {
            sections = new InternalSearchResponse(
                    response.getHits(),
                    (InternalAggregations) response.getAggregations(),
                    response.getSuggest(),
                    new SearchProfileShardResults(response.getProfileResults()),
                    response.isTimedOut(),
                    response.isTerminatedEarly(),
                    response.getNumReducePhases(),
                    Collections.singletonList((SearchExtBuilder) extBuilder));
        } else {
            sections = new SearchResponseSections(
                    response.getHits(),
                    response.getAggregations(),
                    response.getSuggest(),
                    response.isTimedOut(),
                    response.isTerminatedEarly(),
                    new SearchProfileShardResults(response.getProfileResults()),
                    response.getNumReducePhases(),
                    Collections.singletonList((SearchExtBuilder) extBuilder));
        }

        return new SearchResponse(
                sections,
                response.getScrollId(),
                response.getTotalShards(),
                response.getSuccessfulShards(),
                response.getSkippedShards(),
                response.getTook().millis(),
                response.getShardFailures(),
                response.getClusters(),
                response.pointInTimeId());
    }

    public static class Factory implements Processor.Factory<SearchResponseProcessor> {
        @Override
        public SpikeResponseProcessor create(final Map<String, Processor.Factory<SearchResponseProcessor>> registry,
                                             final String tag, final String description, final boolean ignoreFailure,
                                             final Map<String, Object> config,
                                             final Processor.PipelineContext pipelineContext) {
            final String payload = ConfigurationUtils.readStringProperty(TYPE, tag, config, "payload", "spike");
            return new SpikeResponseProcessor(tag, description, ignoreFailure, payload);
        }
    }
}
