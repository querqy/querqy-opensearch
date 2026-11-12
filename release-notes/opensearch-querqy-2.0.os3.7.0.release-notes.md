## Version 2.0.os3.7.0 Release Notes
Compatible with OpenSearch Version 3.7.0

### Breaking Changes
* **The rewriter index was renamed from `.opensearch-querqy` to `opensearch-querqy-store`**
  ([#208](https://github.com/querqy/querqy-opensearch/issues/208)). The leading dot made the index look like a system
  index, which it never was. **Existing rewriter configurations are not migrated**: after upgrading, save all your
  rewriters (and word-break dictionaries) again - via the REST API (`PUT /_plugins/_querqy/rewriter/{id}`) or the
  config CLI - and then delete the old `.opensearch-querqy` index. As long as the old index exists, the plugin logs
  a warning on the elected cluster manager and the config CLI prints one too. If you restrict index access (e.g.
  with fine-grained access control), grant your roles access to `opensearch-querqy-store` instead of
  `.opensearch-querqy`.
* **No write-time validation of rewriter and dictionary documents**
  ([#209](https://github.com/querqy/querqy-opensearch/issues/209)). The plugin no longer registers custom field
  types (`querqy_rewriter_config`, `querqy_term_corpus`) for the rewriter index: an index using a plugin-provided
  field type can't be copied to a cluster without the plugin, which broke updating or uninstalling the plugin on
  Amazon OpenSearch Service (blue/green deployment). The REST API and the config CLI still validate configurations
  before saving them (the config CLI now also validates dictionaries locally), and the plugin verifies a
  dictionary's content and checksum when it loads it. A document written directly to the index (e.g.
  `PUT opensearch-querqy-store/_doc/<id>`) is no longer checked at write time - validate it first with the config
  CLI (`put --validate-only`). If you ran a pre-release 2.0 build of `opensearch-querqy-core` on Amazon OpenSearch
  Service, delete its `.opensearch-querqy` index *before* updating or removing the plugin.
