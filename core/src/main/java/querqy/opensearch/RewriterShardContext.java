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
import querqy.opensearch.query.RefreshMode;
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

    final Cache<String, RewriterFactoryAndLogging> factories;
    final Client client;
    final IndexService indexService;
    final ShardId shardId;
    final long refreshAfterWriteNanos;
    final boolean asyncRefreshEnabled;

    /** Rewriter ids currently being refreshed in the background, to avoid firing duplicate concurrent refreshes. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public RewriterShardContext(final ShardId shardId, final IndexService indexService, final Settings settings,
                                final Client client, final boolean asyncRefreshEnabled) {
        this.indexService = indexService;
        this.shardId = shardId;
        this.client = client;
        this.asyncRefreshEnabled = asyncRefreshEnabled;
        factories = Caches.buildCache(CACHE_EXPIRE_AFTER_WRITE.get(settings), CACHE_EXPIRE_AFTER_READ.get(settings));
        refreshAfterWriteNanos = CACHE_REFRESH_AFTER_WRITE.get(settings).nanos();
        LOGGER.info("Context loaded for shard {} {}. Async refresh enabled: {}", shardId, shardId.getIndex(),
                asyncRefreshEnabled);
    }

    /**
     * @param rewriterRefs The rewriters to look up, each with the {@link RefreshMode} that applies to it for this
     *                     one query (see {@code QuerqyQueryBuilder#effectiveRefreshMode}). A rewriter not yet cached
     *                     on this shard is always loaded synchronously regardless of its mode, since there's nothing
     *                     to compare against or avoid - the mode only matters once something is already cached:
     *                     {@link RefreshMode#NO} behaves exactly as before (serve the cached value, refresh it
     *                     asynchronously in the background if stale and {@code asyncRefreshEnabled}); {@link
     *                     RefreshMode#CHECK} and {@link RefreshMode#FORCE} both block this call on one round trip to
     *                     the index, the former skipping the rebuild if nothing actually changed, regardless of
     *                     {@code asyncRefreshEnabled}.
     */
    public RewriteChainAndLogging getRewriteChain(final List<RewriterRef> rewriterRefs) {
        final List<RewriterFactory> rewriterFactories = new ArrayList<>(rewriterRefs.size());
        final Set<String> loggingEnabledRewriters = new HashSet<>();

        for (final RewriterRef ref : rewriterRefs) {

            final String id = ref.getRewriterId();
            RewriterFactoryAndLogging factoryAndLogging = factories.get(id);

            if (factoryAndLogging == null) {
                factoryAndLogging = loadFactory(id, false);
            } else {
                switch (ref.getRefreshMode()) {
                    case FORCE:
                        factoryAndLogging = loadFactory(id, true);
                        break;
                    case CHECK:
                        factoryAndLogging = checkAndReloadIfChanged(id, factoryAndLogging);
                        break;
                    case NO:
                    default:
                        if (asyncRefreshEnabled) {
                            maybeRefreshAsync(id, factoryAndLogging);
                        }
                        break;
                }
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
     * refresh is in flight. On success the cache is updated; if the rewriter document has been deleted, the cache
     * entry is evicted instead (so the next lookup fails clearly with "not found", as it would for an id that was
     * never cached, rather than going on serving a deleted rewriter forever); on any other failure (e.g. a transient
     * connectivity problem, or a replacement document that fails to parse/validate) the previous version is kept and
     * the failure is logged. The calling search thread is never blocked by any of this.
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
                    if (response.isExists()) {
                        factories.put(rewriterId, buildFactoryAndLogging(rewriterId, response));
                    } else {
                        LOGGER.info("Rewriter {} no longer exists in the index, evicting from cache", rewriterId);
                        factories.invalidate(rewriterId);
                    }
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

    /**
     * Implements {@link RefreshMode#CHECK}: blocks on one round trip to the index (same cost as
     * {@link RefreshMode#FORCE}/{@link #loadFactory(String, boolean)} with {@code forceLoad=true}), but only rebuilds
     * the rewriter if its document's sequence number/primary term actually changed since it was last loaded - cheaper
     * than {@code FORCE} when nothing changed, at the same network cost. If the document has been deleted, evicts the
     * cache entry and throws {@link ResourceNotFoundException}, consistent with the async-refresh path.
     */
    private RewriterFactoryAndLogging checkAndReloadIfChanged(final String rewriterId,
                                                               final RewriterFactoryAndLogging current) {

        final GetResponse response;
        try {
            response = client.prepareGet(QUERQY_INDEX_NAME, rewriterId).execute().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new OpenSearchException("Could not check rewriter " + rewriterId, e);
        }

        if (!response.isExists()) {
            factories.invalidate(rewriterId);
            throw new ResourceNotFoundException("Rewriter not found: " + rewriterId);
        }

        if (response.getSeqNo() == current.seqNo && response.getPrimaryTerm() == current.primaryTerm) {
            return current; // unchanged - skip rebuilding the rewriter
        }

        final RewriterFactoryAndLogging fresh = buildFactoryAndLogging(rewriterId, response);
        factories.put(rewriterId, fresh);
        return fresh;
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

        return new RewriterFactoryAndLogging(factory, loggingEnabled, response.getSeqNo(), response.getPrimaryTerm());
    }


    public static class RewriterFactoryAndLogging {
        public final RewriterFactory rewriterFactory;
        public final boolean loggingEnabled;
        final long loadedAtNanos;
        /** The stored document's sequence number/primary term at load time - used by {@link RefreshMode#CHECK} to
         * tell whether the document has changed since, without re-parsing the rewriter when it hasn't. */
        final long seqNo;
        final long primaryTerm;

        public RewriterFactoryAndLogging(final RewriterFactory rewriterFactory, final boolean loggingEnabled,
                                         final long seqNo, final long primaryTerm) {
            this.rewriterFactory = rewriterFactory;
            this.loggingEnabled = loggingEnabled;
            this.loadedAtNanos = System.nanoTime();
            this.seqNo = seqNo;
            this.primaryTerm = primaryTerm;
        }
    }
}
