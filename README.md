[![Test and Build OpenSearch Querqy Plugin](https://github.com/querqy/querqy-opensearch/actions/workflows/opensearch-querqy-test-and-build-workflow.yml/badge.svg)](https://github.com/querqy/querqy-opensearch/actions/workflows/opensearch-querqy-test-and-build-workflow.yml)

# OpenSearch Querqy

Querqy is a query rewriting plugin that helps to solve relevance issues making search engines more precise regarding 
matching and scoring. Furthermore, it facilitates implementing business rules into a search by simple configurations.
Many rewriters are provided out-of-the-box, such as the CommonRulesRewriter, the WordBreakRewriter, the 
ReplaceRewriter or the NumberUnitRewriter. Additionally, Querqy is considered as a pluggable framework to facilitate 
integrating custom rewriting logic.

## Installation with released zip-packages
* Released zip-pacakges can be found here: [release tags](https://github.com/querqy/querqy-opensearch/tags)
* Querqy can be installed like all other OpenSearch plugins, e.g. using

  `bin/opensearch-plugin install file:///path/to/file/opensearch-querqy.zip`

## Installation with local build
* First, you need to build the plugin by running `./gradlew build`.
* You find the build at `build/distributions/opensearch-querqy.zip`
* Querqy can be installed like all other OpenSearch plugins, e.g. using 

  `bin/opensearch-plugin install file:///path/to/file/opensearch-querqy.zip`
 
**Installation note**: In the above command, please make sure to use the `file://` protocol as the path prefix.

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
  
* Developer documentation can be found [here](docs/opensearch_dev_documentation.md)
* Developer channel: Join #querqy-dev on the [E-commerce search Slack space](https://ecom-search.slack.com)
