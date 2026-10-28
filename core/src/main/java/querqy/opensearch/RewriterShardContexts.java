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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchException;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.indices.IndicesService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RewriterShardContexts implements IndexEventListener {

    private static final Logger LOGGER = LogManager.getLogger(RewriterShardContexts.class);


    private final Map<ShardId, RewriterShardContext> shardContexts;

    private IndicesService indicesService;
    private Settings settings;

    private volatile boolean asyncRefreshEnabled = true;

    public RewriterShardContexts(final Settings settings) {
        this.settings = settings;
        shardContexts = new ConcurrentHashMap<>();
    }

    /**
     * Turns off the async refresh-after-write check (see {@link RewriterShardContext#CACHE_REFRESH_AFTER_WRITE})
     * on every shard context this node creates from now on. Async refresh exists only to pick up changes when
     * there is no push-based reload (e.g. a core-only install on Amazon OpenSearch Service); called once by
     * {@code QuerqyPlugin} (the full, {@code ActionPlugin} install), where push-based reload already keeps every
     * node's cache fresh immediately after every write, making the background check redundant - and, since it
     * writes to the same cache with no version check, a source of an unguarded race against the push-based
     * reload path.
     */
    public void disableAsyncRefresh() {
        asyncRefreshEnabled = false;
    }

    public RewriteChainAndLogging getRewriteChain(final List<RewriterRef> rewriterRefs,
                                                  final QueryShardContext context) {

        final ShardId shardId = new ShardId(context.getFullyQualifiedIndex(), context.getShardId());
        RewriterShardContext shardContext = shardContexts.get(shardId);

        if (shardContext == null) {
            shardContext = loadShardContext(shardId, context);
        }

        return shardContext.getRewriteChain(rewriterRefs);
    }

    protected synchronized RewriterShardContext loadShardContext(final ShardId shardId,
                                                                 final QueryShardContext context) {
        RewriterShardContext shardContext = shardContexts.get(shardId);

        if (shardContext == null) {
            shardContext = new RewriterShardContext(shardId, indicesService.indexService(shardId.getIndex()),  settings,
                    context.getClient(), asyncRefreshEnabled);
            shardContexts.put(shardId, shardContext);
        }

        return shardContext;
    }

    public synchronized void reloadRewriter(final String rewriterId) {
        shardContexts.values().forEach(ctx -> {
            try {
                ctx.reloadRewriter(rewriterId);
            } catch (final Exception e) {
                LOGGER.error("Error reloading rewriter " + rewriterId, e);
                throw new OpenSearchException("Could not reload rewriter " + rewriterId, e);
            }
        });
    }

    public void clearRewriter(final String rewriterId) {
        shardContexts.values().forEach(ctx -> ctx.clearRewriter(rewriterId));
    }

    public void clearRewriters() {
        shardContexts.values().forEach(RewriterShardContext::clearRewriters);
    }

    @Override
    public synchronized void shardRoutingChanged(final IndexShard indexShard, final ShardRouting oldRouting,
                                                 final ShardRouting newRouting) {
        shardContexts.remove(indexShard.shardId());
    }

    @Override
    public synchronized void afterIndexShardClosed(final ShardId shardId, final IndexShard indexShard, final Settings indexSettings) {
        shardContexts.remove(shardId);
    }

    @Inject
    public void setIndicesService(final IndicesService indicesService) {
        this.indicesService = indicesService;
    }

}