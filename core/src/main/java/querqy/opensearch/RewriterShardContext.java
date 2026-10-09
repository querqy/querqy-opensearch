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
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.get.GetResponse;
import org.opensearch.common.cache.Cache;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexService;
import org.opensearch.indices.InvalidTypeNameException;
import org.opensearch.transport.client.Client;
import querqy.opensearch.rewriterstore.LoadRewriterConfig;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriterFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

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
     * while a non-blocking refresh is kicked off in the background - see {@link #getRewriteChain(List)}. This is
     * what lets an install without push-based reload (no {@code ActionPlugin}, e.g. on AWS OpenSearch Service) pick
     * up a config written directly to the index without needing a node restart, without ever blocking a search
     * request past the very first (cold-cache) load of a given rewriter. Default 30s.
     */
    public static final Setting<TimeValue> CACHE_REFRESH_AFTER_WRITE = Setting.timeSetting(
            "querqy.caches.rewriter.refresh_after_write",
            TimeValue.timeValueSeconds(30),
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    private static final Logger LOGGER = LogManager.getLogger(RewriterShardContext.class);

    final Cache<String, RewriterFactoryAndLogging> factories;
    final Client client;
    final IndexService indexService;
    final ShardId shardId;
    final long refreshAfterWriteNanos;

    /** Rewriter ids currently being refreshed in the background, to avoid firing duplicate concurrent refreshes. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public RewriterShardContext(final ShardId shardId, final IndexService indexService, final Settings settings,
                                final Client client) {
        this.indexService = indexService;
        this.shardId = shardId;
        this.client = client;
        factories = Caches.buildCache(CACHE_EXPIRE_AFTER_WRITE.get(settings), CACHE_EXPIRE_AFTER_READ.get(settings));
        refreshAfterWriteNanos = CACHE_REFRESH_AFTER_WRITE.get(settings).nanos();
        LOGGER.info("Context loaded for shard {} {}", shardId, shardId.getIndex());
    }

    public RewriteChainAndLogging getRewriteChain(final List<String> rewriterIds) {
        final List<RewriterFactory> rewriterFactories = new ArrayList<>(rewriterIds.size());
        final Set<String> loggingEnabledRewriters = new HashSet<>();

        for (final String id : rewriterIds) {

            RewriterFactoryAndLogging factoryAndLogging = factories.get(id);
            if (factoryAndLogging == null) {
                factoryAndLogging = loadFactory(id, false);
            } else {
                maybeRefreshAsync(id, factoryAndLogging);
            }
            rewriterFactories.add(factoryAndLogging.rewriterFactory);
            if (factoryAndLogging.loggingEnabled) {
                loggingEnabledRewriters.add(id);
            }

        }

        return new RewriteChainAndLogging(new RewriteChain(rewriterFactories), loggingEnabledRewriters);
    }

    public void clearRewriter(final String rewriterId) {
        factories.invalidate(rewriterId);
    }

    public void clearRewriters() {
        factories.invalidateAll();
    }

    public void reloadRewriter(final String rewriterId) {
        if (factories.get(rewriterId) != null) {
            loadFactory(rewriterId, true);
        }
    }

    /**
     * If {@code current} is older than {@link #CACHE_REFRESH_AFTER_WRITE} and no refresh of this rewriter is already
     * in flight, kicks off a non-blocking reload from the index. The cached value keeps being served as-is while the
     * refresh is in flight; on success the cache is updated, on failure the previous version is kept and the failure
     * is logged - the calling search thread is never blocked by this.
     */
    private void maybeRefreshAsync(final String rewriterId, final RewriterFactoryAndLogging current) {

        if (refreshAfterWriteNanos <= 0 || (System.nanoTime() - current.loadedAtNanos) < refreshAfterWriteNanos) {
            return;
        }

        if (!refreshing.add(rewriterId)) {
            // a refresh for this rewriter is already in flight
            return;
        }

        client.prepareGet(QUERQY_INDEX_NAME, rewriterId).execute(new ActionListener<GetResponse>() {

            @Override
            public void onResponse(final GetResponse response) {
                try {
                    factories.put(rewriterId, buildFactoryAndLogging(rewriterId, response));
                } catch (final Exception e) {
                    LOGGER.error("Could not refresh rewriter " + rewriterId + ", keeping previous version", e);
                } finally {
                    refreshing.remove(rewriterId);
                }
            }

            @Override
            public void onFailure(final Exception e) {
                LOGGER.error("Could not refresh rewriter " + rewriterId + ", keeping previous version", e);
                refreshing.remove(rewriterId);
            }
        });
    }

    public synchronized RewriterFactoryAndLogging loadFactory(final String rewriterId, final boolean forceLoad) {

        RewriterFactoryAndLogging factoryAndLogging = factories.get(rewriterId);

        if (forceLoad || (factoryAndLogging == null)) {

            final GetResponse response;

            try {
                response = client.prepareGet(QUERQY_INDEX_NAME, rewriterId).execute().get();
            } catch (InterruptedException | ExecutionException e) {
                throw new OpenSearchException("Could not load rewriter " + rewriterId, e);
            }

            factoryAndLogging = buildFactoryAndLogging(rewriterId, response);
            factories.put(rewriterId, factoryAndLogging);

        }

        return factoryAndLogging;

    }

    private RewriterFactoryAndLogging buildFactoryAndLogging(final String rewriterId, final GetResponse response) {

        final Map<String, Object> source = response.getSource();

        if (source == null) {
            throw new ResourceNotFoundException("Rewriter not found: " + rewriterId);
        }

        if (!"rewriter".equals(source.get(RewriterConfigMapping.PROP_TYPE))) {
            throw new InvalidTypeNameException("Not a rewriter: " + rewriterId);
        }

        final LoadRewriterConfig loadConfig = new LoadRewriterConfig(rewriterId, source);

        final Map<String, Object> infoLogging = loadConfig.getInfoLoggingConfig();
        final boolean loggingEnabled;
        if (infoLogging != null) {
            final Object sinksObj = infoLogging.get("sinks");
            if (sinksObj instanceof String) {
                loggingEnabled = "log4j".equals(sinksObj);
            } else if (sinksObj instanceof final Collection<?> sinksCollection) {
                loggingEnabled = (!sinksCollection.isEmpty()) && sinksCollection.contains("log4j");
            } else {
                loggingEnabled = false;
            }
        } else {
            loggingEnabled = false;
        }

        final RewriterFactory factory = OpenSearchRewriterFactory.loadConfiguredInstance(loadConfig)
                .createRewriterFactory(indexService.getShard(shardId.id()));

        return new RewriterFactoryAndLogging(factory, loggingEnabled);
    }


    public static class RewriterFactoryAndLogging {
        public final RewriterFactory rewriterFactory;
        public final boolean loggingEnabled;
        final long loadedAtNanos;

        public RewriterFactoryAndLogging(final RewriterFactory rewriterFactory, final boolean loggingEnabled) {
            this.rewriterFactory = rewriterFactory;
            this.loggingEnabled = loggingEnabled;
            this.loadedAtNanos = System.nanoTime();
        }
    }
}
