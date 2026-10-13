[![Test and Build OpenSearch Querqy Plugin](https://github.com/querqy/querqy-opensearch/actions/workflows/opensearch-querqy-test-and-build-workflow.yml/badge.svg)](https://github.com/querqy/querqy-opensearch/actions/workflows/opensearch-querqy-test-and-build-workflow.yml)


# OpenSearch Querqy

Querqy is a query rewriting plugin that helps to solve relevance issues making search engines more precise regarding 
matching and scoring. Furthermore, it facilitates implementing business rules into a search by simple configurations.
Many rewriters are provided out-of-the-box, such as the CommonRulesRewriter, the WordBreakRewriter, the 
ReplaceRewriter or the NumberUnitRewriter. Additionally, Querqy is considered as a pluggable framework to facilitate 
integrating custom rewriting logic.

## Two artifacts: which one do you need?

This repository builds two plugin artifacts from the same code, sharing the same plugin name (so only one of them can
ever be installed on a cluster at a time):

* **`opensearch-querqy`** (module `.` / root) - the full plugin: the `querqy` query, the rewriters, *and* the REST API
  for managing rewriters (`PUT`/`GET`/`DELETE /_plugins/_querqy/rewriter/{id}`). This is what you want on a
  self-managed OpenSearch cluster, where you can install plugins using `ActionPlugin` (custom REST endpoints).
* **`querqy-opensearch-core`** (module `core`) - the `querqy` query and the rewriters, but **no REST API**. Amazon
  OpenSearch Service's custom-plugin support doesn't allow `ActionPlugin`, so this is the artifact to install there.
  Without the REST API, rewriter configurations are managed by writing documents directly to the `.opensearch-querqy`
  index - see [Managing rewriters without the REST API](#managing-rewriters-without-the-rest-api-core-only) below.

If you're not on Amazon OpenSearch Service (or another environment that blocks `ActionPlugin`), install the full
`opensearch-querqy` plugin - everything below about the REST API (`PUT /_plugins/_querqy/rewriter/...`) applies to it
unchanged.

## Installation with released zip-packages
* Released zip-packages can be found here: [release tags](https://github.com/querqy/querqy-opensearch/tags) and on [Maven Central](https://repo1.maven.org/maven2/org/querqy/opensearch-querqy/). 
* Querqy can be installed like all other OpenSearch plugins, e.g. using

  `bin/opensearch-plugin install file:///path/to/file/opensearch-querqy.zip`

## Installation with local build
* First, you need to build the project by running `./gradlew build`.
* The full plugin's zip is built at `build/distributions/opensearch-querqy-<version>.zip`; the core (AWS-compatible)
  plugin's zip is built separately at `core/build/distributions/opensearch-querqy-<version>.zip`.
* Querqy can be installed like all other OpenSearch plugins, e.g. using 

  `bin/opensearch-plugin install file:///path/to/file/opensearch-querqy.zip`
 
**Installation note**: In the above command, please make sure to use the `file://` protocol as the path prefix.

## Managing rewriters without the REST API (core-only)

On an install of `querqy-opensearch-core` (no `ActionPlugin`, no REST API), a rewriter configuration is just a
document in the `.opensearch-querqy` index, and there are two ways to write it:

* **The `querqy-opensearch-tool` CLI** (module `tool`, built at
  `tool/build/distributions/querqy-opensearch-tool-<version>.zip` - unzip it and run `bin/querqy-opensearch-tool`, or
  run it directly from a checkout via `./gradlew tool:run --args="..."`). It mirrors the REST API's
  `PUT`/`GET`/`DELETE` rewriter actions (plus listing) as four subcommands - `put`, `get`, `list`, `delete` - each
  taking `--url` and, except for `list`, `--id`:

  ```shell
  bin/querqy-opensearch-tool put --url https://<host>:9200 --id common_rules --config-file ./common_rules.json \
      [--revision r42] [--username admin --password '***'] [--insecure] [--validate-only]
  bin/querqy-opensearch-tool get --url https://<host>:9200 --id common_rules [--username ... --password ...]
  bin/querqy-opensearch-tool list --url https://<host>:9200 [--username ... --password ...]
  bin/querqy-opensearch-tool delete --url https://<host>:9200 --id common_rules [--username ... --password ...]
  ```

  `put` validates the configuration locally (the same checks the REST API applies) before writing it, and creates or
  migrates the index mapping as needed. `--config-file` holds the same JSON shape as the REST API's PUT body (`class`,
  `config`, optionally `info_logging`/`revision`). `get`/`list` print the same information the REST API's `GET` does,
  as JSON (`list` omits each rewriter's `config`, matching the REST API, since configs can be large). The tool is
  deliberately lightweight (it depends on `opensearch-core`/`opensearch-x-content` rather than the full OpenSearch
  server jar) since all it needs is the REST client and enough of `core` to validate a config and build the documents
  it reads and writes.

  **`delete` note:** unlike the REST API's `DELETE`, this cannot clear the deleted rewriter from every node's
  in-memory cache (that's an `ActionPlugin`-only transport action, unavailable over plain HTTP) - each node notices
  the deletion on its own within the same freshness window described below.
* **A raw document write**, e.g. `PUT .opensearch-querqy/_doc/common_rules` with a hand-built document - the index
  mapping validates the configuration at write time and rejects an invalid one, but building the document body
  correctly (the combined `class`+`config` field) is easier via the CLI tool above than by hand. See the full worked
  example below.

**Freshness note**: without the REST API's push-based reload, a config change becomes visible on each node within
`querqy.caches.rewriter.refresh_after_write` (default 30 seconds) of being written, not instantly - there is no
explicit "reload" action to call. This does not apply to the full `opensearch-querqy` plugin, which still reloads
immediately after a `PUT` via the REST API.

### Forcing an immediate refresh from a query (`refresh_config`)

The up-to-30-second window above is fine for most use cases, but it can get in the way when someone is interactively
tuning a rewriter's configuration (e.g. from a relevance-tuning tool) and wants to see the effect of a change on the
very next query. The `querqy` query accepts an optional `refresh_config` parameter for this:

```json
{
  "query": {
    "querqy": {
      "matching_query": { "query": "laptop" },
      "query_fields": ["title", "description"],
      "rewriters": ["common_rules"],
      "refresh_config": "check"
    }
  }
}
```

Three values are supported:
* **`no`** (default) - use the cached rewriter, refreshing it in the background if it's stale. Never adds latency.
* **`check`** - block to check whether the stored config has changed (by comparing the document's sequence
  number/primary term) and only reload it if it has; otherwise reuse the cached rewriter as-is.
* **`force`** - block and always reload the rewriter from the index, whether it changed or not.

`refresh_config` can also be set per rewriter, overriding the query-level default for just that one, by using the
object form of a `rewriters` entry instead of a bare string:

```json
"rewriters": [
  { "name": "common_rules", "refresh_config": "force" },
  "other_rewriter"
]
```

Here, `common_rules` always reloads while `other_rewriter` falls back to the query-level `refresh_config` (or `no`
if that's absent too).

**Cost caveat**: `check` and `force` each cost one blocking read against the `.opensearch-querqy` index *per shard*
of the index being searched, on every query that uses them (`check` only saves the cost of re-parsing the config
when nothing changed - it still pays the same round trip as `force`). This is fine for a human trying out config
changes at low query volume, but `check`/`force` should not be used as a blanket default for production search
traffic - leave `refresh_config` at its default (`no`) there and let the existing async refresh handle it.

### Example: writing a rewriter config document directly

**The document ID is the rewriter ID.** Whatever ID you index the document under is the ID you later pass to the
`querqy` query's `rewriters` list (just as `common_rules` is both the document ID and the rewriter name in the REST
API examples elsewhere in this README). There is no separate "name" field.

**The `.opensearch-querqy` index must already exist with the correct mapping before you do a raw write.** If the
index doesn't exist yet, OpenSearch creates it automatically on the first write - but with a *dynamically guessed*
mapping (`config_v_005` would become a plain `text`/`keyword` field), not the mapping that enables write-time
validation. An invalid configuration would then be silently accepted instead of rejected. The CLI tool creates the
index with the correct mapping (and the settings below) automatically; to create it by hand instead, `PUT` both the
mapping bundled in the `core` module (`core/src/main/resources/querqy-mapping.json`) *and* explicit settings onto the
index first. The file holds only the mapping (`{"properties": ...}`), while the create-index API expects it under a
`mappings` key alongside a `settings` key, so wrap it (here with [jq](https://jqlang.github.io/jq/)) rather than
sending the file as is - the create request would otherwise fail with `unknown key [properties] for create index`.

Always set `number_of_shards` explicitly rather than relying on the cluster's default: `.opensearch-querqy` holds at
most a few hundred small documents, and a `GET`/`PUT` by rewriter ID always routes to exactly one shard regardless of
count, so more than one shard buys nothing here - only allocation overhead. This especially matters on managed
offerings like Amazon OpenSearch Service, whose default differs from self-managed OpenSearch's default of 1. Also set
`number_of_replicas` to at least 1 so the index is replicated and survives the loss of a single node (assuming your
cluster has 2 or more nodes - OpenSearch never places a primary and its replica on the same node):

```shell
jq '{settings: {number_of_shards: 1, number_of_replicas: 1}, mappings: .}' \
    core/src/main/resources/querqy-mapping.json | \
curl -X PUT "https://<host>:9200/.opensearch-querqy" \
    -H "Content-Type: application/json" \
    -u admin:'<password>' \
    -d @-
```

(Run this only once per cluster - a second attempt fails harmlessly with `resource_already_exists_exception` since
the index already exists by then.)

With the index in place, here is a complete `common_rules` configuration with a single synonym rule (`laptop` should
also match documents containing `macbook`), written directly as a document:

```shell
curl -X PUT "https://<host>:9200/.opensearch-querqy/_doc/common_rules?refresh=true" \
    -H "Content-Type: application/json" \
    -u admin:'<password>' \
    -d '{
  "type": "rewriter",
  "version": 5,
  "config_v_005": "{\"class\":\"querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory\",\"config\":{\"rules\":\"laptop =>\\nSYNONYM: macbook\"}}"
}'
```

A few things worth noting about this document:
* `type` and `version` are always `"rewriter"` and `5` (the current rewriter-config mapping version) respectively.
* `config_v_005` holds the rewriter's `class` and `config` combined into **one JSON string** (not a nested JSON
  object) - this is what the write-time validator reads to resolve and validate the rewriter. The rules text itself
  uses a literal newline between the match and the instruction (`laptop =>` then `SYNONYM: macbook` on the next
  line), which is why it appears as `\\n` here: once for the inner JSON string, once more because that whole inner
  JSON document is itself embedded as a string value in the outer document.
* `refresh=true` makes the write visible to search immediately, which is convenient when trying this out manually;
  omit it in a script that writes many rewriters back to back.

If `config_v_005` doesn't parse as `{"class": ..., "config": {...}}`, if `class` isn't an allow-listed rewriter
class, or if `validateConfiguration` rejects the `config`, the `PUT` above fails with a mapping/parsing error
instead of silently storing a broken rewriter - that only holds once the index has the correct mapping, as described
above.

## Additional resources
A comprehensive documentation can be found here: [Querqy documentation](https://docs.querqy.org/querqy/index.html)

Querqy for OpenSearch can be used in the same way as Querqy for Elasticsearch, with the following exceptions:
* The installation is done calling a script named differently (see above). 
* For the definition of rewriters, paths to classes are slightly different (replacing `elasticsearch` by 
  `opensearch`). For instance, the CommonRulesRewriter could be defined as follows:

  `PUT /_plugins/_querqy/rewriter/common_rules` 
  ```json
  {
    "class": "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory",
    "config": {
        "rules" : "notebook =>\nSYNONYM: laptop"
    }
  }
  ```
  
* Developer documentation can be found [here](docs/opensearch_dev_documentation.md)
* Architecture decisions (e.g. the core/full/tool module split) are recorded under [docs/adr](docs/adr)
* Developer channel: Join #querqy on the [Search Relevancy Slack space](https://opensourceconnections.com/slack)
