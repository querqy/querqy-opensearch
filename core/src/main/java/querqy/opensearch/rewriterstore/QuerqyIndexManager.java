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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure (client-free) logic for creating/migrating the {@code .opensearch-querqy} index mapping, shared by the full
 * plugin module's {@code TransportPutRewriterAction} (which applies it via the node-local {@code IndicesAdminClient})
 * and the {@code tool} module's HTTP-based equivalent (which has no {@code IndicesAdminClient}, only a REST client).
 * Keeping the computation here means both callers stay consistent with the mapping resource and field names defined
 * in {@code core}.
 */
public final class QuerqyIndexManager {

    private QuerqyIndexManager() {
    }

    /**
     * @return The content of the {@code querqy-mapping.json} resource bundled with this module - the mapping to use
     * when creating the {@code .opensearch-querqy} index from scratch.
     */
    public static String readMappingResource() {
        try (InputStream in = QuerqyIndexManager.class.getClassLoader().getResourceAsStream("querqy-mapping.json")) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: querqy-mapping.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read querqy-mapping.json", e);
        }
    }

    /**
     * Computes the properties that are missing from an existing rewriter index's mapping, migrating it towards
     * {@link RewriterConfigMapping#CURRENT_MAPPING_VERSION}.
     *
     * @param existingProperties The properties that are currently mapped in the rewriter index
     * @return property name -&gt; mapping definition JSON fragment, for each property that needs to be added; empty
     * if nothing is missing
     */
    public static Map<String, String> computeMissingProperties(final Map<String, Object> existingProperties) {

        final RewriterConfigMapping mapping = RewriterConfigMapping.CURRENT;

        // property name -> mapping definition of that property
        final Map<String, String> missingProperties = new LinkedHashMap<>();

        if (!existingProperties.containsKey(mapping.getInfoLoggingProperty())) {
            missingProperties.put(mapping.getInfoLoggingProperty(),
                    "      \"" + mapping.getInfoLoggingProperty() + "\": {\n" +
                    "        \"properties\": {\n" +
                    "          \"sinks\": {\"type\" : \"keyword\" }\n" +
                    "        }\n" +
                    "      }");
        }

        if (!existingProperties.containsKey(RewriterConfigMapping.PROP_REVISION)) {
            missingProperties.put(RewriterConfigMapping.PROP_REVISION,
                    "      \"" + RewriterConfigMapping.PROP_REVISION + "\": {\"type\" : \"keyword\" }");
        }

        if (!existingProperties.containsKey(RewriterConfigMapping.PROP_SAVED_AT)) {
            missingProperties.put(RewriterConfigMapping.PROP_SAVED_AT,
                    "      \"" + RewriterConfigMapping.PROP_SAVED_AT + "\": {\"type\" : \"date\" }");
        }

        // mapping.getConfigStringProperty() is "config_v_005" (RewriterConfigMapping.CURRENT, mapping version 5):
        // the combined class+config field, validated at write time by the querqy_rewriter_config field mapper.
        if (!existingProperties.containsKey(mapping.getConfigStringProperty())) {
            missingProperties.put(mapping.getConfigStringProperty(),
                    "      \"" + mapping.getConfigStringProperty() + "\": {\n" +
                    "        \"type\" : \"querqy_rewriter_config\"\n" +
                    "      }");
        }

        return missingProperties;
    }

    /**
     * @param missingProperties as returned by {@link #computeMissingProperties(Map)}
     * @return The {@code {"properties": {...}}} JSON body to PUT as a mapping update
     */
    public static String buildPutMappingBody(final Map<String, String> missingProperties) {
        return "{\n" +
                "    \"properties\": {\n" +
                String.join(",\n", missingProperties.values()) + "\n" +
                "    }\n" +
                "}";
    }
}
