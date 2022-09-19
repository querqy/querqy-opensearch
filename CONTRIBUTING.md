# Contributing to Querqy OpenSearch Plugin

Query and OpenSearch are community projects that are built and maintained by people just like **you**. 
We're glad you're interested in helping out. 
There are several ways you can do it, but before we talk about that, let's talk about how to get started.

- [First Things First](#first-things-first)
- [Ways to Contribute](#ways-to-contribute)
    - [Bug Reports](#bug-reports)
    - [Feature Requests](#feature-requests)
    - [Documentation Changes](#documentation-changes)
    - [Contributing Code](#contributing-code)
- [License Headers](#license-headers)
- [Review Process](#review-process)


## First Things First

1. **When in doubt, open an issue** - For almost any type of contribution, the first step is opening an issue. 
   Even if you think you already know what the solution is, writing down a description of the problem you're trying to solve will help everyone get context when they review your pull request. 
   If it's truly a trivial change (e.g. spelling error), you can skip this step -- but as the subject says, when it doubt, [open an issue](https://github.com/querqy/querqy-opensearch/issues/new).

2. **Only submit your own work**  (or work you have sufficient rights to submit) - Please make sure that any code or documentation you submit is your work or you have the rights to submit.

## Ways to Contribute

### Bug Reports

Ugh! Bugs!

A bug is when software behaves in a way that you didn't expect and the developer didn't intend. To help us understand what's going on, we first want to make sure you're working from the latest version.

Once you've confirmed that the bug still exists in the latest version, you'll want to check to make sure it's not something we already know about on the [open issues GitHub page](https://github.com/querqy/querqy-opensearch/issues/new).

If you've upgraded to the latest version and you can't find it in our open issues list, then you'll need to tell us how to reproduce it, provide as much information as you can. The easier it is for us to recreate your problem, the faster it is likely to be fixed.

### Feature Requests

If you've thought of a way that Querqy OpenSearch Plugin could be better, we want to hear about it. We track feature requests using GitHub, so please feel free to open an issue which describes the feature you would like to see, why you need it, and how it should work.

### Documentation Changes

All Querqy related documentation is maintained in [querqy-docs repository](https://github.com/querqy/querqy-docs). Please feel free, to create issues and pull requests for missing documentation or updating the already present docs.

### Contributing Code

As with other types of contributions, the first step is to [open an issue on GitHub](https://github.com/querqy/querqy-opensearch/issues/new). 
Opening an issue before you make changes makes sure that someone else isn't already working on that particular problem. 
It also lets us all work together to find the right approach before you spend a bunch of time on a PR. So again, when in doubt, open an issue.

Our main branch is the branch used for active development. Please make sure to create PRs to this branch. Learn more about branching and releases [here](https://github.com/querqy/querqy-opensearch/blob/main/RELEASING.md#11-branch-names) 

## License Headers

New files in your code contributions should contain the following license header. If you are modifying existing files with license headers, or including new files that already have license headers, do not remove or modify them without guidance. 

```
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
```


## Review Process

We deeply appreciate everyone who takes the time to make a contribution. We will review all contributions as quickly as possible. As a reminder, [opening an issue](https://github.com/querqy/querqy-opensearch/issues/new) discussing your change before you make it is the best way to smooth the PR process. This will prevent a rejection because someone else is already working on the problem, or because the solution is incompatible with the architectural direction.

During the PR process, expect that there will be some back-and-forth. Please try to respond to comments in a timely fashion, and if you don't wish to continue with the PR, let us know. If a PR takes too many iterations for its complexity or size, we may reject it. Additionally, if you stop responding we may close the PR as abandoned. In either case, if you feel this was done in error, please add a comment on the PR.

If we accept the PR, a [maintainer](https://github.com/querqy/querqy-opensearch/graphs/contributors) will merge your change and usually take care of backporting it to appropriate branches ourselves.

If we reject the PR, we will close the pull request with a comment explaining why. This decision isn't always final: if you feel we have misunderstood your intended change or otherwise think that we should reconsider then please continue the conversation with a comment on the PR and we'll do our best to address any further points you raise.
