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

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.get.MultiGetItemResponse;
import org.opensearch.action.get.MultiGetRequest;
import org.opensearch.action.get.MultiGetResponse;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Periodically checks every rewriter id currently cached (across every {@link RewriterShardContext} on this node,
 * and the {@code querqy_rewrite} search pipeline's own cache via {@link PipelineRewriterCacheHandle}), together with
 * every {@code term_corpus} dictionary any of them depends on, against
 * {@value querqy.opensearch.rewriterstore.Constants#QUERQY_INDEX_NAME} in a single batched {@link MultiGetRequest},
 * independent of query traffic.
 * <p>
 * This closes a gap that exists even with push-based reload: there is no push/REST action for dictionary writes on
 * either install type, so a dictionary updated or deleted out-of-band is never picked up except by this sweep or an
 * explicit {@code RefreshMode#CHECK}/{@code FORCE} query. And on a core-only/AWS OpenSearch Service install (no
 * {@code ActionPlugin} REST/transport PUT/DELETE action at all), the same is true of rewriter documents themselves:
 * {@link RewriterFactoryLoader#maybeRefreshAsync}/{@code checkAndReloadIfChanged} only ever run when a search
 * request actually references a given rewriter id again, so a rewriter (and, for {@code word_break_compound}, its
 * potentially large {@code TermCorpus}) that stops being queried after being deleted or changed would otherwise sit
 * in memory forever. A sweep interval of one shard means every id resolves to exactly one multi-get task on one
 * thread, regardless of how many are cached - see {@code TransportShardMultiGetAction}.
 * <p>
 * On the full plugin ({@code ActionPlugin} install), {@code QuerqyPlugin} calls {@link #disableRewriterLevelChecks()}
 * before {@link #start} ever runs: push-based reload already keeps every rewriter's own cache entry fresh
 * immediately after every write there, making a sweep-driven check of the rewriter document itself redundant - and,
 * since it writes to the same caches with no version check against the push-based path, a potential source of an
 * unguarded race, exactly as for {@link RewriterShardContexts#disableAsyncRefresh()}. The sweep keeps running on
 * every install either way, and its dictionary-dependency check always stays on: there is no push-based mechanism
 * for dictionary writes to race against on any install type.
 */
public final class RewriterCacheSweeper {

    private static final Logger LOGGER = LogManager.getLogger(RewriterCacheSweeper.class);

    /** How often to sweep every cached rewriter id. 0 disables the sweep entirely. Default 5 minutes. */
    public static final Setting<TimeValue> SWEEP_INTERVAL = Setting.timeSetting(
            "querqy.caches.rewriter.sweep_interval",
            TimeValue.timeValueMinutes(5),
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    private final RewriterShardContexts rewriterShardContexts;
    private final PipelineRewriterCacheHandle pipelineRewriterCacheHandle;

    private volatile boolean rewriterLevelChecksEnabled = true;
    private volatile Scheduler.Cancellable scheduled;
    private volatile ThreadPool threadPool;

    public RewriterCacheSweeper(final RewriterShardContexts rewriterShardContexts,
                                final PipelineRewriterCacheHandle pipelineRewriterCacheHandle) {
        this.rewriterShardContexts = rewriterShardContexts;
        this.pipelineRewriterCacheHandle = pipelineRewriterCacheHandle;
    }

    /**
     * Narrows the sweep to dictionary-dependency checks only - called once by {@code QuerqyPlugin} (the full,
     * {@code ActionPlugin} install), where push-based reload already keeps every rewriter's own cache entry fresh
     * synchronously, making a sweep-driven check of the rewriter document itself redundant (and racy against the
     * push-based path - see the class javadoc). The sweep itself keeps running either way: there is no push-based
     * mechanism for dictionary writes on any install type, so the dependency check has nothing to be redundant
     * with, and nothing to race against.
     */
    public void disableRewriterLevelChecks() {
        rewriterLevelChecksEnabled = false;
    }

    /**
     * Separated from {@link #start} so tests can supply a {@link ThreadPool} (needed by {@link #applyResponse} -
     * see there) without also scheduling the real periodic background sweep.
     */
    public void setThreadPool(final ThreadPool threadPool) {
        this.threadPool = threadPool;
    }

    /** Schedules the periodic sweep, unless {@link #SWEEP_INTERVAL} is 0. Runs on every install type. */
    public void start(final Settings settings, final ThreadPool threadPool, final Client client) {
        setThreadPool(threadPool);
        final TimeValue interval = SWEEP_INTERVAL.get(settings);
        if (interval.nanos() <= 0) {
            return;
        }
        scheduled = threadPool.scheduleWithFixedDelay(() -> sweep(client), interval, ThreadPool.Names.GENERIC);
    }

    void sweep(final Client client) {

        final Set<String> ids = new HashSet<>(rewriterShardContexts.cachedRewriterIds());
        ids.addAll(pipelineRewriterCacheHandle.cachedRewriterIds());

        if (ids.isEmpty()) {
            return;
        }

        // Dependency ids are folded into the same batch, not a separate round trip - same index, same single shard.
        ids.addAll(rewriterShardContexts.cachedDictionaryDependencyIds());
        ids.addAll(pipelineRewriterCacheHandle.cachedDictionaryDependencyIds());

        final MultiGetRequest request = new MultiGetRequest();
        ids.forEach(id -> request.add(QUERQY_INDEX_NAME, id));

        client.multiGet(request, ActionListener.wrap(this::applyResponse,
                e -> LOGGER.error("Rewriter cache sweep failed", e)));
    }

    /**
     * Runs on {@code threadPool.generic()}, never directly on the {@code get}-pool thread the multi-get response
     * was delivered on: applying a sweep result can rebuild a rewriter, which for a {@code word_break_compound}
     * rewriter with a {@code dictionaryId} re-resolves the dictionary via another blocking {@code Client} call
     * ({@link DictionaryCorpusLoader#load}). Doing that directly on the thread that's delivering this callback
     * would risk the thread-pool self-starvation deadlock this project already hit once (see {@code
     * TransportPutRewriterAction}'s own {@code threadPool.generic()} dispatch, for the same reason; see also
     * {@link DictionaryCorpusLoader}'s class javadoc). The dispatch happens here, wrapping the whole method body -
     * merely dispatching the nested {@code GET} deeper inside {@code load()} itself would not be enough, since this
     * callback's thread would still sit blocked waiting for it, still occupying its pool's one slot, either way.
     */
    private void applyResponse(final MultiGetResponse response) {
        threadPool.generic().execute(() -> {

            final Map<String, GetResponse> responsesById = new HashMap<>();

            for (final MultiGetItemResponse item : response.getResponses()) {
                if (item.isFailed()) {
                    LOGGER.warn("Could not check {} during cache sweep: {}", item.getId(),
                            item.getFailure().getMessage());
                    continue;
                }
                responsesById.put(item.getId(), item.getResponse());
            }

            rewriterShardContexts.applySweepResponses(responsesById, rewriterLevelChecksEnabled);
            pipelineRewriterCacheHandle.applySweepResponses(responsesById, rewriterLevelChecksEnabled);
        });
    }
}
