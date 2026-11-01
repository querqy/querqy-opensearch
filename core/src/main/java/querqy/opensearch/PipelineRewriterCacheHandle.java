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

/**
 * A stable, early-constructible handle to the {@link RewriterFactoryLoader} used by the {@code querqy_rewrite}
 * search pipeline processor (built inside {@code QuerqyCorePlugin#getRequestProcessors}), so that node components
 * built elsewhere - in particular the full plugin module's push-based cache invalidation (
 * {@code TransportNodesClearRewriterCacheAction}/{@code TransportNodesReloadRewriterAction}, which otherwise only
 * reach {@link RewriterShardContexts}, the per-shard caches behind the classic {@code querqy} query) - can also
 * reach the pipeline's cache, without a code-level dependency on it and without depending on the unspecified
 * ordering between {@code QuerqyCorePlugin#createComponents} (where this handle is registered as a component,
 * available for {@code @Inject}) and {@code getRequestProcessors} (where the real loader is actually built, once a
 * {@code Client} is available).
 *
 * <p>Every method is a no-op while {@link #setLoader} hasn't run yet - a brief window during node startup, or
 * permanently on an install that never configures a {@code querqy_rewrite} pipeline - mirroring {@link
 * RewriterFactoryLoader#reloadRewriter}'s own "no-op if not cached" semantics.</p>
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
}
