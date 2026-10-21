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

package querqy.opensearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchException;
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.get.GetResponse;
import org.opensearch.common.cache.Cache;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.shard.IndexShard;
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
import java.util.function.Supplier;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

/**
 * Loads and caches {@link RewriterFactory} instances from {@value querqy.opensearch.rewriterstore.Constants
 * #QUERQY_INDEX_NAME}, with the same validation, refresh-mode ({@link RefreshMode#NO}/{@link RefreshMode#CHECK}/
 * {@link RefreshMode#FORCE}) and async-refresh-after-write behavior regardless of whether a real
 * {@link IndexShard} is available to pass to {@link OpenSearchRewriterFactory#createRewriterFactory}.
 * <p>
 * {@link RewriterShardContext} uses one of these per shard, supplying its real {@link IndexShard}. A
 * coordinator-side consumer with no shard access at all - e.g. a future search pipeline processor, which
 * runs on the coordinating node before any shard is involved - can use {@link #nodeLevel(Settings, Client,
 * boolean)} instead, which always passes {@code null} for the shard. That's safe for every built-in
 * rewriter factory (SimpleCommonRules, Replace, RegexReplace, NumberUnit, WordBreakCompound): none of them
 * dereference the {@code IndexShard} parameter of {@code createRewriterFactory} - only the real index access
 * their rewriters need happens later, lazily, per request, via the {@code SearchEngineRequestAdapter} passed
 * to {@code RewriterFactory#createRewriter} (see {@code WordBreakCompoundRewriterFactory}). A third-party SPI
 * factory that *does* dereference the shard parameter will fail to build through the node-level path; that's
 * an intentional limitation of coordinator-side rewriting (only shard-independent rewriters are supported
 * there), not a bug in this loader.
 */
public class RewriterFactoryLoader {

    private static final Logger LOGGER = LogManager.getLogger(RewriterFactoryLoader.class);

    private final Cache<String, RewriterFactoryAndLogging> factories;
    private final Client client;
    private final Supplier<IndexShard> indexShardSupplier;
    private final boolean requireCoordinatorSafe;
    private final long refreshAfterWriteNanos;
    private final boolean asyncRefreshEnabled;

    /** Rewriter ids currently being refreshed in the background, to avoid firing duplicate concurrent refreshes. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public RewriterFactoryLoader(final Supplier<IndexShard> indexShardSupplier, final Settings settings,
                                 final Client client, final boolean asyncRefreshEnabled) {
        this(indexShardSupplier, settings, client, asyncRefreshEnabled, false);
    }

    private RewriterFactoryLoader(final Supplier<IndexShard> indexShardSupplier, final Settings settings,
                                  final Client client, final boolean asyncRefreshEnabled,
                                  final boolean requireCoordinatorSafe) {
        this.indexShardSupplier = indexShardSupplier;
        this.client = client;
        this.asyncRefreshEnabled = asyncRefreshEnabled;
        this.requireCoordinatorSafe = requireCoordinatorSafe;
        this.factories = Caches.buildCache(RewriterShardContext.CACHE_EXPIRE_AFTER_READ.get(settings),
                RewriterShardContext.CACHE_EXPIRE_AFTER_WRITE.get(settings));
        this.refreshAfterWriteNanos = RewriterShardContext.CACHE_REFRESH_AFTER_WRITE.get(settings).nanos();
    }

    /**
     * A loader with no {@link IndexShard} at all, for use on the coordinating node (e.g. from a search
     * pipeline processor, via {@code SearchPipelinePlugin.Parameters#client}), where there is no shard
     * access of any kind - see the class Javadoc for why this is safe for the built-in rewriter factories.
     * Unlike the per-shard constructor, this also enforces {@link OpenSearchRewriterFactory
     * #isSupportedOnCoordinator()}: a rewriter that needs real shard access (e.g. word-break-compound) fails
     * clearly here instead of failing confusingly later when its rewrite actually runs with no shard behind it.
     */
    public static RewriterFactoryLoader nodeLevel(final Settings settings, final Client client,
                                                  final boolean asyncRefreshEnabled) {
        return new RewriterFactoryLoader(() -> null, settings, client, asyncRefreshEnabled, true);
    }

    /**
     * @param rewriterRefs The rewriters to look up, each with the {@link RefreshMode} that applies to it for this
     *                     one query (see {@code QuerqyQueryBuilder#effectiveRefreshMode}). A rewriter not yet cached
     *                     is always loaded synchronously regardless of its mode, since there's nothing to compare
     *                     against or avoid - the mode only matters once something is already cached: {@link
     *                     RefreshMode#NO} behaves exactly as before (serve the cached value, refresh it
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
     * If {@code current} is older than {@code RewriterShardContext#CACHE_REFRESH_AFTER_WRITE} and no refresh
     * of this rewriter is already in flight, kicks off a non-blocking reload from the index. The cached value
     * keeps being served as-is while the refresh is in flight. On success the cache is updated; if the
     * rewriter document has been deleted, the cache entry is evicted instead (so the next lookup fails clearly
     * with "not found", as it would for an id that was never cached, rather than going on serving a deleted
     * rewriter forever); on any other failure (e.g. a transient connectivity problem, or a replacement
     * document that fails to parse/validate) the previous version is kept and the failure is logged. The
     * calling thread is never blocked by any of this.
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
     * {@link RefreshMode#FORCE}/{@link #loadFactory(String, boolean)} with {@code forceLoad=true}), but only
     * rebuilds the rewriter if its document's sequence number/primary term actually changed since it was last
     * loaded - cheaper than {@code FORCE} when nothing changed, at the same network cost. If the document has
     * been deleted, evicts the cache entry and throws {@link ResourceNotFoundException}, consistent with the
     * async-refresh path.
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

        final OpenSearchRewriterFactory configuredFactory = OpenSearchRewriterFactory.loadConfiguredInstance(loadConfig);

        if (requireCoordinatorSafe && !configuredFactory.isSupportedOnCoordinator()) {
            throw new IllegalArgumentException("Rewriter '" + rewriterId + "' (" + configuredFactory.getClass().getName()
                    + ") cannot be used with a coordinator-side rewrite (e.g. the querqy_rewrite search pipeline "
                    + "processor): it needs direct access to a shard's index, which isn't available on the "
                    + "coordinating node. Use it only with the per-shard querqy query (no search pipeline), or "
                    + "remove it from this request.");
        }

        final RewriterFactory factory = configuredFactory.createRewriterFactory(indexShardSupplier.get());

        return new RewriterFactoryAndLogging(factory, loggingEnabled, response.getSeqNo(), response.getPrimaryTerm());
    }
}
