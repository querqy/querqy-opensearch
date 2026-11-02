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
import org.opensearch.threadpool.ThreadPool;
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
 * {@code WordBreakCompoundRewriterFactory} configured with a {@code dictionaryId}) from {@code term_corpus}
 * documents in {@value querqy.opensearch.rewriterstore.Constants#QUERQY_INDEX_NAME}. See
 * {@code docs/adr/0005-mutable-checksum-verified-dictionaries.md}.
 *
 * <p>This is a <b>node-level singleton</b> (one instance per node, constructed once by {@code QuerqyCorePlugin} and
 * shared by every {@link RewriterFactoryLoader} - every per-shard one, via {@link RewriterShardContext}, and the
 * node-level/pipeline one), not one per {@code RewriterFactoryLoader}: the same dictionary id is parsed and held in
 * memory at most once per node, however many shards or pipelines reference it.</p>
 *
 * <p>A dictionary document is intentionally mutable - updating it in place (same id, new content, a new checksum)
 * is the supported way to change it. {@link #load(String)} therefore always checks the live document's checksum
 * (verified server-side at write time, see {@code QuerqyTermCorpusFieldMapper}) against what's cached: if they
 * match, the cached {@link TermCorpus} is returned (skipping the gunzip/parse cost); if not, the new content is
 * fetched, parsed, and cached, replacing the old entry. This check is cheap relative to how often it runs - {@code
 * load} is only ever called once per rewriter-factory rebuild (by {@code WordBreakCompoundRewriterFactory
 * #createRewriterFactory}, itself only called from {@code RewriterFactoryLoader#buildFactoryAndLogging}), never once
 * per query.</p>
 *
 * <p>Cached by doc id as a {@link WeakReference}, paired with the checksum it was built from. A dictionary's only
 * strong owners are the {@code RewriterFactory} instances built from it, which live inside each {@code
 * RewriterFactoryLoader}'s own cache. As long as at least one of those caches (any shard's, or the pipeline's)
 * still holds a live entry built from this dictionary, the {@link TermCorpus} stays strongly reachable through that
 * chain and this loader keeps returning the same shared instance for the same checksum - concurrent users of the
 * same dictionary content always share one instance, never duplicates. Once every such entry has been evicted or
 * replaced and garbage-collected, the weak reference clears itself: memory is reclaimed automatically, tied to
 * actual need, with no manual reference counting.</p>
 *
 * <p>Concurrent cold builds of the same id are coalesced - but keyed by {@code id + checksum}, not bare id, so
 * concurrent callers that observe the <em>same</em> new checksum share one build, while callers racing across a
 * rapid sequence of writes (observing genuinely different checksums) each get their own build rather than racing
 * on a single shared future for different content. This is a bounded-parallelism/cache-churn refinement, not a
 * correctness concern: {@code checksum} and {@code data} live in the same document, so one {@code GET} is always
 * one atomically-consistent snapshot - there is no way to observe new data paired with a stale checksum or vice
 * versa.</p>
 *
 * <p>This object is constructed early, in {@code QuerqyCorePlugin}'s own constructor, before a real {@link Client}/
 * {@link ThreadPool} exist - {@code QuerqyCorePlugin#createComponents} calls {@link #setClient(Client)} and {@link
 * #setThreadPool(ThreadPool)} with the real ones the moment they become available (that method already receives
 * both directly as parameters, unlike e.g. {@code IndicesService}, so no {@code @Inject}-based deferred
 * member-injection is needed here, unlike {@code RewriterShardContexts#setIndicesService}). There's exactly one of
 * each per node, and every other consumer already resolves to the same instances, so this loader does not need a
 * separate one per caller.</p>
 *
 * <p><b>Deadlock precaution for callers.</b> {@link #load} blocks the calling thread on a {@code GET}. Two places
 * rebuild a rewriter from inside a {@code GET}/multi-get response callback that already runs on a thread from the
 * small {@code get} thread pool - {@code RewriterFactoryLoader#maybeRefreshAsync} and {@link RewriterCacheSweeper}
 * - and a rebuild may call back into {@code load} (e.g. for a {@code word_break_compound} rewriter with a {@code
 * dictionaryId}). Both dispatch onto {@link #threadPool()}{@code .generic()} *before* doing any such rebuild, so
 * the originating {@code get}-pool thread returns immediately rather than staying blocked for the rebuild's
 * duration - dispatching only the actual {@code GET} inside {@code load} itself would not be enough, since the
 * thread that called {@code load} would still sit blocked occupying its pool's slot either way. This is the exact
 * thread-pool self-starvation shape already hit once in this project (see {@code TransportPutRewriterAction}'s own
 * {@code threadPool.generic()} dispatch, for the same reason) - any future caller of {@code load} from inside a
 * response callback must dispatch the same way first.</p>
 */
public final class DictionaryCorpusLoader {

    /** A loaded {@link TermCorpus} together with the checksum of the document content it was built from. */
    public static final class LoadedTermCorpus {
        public final TermCorpus termCorpus;
        public final String checksum;

        LoadedTermCorpus(final TermCorpus termCorpus, final String checksum) {
            this.termCorpus = termCorpus;
            this.checksum = checksum;
        }
    }

    private static final class CachedCorpus {
        final String checksum;
        final WeakReference<TermCorpus> ref;

        CachedCorpus(final String checksum, final TermCorpus corpus) {
            this.checksum = checksum;
            this.ref = new WeakReference<>(corpus);
        }
    }

    private final ConcurrentHashMap<String, CachedCorpus> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<TermCorpus>> building = new ConcurrentHashMap<>();

    private volatile Client client;
    private volatile ThreadPool threadPool;

    public void setClient(final Client client) {
        this.client = client;
    }

    public void setThreadPool(final ThreadPool threadPool) {
        this.threadPool = threadPool;
    }

    /**
     * The node's {@link ThreadPool}, for callers that need to dispatch work off whatever thread they're currently
     * running on before calling {@link #load} - see {@code RewriterFactoryLoader#maybeRefreshAsync} and {@link
     * RewriterCacheSweeper} for why/where that matters. {@code null} until {@link #setThreadPool} has run.
     */
    public ThreadPool threadPool() {
        return threadPool;
    }

    /**
     * @param id The dictionary id
     * @return The (possibly shared, cached) {@link TermCorpus}, paired with the checksum it was built from
     * @throws IllegalStateException if called before {@link #setClient(Client)} - shouldn't be reachable in
     * practice, since injection completes during node startup, well before any rewrite chain is ever built
     * @throws ResourceNotFoundException if no such document exists
     * @throws OpenSearchException if the document exists but isn't a valid, checksummed {@code term_corpus}
     * document
     *
     * <p><b>Caution:</b> this blocks the calling thread on a {@code GET}. Never call this directly from inside a
     * {@code GET}/multi-get response callback (a thread from the small {@code get} pool) without first dispatching
     * onto {@link #threadPool()}{@code .generic()} - see the class javadoc's deadlock note.</p>
     */
    public LoadedTermCorpus load(final String id) {

        final Client currentClient = client;
        if (currentClient == null) {
            throw new IllegalStateException(
                    "DictionaryCorpusLoader has no Client yet - setClient(...) was not called before load(...)");
        }

        final String docId = TermCorpusConfigMapping.docId(id);
        final Map<String, Object> corpus = fetchCorpus(docId, currentClient);

        final String checksum = TermCorpusConfigMapping.getChecksum(corpus);
        if (checksum == null) {
            throw new OpenSearchException("Term corpus '" + docId + "' is missing a checksum");
        }

        final TermCorpus live = getLiveIfCurrent(docId, checksum);
        if (live != null) {
            return new LoadedTermCorpus(live, checksum);
        }

        return new LoadedTermCorpus(buildCoalesced(docId, checksum, corpus), checksum);
    }

    private TermCorpus getLiveIfCurrent(final String docId, final String checksum) {
        final CachedCorpus cached = cache.get(docId);
        if (cached == null || !cached.checksum.equals(checksum)) {
            return null;
        }
        return cached.ref.get();
    }

    private TermCorpus buildCoalesced(final String docId, final String checksum, final Map<String, Object> corpus) {

        final String buildKey = docId + '\0' + checksum;

        final CompletableFuture<TermCorpus> future = new CompletableFuture<>();
        final CompletableFuture<TermCorpus> inFlight = building.putIfAbsent(buildKey, future);
        if (inFlight != null) {
            return join(inFlight, docId);
        }

        try {
            final TermCorpus built = TermCorpusConfigMapping.buildTermCorpus(corpus);
            cache.put(docId, new CachedCorpus(checksum, built));
            future.complete(built);
            return built;
        } catch (final IOException e) {
            final OpenSearchException wrapped = new OpenSearchException("Could not parse term corpus '" + docId
                    + "'", e);
            future.completeExceptionally(wrapped);
            throw wrapped;
        } catch (final RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            building.remove(buildKey, future);
        }
    }

    private static Map<String, Object> fetchCorpus(final String docId, final Client client) {

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

        return TermCorpusConfigMapping.getCorpus(docId, source);
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
