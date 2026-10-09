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
  run it directly from a checkout via `./gradlew tool:run --args="..."`): validates the configuration locally (the
  same checks the REST API applies) and then writes it to the index over HTTP, creating or migrating the index
  mapping as needed:

  ```shell
  bin/querqy-opensearch-tool \
      --url https://<host>:9200 --id common_rules --config-file ./common_rules.json \
      [--revision r42] [--username admin --password '***'] [--insecure] [--validate-only]
  ```

  `--config-file` holds the same JSON shape as the REST API's PUT body (`class`, `config`, optionally
  `info_logging`/`revision`).
* **A raw document write**, e.g. `PUT .opensearch-querqy/_doc/common_rules` with a hand-built document - the index
  mapping validates the configuration at write time and rejects an invalid one, but building the document body
  correctly (the combined `class`+`config` field) is easier via the CLI tool above than by hand. A large config does
  **not** need to be chunked up: just put `class` and `config` as plain JSON under a single `config_v_005` field,
  e.g. `{"type": "rewriter", "version": 5, "config_v_005": "{\"class\":\"...\",\"config\":{...}}"}` - normal HTTP
  request-size limits still apply, but there is no Lucene field-length limit to work around here.

**Freshness note**: without the REST API's push-based reload, a config change becomes visible on each node within
`querqy.caches.rewriter.refresh_after_write` (default 30 seconds) of being written, not instantly - there is no
explicit "reload" action to call. This does not apply to the full `opensearch-querqy` plugin, which still reloads
immediately after a `PUT` via the REST API.

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
