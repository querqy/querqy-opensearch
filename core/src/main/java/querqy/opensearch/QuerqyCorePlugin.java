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

package querqy.opensearch;

import static querqy.opensearch.rewriterstore.Constants.SETTINGS_QUERQY_INDEX_NUM_REPLICAS;

import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.index.mapper.Mapper;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.plugins.MapperPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPipelinePlugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchRequestProcessor;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;
import querqy.opensearch.infologging.Log4jSink;
import querqy.opensearch.pipeline.QuerqyRewriteRequestProcessor;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.rewriterstore.mapper.QuerqyRewriterConfigFieldMapper;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Core Querqy plugin: the {@code querqy} query, the rewriters, and the index-based rewriter config format/loading -
 * everything except the {@code ActionPlugin} REST/transport rewriter-management API, which AWS OpenSearch Service
 * doesn't support. This class is installable as its own OpenSearch plugin (see {@code core/build.gradle}'s
 * {@code opensearchplugin} block). {@code querqy.opensearch.QuerqyPlugin} (the full plugin module) extends this
 * class and adds the REST/transport API back for self-managed deployments.
 */
public class QuerqyCorePlugin extends Plugin implements SearchPlugin, MapperPlugin, SearchPipelinePlugin {

    protected final QuerqyProcessor querqyProcessor;
    protected final RewriterShardContexts rewriterShardContexts;

    public QuerqyCorePlugin(final Settings settings) {
        rewriterShardContexts = new RewriterShardContexts(settings);
        querqyProcessor = new QuerqyProcessor(rewriterShardContexts, new Log4jSink());
    }

    @Override
    public void onIndexModule(final IndexModule indexModule) {

        indexModule.addIndexEventListener(rewriterShardContexts);

    }

    /**
     * The new {@link QuerySpec}s defined by this plugin.
     */
    @Override
    public List<QuerySpec<?>> getQueries() {
        return Collections.singletonList(
                new QuerySpec<QueryBuilder>(
                        QuerqyQueryBuilder.NAME,
                        (in) -> new QuerqyQueryBuilder(in, querqyProcessor),
                        (parser) -> QuerqyQueryBuilder.fromXContent(parser, querqyProcessor)));
    }

    @Override
    public Collection<Object> createComponents(final Client client, final ClusterService clusterService,
                                               final ThreadPool threadPool,
                                               final ResourceWatcherService resourceWatcherService,
                                               final ScriptService scriptService,
                                               final NamedXContentRegistry xContentRegistry,
                                               final Environment environment, final NodeEnvironment nodeEnvironment,
                                               final NamedWriteableRegistry namedWriteableRegistry,
                                               IndexNameExpressionResolver indexNameExpressionResolver,
                                               Supplier<RepositoriesService> repositoriesServiceSupplier) {
        return Arrays.asList(rewriterShardContexts, querqyProcessor);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(
                Setting.intSetting(SETTINGS_QUERQY_INDEX_NUM_REPLICAS, 1, 0, Setting.Property.NodeScope),
                RewriterShardContext.CACHE_EXPIRE_AFTER_WRITE,
                RewriterShardContext.CACHE_EXPIRE_AFTER_READ,
                RewriterShardContext.CACHE_REFRESH_AFTER_WRITE);

    }

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        return Collections.singletonMap(QuerqyRewriterConfigFieldMapper.CONTENT_TYPE,
                new QuerqyRewriterConfigFieldMapper.TypeParser());
    }

    @Override
    public Map<String, Processor.Factory<SearchRequestProcessor>> getRequestProcessors(
            final SearchPipelinePlugin.Parameters parameters) {
        return Collections.singletonMap(QuerqyRewriteRequestProcessor.TYPE,
                new QuerqyRewriteRequestProcessor.Factory(parameters.client, parameters.env.settings(),
                        querqyProcessor));
    }
}
