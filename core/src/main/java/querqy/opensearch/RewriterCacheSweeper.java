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
 * and the {@code querqy_rewrite} search pipeline's own cache via {@link PipelineRewriterCacheHandle}) against
 * {@value querqy.opensearch.rewriterstore.Constants#QUERQY_INDEX_NAME} in a single batched {@link MultiGetRequest},
 * independent of query traffic.
 * <p>
 * This closes a gap that only exists without push-based reload (i.e. on a core-only/AWS OpenSearch Service
 * install, which has no {@code ActionPlugin} REST/transport PUT/DELETE action at all): {@link
 * RewriterFactoryLoader#maybeRefreshAsync}/{@code checkAndReloadIfChanged} only ever run when a search request
 * actually references a given rewriter id again, so a rewriter (and, for {@code word_break_compound}, its
 * potentially large {@code TermCorpus}) that stops being queried after being deleted or changed would otherwise sit
 * in memory forever. A sweep interval of one shard means every rewriter id resolves to exactly one multi-get task
 * on one thread, regardless of how many ids are cached - see {@code TransportShardMultiGetAction}.
 * <p>
 * On the full plugin ({@code ActionPlugin} install), {@code QuerqyPlugin} calls {@link #disable()} before {@link
 * #start} ever runs: push-based reload already keeps every cache fresh immediately after every write there, making
 * this sweep redundant - and, since it writes to the same caches with no version check against the push-based path,
 * a potential source of an unguarded race, exactly as for {@link RewriterShardContexts#disableAsyncRefresh()}.
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

    private volatile boolean enabled = true;
    private volatile Scheduler.Cancellable scheduled;

    public RewriterCacheSweeper(final RewriterShardContexts rewriterShardContexts,
                                final PipelineRewriterCacheHandle pipelineRewriterCacheHandle) {
        this.rewriterShardContexts = rewriterShardContexts;
        this.pipelineRewriterCacheHandle = pipelineRewriterCacheHandle;
    }

    /**
     * Turns the sweep off for good - called once by {@code QuerqyPlugin} (the full, {@code ActionPlugin} install),
     * where push-based reload already makes it redundant. Safe to call before {@link #start}, in which case
     * {@link #start} becomes a no-op.
     */
    public void disable() {
        enabled = false;
        final Scheduler.Cancellable current = scheduled;
        if (current != null) {
            current.cancel();
        }
    }

    /** Schedules the periodic sweep, unless disabled or {@link #SWEEP_INTERVAL} is 0. */
    public void start(final Settings settings, final ThreadPool threadPool, final Client client) {
        final TimeValue interval = SWEEP_INTERVAL.get(settings);
        if (!enabled || interval.nanos() <= 0) {
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

        final MultiGetRequest request = new MultiGetRequest();
        ids.forEach(id -> request.add(QUERQY_INDEX_NAME, id));

        client.multiGet(request, ActionListener.wrap(this::applyResponse,
                e -> LOGGER.error("Rewriter cache sweep failed", e)));
    }

    private void applyResponse(final MultiGetResponse response) {

        final Map<String, GetResponse> responsesById = new HashMap<>();

        for (final MultiGetItemResponse item : response.getResponses()) {
            if (item.isFailed()) {
                LOGGER.warn("Could not check rewriter {} during cache sweep: {}", item.getId(),
                        item.getFailure().getMessage());
                continue;
            }
            responsesById.put(item.getId(), item.getResponse());
        }

        rewriterShardContexts.applySweepResponses(responsesById);
        pipelineRewriterCacheHandle.applySweepResponses(responsesById);
    }
}
