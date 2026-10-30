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
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexService;
import org.opensearch.transport.client.Client;

import java.util.List;

/**
 * Per-shard entry point for resolving rewriters, keyed by {@link ShardId}. The actual loading/caching/
 * refresh logic lives in {@link RewriterFactoryLoader}, which this class supplies with this shard's real
 * {@link org.opensearch.index.shard.IndexShard} (resolved freshly on each load, matching this class's
 * previous behavior, in case the shard reference changes while this context is alive). A coordinator-side
 * consumer with no shard at all uses {@link RewriterFactoryLoader#nodeLevel(Settings, Client, boolean)}
 * directly instead of this class.
 */
public class RewriterShardContext {


    public static final Setting<TimeValue> CACHE_EXPIRE_AFTER_WRITE = Setting.timeSetting(
            "querqy.caches.rewriter.expire_after_write",
            TimeValue.timeValueNanos(0), // do not expire by default
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    public static final Setting<TimeValue> CACHE_EXPIRE_AFTER_READ = Setting.timeSetting(
            "querqy.caches.rewriter.expire_after_read",
            TimeValue.timeValueNanos(0), // do not expire by default
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    /**
     * How long a cached rewriter factory may be served before it is refreshed from the index in the background.
     * Unlike {@link #CACHE_EXPIRE_AFTER_WRITE}/{@link #CACHE_EXPIRE_AFTER_READ} (which make a cache miss block the
     * calling search thread on a synchronous index {@code get}), a stale entry here is still returned immediately,
     * while a non-blocking refresh is kicked off in the background - see {@link RewriterFactoryLoader}. This is
     * what lets an install without push-based reload (no {@code ActionPlugin}, e.g. on AWS OpenSearch Service) pick
     * up a config written directly to the index without needing a node restart, without ever blocking a search
     * request past the very first (cold-cache) load of a given rewriter. Default 30s.
     * <p>
     * This setting (and the async-refresh mechanism entirely) has no effect on the full plugin ({@code
     * ActionPlugin} install): {@code QuerqyPlugin} disables it via {@link RewriterShardContexts#disableAsyncRefresh()}
     * since push-based reload already keeps every node's cache fresh immediately after every write there, making
     * the background check redundant - and, since it writes to the same cache with no version check, a source of
     * an unguarded race against the push-based reload path.
     */
    public static final Setting<TimeValue> CACHE_REFRESH_AFTER_WRITE = Setting.timeSetting(
            "querqy.caches.rewriter.refresh_after_write",
            TimeValue.timeValueSeconds(30),
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    private static final Logger LOGGER = LogManager.getLogger(RewriterShardContext.class);

    private final RewriterFactoryLoader loader;

    public RewriterShardContext(final ShardId shardId, final IndexService indexService, final Settings settings,
                                final Client client, final boolean asyncRefreshEnabled) {
        this.loader = new RewriterFactoryLoader(() -> indexService.getShard(shardId.id()), settings, client,
                asyncRefreshEnabled);
        LOGGER.info("Context loaded for shard {} {}. Async refresh enabled: {}", shardId, shardId.getIndex(),
                asyncRefreshEnabled);
    }

    public RewriteChainAndLogging getRewriteChain(final List<RewriterRef> rewriterRefs) {
        return loader.getRewriteChain(rewriterRefs);
    }

    public void clearRewriter(final String rewriterId) {
        loader.clearRewriter(rewriterId);
    }

    public void clearRewriters() {
        loader.clearRewriters();
    }

    public void reloadRewriter(final String rewriterId) {
        loader.reloadRewriter(rewriterId);
    }

    public RewriterFactoryAndLogging loadFactory(final String rewriterId, final boolean forceLoad) {
        return loader.loadFactory(rewriterId, forceLoad);
    }
}
