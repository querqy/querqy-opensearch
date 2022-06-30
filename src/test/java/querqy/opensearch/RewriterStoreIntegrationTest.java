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

package querqy.opensearch;

import org.opensearch.action.admin.cluster.node.info.NodeInfo;
import org.opensearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.opensearch.action.admin.cluster.node.info.PluginsAndModules;
import org.opensearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.opensearch.test.OpenSearchIntegTestCase.Scope.SUITE;
import static org.hamcrest.Matchers.greaterThan;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;
import static querqy.opensearch.rewriterstore.Constants.SETTINGS_QUERQY_INDEX_NUM_REPLICAS;

@OpenSearchIntegTestCase.ClusterScope(scope = SUITE, numClientNodes = 1, minNumDataNodes = 4, maxNumDataNodes = 6)
public class RewriterStoreIntegrationTest extends OpenSearchIntegTestCase {

    private static final int NUM_DOT_QUERY_REPLICAS = 2 + new Random().nextInt(4);


    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    @Override
    protected Settings nodeSettings(final int nodeOrdinal) {

        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
                .put(SETTINGS_QUERQY_INDEX_NUM_REPLICAS, NUM_DOT_QUERY_REPLICAS)
                .build();
    }

    public void testPluginIsLoaded() {

        final NodesInfoResponse response = client().admin().cluster().prepareNodesInfo().addMetric("plugins").get();
        final List<NodeInfo> nodes = response.getNodes();

        assertThat(nodes.size(), greaterThan(0));

        for (final NodeInfo nodeInfo : nodes) {
            assertTrue(nodeInfo
                    .getInfo(PluginsAndModules.class)
                    .getPluginInfos()
                    .stream()
                    .anyMatch(info -> info.getName().equals(QuerqyPlugin.class.getName())));


        }
    }


    public void testThatRewriterConfigCanUseDifferentTypeForSamePropertyName() throws Exception {

        final Map<String, Object> payload1 = new HashMap<>();
        payload1.put("class", DummyOpenSearchRewriterFactory.class.getName());
        final Map<String, Object> config1 = new HashMap<>();
        config1.put("p1", 1L); // p1 as long
        payload1.put("config", config1);

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest("r1", payload1)).get();


        final Map<String, Object> payload2 = new HashMap<>();
        payload2.put("class", DummyOpenSearchRewriterFactory.class.getName());
        final Map<String, Object> config2 = new HashMap<>();
        config2.put("p1", false); // p1 as boolean
        payload2.put("config", config2);

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest("r2", payload2)).get();


        final Map<String, Object> payload3 = new HashMap<>();
        payload3.put("class", DummyOpenSearchRewriterFactory.class.getName());
        final Map<String, Object> config3 = new HashMap<>();
        final Map<String, Object> p1 = new HashMap<>();
        p1.put("p1", "c3p1");

        config3.put("p1", p1); // p1 as object
        payload3.put("config", config3);

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest("r3", payload3)).get();

    }

    public void testThatReplicaSettingForDotQuerqyIndexIsApplied() throws Exception {
        final Map<String, Object> payload1 = new HashMap<>();
        payload1.put("class", DummyOpenSearchRewriterFactory.class.getName());
        final Map<String, Object> config1 = new HashMap<>();
        config1.put("p1", 1L);
        payload1.put("config", config1);

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest("r1", payload1)).get();

        final GetSettingsResponse idxSettings = client().admin().indices().prepareGetSettings(QUERQY_INDEX_NAME).get();

        assertNotNull(idxSettings);
        assertEquals(NUM_DOT_QUERY_REPLICAS,
                Integer.parseInt(idxSettings.getIndexToSettings().get(QUERQY_INDEX_NAME).get("index.number_of_replicas")));
    }


    public void index() {
        final String indexName = "idx";
        client().admin().indices().prepareCreate(indexName).get();
        client().prepareIndex(indexName)
                .setSource("field1", "a b", "field2", "a c")
                .get();
        client().prepareIndex(indexName)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "b c")
                .get();
    }

}
