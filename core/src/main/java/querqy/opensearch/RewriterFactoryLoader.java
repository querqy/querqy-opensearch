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
import org.opensearch.action.get.MultiGetItemResponse;
import org.opensearch.action.get.MultiGetRequest;
import org.opensearch.action.get.MultiGetResponse;
import org.opensearch.common.cache.Cache;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.InvalidTypeNameException;
import org.opensearch.transport.client.Client;
import querqy.opensearch.query.RefreshMode;
import querqy.opensearch.rewriterstore.LoadRewriterConfig;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriterFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
    private final DictionaryCorpusLoader dictionaryCorpusLoader;

    /** Rewriter ids currently being refreshed in the background, to avoid firing duplicate concurrent refreshes. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public RewriterFactoryLoader(final Supplier<IndexShard> indexShardSupplier, final Settings settings,
                                 final Client client, final boolean asyncRefreshEnabled,
                                 final DictionaryCorpusLoader dictionaryCorpusLoader) {
        this(indexShardSupplier, settings, client, asyncRefreshEnabled, false, dictionaryCorpusLoader);
    }

    private RewriterFactoryLoader(final Supplier<IndexShard> indexShardSupplier, final Settings settings,
                                  final Client client, final boolean asyncRefreshEnabled,
                                  final boolean requireCoordinatorSafe,
                                  final DictionaryCorpusLoader dictionaryCorpusLoader) {
        this.indexShardSupplier = indexShardSupplier;
        this.client = client;
        this.asyncRefreshEnabled = asyncRefreshEnabled;
        this.requireCoordinatorSafe = requireCoordinatorSafe;
        this.dictionaryCorpusLoader = dictionaryCorpusLoader;
        this.factories = Caches.buildCache(RewriterShardContext.CACHE_EXPIRE_AFTER_READ.get(settings),
                RewriterShardContext.CACHE_EXPIRE_AFTER_WRITE.get(settings));
        this.refreshAfterWriteNanos = RewriterShardContext.CACHE_REFRESH_AFTER_WRITE.get(settings).nanos();
    }

    /**
     * A loader with no {@link IndexShard} at all, for use on the coordinating node (e.g. from a search
     * pipeline processor, via {@code SearchPipelinePlugin.Parameters#client}), where there is no shard
     * access of any kind - see the class Javadoc for why this is safe for the built-in rewriter factories.
     * Unlike the per-shard constructor, this also enforces {@link OpenSearchRewriterFactory
     * #isSupportedOnCoordinator()}: a rewriter that needs real shard access (e.g. word-break-compound configured
     * with a shard-side {@code dictionaryField}) fails clearly here instead of failing confusingly later when its
     * rewrite actually runs with no shard behind it. A word-break-compound rewriter configured with a {@code
     * dictionary} reference instead needs no shard access at all and is supported here.
     */
    public static RewriterFactoryLoader nodeLevel(final Settings settings, final Client client,
                                                  final boolean asyncRefreshEnabled,
                                                  final DictionaryCorpusLoader dictionaryCorpusLoader) {
        return new RewriterFactoryLoader(() -> null, settings, client, asyncRefreshEnabled, true,
                dictionaryCorpusLoader);
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
     * The rewriter ids currently cached by this loader, as a snapshot - used by {@link RewriterCacheSweeper} to
     * know which ids to check in its next sweep.
     */
    public Set<String> cachedRewriterIds() {
        final Set<String> ids = new HashSet<>();
        factories.keys().forEach(ids::add);
        return ids;
    }

    /**
     * The {@code term_corpus} dictionary ids every currently cached rewriter factory depends on (see {@link
     * OpenSearchRewriterFactory#getDictionaryDependencyChecksums()}), unioned across every cached rewriter - used
     * by {@link RewriterCacheSweeper} to fold dependency ids into its batched multi-get alongside rewriter ids.
     */
    public Set<String> cachedDictionaryDependencyIds() {
        final Set<String> ids = new HashSet<>();
        for (final String rewriterId : cachedRewriterIds()) {
            final RewriterFactoryAndLogging current = factories.get(rewriterId);
            if (current != null) {
                ids.addAll(current.dictionaryDependencyChecksums.keySet());
            }
        }
        return ids;
    }

    /**
     * Applies the result of a {@link RewriterCacheSweeper} multi-get round trip: for each currently cached
     * rewriter id that was part of the sweep, checks it (and any dictionary it depends on) against the fetched
     * responses using the same comparison as {@link #checkAndReloadIfChanged}. Unlike that method, this never
     * blocks and never throws - the sweep already has the responses in hand. If a rebuild fails because the id
     * (or a dictionary it depends on) is confirmed gone or now the wrong document type, the cache entry is
     * evicted, matching {@link #maybeRefreshAsync}'s handling of the same cases; any other failure (e.g. a
     * replacement document that fails to parse) just keeps the previous version and logs. An id missing from
     * {@code responsesById} (not part of this sweep's batch) or no longer cached (evicted/replaced concurrently)
     * is silently skipped.
     *
     * @param responsesById Every id (rewriter and dictionary dependency) the sweep fetched, by id
     * @param rewriterLevelChecksEnabled Whether to also evict/rebuild based on the rewriter document's own
     *                                  existence/seqNo/primaryTerm - {@code false} on the full ({@code ActionPlugin})
     *                                  plugin, where push-based reload already owns that synchronously and a
     *                                  concurrent sweep-driven check of it would be an unguarded race; dependency
     *                                  checksum checking always stays on, since there is no push-based equivalent
     *                                  for dictionary writes on either install type.
     */
    public void applySweepResponses(final Map<String, GetResponse> responsesById,
                                    final boolean rewriterLevelChecksEnabled) {
        for (final String id : cachedRewriterIds()) {
            final GetResponse response = responsesById.get(id);
            final RewriterFactoryAndLogging current = factories.get(id);
            if (response == null || current == null) {
                continue;
            }
            try {
                applyFreshResponse(id, current, response, responsesById, rewriterLevelChecksEnabled);
            } catch (final ResourceNotFoundException e) {
                LOGGER.info("Rewriter {} can no longer be built ({}), evicting from cache", id, e.getMessage());
                factories.invalidate(id);
            } catch (final InvalidTypeNameException e) {
                LOGGER.warn("Rewriter {} can no longer be built ({}), evicting from cache", id, e.getMessage());
                factories.invalidate(id);
            } catch (final Exception e) {
                LOGGER.error("Could not apply sweep result for rewriter " + id + ", keeping previous version", e);
            }
        }
    }

    /**
     * If {@code current} is older than {@code RewriterShardContext#CACHE_REFRESH_AFTER_WRITE} and no refresh
     * of this rewriter is already in flight, kicks off a non-blocking reload from the index. The cached value
     * keeps being served as-is while the refresh is in flight. On success the cache is updated; if the
     * rewriter document has been deleted, or is confirmed to now be the wrong document type (e.g. its id got
     * reused for a {@code term_corpus} dictionary, or a dictionary it depends on got reused for something
     * else), the cache entry is evicted instead (so the next lookup fails clearly with "not found"/"wrong
     * type", as it would for an id that was never cached, rather than going on serving a stale rewriter
     * forever); on any other failure (e.g. a transient connectivity problem, or a replacement document that
     * fails to parse/validate) the previous version is kept and the failure is logged. The calling thread is
     * never blocked by any of this.
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
                if (!response.isExists()) {
                    LOGGER.info("Rewriter {} no longer exists in the index, evicting from cache", rewriterId);
                    factories.invalidate(rewriterId);
                    refreshing.remove(rewriterId);
                    return;
                }
                // Dispatch off this get-pool callback thread before rebuilding: rebuilding a rewriter that
                // depends on a term_corpus dictionary (e.g. word_break_compound with dictionaryId) re-resolves
                // it via another blocking Client call (DictionaryCorpusLoader#load) - doing that directly here
                // would risk the thread-pool self-starvation deadlock this project already hit once (see
                // TransportPutRewriterAction's own threadPool.generic() dispatch, for the same reason; see also
                // DictionaryCorpusLoader's class javadoc). The dispatch must happen here, before calling
                // buildFactoryAndLogging - merely dispatching the nested GET inside load() itself would not be
                // enough, since this thread would still sit blocked, still occupying its pool's one slot, either
                // way.
                dictionaryCorpusLoader.threadPool().generic().execute(() -> {
                    try {
                        factories.put(rewriterId, buildFactoryAndLogging(rewriterId, response));
                    } catch (final ResourceNotFoundException e) {
                        LOGGER.info("Rewriter {} can no longer be built ({}), evicting from cache", rewriterId,
                                e.getMessage());
                        factories.invalidate(rewriterId);
                    } catch (final InvalidTypeNameException e) {
                        LOGGER.warn("Rewriter {} can no longer be built ({}), evicting from cache", rewriterId,
                                e.getMessage());
                        factories.invalidate(rewriterId);
                    } catch (final Exception e) {
                        LOGGER.error("Could not refresh rewriter " + rewriterId + ", keeping previous version", e);
                    } finally {
                        refreshing.remove(rewriterId);
                    }
                });
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
     * rebuilds the rewriter if its document's sequence number/primary term - or, if it depends on one or more
     * {@code term_corpus} dictionaries (see {@link OpenSearchRewriterFactory#getDictionaryDependencyChecksums()}),
     * any of their checksums - actually changed since it was last loaded. Cheaper than {@code FORCE} when nothing
     * changed, at the same network cost: with no dependencies this is a single {@code GET}, unchanged from before;
     * with dependencies, one {@code MultiGetRequest} covers the rewriter's own document and every dependency in one
     * round trip. If the rewriter document has been deleted, evicts the cache entry and throws {@link
     * ResourceNotFoundException}; if it (or a dictionary it depends on) is confirmed to now be the wrong document
     * type, evicts the cache entry and throws {@link InvalidTypeNameException} instead - either way, the cache is
     * left clean rather than going on to serve a stale rewriter on a later plain lookup, consistent with the
     * async-refresh path. Always evaluates both the rewriter's own staleness and its dependencies' - {@code
     * RefreshMode#CHECK} is a synchronous, per-request check, not a background one, so it has nothing to race
     * against push-based reload the way {@link RewriterCacheSweeper} does.
     */
    private RewriterFactoryAndLogging checkAndReloadIfChanged(final String rewriterId,
                                                               final RewriterFactoryAndLogging current) {

        if (current.dictionaryDependencyChecksums.isEmpty()) {

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

            return applyFreshResponseOrEvict(rewriterId, current, response, Collections.emptyMap());
        }

        final MultiGetRequest request = new MultiGetRequest();
        request.add(QUERQY_INDEX_NAME, rewriterId);
        current.dictionaryDependencyChecksums.keySet().forEach(depId -> request.add(QUERQY_INDEX_NAME, depId));

        final MultiGetResponse multiResponse;
        try {
            multiResponse = client.multiGet(request).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new OpenSearchException("Could not check rewriter " + rewriterId, e);
        }

        final Map<String, GetResponse> byId = toResponseMap(multiResponse);
        final GetResponse response = byId.get(rewriterId);
        if (response == null || !response.isExists()) {
            factories.invalidate(rewriterId);
            throw new ResourceNotFoundException("Rewriter not found: " + rewriterId);
        }

        return applyFreshResponseOrEvict(rewriterId, current, response, byId);
    }

    /**
     * Calls {@link #applyFreshResponse} with {@code rewriterLevelChecksEnabled=true} (always the case for {@link
     * #checkAndReloadIfChanged}, the only caller), additionally evicting the cache entry before rethrowing if the
     * rebuild fails because the rewriter (or a dictionary it depends on) is confirmed gone ({@link
     * ResourceNotFoundException}) or now the wrong document type ({@link InvalidTypeNameException}) - so a
     * {@code CHECK} query that fails loudly also leaves the cache clean, rather than reverting to serving the
     * stale entry on the next plain lookup.
     */
    private RewriterFactoryAndLogging applyFreshResponseOrEvict(final String rewriterId,
                                                                final RewriterFactoryAndLogging current,
                                                                final GetResponse response,
                                                                final Map<String, GetResponse> dependencyResponses) {
        try {
            return applyFreshResponse(rewriterId, current, response, dependencyResponses, true);
        } catch (final ResourceNotFoundException | InvalidTypeNameException e) {
            factories.invalidate(rewriterId);
            throw e;
        }
    }

    private static Map<String, GetResponse> toResponseMap(final MultiGetResponse response) {
        final Map<String, GetResponse> byId = new HashMap<>();
        for (final MultiGetItemResponse item : response.getResponses()) {
            if (!item.isFailed()) {
                byId.put(item.getId(), item.getResponse());
            }
        }
        return byId;
    }

    /**
     * Shared by {@link #checkAndReloadIfChanged} and {@link #applySweepResponses}: given a rewriter's current
     * cached entry and a freshly fetched {@link GetResponse} for it, evicts the cache entry if the document has
     * been deleted (and {@code rewriterLevelChecksEnabled}), leaves the entry untouched if nothing relevant
     * changed, or rebuilds and replaces it otherwise.
     *
     * @param dependencyResponses Every dependency doc id the caller fetched, by id - used to evaluate {@link
     *                           OpenSearchRewriterFactory#getDictionaryDependencyChecksums()}; may be missing ids
     *                           this rewriter doesn't actually depend on (harmless, never consulted) or lack ids it
     *                           does (treated as "changed", forcing a rebuild that then fails clearly if the
     *                           dependency is genuinely gone)
     * @param rewriterLevelChecksEnabled Whether to evict/rebuild based on the rewriter document's own existence/
     *                                  seqNo/primaryTerm at all - see {@link #applySweepResponses} for why this is
     *                                  sometimes {@code false}; always {@code true} from {@link
     *                                  #checkAndReloadIfChanged}, a synchronous per-request check with no
     *                                  background push-based reload to race.
     */
    private RewriterFactoryAndLogging applyFreshResponse(final String rewriterId,
                                                          final RewriterFactoryAndLogging current,
                                                          final GetResponse response,
                                                          final Map<String, GetResponse> dependencyResponses,
                                                          final boolean rewriterLevelChecksEnabled) {

        if (rewriterLevelChecksEnabled && !response.isExists()) {
            LOGGER.info("Rewriter {} no longer exists in the index, evicting from cache", rewriterId);
            factories.invalidate(rewriterId);
            return current;
        }

        final boolean rewriterUnchanged = !rewriterLevelChecksEnabled
                || (response.getSeqNo() == current.seqNo && response.getPrimaryTerm() == current.primaryTerm);

        if (rewriterUnchanged && !hasDependencyChanged(current, dependencyResponses)) {
            return current; // nothing relevant changed - skip rebuilding the rewriter
        }

        if (!response.isExists()) {
            // only reachable with rewriterLevelChecksEnabled=false and a dependency change: the rewriter's own
            // document is also (independently) missing from this batch - nothing to rebuild from right now, a
            // later sweep cycle will pick it up once/if it reappears.
            return current;
        }

        final RewriterFactoryAndLogging fresh = buildFactoryAndLogging(rewriterId, response);
        factories.put(rewriterId, fresh);
        return fresh;
    }

    /** Whether any dictionary {@code current} depends on now has a different checksum than it was built from. */
    private static boolean hasDependencyChanged(final RewriterFactoryAndLogging current,
                                                final Map<String, GetResponse> dependencyResponses) {
        for (final Map.Entry<String, String> dependency : current.dictionaryDependencyChecksums.entrySet()) {
            final String liveChecksum = extractDictionaryChecksum(dependency.getKey(),
                    dependencyResponses.get(dependency.getKey()));
            if (!dependency.getValue().equals(liveChecksum)) {
                return true;
            }
        }
        return false;
    }

    /** @return The dependency document's current checksum, or {@code null} if missing/malformed/not fetched. */
    private static String extractDictionaryChecksum(final String dictionaryId, final GetResponse response) {
        if (response == null || !response.isExists()) {
            return null;
        }
        final Map<String, Object> source = response.getSource();
        if (source == null) {
            return null;
        }
        try {
            final Map<String, Object> corpus = TermCorpusConfigMapping.getCorpus(dictionaryId, source);
            return TermCorpusConfigMapping.getChecksum(corpus);
        } catch (final Exception e) {
            return null;
        }
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

            try {
                factoryAndLogging = buildFactoryAndLogging(rewriterId, response);
            } catch (final ResourceNotFoundException | InvalidTypeNameException e) {
                // confirmed gone, or confirmed to now be the wrong document type - evict before rethrowing so a
                // later plain lookup doesn't fall back to serving this (now provably stale) cache entry
                factories.invalidate(rewriterId);
                throw e;
            }
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

        final RewriterFactory factory = configuredFactory.createRewriterFactory(indexShardSupplier.get(),
                dictionaryCorpusLoader);
        // Must be read immediately after createRewriterFactory(...) - configuredFactory isn't retained anywhere
        // else, see OpenSearchRewriterFactory#getDictionaryDependencyChecksums's javadoc.
        final Map<String, String> dictionaryDependencyChecksums = configuredFactory.getDictionaryDependencyChecksums();

        return new RewriterFactoryAndLogging(factory, loggingEnabled, response.getSeqNo(), response.getPrimaryTerm(),
                dictionaryDependencyChecksums);
    }
}
