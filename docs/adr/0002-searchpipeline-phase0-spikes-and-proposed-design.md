# 2. SearchPipelinePlugin integration: spike findings and implemented design

Date: 2026-10-21 (spikes); updated 2026-10-22 (implementation landed)

## Status

Accepted and implemented on branch `spike179-searchpipeline-v2` (based on `issue179`):
`querqy_rewrite` request and response processors, registered by `QuerqyCorePlugin`. This ADR's
original Phase-0 section (spike findings) is unchanged below; the design section has been
rewritten to describe what was actually built, which differs from the original proposal in one
significant way - see "Design revision" below.

Not yet implemented/verified: AWS spikes 1-2 (need an AWS account; tracked separately), and real
`HybridQueryBuilder` validation (neural-search isn't available as a build/test dependency - see
"Testing" below).

## Context

ADR 0001 made `querqy-opensearch` installable on Amazon OpenSearch Service by moving rewriter
*configuration storage* out of `ActionPlugin`. That is a closed problem; this ADR is not about
it. What remains - adding Querqy as a `SearchPipelinePlugin` processor - is motivated by two
things the existing shard-side `querqy` `QueryBuilder` structurally cannot do, per the briefing
(`/Users/rene/Developer/projects/querqy/querqy-opensearch-core-briefing.md`, decision 5):

1. **Hybrid query visibility.** `doToQuery(QueryShardContext)` runs per shard, after the
   coordinator has already fanned the request out - it cannot see or restructure a `neural-search`
   `hybrid` query's other legs, and in particular cannot push Querqy-produced **filter** queries
   onto the vector/kNN leg the way `HybridQueryBuilder.filter(...)` pushes a filter onto every
   sub-query.
2. **Response-level decorations.** `QuerqyProcessor.parseQuery` returns a bare Lucene `Query`;
   there is no hook back into the `SearchResponse` for Common Rules' `DECORATE` output or
   rewriter debug/logging data (today simply commented out with a TODO).

The briefing's decision 5 ("cut at the Querqy model") proposes running the full rewrite chain
**once, on the coordinator**, and shipping the *rewritten* `querqy.model.ExpandedQuery` to each
shard, where only the existing `LuceneQueryBuilder` stage (shard analyzers,
`DocumentFrequencyCorrection`/`DependentTermQuery`, field boost models) still runs - unchanged,
with real per-shard document frequencies, nothing faked. The briefing was explicit that this is
contingent on a decisive spike: is `querqy.model` actually serializable across that cut? This
round of work ran that spike, plus the two spikes it depends on.

## Decision: Phase-0 spike findings

1. **Node-level rewriter resolution (prerequisite, implemented).** The existing
   `RewriterShardContext` is keyed by `ShardId` and resolved via `IndicesService` - unusable from
   a coordinator with no local shard of the target index. `createRewriterFactory(IndexShard)` is
   unused-in-body by every built-in rewriter factory (confirmed by reading all five), so the
   loading/caching/refresh logic was extracted into `RewriterFactoryLoader`, parameterized by a
   *nullable* `IndexShard` supplier. `RewriterShardContext` now delegates to it, supplying its real
   shard (zero behavior change, confirmed by the existing test suite passing unchanged);
   `RewriterFactoryLoader.nodeLevel(Settings, Client, boolean)` supplies none, for coordinator-side
   use. Proven by `RewriterFactoryLoaderTest`, which loads and builds a real rewriter factory with
   no `IndexShard`/`IndexService` anywhere in reach.
   - `RewriterFactoryLoader.nodeLevel(...)` also enforces a new `OpenSearchRewriterFactory
     #isSupportedOnCoordinator()` check (default `true`): `WordBreakCompoundRewriterFactory`
     overrides it to `false`, since its rewrite (not just its factory build) needs a real
     shard-backed `IndexSearcher`/`LuceneTermCorpus` that genuinely does not exist on a
     coordinator. A rewriter that fails this check fails the whole request clearly
     (`IllegalArgumentException` naming the rewriter and class), rather than being silently
     skipped or failing confusingly later - a deliberate choice: silently producing a *different*
     result set than the per-shard path would for the same rewriter is worse than a clear error.

2. **Response `ext` spike: can a response processor add data to a `SearchResponse`?** Yes.
   A throwaway `SearchResponseProcessor` (`SpikeResponseProcessor`) rebuilds the response with a
   custom `SearchExtBuilder` attached - there is no in-place mutator for a response's ext-builder
   list, so the processor reconstructs a new `InternalSearchResponse`/`SearchResponse` copying
   every other section across, mirroring the pattern core's own (undocumented-for-this-purpose)
   `SearchResponseUtil.replaceHits` uses for replacing hits. The added data survives, typed, back
   to the client (`response.getInternalResponse().getSearchExtBuilders()`). Incidental finding:
   a processor's config `Factory` must consume keys via `ConfigurationUtils`'s remove-based
   readers (e.g. `readStringProperty`) - a plain `Map.get`/`getOrDefault` leaves the key present,
   and core rejects the pipeline `PUT` outright ("doesn't support one or more provided
   configuration parameters"). Proven by `ResponseExtSpikeTest`; the same rebuild pattern is what
   the real `QuerqyRewriteResponseProcessor` uses (see below).

3. **Decisive serialization spike: does a rewritten `querqy.model.ExpandedQuery` survive being
   shipped elsewhere and rebuilt?** Yes, for a representative case, and this was since extended to
   production coverage (see "Production serializer" below). A Common Rules rewrite exercising
   `SYNONYM`, `UP`, and `FILTER` together was run once (parse + rewrite only, no Lucene building);
   the resulting `ExpandedQuery` was serialized, deserialized into a fully independent object
   graph, and then run through the existing `LuceneQueryBuilder` stage. The resulting main query,
   boost query, and filter query were all identical (`equals()` and `toString()`) to the
   no-round-trip baseline.
   - `querqy.model` classes turned out to be plain, engine-agnostic data: no Lucene objects, no
     suppliers, no `IndexReader`/`IndexSearcher` references anywhere in the types exercised.
   - The parent back-pointers (`AbstractNode.getParent()`) are not an obstacle: every node type's
     existing constructor already takes its parent as an argument, so a serializer can write
     child-before-parent-exists and reconstruct top-down, passing each freshly-built parent into
     its children's constructors as it goes - exactly how parsers and rewriters already build
     these objects.
   - This spike led to a querqy-lucene change: `QueryParsingController` gained a public
     `buildFromRewrittenQuery(QuerqyQuery<?> originalUserQuery, ExpandedQuery rewrittenExpandedQuery)`
     method (alongside the existing `process()`), so a caller splitting parse+rewrite from Lucene
     building no longer has to call three separately-exposed internal methods directly. Note the
     `originalUserQuery` parameter: request-level boosts (e.g. Solr's `bq`/`bf`/`boost`-style
     additive/multiplicative boosts) are defined in terms of what the user actually typed, not of
     whatever the rewrite chain turned it into - so the *unrewritten* user query must be preserved
     and threaded through alongside the rewritten model, not derived from it after the fact.

4. **Mutation safety, explicitly verified** (a concern flagged during this work, not assumed): is
   it safe to find a `QuerqyQueryBuilder` via `visit()` and mutate it in place, given it might be
   used as a cache key somewhere? Verified against OpenSearch 3.7.0 source: the shard request
   cache (`IndicesRequestCache.Key`) hashes/equals a `BytesReference` - a byte-level digest of an
   already-serialized `ShardSearchRequest`, computed shard-side, strictly after the coordinator's
   pipeline-processor phase and query rewrite phase have both already run and the request has
   already been dispatched. `AbstractQueryBuilder.hashCode()/equals()` are never used as a
   core-level `HashMap`/`HashSet` key anywhere in the coordinator-side request path. For local
   shards, the *same* live object is read by a shard-execution thread without re-serialization,
   but the `ActionListener`-chain handoff between "pipeline processor mutates" and "shard dispatch
   reads" is a JLS synchronization action, so this is happens-before safe. The one real constraint
   this imposes: a request processor must fully finish all mutation before signaling its own
   completion - satisfied here, since `QuerqyRewriteRequestProcessor.processRequest` mutates every
   found query synchronously before returning.

5. **Search templates compose with search pipelines for free.** A `_search/template` request
   renders into the same `SearchRequest` object a plain `_search` call builds and dispatches
   through the identical `TransportSearchAction` → `SearchPipelineService.resolvePipeline(...)`
   path - zero special-casing needed. `search_pipeline` reaches it via the URL param (works since
   search pipelines shipped) and via a body field (OpenSearch 3.2.0+). Existing client templates
   that write a `querqy` clause with Mustache variables don't need to change shape at all - they
   just add `search_pipeline` to route through `querqy_rewrite`.

6. **Not yet run (blocked on an AWS account):**
   - Spike 1: does AWS's `create-package`/`describe-packages` validation accept a plugin
     implementing `SearchPlugin` + `SearchPipelinePlugin` and using `Client`, and what
     `descriptor.properties`/engine-version format does it actually require for 3.x?
   - Spike 2: under OpenSearch Security's fine-grained access control, does a `Client` call made
     from inside a `SearchRequestProcessor` run as the searching user's own identity, or as an
     elevated/internal one? This determines whether an ordinary, non-admin searching user (who
     has no explicit read grant on `.opensearch-querqy`) can trigger rewriter resolution through
     a pipeline at all.

## Design revision (supersedes this ADR's original "new `querqy_rewritten` QueryBuilder" proposal)

The original proposal below this point (still visible in this branch's history) had the
`querqy_rewrite` processor **replace** a found `QuerqyQueryBuilder` with a new, separate
`querqy_rewritten` `QueryBuilder` type carrying the rewritten model. Given the mutation-safety
finding above, and a second finding - any `QueryBuilder` passed to `QueryBuilder#filter(...)` is
converted to Lucene form later, per shard, via its own `toQuery(...)`, regardless of which leg
holds it, so a filter carrier needs no new querqy-model-to-OpenSearch-query translator - that
turned out to be unnecessary complexity. The implemented design instead:

- **Mutates the found `QuerqyQueryBuilder` instance itself, in place.** `QuerqyQueryBuilder`
  gained two fields - `originalUserQuery`/`rewrittenExpandedQuery` - and a setter,
  `setPreRewritten(QuerqyQuery<?>, ExpandedQuery)`, called only programmatically by
  `QuerqyRewriteRequestProcessor` on an instance it found via `QueryBuilder.visit
  (QueryBuilderVisitor)` (never wired into `PARSER`/XContent parsing anywhere - a client can never
  supply a pre-rewritten model directly and bypass the rewrite chain; the security boundary ADR
  0001/this ADR wanted is achieved by omission from the parser, not by a separate unparseable
  type). `doWriteTo`/the `StreamInput` constructor read/write these two fields (guarded by a
  presence boolean) exactly like every other field, so the mutation survives normal cross-node
  serialization. `doToQuery(QueryShardContext)` branches: if `rewrittenExpandedQuery != null`, it
  calls `QuerqyProcessor.buildFromRewrittenQuery(...)` (only the Lucene-building stage, via
  querqy-lucene's new `QueryParsingController#buildFromRewrittenQuery`); otherwise it falls
  through to exactly what it does today, unchanged. `doEquals`/`doHashCode` deliberately do **not**
  incorporate these fields (confirmed safe by the mutation-safety finding - nothing hashes this
  object before the mutation happens anyway).
- **Filter-query pushdown needs no hybrid-specific code and no new translator.** For each found
  `QuerqyQueryBuilder` that produced filter queries after rewriting, the request processor builds
  a *second*, fresh `QuerqyQueryBuilder` - pre-rewritten via the same mechanism, with a placeholder
  `MatchAllQuery` as its "main" query (required since `ExpandedQuery`'s constructor rejects a null
  user query) and the produced filter queries as its filters, copying the originating query's own
  `query_fields` (a field-less filter term is only resolved against query fields at the shard-side
  Lucene-building stage, using whichever builder instance it is attached to - so the wrapper must
  carry the *originating* query's fields, not the processor's own, and not some merge across every
  `querqy` clause found in the request, built once per originating query for exactly this reason).
  This wrapper is handed to `topLevelQuery.filter(wrapper)` - **one call**, on the request's actual
  top-level query, relying entirely on `QueryBuilder#filter(QueryBuilder)`'s existing polymorphic
  dispatch: `BoolQueryBuilder` appends it to its own filter clauses in place (ANDing across every
  `should`/`must` clause); a plain query gets wrapped in a new outer `BoolQueryBuilder`; and (per
  the same mechanism, untested here - see "Testing" below) `HybridQueryBuilder` pushes it onto
  every sub-query/leg in place, which is exactly the recall-preserving, pre-filter-the-ANN-search
  behavior the briefing's decision 5 wanted. Note core's own shipped `filter_query` search-pipeline
  processor does *not* use this polymorphic path - it unconditionally wraps the query in a new
  outer `bool` (a post-filter) - so this per-leg behavior is new, not something already available.
- **Discovery is fully generic.** `QuerqyRewriteRequestProcessor.findQuerqyQueries` walks the
  entire query tree via `QueryBuilder.visit(QueryBuilderVisitor)`, collecting every live
  `QuerqyQueryBuilder` reference at any nesting depth - not special-cased to "top level, or one
  level inside a `hybrid`" as originally proposed. `visit()` hands back the live object sitting in
  the tree (not a copy), which is what makes in-place mutation possible at all.

This removes an entire class (`querqy_rewritten`), its XContent-rejection story, and its
registration from the implemented design, while keeping every actual requirement the original
proposal had (security boundary, shard-side stage reuse, backward compatibility for non-pipeline
requests).

## The `querqy_rewrite` request processor (implemented)

```json
PUT _search/pipeline/my-querqy-pipeline
{
  "request_processors": [
    { "querqy_rewrite": { "ignore_missing": false } }
  ]
}
```

- `ignore_missing` (boolean, default `false`): if no `querqy` clause is found anywhere in the
  request, `false` fails the request with a clear error; `true` passes the request through
  unchanged. No rewriter/field configuration lives on the processor itself - that would duplicate
  the query DSL's own `rewriters`/`query_fields` for no benefit.

For each found `QuerqyQueryBuilder` with a non-empty `rewriters` list: resolves the rewrite chain
via a `RewriterFactoryLoader.nodeLevel(...)` shared across all pipelines on the node (built once
by `QuerqyRewriteRequestProcessor.Factory`); runs `createExpandedQuery()` + the chain's
`rewrite(...)` against a new `CoordinatorSearchEngineRequestAdapter` (a `LuceneSearchEngineRequestAdapter`
with no shard access at all - pure-data methods delegate straight to the query builder; the
later, shard-only Lucene-building-stage methods throw a clear `UnsupportedOperationException`);
calls `setPreRewritten(...)` on the found instance; pushes any produced filter queries via the
wrapper mechanism above; and captures `DecorateInstruction.DECORATION_CONTEXT_KEY`/
`_MAP_KEY` data plus the rewrite's `RewriteChainLog` (always captured in full detail - the
adapter builds its own always-active `RewriteLoggingConfig`, independent of the query's own
`info_logging` field and of any rewriter's stored sink config, which only gate the older,
unrelated `Log4jSink` delivery mechanism) into a `QuerqyRewriteCapture`, stashed as a list on the
request's `PipelineProcessingContext` for the response processor to read back. A shard-dependent
rewriter (e.g. word-break-compound) causes `RewriterFactoryLoader` to throw
`IllegalArgumentException`, left to propagate - the request fails clearly rather than silently
producing a different result than the per-shard path would.

## The `querqy_rewrite` response processor (implemented)

```json
{
  "response_processors": [
    { "querqy_rewrite": { "info_logging": { "type": "detail" } } }
  ]
}
```

Reads the stashed `QuerqyRewriteCapture` list and, unless everything resolves to "nothing to
report" (default: quiet), attaches a `QuerqyExtBuilder` under `ext.querqy` - combining every
found query's `RewriteChainLog` entries (field names verbatim - `rewriteChain`, `rewriterId`,
`actions`, ... - matching the modern `debug.querqy.rewrite` convention, serialized generically via
the `JsonProvider` SPI rather than a hand-built typed `Writeable`, since the wrapped data's own
shape already is the schema) plus Common Rules DECORATE output (`decorations`,
`decorationsByKey`), modeled on the `searchnik/querqy-opensearch@querqy-debug-response` branch's
field shape so existing tooling built against it keeps working.

**On/off control, two levels:** the response processor's own `info_logging` config (same shape as
the query-DSL field) is the pipeline-level default (`"none"` if omitted - safe by default); a
request's own `querqy` query carrying its own `info_logging` field overrides that default for that
one query specifically (captured per-query by the request processor as
`QuerqyRewriteCapture#getPerQueryPayloadTypeOverride()` - presence, even an explicit `"none"`,
always wins over the pipeline default). DECORATE output is never gated by this - it is emitted
whenever present, since it is real decoration data for the application to use, not a debug
concern. When the resolved level is `"none"` for every captured query and no decorations are
present anywhere, no `ext.querqy` section is attached at all, rather than an empty one.

Since there is no in-place mutator for a `SearchResponse`'s ext builders, attaching one rebuilds
the response's sections wholesale - the same `SpikeResponseProcessor`/`ResponseExtSpikeTest`
pattern from the Phase-0 spike.

## Testing

`neural-search` isn't available as a build or test dependency (not published to Maven Central,
plugin-zip only). The request-processor tests exercise the generic `visit()` + `.filter()`
mechanism against a `BoolQueryBuilder`-nested `querqy` clause as the dependency-free stand-in for
a hybrid query's multi-leg structure - validating that the pushed-down filter reaches the *whole*
top-level query, not just the `querqy` clause's own subtree, which is the generic mechanism a real
`HybridQueryBuilder` would also exercise via its own `filter(...)` override. Real
`HybridQueryBuilder` validation is a manual/external step once a neural-search-enabled cluster is
available, not something this repo's own automated suite can cover.

## Consequences

- `querqy-lucene` gained one new public method (`QueryParsingController#buildFromRewrittenQuery`)
  on a new branch (`querqy-rewrite-search-pipeline`), not yet released - `querqy-opensearch`
  currently depends on a local Maven SNAPSHOT build of it (`core/build.gradle`'s `mavenLocal()`
  repository addition) until that change is released.
- Not addressed in this round: spikes 1-2 (need an AWS account), real `HybridQueryBuilder`
  end-to-end validation (needs a neural-search-enabled cluster), and word-break-style rewriters
  running on the coordinator at all (explicitly out of scope - they fail clearly rather than being
  supported, per finding 1 above; a dictionary-based approach for these is a later phase per the
  briefing's decision 6).
- A production `querqy.model` serializer (`ExpandedQueryWriteable`) now covers every concrete node
  type reachable from the built-in rewriters, with an explicit version field, and raises a clear,
  documented exception for `ParsedRawQuery` (generic, unbounded payload - no concrete subclass
  exists anywhere in querqy-core; an SPI extension point for third parties, deliberately
  unsupported rather than guessed at).
