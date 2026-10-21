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

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.junit.After;
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import querqy.opensearch.rewriterstore.QuerqyIndexManager;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.rewrite.RewriterFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Proves the central claim behind {@link RewriterFactoryLoader#nodeLevel(Settings, Client, boolean)}: loading
 * and building a {@link RewriterFactory} from a rewriter-config document needs nothing shard-specific at all -
 * no {@code IndexService}, no {@code IndexShard}, not even an index with any local shard on this node. This is
 * the prerequisite a coordinator-side consumer (e.g. a future search pipeline processor, which runs with no
 * shard access whatsoever) needs: unlike {@link RewriterShardContext} (keyed by {@code ShardId}, resolved via
 * {@code IndicesService}), {@link RewriterFactoryLoader#nodeLevel} only ever needs a {@link
 * org.opensearch.transport.client.Client}.
 */
public class RewriterFactoryLoaderTest extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyCorePlugin.class);
    }

    @After
    public void deleteRewriterIndex() {
        try {
            client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
        } catch (final IndexNotFoundException e) {
            // Ignore
        }
    }

    private void ensureRewriterIndexExists() throws Exception {
        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();
    }

    private void writeCommonRulesRewriter(final String rewriterId, final String rules) throws Exception {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        client().prepareIndex(QUERQY_INDEX_NAME).setId(rewriterId)
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
    }

    /**
     * No index named in this test ever has any document indexed into it (other than {@code .opensearch-querqy}
     * itself, which the loader only ever accesses via plain document GETs, never via a shard reference) - so
     * there is no "target" {@code IndexShard} anywhere this loader could have reached for even if it wanted to.
     */
    public void testLoadsAndBuildsRewriterFactoryWithNoShardAccessAtAll() throws Exception {

        ensureRewriterIndexExists();
        writeCommonRulesRewriter("node_level_rewriter", "k =>\nSYNONYM: c");

        final RewriterFactoryLoader loader = RewriterFactoryLoader.nodeLevel(Settings.EMPTY, client(), false);

        final RewriterFactoryAndLogging loaded = loader.loadFactory("node_level_rewriter", false);

        assertNotNull(loaded);
        final RewriterFactory factory = loaded.rewriterFactory;
        assertNotNull(factory);
        assertEquals("node_level_rewriter", factory.getRewriterId());

        // cached on the second call too, still with no shard involved
        final RewriterFactoryAndLogging loadedAgain = loader.loadFactory("node_level_rewriter", false);
        assertSame(factory, loadedAgain.rewriterFactory);
    }

    public void testUnknownRewriterFailsClearlyWithNoShardAccess() throws Exception {

        ensureRewriterIndexExists();

        final RewriterFactoryLoader loader = RewriterFactoryLoader.nodeLevel(Settings.EMPTY, client(), false);

        final ResourceNotFoundException e = expectThrows(ResourceNotFoundException.class,
                () -> loader.loadFactory("does_not_exist", false));
        assertEquals("Rewriter not found: does_not_exist", e.getMessage());
    }
}
