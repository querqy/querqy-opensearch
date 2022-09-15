# OpenSearch Developer Documentation

## 1. OpenSearch Querqy Plugin

### 1.1 Getting started

* This plugin uses gradle wrapper for build and package management. 
* **Build Plugin**: To build and install the plugin follow steps mentioned in the [README](../README.md#installation-with-local-build).
* **Install Plugin with pre-built packages**: To install pre-built zip-packages follow steps mentioned in the [README](../README.md#installation-with-released-zip-packages).
* **Release documentation**: Checkout our release steps [here](../RELEASING.md)

### 1.2 Architecture

The plugin works similar to Querqy’s ES plugin
![querqy-plugin](https://user-images.githubusercontent.com/4348487/177487716-3f719d70-99ba-49bf-98d9-b651e0d38b61.jpg)


* By René Kriegler @renekrie, Querqy Co-author & Maintainer

### 1.3 Configuration Options/Settings

The Querqy plugin needs three configuration settings:

1. Querqy Index number of replicas
2. Rules cache expire time after write operation
3. Rules cache expire time after read operation

NOTE: More details on caching in section 1.5

### 1.4 Index

Querqy index →  `.opensearch-querqy`

All the Querqy configurations like rules, synonyms, word breaks, etc will be stored in a new plugin index called `.opensearch-querqy.` For each rewriter a separate doc is formed inside the index. Below is a sample of `common_rules` stored in the plugin.

```
GET /.opensearch-querqy/_doc/common_rules

{
  "_index" : ".opensearch-querqy",
  "_type" : "_doc",
  "_id" : "common_rules",
  "_version" : 3,
  "_seq_no" : 2,
  "_primary_term" : 3,
  "found" : true,
  "_source" : {
    "type" : "rewriter",
    "version" : 3,
    "class" : "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory",
    "config_v_003" : """{"rules":"request =>\nSYNONYM: POST"}"""
  }
}
```

### 1.5 Processed rule caching

Usually users have thousand of rules in their index. Processing these rules and converting them to object factories take considerable amount of time, this processing cannot be done per request. Hence, the plugin resorts to caching the processed rules. The cache is build for each rewriter on the first search request made by any user. The cache stored is reloaded with each `PUT request` made to the querqy plugin. The cache is cleared when a particular rewriter is deleted with a `DELETE request`.

### 1.6 Security & FGAC

#### 1.6.1 Access Control for querying over an index:

* Users querying over an index with or w/o Querqy should have access to read/search that index. The security plugin will block the user for any unauthorized access based on the permissions mapped to the user’s role.

#### 1.6.2 Access Control for plugin and its index:

**Plugin Users:**
Admin User : creating rules read/update/delete [All CRUD]
Search User:  searches with querqy, not to view the rules but use it. [Only Read]


* **Querqy Index:** This stores all the rules `.opensearch-querqy`
* **Querqy Query Component:** This is responsible for using rules and applying it to index search queries made by the user
* **Approach 1**:
    * Only the users having read access to the Querqy index, can use the plugin with the search API.
    * Only the users having write permissions on the Querqy index, can add/configure rules to the plugin.
    * For users not having the access, we straight away deny request with `403` error response.
* **Approach 2**:
    * All the users can use the plugin with the search API.
    * Only the users having write permissions on the Querqy index, can add/configure/delete rules to the plugin.
    * For users not having the access, we straight away deny request with `403` error response.

More discussion about the issue here: https://github.com/querqy/querqy-opensearch/issues/14

## 2. References:

1. Querqy project docs: https://querqy.org/
2. Querqy project github: https://github.com/querqy
3. Querqy ElasticSearch Plugin Details: https://github.com/querqy/querqy-elasticsearch
5. Querqy 5: https://opensourceconnections.com/blog/2021/03/05/whats-new-in-querqy-the-query-preprocessor-for-solr-and-elasticsearch/














