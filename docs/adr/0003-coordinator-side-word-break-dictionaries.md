# 3. Coordinator-side word-break dictionaries

Date: 2026-10-30

## Status

Accepted and implemented.

## Context

The `querqy_rewrite` search pipeline request processor (ADR 0002) runs the rewrite chain once, on
the coordinating node, before any shard is involved. `RewriterFactoryLoader.nodeLevel(...)`
enforces `OpenSearchRewriterFactory#isSupportedOnCoordinator()` for every rewriter used there.
`WordBreakCompoundRewriterFactory` was the one built-in rewriter that returned `false`: its
`createRewriterFactory` builds a `LuceneTermCorpus` directly from a live shard's `IndexReader`,
which does not exist on the coordinator. Word-break-compound was therefore hard-blocked from the
pipeline.

querqy-core (4.3.0) already ships two `TermCorpus` implementations that need no index at all:
`TsvDfTermCorpus` (`term\tdocFreq`) and `TsvDfCoocTermCorpus` (`term\tdocFreq\tbloomHex`, which also
supports collation). Nothing in querqy-opensearch wired them up. The
`querqy-opensearch-core-briefing.md` (decision 6) had already sketched a design for this: one
immutable document per dictionary revision, gzip-compressed, referenced by name+revision from the
rewriter config. This ADR records the design actually implemented, which follows that sketch with
two adjustments made after further review:

- The briefing's original sketch called for *asynchronous* dictionary loading with a *fail-open*
  fallback (skip the rewriter, log), specifically to avoid blocking `SearchPipelinePlugin.Factory
  #create`. That concern doesn't apply here: dictionary resolution happens inside
  `RewriterFactoryLoader.buildFactoryAndLogging`, which already does a blocking `Client` `GET` to
  load the rewriter's own config - on a request thread, not the cluster-applier thread, and
  identically for the per-shard and node-level paths. A dictionary load fits the same model: it is
  synchronous, and a missing/malformed dictionary fails the request clearly, the same way every
  other bad rewriter configuration already does. No separate fail-open path was introduced.
- Dictionaries are shared, process-wide state rather than something tied to one rewriter's config,
  so the loader is a node-level singleton with weak-reference caching (see below), not something
  built fresh for the asynchronous path alone.

## Decision

### Document format

A new document type, `term_corpus`, in `.opensearch-querqy`, id `<name>-<revision>` (both
user-supplied, uninterpreted):

```json
{
  "type": "term_corpus",
  "version": 1,
  "name": "dutch-compounds",
  "revision": "2026-01",
  "saved_at": "2026-10-30T12:00:00Z",
  "corpus": "{\"format\":\"tsv_df\",\"gzip\":true,\"numDocs\":409000,\"data\":\"<base64>\"}"
}
```

`corpus` is a single JSON-string-valued field (like the rewriter document's `config_v_005`), so a
write-time field mapper (`QuerqyTermCorpusFieldMapper`, content type `querqy_term_corpus`) can see
`format`/`gzip`/`numDocs`/`hashFunctions`/`data` together and actually build the `TermCorpus` it
describes (`TsvDfTermCorpus` or `TsvDfCoocTermCorpus`, dispatched on `format`) to validate it -
rejecting the write if the content doesn't decompress/parse as declared. This also happens to
validate per-line content a human wouldn't otherwise check before saving: a `tsv_df_cooc`
dictionary's bloom-filter hex values are validated by `BloomFilter.fromHex` as a side effect of
actually building the corpus (e.g. a hex value whose length isn't a multiple of 16 is rejected at
write time, not discovered later at load time).

Unlike `config_v_005` - whose version suffix exists only because of real migration history (it
started as plain `config`, and only got a version baked into the field name once a later,
incompatible format needed to coexist with documents already written under the old one) - this is
a brand-new document type with no such history, so the field is named plainly (`corpus`), and the
top-level `version` integer tracks the document schema generation instead. A future incompatible
change mints `corpus_v2` and bumps `version`, the same way the rewriter document's own history did.

`gzip` is a flag, not an assumption: the CLI (`put-dictionary`, below) always gzips, but a small
dictionary can be written directly, uncompressed, via a raw `PUT` with `gzip: false` - the payload
is always base64-transported either way (an ordinary property of a binary-shaped JSON field), only
the extra gunzip step is conditional.

`dictionaryField` (shard-side, live Lucene index stats) and a new `dictionary: {name, revision}`
config block (this document type) are **mutually exclusive** on `WordBreakCompoundRewriterFactory`.
`isSupportedOnCoordinator()` became instance-state-dependent: `true` iff `dictionary` is configured,
preserving today's behavior (`false`) for existing shard-field configs.

### Loading and caching: a node-level, weak-reference singleton

`DictionaryCorpusLoader` is constructed once by `QuerqyCorePlugin` and shared by every
`RewriterFactoryLoader` on the node - every per-shard one (via `RewriterShardContext`) and the
node-level/pipeline one - rather than one per loader instance. This solves two problems at once:

1. **No duplication.** The same `(name, revision)` dictionary, however many rewriters/shards/
   pipelines reference it, is parsed and held in memory at most once per node.
2. **No unbounded growth, no manual invalidation.** Cached by doc id as a `WeakReference<TermCorpus>`.
   A dictionary's only strong owners are the `RewriterFactory` instances built from it, which live
   inside each `RewriterFactoryLoader`'s own cache. As long as any of those caches still holds a
   live entry built from this dictionary, it stays strongly reachable and the loader keeps
   returning the shared instance. Once every such entry has been evicted or replaced (reconfigured
   to a new revision, cache cleared, normal rewriter-cache expiry) and garbage-collected, the weak
   reference clears itself automatically - memory reclaimed, tied to actual need, no reference
   counting.

Dictionary documents are immutable by convention (a change is a new `(name, revision)`, not an
in-place update), so unlike `RewriterFactoryLoader` this loader never refreshes an already-cached
entry. It does coalesce concurrent cold loads of the same doc id (e.g. many shards, or many
pipeline requests, asking for a brand-new dictionary at the same moment) so they don't all fetch,
gunzip and parse the same multi-MB file in parallel.

`OpenSearchRewriterFactory` gained a new method, defaulted to delegate to the existing one, so the
other four built-ins and any third-party SPI factory are unaffected:

```java
public RewriterFactory createRewriterFactory(IndexShard indexShard,
        DictionaryCorpusLoader dictionaryCorpusLoader, Client client) throws OpenSearchException {
    return createRewriterFactory(indexShard);
}
```

`WordBreakCompoundRewriterFactory` overrides it: when `dictionary` is configured, it resolves the
corpus once here (not per rewrite) and builds the rewriter directly from it, ignoring `indexShard`
entirely - so the same config also works in the classic per-shard `querqy` query, not only the
pipeline.

### Write tooling

The `tool` module (already the AWS-compatible, `ActionPlugin`-free way to write rewriter configs
over HTTP) gained a `put-dictionary` command: gzips a local TSV file and writes it as a
`term_corpus` document, reusing the same `HttpQuerqyIndexManager`/mapping-migration path as
`put`. Writing directly via a raw `PUT <index>/_doc/<name>-<revision>` (no tooling at all) remains
possible and is how a small, uncompressed (`gzip: false`) dictionary would typically be written by
hand.

## Consequences

- Word-break-compound is now usable from the `querqy_rewrite` search pipeline when configured with
  a `dictionary` reference - the one built-in rewriter that previously could not run there at all.
- A dictionary is a static snapshot: it does not reflect live index content the way
  `dictionaryField` does. Operators who need frequency statistics derived from their own, changing
  index content must still rebuild and write a new revision themselves; this is unchanged from the
  briefing's original plan and is not addressed here.
- `verifyDecompoundCollation` continues to require a collation-capable corpus (`tsv_df_cooc` via a
  `dictionary` reference, or the Lucene-backed path); a `tsv_df` dictionary does not support it -
  unchanged from the existing `TermCorpus#isCollationSupported()` contract.
