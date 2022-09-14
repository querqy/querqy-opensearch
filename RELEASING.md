# OpenSearch Querqy Plugin Releases

This document contains details about the release strategy for OpenSearch Querqy Plugin. 
We discuss things like branching and release cycles for easy maintenance in the long term.

OpenSearch Querqy Plugin coupled with two opensource projects: Querqy and OpenSearch. 
Both of these projects have different release cycles, versioning and package deployment strategies. 
This document chooses the best of both worlds and defines a way to ensure the community plugins have a smoother blend to the OpenSearch release strategy.

## 1. Branching

### 1.1 Branch names
* Querqy OpenSearch plugin branch names are as follows: 

  * `main`: contains the current release in progress, currently 2.3.0. This is the branch where all merges take place, and code moves fast.
  * `2.2`: contains the previous released code, i.e. 2.2.0. No further feature updates are done to this branch. 
  * `2.3`: The current release, currently 2.3.0. In between minor releases, only hotfixes (e.g. security) are backported to 2.3. 
    The next release out of this branch will be 2.3.1.
  * `dev/feature_branch`: Any feature which is not part of the immediate next release/needs longer timeframe of implementation with multiple PRs can be part of separate feature branches.
    
* __Note__: OpenSearch follows [semver](https://semver.org/) for versioning the releases. Querqy OpenSearch plugin follows the same.


### 1.2 Release Tags

* The release tag names are a combination of OpenSearch Querqy plugin version and the suported OpenSearch version: `querqy-opensearch-<major.minor>.os2.3.0-patch1`. 
  For example, plugin built for `OpenSearch 2.3.0` and plugin version being `1.0`, will have the tag name: `querqy-opensearch-1.0.os2.3.0`
* Keeping tag names this way, help us to see the plugin's compatibility with OpenSearch and any plugin specific changes.
* Release tags contain, assets for source-codes and pre-built zip artifacts. 
* Hotfixes and security patches will have a patch suffix at the end of the release tag example: `querqy-opensearch-5.4.l900.1.os2.3.0-patch1`

## 2. Releases

### 2.1 Release Cycles

* This plugin follows the OpenSearch release cycles. 
  The plugin maintainers make sure that the releases are in sync with [OpenSearch Roadmap.](https://github.com/orgs/opensearch-project/projects/1)
  
* We do the plugin release immediately after the OpenSearch artifact for the new version is released. 

### 2.2 Release Notes

* Release notes help OpenSearch users learn about new features, and make educated decisions in regard to upgrading.
* Each release has to be accompanied by a release notes document. 
  The release notes follow a structure similar to other OpenSearch plugins, explained [here](https://github.com/opensearch-project/opensearch-plugins/blob/main/RELEASE_NOTES.md).  

### 2.3 Upstream Querqy-core changes/new features adoption

* Changes from upstream library changes from Querqy-core and Querqy-Lucene are adopted in the immediate next release. 
* The adoption of upstream changes should match the [semver](https://semver.org/) updates: 
```
    MAJOR version when you make incompatible API changes
    MINOR version when you add functionality in a backwards compatible manner
    PATCH version when you make backwards compatible bug fixes
```

### 2.5 Automated Github Release Actions

* OpenSearch releases usually happen every 6 weeks. 
  Querqy release cycles do not have specific timelines rather they come in based on the community adding new features whenever requested.
* Therefore, there would be many releases where this plugin is just bumped up to the next release version. 
  A long term way is to semi/automate the bump of version through github actions.
* More details to be added later.

### 2.6 Maven publish

* Querqy packages are published in maven under the group `org.querqy`. 
  This plugin would follow a similar route with artifact id `querqy-opensearch`.
* Today publishing to Maven is a manual process, we will automate this soon.

## 3. CVE fixes

* The CVE fixes are only forward patched. For example if, a CVE vulnerability is found in version 2.1, 2.2 and 2.3 (latest release), only version 2.3 is patched. 
* OpenSearch upstream CVE issues will follow the OpenSearch patch releases example: `2.3.0 -> 2.3.1`.
* Querqy upstream CVE fixes shall be taken up as patch fixes released out the cycle (check pt.4 in section 1.2 Release Tags). 




