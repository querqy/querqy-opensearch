# 4. PipelineRewriterCacheHandle: a stable handle to the pipeline's rewriter cache

Date: 2026-11-02

## Status

Accepted and implemented.

## Context

The `querqy_rewrite` search pipeline request processor (ADR 0002) runs on the coordinating node
with no shard access, so it cannot share the per-shard `RewriterFactoryLoader` instances that
`RewriterShardContexts` manages for the classic `querqy` query. Instead, `QuerqyCorePlugin
#getRequestProcessors` builds a single, separate, node-level `RewriterFactoryLoader` (via
`RewriterFactoryLoader.nodeLevel(...)`) the first time a `querqy_rewrite` pipeline request
processor factory is requested.

This created a gap once two features needed to reach *that* loader from outside the pipeline
module:

- The full plugin module's push-based cache invalidation (`TransportNodesClearRewriterCacheAction`/
  `TransportNodesReloadRewriterAction`, introduced to make a REST `PUT`/`DELETE` of a rewriter take
  effect immediately everywhere) originally only reached `RewriterShardContexts` - the pipeline's
  cache kept serving a stale rewriter until its own lazy refresh-after-write window elapsed.
- `RewriterCacheSweeper` (the periodic, traffic-independent cache sweep added for core-only/AWS
  installs, which have no push notification at all) needs to enumerate and refresh every cached
  rewriter id on the node, including whatever the pipeline has cached - not just the per-shard
  caches.

Neither consumer could simply hold a `RewriterFactoryLoader` reference, because the pipeline's real
loader does not exist yet at the point these consumers are normally wired up:
`TransportNodesClearRewriterCacheAction`'s constructor is `@Inject`-resolved from the components
`QuerqyCorePlugin#createComponents` returns, and `RewriterCacheSweeper` is likewise constructed and
started around that same time - but the pipeline's loader is only built later, inside
`getRequestProcessors`, once a `Client` is available. OpenSearch does not guarantee which of
`createComponents`/`getRequestProcessors` runs first, so neither "build the loader earlier" nor
"inject it directly" is an option without depending on unspecified ordering.

## Decision

`PipelineRewriterCacheHandle` (core module) is a small, dependency-free indirection that decouples
*when a consumer needs a reference* from *when the real loader exists*:

- It is constructed immediately, in `QuerqyCorePlugin`'s own constructor - before any component
  wiring happens at all, so it is always available.
- It is registered as a component in `createComponents`, making it resolvable via `@Inject`
  regardless of whether `createComponents` or `getRequestProcessors` runs first.
- Its real `RewriterFactoryLoader` is filled in later, via `setLoader(...)`, the moment
  `getRequestProcessors` actually builds it.
- Every method (`clearRewriter`, `clearRewriters`, `reloadRewriter`, `cachedRewriterIds`,
  `applySweepResponses`) delegates to the real loader if one has been set, or is a no-op/empty
  result otherwise - mirroring `RewriterFactoryLoader#reloadRewriter`'s own "no-op if not cached"
  philosophy, so callers never need their own readiness check or null-handling.

This is a general-purpose mechanism, not tied to either consumer: any future node-level component
that needs to reach the pipeline's cache uses the same handle rather than inventing its own
wiring or its own ordering workaround.

## Consequences

- Both `TransportNodesClearRewriterCacheAction`/`TransportNodesReloadRewriterAction` and
  `RewriterCacheSweeper` can treat the pipeline's cache exactly like any other cache they manage,
  with no special-casing for "is the pipeline even configured on this node" or "has it finished
  starting up yet".
- The no-op-until-set design means a caller cannot distinguish three different states that all look
  identical from the outside: the handle not filled in yet (startup), a node that never configures a
  `querqy_rewrite` pipeline at all, and a configured pipeline with nothing currently cached. This is
  harmless for both current consumers, which only ever express "do this if there is anything to do"
  - neither needs to tell these cases apart. A future consumer that does need to distinguish them
  would need a different mechanism (e.g. an explicit "is a pipeline loader registered" query) rather
  than reusing this handle as-is.
