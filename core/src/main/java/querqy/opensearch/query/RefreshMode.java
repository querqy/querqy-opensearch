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

package querqy.opensearch.query;

import java.util.Locale;

/**
 * Controls how up to date a rewriter's configuration must be for a single search request, overriding the default
 * time-based async refresh ({@code querqy.caches.rewriter.refresh_after_write}, see {@code RewriterShardContext}) on
 * a per-query (or per-rewriter) basis. Settable at the query level ({@code "refresh_config"}) and/or per rewriter
 * entry (where it overrides the query-level value) - see {@link QuerqyQueryBuilder} and {@link Rewriter}.
 *
 * <p><b>Cost:</b> {@code doToQuery}/the rewrite chain runs once per shard of the searched index, each shard with its
 * own independent cache - so {@code CHECK}/{@code FORCE} cost scales with shard count: N shards means N independent
 * blocking round trips to the {@code .opensearch-querqy} index per query, not one. This is fine for interactively
 * trying out a config change at low QPS (the motivating use case), but these modes should not be set as a blanket
 * default for production query traffic.</p>
 */
public enum RefreshMode {

    /**
     * Default. Unchanged from the plugin's normal behavior: a cached rewriter is used as-is; if it's older than
     * {@code refresh_after_write}, a non-blocking background refresh is kicked off, but the current query is never
     * delayed by it.
     */
    NO,

    /**
     * Synchronously checks whether the rewriter's stored configuration has changed (comparing the document's
     * sequence number/primary term) and reloads it if so, before continuing with this query. Always pays one
     * blocking round trip to the index per shard, like {@link #FORCE}, but only pays the cost of rebuilding the
     * rewriter (re-parsing its configuration) when something actually changed.
     */
    CHECK,

    /**
     * Synchronously reloads the rewriter unconditionally before continuing with this query, regardless of whether
     * its configuration actually changed. Always pays one blocking round trip to the index per shard, like
     * {@link #CHECK}, and always rebuilds the rewriter.
     */
    FORCE;

    public static RefreshMode fromString(final String value) {
        try {
            return RefreshMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid refresh_config value '" + value
                    + "', expected one of: no, check, force");
        }
    }
}
