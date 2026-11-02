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

import org.opensearch.action.get.GetResponse;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A stable, early-constructible handle to the {@link RewriterFactoryLoader} used by the {@code querqy_rewrite}
 * search pipeline processor. Full design rationale: {@code docs/adr/0004-pipeline-rewriter-cache-handle.md}.
 *
 * <p><b>The problem.</b> A node has two independent rewriter caches: one {@link RewriterFactoryLoader} per shard
 * (via {@link RewriterShardContexts}, behind the classic {@code querqy} query), and a separate, single node-level
 * loader used only by the {@code querqy_rewrite} pipeline (built inside {@code QuerqyCorePlugin
 * #getRequestProcessors}). Some node components need to reach <em>both</em> caches - the full plugin module's
 * push-based cache invalidation ({@code TransportNodesClearRewriterCacheAction}/
 * {@code TransportNodesReloadRewriterAction}) and the periodic {@link RewriterCacheSweeper} - but neither has any
 * way to get a reference to the pipeline's loader: it lives inside a different module's processor factory, with no
 * shared type connecting the two.</p>
 *
 * <p><b>The ordering problem.</b> The pipeline's real loader cannot be built until {@code getRequestProcessors}
 * runs (it needs a {@code Client}), but a consumer like {@code TransportNodesClearRewriterCacheAction} needs
 * something {@code @Inject}-able the moment components are registered, in {@code QuerqyCorePlugin#createComponents}
 * - and OpenSearch does not guarantee which of those two plugin methods runs first.</p>
 *
 * <p><b>The fix.</b> This handle is constructed immediately, in {@code QuerqyCorePlugin}'s own constructor, so it
 * always exists before any component wiring happens. It is registered as a component in {@code createComponents},
 * making it available for {@code @Inject} regardless of ordering, and filled in later via {@link #setLoader} once
 * {@code getRequestProcessors} actually builds the real loader.</p>
 *
 * <p><b>The no-op window.</b> Every method below is a no-op while {@link #setLoader} hasn't run yet - a brief
 * window during node startup, or permanently on an install that never configures a {@code querqy_rewrite} pipeline
 * at all - mirroring {@link RewriterFactoryLoader#reloadRewriter}'s own "no-op if not cached" semantics. Callers
 * never need to check readiness themselves.</p>
 *
 * <p><b>Current consumers</b> (this handle is a general mechanism, not specific to either): push-based cache
 * invalidation ({@code TransportNodesClearRewriterCacheAction}/{@code TransportNodesReloadRewriterAction}) and
 * {@link RewriterCacheSweeper} both hold a reference to the same handle instance and call through it exactly as
 * they would call a real {@link RewriterFactoryLoader} directly.</p>
 */
public final class PipelineRewriterCacheHandle {

    private volatile RewriterFactoryLoader loader;

    public void setLoader(final RewriterFactoryLoader loader) {
        this.loader = loader;
    }

    public void clearRewriter(final String rewriterId) {
        final RewriterFactoryLoader current = loader;
        if (current != null) {
            current.clearRewriter(rewriterId);
        }
    }

    public void clearRewriters() {
        final RewriterFactoryLoader current = loader;
        if (current != null) {
            current.clearRewriters();
        }
    }

    public void reloadRewriter(final String rewriterId) {
        final RewriterFactoryLoader current = loader;
        if (current != null) {
            current.reloadRewriter(rewriterId);
        }
    }

    /** Used by {@link RewriterCacheSweeper} to know which ids to check in its next sweep. */
    public Set<String> cachedRewriterIds() {
        final RewriterFactoryLoader current = loader;
        return current == null ? Collections.emptySet() : current.cachedRewriterIds();
    }

    /** Applies a {@link RewriterCacheSweeper} multi-get result to the pipeline's cache. */
    public void applySweepResponses(final Map<String, GetResponse> responsesById) {
        final RewriterFactoryLoader current = loader;
        if (current != null) {
            current.applySweepResponses(responsesById);
        }
    }
}
