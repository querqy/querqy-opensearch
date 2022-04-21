# OpenSearch Querqy - Haystack 2022

Querqy is a query rewriting plugin that helps to solve relevance issues making search engines more precise regarding matching and scoring. Furthermore, it facilitates implementing business rules into a search by simple configurations. Many rewriters are provided out-of-the-box, such as the CommonRulesRewriter, the WordBreakRewriter, the ReplaceRewriter or the NumberUnitRewriter. Additionally, Querqy is considered as a pluggable framework to facilitate integrating custom rewriting logic.

## Conference Talk resources
```
.
├── data 
│   ├── icecat-products-w_price-19k-20201127.json
│   └── index_chorus_data.py
└── slides
```

## Additional resources
A comprehensive documentation can be found here: [Querqy documentation](https://docs.querqy.org/querqy/index.html)

Querqy for OpenSearch can be used in the same way as Querqy for Elasticsearch, with the following exceptions:
* The installation is done calling a script named differently (see above). 
* For the definition of rewriters, paths to classes are slightly different (replacing `elasticsearch` by 
  `opensearch`). For instance, the CommonRulesRewriter could be defined as follows:

  `PUT /_querqy/rewriter/common_rules` 
  ```json
  {
    "class": "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory",
    "config": {
        "rules" : "notebook =>\nSYNONYM: laptop"
    }
  }
  ``` 
