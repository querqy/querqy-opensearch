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

import org.opensearch.OpenSearchException;
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.get.GetResponse;
import org.opensearch.indices.InvalidTypeNameException;
import org.opensearch.transport.client.Client;
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;
import querqy.rewriter.wordbreak.TermCorpus;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

/**
 * Loads and caches {@link TermCorpus} instances (word-frequency dictionaries for a coordinator-safe rewriter, e.g.
 * {@code WordBreakCompoundRewriterFactory} configured with a {@code dictionary} reference) from {@code term_corpus}
 * documents in {@value querqy.opensearch.rewriterstore.Constants#QUERQY_INDEX_NAME}.
 *
 * <p>This is a <b>node-level singleton</b> (one instance per node, constructed once by {@code QuerqyCorePlugin} and
 * shared by every {@link RewriterFactoryLoader} - every per-shard one, via {@link RewriterShardContext}, and the
 * node-level/pipeline one), not one per {@code RewriterFactoryLoader}: the same {@code (name, revision)} dictionary
 * is parsed and held in memory at most once per node, however many shards or pipelines reference it.</p>
 *
 * <p>Cached by doc id ({@code name + "-" + revision}) as a {@link WeakReference}. A dictionary's only strong
 * owners are the {@code RewriterFactory} instances built from it, which live inside each {@code
 * RewriterFactoryLoader}'s own cache. As long as at least one of those caches (any shard's, or the pipeline's)
 * still holds a live entry built from this dictionary, the {@link TermCorpus} stays strongly reachable through that
 * chain and this loader keeps returning the same shared instance - concurrent users of the same dictionary always
 * share one instance, never duplicates. Once every such entry has been evicted or replaced (the rewriter was
 * reconfigured to a new revision, its cache was cleared, or it expired under the normal rewriter-cache settings)
 * and garbage-collected, the weak reference clears itself: memory is reclaimed automatically, tied to actual need,
 * with no manual reference counting.</p>
 *
 * <p>A dictionary document is immutable by convention (a change is a new {@code (name, revision)}, not an in-place
 * update), so unlike {@link RewriterFactoryLoader} this loader never needs to refresh an already-cached entry - it
 * only ever loads a given doc id once (until it's weakly collected and re-requested). The one thing it does handle
 * explicitly is coalescing concurrent cold loads of the same doc id (e.g. many shards of the same index, or many
 * pipeline requests, asking for a brand-new dictionary at the same moment), so they don't all fetch, gunzip and
 * parse the same multi-MB file in parallel.</p>
 *
 * <p>This object is constructed early, in {@code QuerqyCorePlugin}'s own constructor, before a real {@link Client}
 * exists - {@code QuerqyCorePlugin#createComponents} calls {@link #setClient(Client)} with the real one the moment
 * it becomes available (that method already receives a {@code Client} directly, unlike e.g. {@code IndicesService},
 * so no {@code @Inject}-based deferred member-injection is needed here, unlike {@code
 * RewriterShardContexts#setIndicesService}). There's exactly one {@code Client} per node, and every other consumer
 * of it (per-shard {@code RewriterFactoryLoader}s, the pipeline's) already resolves to the same instance, so this
 * loader does not need a separate one per caller.</p>
 */
public final class DictionaryCorpusLoader {

    private final ConcurrentHashMap<String, WeakReference<TermCorpus>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<TermCorpus>> loading = new ConcurrentHashMap<>();

    private volatile Client client;

    public void setClient(final Client client) {
        this.client = client;
    }

    /**
     * @param name The dictionary name
     * @param revision The dictionary revision
     * @return The (possibly shared, cached) {@link TermCorpus}
     * @throws IllegalStateException if called before {@link #setClient(Client)} - shouldn't be reachable in
     * practice, since injection completes during node startup, well before any rewrite chain is ever built
     * @throws ResourceNotFoundException if no such document exists
     * @throws OpenSearchException if the document exists but isn't a valid {@code term_corpus} document
     */
    public TermCorpus load(final String name, final String revision) {

        final Client currentClient = client;
        if (currentClient == null) {
            throw new IllegalStateException(
                    "DictionaryCorpusLoader has no Client yet - setClient(...) was not called before load(...)");
        }

        final String docId = TermCorpusConfigMapping.docId(name, revision);

        final TermCorpus cached = getLive(docId);
        if (cached != null) {
            return cached;
        }

        final CompletableFuture<TermCorpus> future = new CompletableFuture<>();
        final CompletableFuture<TermCorpus> inFlight = loading.putIfAbsent(docId, future);
        if (inFlight != null) {
            return join(inFlight, docId);
        }

        try {
            final TermCorpus loaded = doLoad(docId, currentClient);
            cache.put(docId, new WeakReference<>(loaded));
            future.complete(loaded);
            return loaded;
        } catch (final RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(docId, future);
        }
    }

    private TermCorpus getLive(final String docId) {
        final WeakReference<TermCorpus> ref = cache.get(docId);
        return ref == null ? null : ref.get();
    }

    private static TermCorpus doLoad(final String docId, final Client client) {

        final GetResponse response;
        try {
            response = client.prepareGet(QUERQY_INDEX_NAME, docId).execute().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new OpenSearchException("Could not load term corpus '" + docId + "'", e);
        }

        final Map<String, Object> source = response.getSource();
        if (source == null) {
            throw new ResourceNotFoundException("Term corpus not found: " + docId);
        }

        if (!TermCorpusConfigMapping.TYPE_VALUE.equals(source.get(TermCorpusConfigMapping.PROP_TYPE))) {
            throw new InvalidTypeNameException("Not a term corpus: " + docId);
        }

        final Map<String, Object> corpus = TermCorpusConfigMapping.getCorpus(docId, source);
        try {
            return TermCorpusConfigMapping.buildTermCorpus(corpus);
        } catch (final IOException e) {
            throw new OpenSearchException("Could not parse term corpus '" + docId + "'", e);
        }
    }

    private static TermCorpus join(final CompletableFuture<TermCorpus> future, final String docId) {
        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenSearchException("Interrupted while waiting for term corpus '" + docId + "'", e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new OpenSearchException("Could not load term corpus '" + docId + "'", cause);
        }
    }
}
