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

package querqy.opensearch.rewriterstore;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.opensearch.action.get.GetResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The rewriter index must be usable on a node that doesn't have this plugin installed at all (no plugins here): its
 * mapping must not depend on any plugin-provided field type. Otherwise the index can't be copied to a cluster
 * without the plugin - which is exactly what Amazon OpenSearch Service's blue/green deployment does when the plugin
 * is updated or removed (see querqy-opensearch#209).
 */
public class QuerqyIndexWithoutPluginTest extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.emptyList();
    }

    public void testIndexWithBundledMappingWorksWithoutThePlugin() throws Exception {

        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME)
                .setMapping(QuerqyIndexManager.readMappingResource()).get();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory");
        content.put("config", Collections.singletonMap("rules", "a =>\nSYNONYM: b"));
        client().prepareIndex(QUERQY_INDEX_NAME).setId("rewriter")
                .setSource(RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        client().prepareIndex(QUERQY_INDEX_NAME).setId("dict")
                .setSource(TermCorpusConfigMapping.toLuceneSource("dict", TermCorpusConfigMapping.FORMAT_TSV_DF,
                        true, null, null, "haus\t10\n".getBytes(StandardCharsets.UTF_8),
                        System.currentTimeMillis()))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        final GetResponse rewriter = client().prepareGet(QUERQY_INDEX_NAME, "rewriter").get();
        assertTrue(rewriter.isExists());
        assertEquals("querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory",
                RewriterConfigMapping.CURRENT.getRewriterClassName("rewriter", rewriter.getSourceAsMap()));

        final GetResponse dictionary = client().prepareGet(QUERQY_INDEX_NAME, "dict").get();
        assertTrue(dictionary.isExists());
        assertNotNull(TermCorpusConfigMapping.buildVerifiedTermCorpus(
                TermCorpusConfigMapping.getCorpus("dict", dictionary.getSourceAsMap())));
    }

    /**
     * Same check as above, but for an existing index whose mapping is migrated by adding the properties computed by
     * {@link QuerqyIndexManager#computeMissingProperties(Map)} - those must not use a plugin-provided type either.
     */
    public void testMigratedPropertiesWorkWithoutThePlugin() {

        client().admin().indices().prepareCreate(QUERQY_INDEX_NAME).setMapping("{\"properties\":{}}").get();

        final Map<String, String> missing = QuerqyIndexManager.computeMissingProperties(Collections.emptyMap());
        assertTrue(missing.containsKey(RewriterConfigMapping.CURRENT.getConfigStringProperty()));
        assertTrue(missing.containsKey(TermCorpusConfigMapping.PROP_CORPUS));

        assertTrue(client().admin().indices().preparePutMapping(QUERQY_INDEX_NAME)
                .setSource(QuerqyIndexManager.buildPutMappingBody(missing), XContentType.JSON)
                .get().isAcknowledged());
    }
}
