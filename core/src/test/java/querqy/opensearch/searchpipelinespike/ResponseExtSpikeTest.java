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

import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.plugins.Plugin;
import org.opensearch.search.SearchExtBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Phase-0 spike (briefing section 5, "Can a response processor add a {@code GenericSearchExtBuilder} to a
 * {@code SearchResponse}?"): proves a dedicated {@link SearchExtBuilder}, added by a {@code
 * SearchResponseProcessor}, survives back to the client - i.e. this is not blocked at the transport/
 * serialization layer, and {@link SpikeExtBuilder}'s {@code NamedWriteable} registration (via {@code
 * SearchPlugin#getSearchExts()}) is sufficient for this to round-trip correctly.
 * <p>
 * <b>Finding:</b> yes - a response processor can rebuild the {@code SearchResponse} with an added ext
 * builder (see {@link SpikeResponseProcessor}, there is no in-place mutator so a full section/response
 * rebuild is required, mirroring core's own {@code SearchResponseUtil} pattern used for other response
 * processors), and the added data is present on the {@code SearchResponse} the client receives, both as a
 * typed object ({@link SearchResponse#getInternalResponse()}{@code .getSearchExtBuilders()}) and in the
 * rendered XContent (the {@code ext} object).
 */
public class ResponseExtSpikeTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "ext_spike_index";
    private static final String PIPELINE_ID = "querqy_spike_pipeline";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(SpikeSearchPipelinePlugin.class);
    }

    public void testResponseProcessorAddedExtBuilderSurvivesBackToTheClient() throws Exception {

        client().admin().indices().prepareCreate(INDEX_NAME).get();
        client().prepareIndex(INDEX_NAME).setSource("field1", "a").get();
        client().admin().indices().prepareRefresh(INDEX_NAME).get();

        final String pipelineBody = "{"
                + "\"response_processors\": [ { \"" + SpikeResponseProcessor.TYPE + "\": "
                + "{ \"payload\": \"hello-from-querqy\" } } ]"
                + "}";

        client().admin().cluster().putSearchPipeline(
                new PutSearchPipelineRequest(PIPELINE_ID, new BytesArray(pipelineBody), XContentType.JSON)).get();

        final SearchRequest searchRequest = new SearchRequest(INDEX_NAME)
                .source(new SearchSourceBuilder().query(org.opensearch.index.query.QueryBuilders.matchAllQuery()));
        searchRequest.pipeline(PIPELINE_ID);

        final SearchResponse response = client().search(searchRequest).get();

        assertEquals(1L, response.getHits().getTotalHits().value());

        final List<SearchExtBuilder> extBuilders = response.getInternalResponse().getSearchExtBuilders();
        assertEquals(1, extBuilders.size());
        assertTrue(extBuilders.get(0) instanceof SpikeExtBuilder);
        assertEquals("hello-from-querqy", ((SpikeExtBuilder) extBuilders.get(0)).getPayload());
    }
}
