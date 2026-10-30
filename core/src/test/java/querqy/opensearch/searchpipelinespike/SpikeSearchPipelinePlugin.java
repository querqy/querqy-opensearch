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

import org.opensearch.common.settings.Settings;
import org.opensearch.core.ParseField;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPipelinePlugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.search.SearchExtBuilder;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Phase-0 spike plugin only (see plan: "Response ext spike") - answers whether a {@code
 * SearchResponseProcessor} can add data to a response's {@code ext} section and have it survive back to the
 * client. Not production code; not referenced by {@code QuerqyCorePlugin}.
 */
public class SpikeSearchPipelinePlugin extends Plugin implements SearchPlugin, SearchPipelinePlugin {

    public SpikeSearchPipelinePlugin(final Settings settings) {
    }

    @Override
    public List<SearchExtSpec<?>> getSearchExts() {
        return Collections.singletonList(new SearchExtSpec<>(
                new ParseField(SpikeExtBuilder.NAME),
                SpikeExtBuilder::new,
                parser -> { throw new UnsupportedOperationException("not needed for this spike"); }));
    }

    @Override
    public Map<String, Processor.Factory<SearchResponseProcessor>> getResponseProcessors(final Parameters parameters) {
        return Collections.singletonMap(SpikeResponseProcessor.TYPE, new SpikeResponseProcessor.Factory());
    }
}
