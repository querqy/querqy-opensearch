# 1. Split into core/full/tool modules and a validated rewriter-config index format

Date: 2026-10-09

## Status

Accepted

## Context

Amazon OpenSearch Service only supports a fixed set of custom-plugin extension points:
`AnalysisPlugin`, `SearchPlugin`, `MapperPlugin`, `ScriptPlugin`, `SearchPipelinePlugin`.
`ActionPlugin` is not supported and will not be. The current `querqy-opensearch` plugin
depends entirely on `ActionPlugin` for its rewriter-management REST API
(`PUT/GET/DELETE /_plugins/_querqy/rewriter/{id}`) and for cluster-wide push-based reload
after a write, so it cannot be installed on AWS OpenSearch Service as-is.

AWS also only ever hosts odd minor OpenSearch versions (3.5, 3.7, 3.9, ...) for custom plugins,
so AWS-targeted work branches from the latest odd-minor release tag
(`opensearch-querqy-1.2.os3.7.0`) rather than `main` (currently on 3.8); it will be merged
forward into `main` separately.

A fuller design (module split, a `SearchPipelinePlugin` processor, dictionaries, etc.) is
captured outside this repository in a briefing document; this ADR covers only the first
phase implemented on the `issue179` branch.

## Decision

1. **Split into three Gradle modules:**
   - `core`: the `querqy` query, rewriter factories, the rewriter-config index format and
     loading, implementing only `SearchPlugin` (+ `MapperPlugin`, see below) — no
     `ActionPlugin`. Independently installable as its own OpenSearch plugin
     (`querqy.opensearch.QuerqyCorePlugin`), the artifact intended for AWS.
   - Root module (unchanged plugin name/classname `querqy.opensearch.QuerqyPlugin`):
     depends on `core`, extends `QuerqyCorePlugin`, adds back `ActionPlugin` (the existing
     REST/transport rewriter-management API). Zero behavior change for existing installs.
   - `tool`: a standalone CLI that validates a rewriter config and writes it directly to the
     `.opensearch-querqy` index over HTTP — the AWS-compatible replacement for the REST PUT
     action, since `core` has no REST surface to do this.
   - Both `core` and the root module use the same plugin `name`, so only one of the two can
     ever be installed on a given cluster at a time.

2. **New rewriter-config index format (mapping version 5):** combine the rewriter's class
   name and config into a single JSON-string field (`config_v_005`,
   `{"class": ..., "config": {...}}`), replacing the previous separate `class` keyword field
   and `config_v_003` field. This is what makes write-time validation (next point) possible:
   a validator needs class and config together to resolve and validate a rewriter factory.
   Reading continues to support every older stored format (pre-v3, v3, v4) via the existing
   per-version-singleton dispatch in `RewriterConfigMapping`; writing (from both the
   REST/`ActionPlugin` path and the new `core`/`tool` path) always uses only the new format.
   Unlike `config_v_003` (a real, if unindexed, Lucene keyword field, hence subject to
   Lucene's 32766-byte term-length limit), `config_v_005` uses the custom field type from the
   next point, which never creates a Lucene field at all - so, confirmed experimentally, that
   particular limit no longer applies, and `toLuceneSource` no longer chunks large configs on
   write (ordinary request-size limits still apply, of course). Reading still tolerates an
   already-chunked value (`String[]`/`List`) for documents written by older code.

3. **Write-time validation via a custom field mapper:** `core` registers a custom field type
   (via `MapperPlugin.getMappers()`) for `config_v_005` that validates the rewriter config
   (class allow-list + `validateConfiguration(...)`) during document parsing and rejects an
   invalid write with a `MapperParsingException`. This enforces the same validation rule the
   REST path and the new CLI tool already apply client-side, but now also covers a raw
   `PUT .opensearch-querqy/_doc/{id}` — the only way to write configs on a core-only/AWS
   install. `MapperPlugin` is on AWS's supported list, so this is deployable there.

4. **Bounded, asynchronous config refresh:** since a core-only install has no push-based
   reload (`ActionPlugin`-only), `RewriterShardContext`'s per-shard cache gains a
   `refresh_after_write` setting (default 30s): a stale cache entry is still served
   immediately, with a non-blocking background refresh kicked off via the async `Client` API.
   Only the very first (cold-cache) load for a given rewriter id still blocks, as today. This
   scoping to "installs without push-based reload" is enforced in code, not just stated here:
   `QuerqyPlugin` (the full, `ActionPlugin` install) calls
   `RewriterShardContexts#disableAsyncRefresh()` on construction, since on that install
   push-based reload already keeps every node's cache fresh immediately after every write,
   making the background check redundant — and, since both write to the same cache with no
   version check between them, a source of an unguarded race (a delayed background refresh
   reading a pre-write version of a document could otherwise overwrite a cache entry a
   concurrent push-based reload had just installed). The one accepted tradeoff: a node that
   misses a push notification no longer has a passive fallback back to a fresh cache for that
   rewriter — it needs another write event for it, a restart, or an explicit `refresh_config:
   check`/`force` query (unaffected by this, since it's a separate, caller-opted-in synchronous
   path) to recover.

## Consequences

- AWS-installable `core` plugin is independently buildable and testable without the
  REST/transport layer.
- Existing `querqy-opensearch` installs see no behavior change: same plugin name/classname,
  same REST API, now writing the new v5 format transparently.
- A config change on a core-only install is no longer instant (as it is today via push
  reload) but takes effect within roughly `refresh_after_write`. The full node-level
  background observer design (polling across all configs, not just on cache access) remains
  future work.
- The write-time validation mapper runs on every shard copy (primary and replicas) and again
  during translog replay on recovery. A document that was valid when written will always
  re-validate the same way *as long as the class allow-list and `validateConfiguration` logic
  don't change* — a later tightening of either could make a previously-valid, still-queued
  translog entry fail to replay during recovery. This is an accepted, inherent tradeoff of
  this approach (not something this round attempts to fully solve): treat allow-list or
  validation-logic changes as needing a data migration, not a casual change. No toggle to
  disable this enforcement exists yet; add one if this risk proves troublesome in practice.
- Not addressed in this round: the `SearchPipelinePlugin` processor, the full Phase-2
  background config observer, AWS SigV4 signing in the CLI tool, and publishing `core`/`tool`
  as released artifacts.
