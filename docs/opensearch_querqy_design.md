# OpenSearch Querqy Plugin Design Document

Plugin Code: https://github.com/querqy/querqy-opensearch
Querqy Project: https://querqy.org/

## 1. What is Querqy?

A query rewriting library. It helps you to tune your search results for specific search terms. It also comes with some query-independent search relevance optimisations. You can plugin in your own query rewriter. Available for Solr and Elasticsearch. Querqy is a query rewriting framework for Java-based search engines. It is probably best-known for its rule-based query rewriting, which applies synonyms, query dependent filters, boostings and demoting of documents. This rule-based query rewriting is implemented in the ‘Common Rules Rewriter’, but Querqy’s capabilities go far beyond this rewriter.

## 2. How does Querqy fit in the Search Relevancy Ecosystem?

Querqy Plugin will be a crucial part of our search relevancy project. It is the starting point for users to dive into query relevancy and optimization. Users can then add Querqy rules to deal with synonyms, query relaxations (with number units, replacements) and word breaks. More details on [Search Relevancy RFC](https://github.com/opensearch-project/search-relevance/issues/1)

## 3. What are Re-writers in Querqy?

Rewriters manipulate the query that was entered by the user. They can change the result set by adding alternative tokens, by removing tokens or by adding filters. They can also influence the ranking by adding boosting information. A single query can be rewritten by more than one rewriter. Together they form the **rewrite chain**.

## 4. Different rewriters available:

* **Common Rules Rewriter:** The Common Rules Rewriter uses configurable rules to manipulate the matching and ranking of search results depending on the input query. In e-commerce search it is a powerful tool for merchandisers to fine-tune search results, especially for high-traffic queries.

```
notebook => (laptop or notebook) 
```

* **Replace Rewriter:** The Replace Rewriter is considered to be a preprocessor for other rewriters. In contrast to the Common Rules Rewriter, its main scope is to handle different variants of terms rather than enhancing the query by business logic.
```
notbook; noteboo => notebook 
```
* **Word Break Rewriter:** The Word Break Rewriter deals with compound words in queries. It works in two directions: it will split compound words found in queries and it will create compound words from adjacent query tokens.
```
iphone => (iphone or i phone) 
```
* **Number-Unit Rewriter:** The Number-Unit Rewriter takes term combinations comprising a number and a unit and rewrites these combinations to filter and boost queries. The precondition for configuring this rewriting for a certain unit is a numeric field in the index containing standardized values for the respective unit.
```
Laptop 15” => laptop and screen_size:[13.5 to 16.5] 
```

## 5. OpenSearch Querqy Plugin

### 5.1 Sample Usage with Synonym rules

```
// Index sample docs

POST sample-index/_doc/1
{
  "@timestamp": "2099-11-15T13:12:00",
  "message": "GET /search HTTP/1.1 200 1070000",
  "user": {
    "id": "John"
  }
}

POST  sample-index/_doc/2
{
  "@timestamp": "2099-11-15T13:12:00",
  "message": "POST /search HTTP/1.1 200 1070000",
  "user": {
    "id": "David"
  }
}

POST  sample-index/_doc/3
{
  "@timestamp": "2099-11-15T13:12:00",
  "message": "PUT /search HTTP/1.1 200 1070000",
  "user": {
    "id": "Ani"
  }
}

POST  sample-index/_doc/4
{
  "@timestamp": "2099-11-15T13:12:00",
  "message": "DELETE HTTP/1.1 200 1070000",
  "user": {
    "id": "Josh"
  }
}

POST  sample-index/_doc/5
{
  "@timestamp": "2099-11-15T13:12:00",
  "message": "GETREQUEST HTTP/1.1 200 1070000",
  "user": {
    "id": "Jake"
  }
}

POST  sample-index/_doc/6
{
  "@timestamp": "2099-11-15T13:12:00",
  "message": "GET REQUEST HTTP/1.1 200 1070000",
  "user": {
    "id": "Paul"
  }
}

// Add a synonym rule to the Querqy common rules

PUT  /_plugins/_querqy/rewriter/common_rules
{
  "class": "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory",
  "config": {
      "rules" : "request =>\nSYNONYM: GET"
  }
}

// Query the sample index with synonym rule
// [Search for "requests", ]

POST sample-index/_search
{
    "query": {
       "querqy": {
           "matching_query": {
               "query": "request"
           },
           "query_fields": [ "message"],
            "rewriters": ["common_rules"]
     }
  }
}

// Result of the above query
// [Results contain docs containing "GET"]

 {
  "took" : 8,
  "timed_out" : false,
  "_shards" : {
    "total" : 1,
    "successful" : 1,
    "skipped" : 0,
    "failed" : 0
  },
  "hits" : {
    "total" : {
      "value" : 2,
      "relation" : "eq"
    },
    "max_score" : 1.0054247,
    "hits" : [
      {
        "_index" : "sample-index",
        "_type" : "_doc",
        "_id" : "1",
        "_score" : 1.0054247,
        "_source" : {
          "@timestamp" : "2099-11-15T13:12:00",
          "message" : "GET /search HTTP/1.1 200 1070000",
          "user" : {
            "id" : "John"
          }
        }
      },
      {
        "_index" : "sample-index",
        "_type" : "_doc",
        "_id" : "6",
        "_score" : 1.0054247,
        "_source" : {
          "@timestamp" : "2099-11-15T13:12:00",
          "message" : "GET REQUEST HTTP/1.1 200 1070000",
          "user" : {
            "id" : "Paul"
          }
        }
      }
    ]
  }
}

// DELETE querqy rule

DELETE  /_plugins/_querqy/rewrite/common_rules
```

### 5.2 Architecture

The plugin would work similar to Querqy’s ES plugin
![querqy-plugin](https://user-images.githubusercontent.com/4348487/177487716-3f719d70-99ba-49bf-98d9-b651e0d38b61.jpg)


* By René Kriegler @renekrie, Querqy Co-author & Maintainer

### 5.3 Configuration Options/Settings

The Querqy plugin needs three configuration settings:

1. Querqy Index number of replicas
2. Rules cache expire time after write operation
3. Rules cache expire time after read operation

NOTE: More details on caching in section 5.5

### 5.4 Index

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

### 5.5 Processed rule caching

Usually users have thousand of rules in their index. Processing these rules and converting them to object factories take considerable amount of time, this processing cannot be done per request. Hence, the plugin resorts to caching the processed rules. The cache is build for each rewriter on the first search request made by any user. The cache stored is reloaded with each `PUT request` made to the querqy plugin. The cache is cleared when a particular rewriter is deleted with a `DELETE request`.

### 5.6 Security & FGAC

#### 5.6.1 Access Control for querying over an index:

* Users querying over an index with or w/o Querqy should have access to read/search that index. The security plugin will block the user for any unauthorized access based on the permissions mapped to the user’s role.

#### 5.6.2 Access Control for plugin and its index:

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

## 6. References:

1. Querqy project docs: https://querqy.org/
2. Querqy project github: https://github.com/querqy
3. Querqy ElasticSearch Plugin Details: https://github.com/querqy/querqy-elasticsearch
5. Querqy 5: https://opensourceconnections.com/blog/2021/03/05/whats-new-in-querqy-the-query-preprocessor-for-solr-and-elasticsearch/














