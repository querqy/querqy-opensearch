# 5. Mutable, checksum-verified word-break dictionaries

Date: 2026-11-02

## Status

Accepted and implemented. Supersedes the document-format and immutability sections of ADR 0003
("Coordinator-side word-break dictionaries") - the loading/caching architecture (node-level
`DictionaryCorpusLoader` singleton, weak-reference sharing) and the rest of ADR 0003 are unaffected
and still apply.

## Context

ADR 0003 introduced `term_corpus` documents addressed by `(name, revision)`, designed around an
"immutable by convention" assumption: a dictionary's content was never supposed to change once
written. Nothing actually enforced that. A plain `PUT <index>/_doc/<id>` - the only write path that
exists, used identically by the CLI and any direct write - always uses the default `index` op type,
silently overwriting. `QuerqyTermCorpusFieldMapper` validated a dictionary's content shape at write
time but had no notion of "does this match what was here before," and `DictionaryCorpusLoader`
cached a loaded `TermCorpus` forever, by design, with no refresh.

Had someone overwritten a dictionary document, the result would have been silent, undetectable,
permanent divergence across nodes: a node that already had a rewriter built from the old content
would never notice the change, while a node loading it fresh would get the new content - with no
error, no log, and no way to tell the two states apart.

Considered and rejected:
- **Enforce immutability** via `op_type=create` on the write path. This only protects writes that
  go through code choosing to set it; a raw write using the default `index` op type - which nothing
  can distinguish from a legitimate first write at the index/mapper/engine level - still silently
  overwrites. OpenSearch's role-based permissions don't help either: `create` and `index` resolve to
  the same transport action, so there's no separate permission to grant one but not the other.
- **A server-computed checksum**, so the write-time payload wouldn't need to include one itself.
  Traced the actual `DocumentParser`/`SourceFieldMapper` parsing order: `_source` is captured and
  finalized by `SourceFieldMapper.preParse` - a `MetadataFieldMapper` hook - *before* any regular
  field mapper (including ours) ever parses anything. A field mapper has no way to inject a computed
  value into `_source`, by construction, not just by missing API surface. The only server-side way to
  derive a persisted field before indexing is an Ingest `Processor` (needs `IngestPlugin`, and
  wouldn't remove the need for the mapper's own content validation) or a follow-up `UpdateRequest`
  (racy, non-atomic, two round trips).
- **Nested documents** holding multiple versions (append a new nested version instead of overwriting,
  preserving full history within one parent document addressed by one stable id). Rejected: a nested
  array is still part of the single JSON body of one write request - the same `_source`-finalized-
  before-parsing constraint applies identically, so this doesn't solve the checksum-injection problem
  either. It's also a poor mechanical fit for versioning specifically: there is no "append one element"
  write at the document-storage level - adding a version means reindexing the *entire* document,
  including every prior version's full payload (Lucene documents, nested children included, are
  immutable once written; an "update" is always delete-and-rewrite-everything). For a dictionary that
  can be multi-MB, that means unbounded document growth and O(n²) cumulative write cost across n
  versions, plus no cheap single-version read - a plain `GET` would return every historical version's
  full payload every time, not just the current one.

## Decision

### Drop `revision`; a dictionary is addressed by a single, mutable `id`

Updating a dictionary's content in place (same id, new content) is now the supported way to change
it. An old version that needs to stay addressable is saved under a different id and the referencing
rewriter config is repointed - a deliberate, explicit action, not something the system does
automatically.

### A server-verified checksum, inside the `corpus` blob, writer-supplied

The write-time payload (`corpus`, the existing single JSON-string field `QuerqyTermCorpusFieldMapper`
already owns and fully parses) gains a `checksum` key: `"sha256:<lowercase-hex>"`, computed by the
writer over the raw, uncompressed TSV bytes (so gzip settings never affect it). `
QuerqyTermCorpusFieldMapper.validate()` - which already decodes/decompresses the content to build a
`TermCorpus` for validation - additionally recomputes the same hash over those same bytes and rejects
the write (same `MapperParsingException` pattern as the existing malformed-TSV/bad-gzip/bad-bloom-hex
checks) if it's missing or doesn't match. `TermCorpusConfigMapping.computeChecksum(byte[])` is the
single shared implementation, called identically by the CLI write path and the mapper's verification
path, so the two can never disagree on algorithm or encoding. Checksum lives inside `corpus` rather
than as a new sibling document field specifically so there is never a second field that could drift
out of sync with it.

### `DictionaryCorpusLoader` becomes self-verifying

`load(id)` (previously `load(name, revision)`) now always does one `GET` - cheap relative to how
rarely it runs: it's only ever called once per rewriter-factory rebuild (via
`WordBreakCompoundRewriterFactory#createRewriterFactory`, itself only called from
`RewriterFactoryLoader#buildFactoryAndLogging`), never once per query - compares the live document's
checksum against what the cached (weakly-referenced) `TermCorpus` was built from, and only
re-decodes/re-parses when it changed. Returns a `LoadedTermCorpus(termCorpus, checksum)` pair rather
than exposing a side-channel "what checksum did you last see" accessor, since that would race against
a concurrent `load(id)` call for the same id observing a different checksum. Concurrent cold builds
are still coalesced, now keyed by `id + checksum` rather than bare `id`, so callers observing the
*same* new checksum still share one build while callers racing across a rapid sequence of writes
(observing genuinely different checksums) each get their own - a cache-churn/parallelism refinement,
not a correctness concern, since `checksum` and `data` live in the same document and one `GET` is
always one atomically-consistent snapshot.

### Dependency-aware staleness detection, reusing the existing rewriter-cache machinery

`OpenSearchRewriterFactory` gains a defaulted method:

```java
public Map<String, String> getDictionaryDependencyChecksums() {
    return Collections.emptyMap();
}
```

Defaulted so the four other built-in rewriter factories and the test-only
`DummyOpenSearchRewriterFactory` need no change. `WordBreakCompoundRewriterFactory` overrides it,
returning `{dictionaryId: checksumAtBuildTime}` once `createRewriterFactory(IndexShard,
DictionaryCorpusLoader)` has resolved it. `RewriterFactoryLoader.buildFactoryAndLogging` captures the
result immediately afterward (the `OpenSearchRewriterFactory` instance that resolved it is otherwise
discarded) into a new field on `RewriterFactoryAndLogging`.

Tracing every `RefreshMode` path: `FORCE` and the async `RefreshMode.NO` path (`maybeRefreshAsync`,
which rebuilds unconditionally once its time window elapses - it never compares seqNo/primaryTerm at
all) both always call `buildFactoryAndLogging` → `createRewriterFactory` → `dictionaryCorpusLoader
.load(id)` every time they fire. So once `DictionaryCorpusLoader.load` is self-verifying, those two
paths get correct dependency freshness with no further change. `checkAndReloadIfChanged`
(`RefreshMode.CHECK`) and `RewriterCacheSweeper`'s periodic sweep are the two paths that explicitly
*skip* rebuilding when nothing changed, so they're the ones extended: `checkAndReloadIfChanged` now
does one `MultiGetRequest` (rewriter document plus every declared dependency, one round trip) when a
rewriter has dependencies, and rebuilds if either the rewriter's own seqNo/primaryTerm or any
dependency's checksum changed. `RewriterCacheSweeper` folds dependency ids into its existing batched
multi-get (same index/shard, zero extra round trips) and applies the same extended comparison.

### The sweep is narrowed, not disabled, on the full plugin

There is no push/REST action for dictionary writes on *either* install type - `HttpQuerqyIndexManager
.putDictionary`/`deleteDictionary` are raw HTTP `PUT`/`DELETE` on both. So on the full (`ActionPlugin`)
install, where the sweep was previously disabled entirely (push-based reload already covers rewriter
documents there), a dictionary-only change would otherwise only ever be caught via an explicit
`RefreshMode.CHECK`/`FORCE` query - reopening the exact silent-staleness problem this redesign exists
to close, for one install type.

`RewriterCacheSweeper.disable()` is replaced with a narrower `disableRewriterLevelChecks()`: the
sweep keeps running, unconditionally, on every install type. On the full plugin it only evaluates
dependency checksums - it ignores the rewriter document's own existence/seqNo/primaryTerm entirely,
preserving the existing guarantee that push-based reload (not the sweep) owns rewriter-doc freshness
there, and avoiding the unguarded race the original full disablement protected against (a delayed
sweep-driven rebuild of a *rewriter* document racing a concurrent push-based reload of it). There is
no push-based mechanism for dictionary writes to race against on either install type, so the
dependency check stays on unconditionally everywhere.

### Config shape: flat `dictionaryId`

`dictionary: {name, revision}` (a nested object) becomes a flat `dictionaryId` config key, symmetric
with the existing `dictionaryField` (shard-side) key - `dictionaryField` xor `dictionaryId`, both
flat strings, same validation pattern. With only one scalar left after dropping `revision`, a nested
object added a cast and a null-check at every call site for no benefit.

### Nested-blocking-call precaution (found during testing, not by design review)

Making `DictionaryCorpusLoader.load` always do a `GET` (rather than only on a cache miss) surfaced a
real thread-pool self-starvation deadlock, reproduced by the test suite hanging indefinitely: two
places rebuild a rewriter from inside a `GET`/multi-get response callback that OpenSearch already
runs on a thread from its small `get` pool (`FixedExecutorBuilder`, sized to `allocatedProcessors` -
as low as 1-2 on a constrained node) - `RewriterFactoryLoader#maybeRefreshAsync` and
`RewriterCacheSweeper#applyResponse`. Rebuilding a rewriter with a `dictionaryId` re-resolves it via
*another* blocking `Client` call, needing a second thread from that same small pool - with none free,
since the only one available was the one already stuck waiting.

A first fix attempt - dispatching only the nested `GET` itself onto `threadPool.generic()`, from
inside `DictionaryCorpusLoader.load` - did not work: the *calling* thread (the one running the `get`-
pool callback) was still blocked waiting for that dispatched work to finish, so it never actually
returned to its pool. Moving where the wait happens doesn't help if the originating thread still
occupies its pool's slot for the same duration either way.

The actual fix: both callbacks now dispatch their *entire* body onto `threadPool.generic()` and
return immediately, before doing any work that might block - exactly the pattern
`TransportPutRewriterAction` already used for the same reason, for a deadlock found earlier in this
project's history. `DictionaryCorpusLoader.load` itself was reverted to a plain, single blocking
`GET` with no internal dispatch; it documents the precaution for any future caller instead
(`threadPool()` exposes the node's `ThreadPool` for exactly this purpose). A follow-up audit of every
other `ActionListener`/thread-pool touch point in the `core` and root modules found no other instance
of this pattern - the two fixed here, plus `TransportPutRewriterAction`'s earlier one, are the only
three.

## Consequences

- A dictionary's content can be changed in place without minting a new identifier, matching how
  operators are likely to actually want to fix/update one; keeping an old version addressable is a
  deliberate, visible action (a new id) rather than an automatic one.
- `RefreshMode.CHECK` on a rewriter with a dictionary dependency now costs one `MultiGetRequest`
  instead of one `GET` - still one round trip, slightly larger. Every rewriter without a dictionary
  dependency (the common case) is completely unaffected.
- The full plugin's periodic sweep now always runs (a small, fixed-interval batched multi-get per
  node), where it previously did nothing at all - a modest, bounded addition of background work in
  exchange for closing the dictionary-staleness gap there.
- This is a breaking change to the `dictionary: {name, revision}` config shape and the `term_corpus`
  document format introduced by ADR 0003 - acceptable because PR #191/#192 (which introduced both)
  were still open/unmerged when this redesign landed, so there is no stored-document or
  config-shape backward-compatibility burden.
