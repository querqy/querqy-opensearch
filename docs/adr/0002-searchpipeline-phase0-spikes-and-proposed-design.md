# 2. SearchPipelinePlugin integration: Phase-0 spike findings and proposed design

Date: 2026-10-21

## Status

Accepted (Phase-0 spike findings below are complete and landed on branch
`spike179-searchpipeline-v2`, based on `issue179`). The Phase-3 design (new request processor,
new `QueryBuilder`) described here is **proposed, not yet implemented** - it is the next
candidate unit of work, not a decision to build without further review.

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
   configuration parameters"). Proven by `ResponseExtSpikeTest`.

3. **Decisive serialization spike: does a rewritten `querqy.model.ExpandedQuery` survive being
   shipped elsewhere and rebuilt?** Yes, for a representative case. A Common Rules rewrite
   exercising `SYNONYM`, `UP`, and `FILTER` together was run once (parse + rewrite only, no Lucene
   building); the resulting `ExpandedQuery` was serialized via a hand-rolled codec
   (`SpikeQuerqyModelCodec`), deserialized into a fully independent object graph, and then run
   through the existing `LuceneQueryBuilder` stage (`QueryParsingController.transformUserQuery`/
   `transformFilterQueries`/`getAdditiveQuerqyBoostQueries`, called directly, not through
   `process()`). The resulting main query, boost query, and filter query were all identical
   (`equals()` and `toString()`) to the no-round-trip baseline. Proven by
   `CutAtQuerqyModelSpikeTest`.
   - `querqy.model` classes turned out to be plain, engine-agnostic data: no Lucene objects, no
     suppliers, no `IndexReader`/`IndexSearcher` references anywhere in the types exercised
     (`Query`, `BooleanQuery`, `DisjunctionMaxQuery`, `Term`, `BoostQuery`, `MatchAllQuery`).
   - The parent back-pointers (`AbstractNode.getParent()`) are not an obstacle: every node type's
     existing constructor already takes its parent as an argument, so a serializer can write
     child-before-parent-exists and reconstruct top-down, passing each freshly-built parent into
     its children's constructors as it goes - exactly how parsers and rewriters already build
     these objects.
   - **Not covered by the spike codec** (a production serializer must cover these):
     `PhraseQuery`, `BoostedTerm`/`BoostedPhraseQuery`, every `RawQuery` subtype
     (`StringRawQuery`, `ParsedRawQuery`, `ParametrizedRawQuery`). Also, `Term#getValue()`'s
     concrete `ComparableCharSequence` subtype (e.g. `LowerCaseCharSequence`) is not preserved by
     the spike codec - only its character content is. Whether that specific loss matters needs
     checking against how `LuceneQueryBuilder`/`LuceneTermQueryBuilder` actually dispatch on it
     before a production serializer ships.
   - `QueryParsingController.process()` itself was *not* modified - this spike worked entirely
     through its existing public surface (`createExpandedQuery()`, the real `RewriteChain`'s
     `rewrite(...)`, the already-public `transformUserQuery`/`transformFilterQueries`/
     `getAdditiveQuerqyBoostQueries`), confirming the split point already exists without requiring
     a querqy-lucene change to prove feasibility. A real implementation would still want a
     purpose-built entry point there rather than relying on three separately-called public methods.

4. **Not yet run (blocked on an AWS account):**
   - Spike 1: does AWS's `create-package`/`describe-packages` validation accept a plugin
     implementing `SearchPlugin` + `SearchPipelinePlugin` and using `Client`, and what
     `descriptor.properties`/engine-version format does it actually require for 3.x?
   - Spike 2: under OpenSearch Security's fine-grained access control, does a `Client` call made
     from inside a `SearchRequestProcessor` run as the searching user's own identity, or as an
     elevated/internal one? This determines whether an ordinary, non-admin searching user (who
     has no explicit read grant on `.opensearch-querqy`) can trigger rewriter resolution through
     a pipeline at all.

## Proposed design for Phase 3 (not yet implemented)

Everything below is a concrete syntax proposal informed by the spikes above, meant to be
reviewed and refined before implementation starts - not a final interface.

### What does *not* change

The client-facing `querqy` query-DSL clause is unchanged - `matching_query`, `query_fields`,
`rewriters`, `tie_breaker`, `minimum_should_match`, `field_boost_model`, `info_logging` all mean
exactly what they mean today, and a request that never selects a search pipeline behaves exactly
as it does today (shard-side rewriting via `QuerqyQueryBuilder.doToQuery`, untouched). The new
processor is purely an alternative *when* and *where* rewriting happens, selected per-request via
`?search_pipeline=<id>` like any other search pipeline.

### The new request processor: `querqy_rewrite`

```json
PUT _search/pipeline/my-querqy-pipeline
{
  "request_processors": [
    {
      "querqy_rewrite": {
        "ignore_missing": false
      }
    }
  ]
}
```

- No rewriter/field configuration lives here - that would duplicate the query DSL's own
  `rewriters`/`query_fields` for no benefit, now that config distribution is solved independently
  of this processor (ADR 0001). The processor's config surface is deliberately minimal:
  - `ignore_missing` (boolean, default `false`): if no `querqy` clause is found anywhere in the
    request (see below), `false` fails the request with a clear error; `true` passes the request
    through unchanged. Mirrors the convention of ingest processors like `remove`'s
    `ignore_missing`.
- Standard processor keys (`tag`, `description`, `ignore_failure`) apply as usual and are stripped
  from this config object by core before `Factory.create` sees it (confirmed in the response-ext
  spike).

**Behavior:** `processRequest(SearchRequest)` looks for every `QuerqyQueryBuilder` instance in the
request's query tree, in exactly two places (not a general recursive search of arbitrary compound
queries - see "Known limitations" below):

1. The top-level query itself (`request.source().query()`).
2. Any entry of a `HybridQueryBuilder`'s sub-query list, if the top-level query is `hybrid`.

For each `QuerqyQueryBuilder` found, the processor:

1. Builds a `LuceneSearchEngineRequestAdapter`-equivalent wrapping the query's own
   `matching_query`/fields/etc. (as `DismaxSearchEngineRequestAdapter` does today), but with its
   rewriter resolution backed by `RewriterFactoryLoader.nodeLevel(...)` (via
   `SearchPipelinePlugin.Parameters.client`) instead of a shard-scoped context.
2. Runs `createExpandedQuery()` + the resolved `RewriteChain`'s `rewrite(...)` - parse and rewrite
   only, no Lucene building.
3. Replaces that `QuerqyQueryBuilder` instance, in place, with a new `querqy_rewritten`
   `QueryBuilder` (below) carrying the rewritten `ExpandedQuery` plus the settings the shard-side
   `LuceneQueryBuilder` stage still needs (`query_fields`, `tie_breaker`, `minimum_should_match`,
   `field_boost_model`, similarity scoring).
4. Extracts the rewrite's filter queries. If the enclosing query is `hybrid`, pushes them onto
   `HybridQueryBuilder.filter(...)` (constraining every leg, vector/kNN included - the concern
   raised earlier in this work and specifically covered by the spike's `FILTER` case); otherwise
   wraps the top-level query in a filtering `bool` the same way core's own `filter_query` processor
   does.
5. Stashes rewrite-chain logging/decoration data (`DECORATE`, rewriter debug output) on the
   `PipelineProcessingContext` for a paired `querqy_rewrite` **response** processor to pick up and
   attach to the response's `ext` section (per the response-ext spike) - that response processor
   is a separate, not-yet-designed piece of this same proposal.

#### Request shape - without hybrid search

Client request (unchanged from today):

```json
GET /my-index/_search?search_pipeline=my-querqy-pipeline
{
  "query": {
    "querqy": {
      "matching_query": { "query": "a" },
      "query_fields": ["title^2", "description"],
      "rewriters": ["common_rules"]
    }
  }
}
```

What the request becomes internally, after `querqy_rewrite` runs and before shard fan-out (never
visible to the client as JSON - see "The `querqy_rewritten` QueryBuilder" below for why):

```
query: querqy_rewritten {
  expanded_query: <serialized ExpandedQuery, already rewritten by "common_rules">
  query_fields: ["title^2", "description"]
}
```

#### Request shape - with hybrid search

Client request:

```json
GET /my-index/_search?search_pipeline=my-querqy-pipeline
{
  "query": {
    "hybrid": {
      "queries": [
        {
          "querqy": {
            "matching_query": { "query": "a" },
            "query_fields": ["title^2", "description"],
            "rewriters": ["common_rules"]
          }
        },
        {
          "neural": {
            "embedding_field": { "query_text": "a", "model_id": "my-model", "k": 10 }
          }
        }
      ]
    }
  }
}
```

Internally, after `querqy_rewrite` runs (assuming the rewrite chain produced a `FILTER` query,
e.g. `category:shoes`):

```
query: hybrid {
  queries: [
    querqy_rewritten { expanded_query: <...>, query_fields: [...] },
    neural { embedding_field: { query_text: "a", model_id: "my-model", k: 10 } }
  ]
  filter: [ term { category: "shoes" } ]
}
```

The neural/kNN leg is untouched other than now sitting behind the same filter the lexical leg's
rewrite produced - this is the entire point of running the rewrite on the coordinator instead of
per-shard inside the lexical leg's own `doToQuery`.

### The `querqy_rewritten` QueryBuilder

A new `QueryBuilder`, registered via `SearchPlugin.getQueries()` exactly like `querqy` is today,
but **never meant to be authored directly by a client** - the only way an instance is ever
created is the `querqy_rewrite` processor building one in Java and placing it into the
`SearchSourceBuilder`. Its `doToQuery(QueryShardContext)` runs *only* the existing
`LuceneQueryBuilder` stage against the already-rewritten model - no parsing, no rewrite chain, no
`RewriterFactoryLoader`/`.opensearch-querqy` access at all on the shard for this query type.

Conceptual shape (binary `Writeable` wire format, not a JSON query-DSL clause a client would ever
write):

```
querqy_rewritten {
  model_version: <int>                      // explicit version; see "Consequences" below
  expanded_query: <ExpandedQuery, via a production querqy.model serializer>
  query_fields: [<field^boost>, ...]
  tie_breaker: <float, optional>
  minimum_should_match: <string, optional>
  field_boost_model: <string, optional>
  similarity_scoring: <string, optional>
}
```

- **`fromXContent` is unsupported, deliberately.** `SearchPlugin.getQueries()`'s `QuerySpec`
  requires a parser function to register the query type at all, but this one throws a clear
  `ParsingException` ("`querqy_rewritten` cannot be specified directly in a query; it is produced
  internally by the `querqy_rewrite` search pipeline processor") rather than attempting to accept
  arbitrary client input - accepting a pre-rewritten model from an untrusted client would bypass
  the rewrite chain entirely, which is a correctness and (likely) security boundary, not just a
  style choice.
- **`doXContent` exists only for introspection** (`explain`, profiling, debug logging) and emits a
  human-readable summary (the rewritten query's string form, which rewriters were applied, the
  settings in effect) - not a byte-exact, re-parseable representation of `expanded_query`.
- **`doWriteTo`/the `StreamInput` constructor carry the real payload** (the serialized
  `ExpandedQuery` plus settings), since that is the only path this query type actually travels:
  coordinator → shard, inside the normal `ShardSearchRequest` binary serialization, the same
  mechanism `QuerqyQueryBuilder` already uses today.

## Consequences

- The spikes de-risk the central technical question (decision 5, "option B") for a representative
  rewrite shape; they do not yet prove full coverage (phrase/boosted-term/raw queries), nor do
  they address AWS-specific validation/identity questions (spikes 1-2), nor word-break-style
  rewriters that need shard-local data (explicitly deferred to a later dictionary-based phase per
  the briefing's decision 6).
- A production `querqy.model` serializer needs an explicit version field and a documented policy
  for what happens on a rolling upgrade or mixed-version cluster (an old node receiving a new
  version's serialized model, or vice versa) - the spike codec has no such versioning since it
  never leaves a single test's memory space.
- The `querqy_rewrite` response processor (decorations/logging into the response `ext`) is named
  and motivated here but not designed in this round - it is the natural next piece once the
  request-processor/`querqy_rewritten` design above is reviewed.
- Not addressed in this round: spikes 1-2 (need an AWS account), a production `querqy.model`
  serializer, the response processor's design, and whether `querqy_rewritten` needs to support
  `visit(QueryBuilderVisitor)` meaningfully for other processors that might wrap it (e.g. a
  `filter_query` processor running after `querqy_rewrite` in the same pipeline).
