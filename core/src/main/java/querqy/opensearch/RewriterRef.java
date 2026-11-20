/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Querqy for OpenSearch Contributors
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

package querqy.opensearch;

import querqy.opensearch.query.RefreshMode;

/**
 * A rewriter ID plus the {@link RefreshMode} that applies to it for one query - internal to how
 * {@link QuerqyProcessor} hands rewriter references down to {@link RewriterShardContexts}/{@link RewriterShardContext}.
 * No external contract to preserve (unlike {@code querqy.opensearch.query.Rewriter}, the public, wire-serialized
 * query-DSL type this is resolved from).
 */
public final class RewriterRef {

    private final String rewriterId;
    private final RefreshMode refreshMode;

    public RewriterRef(final String rewriterId, final RefreshMode refreshMode) {
        this.rewriterId = rewriterId;
        this.refreshMode = refreshMode;
    }

    public String getRewriterId() {
        return rewriterId;
    }

    public RefreshMode getRefreshMode() {
        return refreshMode;
    }
}
