/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2021 Querqy for OpenSearch Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package querqy.opensearch.rewriterstore;

public interface Constants {

    // TODO: configurable?
    String QUERQY_INDEX_NAME = ".opensearch-querqy";

    String QUERQY_REWRITER_BASE_ROUTE = "/_plugins/_querqy/rewriter";

    String SETTINGS_QUERQY_INDEX_NUM_REPLICAS = "querqy.store.replicas";

    int DEFAULT_QUERQY_INDEX_NUM_REPLICAS = 1;

    /**
     * The index holds at most a few hundred small rewriter-config documents, and a {@code GET}/{@code PUT} by
     * rewriter ID already routes to exactly one shard regardless of shard count - there is no workload here that
     * benefits from more than one shard, only allocation overhead. Fixed, not configurable: unlike
     * {@link #SETTINGS_QUERQY_INDEX_NUM_REPLICAS}, there's no legitimate reason for an operator to want a different
     * value. Some managed OpenSearch offerings default new indices to more than one shard, so this is set
     * explicitly at index-creation time rather than left to the cluster default.
     */
    int QUERQY_INDEX_NUM_SHARDS = 1;
}