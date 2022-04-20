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

import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.opensearch.client.IndicesAdminClient;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import org.junit.After;
import org.junit.Test;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class QuerqyMappingsUpdate2To3IntegrationTest extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }


    @After
    public void deleteRewriterIndex() {
        try {
            client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
        } catch (final IndexNotFoundException e) {
            // Ignore
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpdate2To3() throws Exception {

        final String v2Mapping = "{\n" +
                "    \"properties\": {\n" +
                "      \"class\": {\"type\": \"keyword\"},\n" +
                "      \"type\": {\"type\": \"keyword\"},\n" +
                "      \"info_logging\": {\n" +
                "        \"properties\": {\n" +
                "          \"sinks\": {\"type\" : \"keyword\" }\n" +
                "        }\n" +
                "      },\n" +
                "      \"config\": {\n" +
                "        \"type\" : \"keyword\",\n" +
                "        \"index\": false\n" +
                "      }\n" +
                "\n" +
                "    }\n" +
                "}";

        final IndicesAdminClient indicesClient = client().admin().indices();

        final CreateIndexRequestBuilder createIndexRequestBuilder = indicesClient.prepareCreate(QUERQY_INDEX_NAME);
        final CreateIndexRequest createIndexRequest = createIndexRequestBuilder
                .addMapping("querqy-rewriter", v2Mapping, XContentType.JSON)
                .setSettings(Settings.builder().put("number_of_replicas", 2))
                .request();
        indicesClient.create(createIndexRequest).get();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest("common_rules", content)).get();

        final GetMappingsRequest getMappingsRequest = new GetMappingsRequest().indices(QUERQY_INDEX_NAME);
        final ImmutableOpenMap<String, ImmutableOpenMap<String, MappingMetadata>> mappings = indicesClient
                .getMappings(getMappingsRequest).get().getMappings();
        final Map<String, Object> properties = (Map<String, Object>) mappings.get(QUERQY_INDEX_NAME)
                .get("querqy-rewriter").getSourceAsMap().get("properties");
        assertNotNull(properties);
        final Map<String, Object> info_logging = (Map<String, Object>) properties.get("info_logging");
        assertNotNull(info_logging);
        final Map<String, Object> info_logging_props = (Map<String, Object>) info_logging.get("properties");
        assertNotNull(info_logging_props);

        assertThat( (Map<String, Object>) info_logging_props.get("sinks"), hasEntry("type", "keyword"));

        final Map<String, Object> config_v_003_mapping = (Map<String, Object>) properties.get("config_v_003");
        assertNotNull(config_v_003_mapping);
        assertEquals(false, config_v_003_mapping.get("doc_values"));

    }
}
