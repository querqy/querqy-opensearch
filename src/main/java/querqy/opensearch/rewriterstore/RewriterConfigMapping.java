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

package querqy.opensearch.rewriterstore;

import org.opensearch.OpenSearchException;
import org.opensearch.common.ParsingException;
import org.opensearch.common.bytes.BytesArray;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.common.xcontent.XContentType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public abstract class RewriterConfigMapping {

    public static final int CURRENT_MAPPING_VERSION = 3;

    public static final String PROP_VERSION = "version";
    public static final String PROP_TYPE = "type";

    public static final RewriterConfigMapping CURRENT = new RewriterConfigMapping() {

        @Override
        public String getConfigStringProperty() {
            return "config_v_003";
        }

        @Override
        public String getRewriterClassNameProperty() {
            return "class";
        }

        @Override
        public String getInfoLoggingProperty() {
            return "info_logging";
        }

        @Override
        public String getRewriterClassName(final String rewriterId, final Map<String, Object> source) {
            return (String) source.get(getRewriterClassNameProperty());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> getInfoLoggingConfig(final String rewriterId, final Map<String, Object> source) {
            return (Map<String, Object>) source.get(getInfoLoggingProperty());
        }

    };

    public static final RewriterConfigMapping PRE3_MAPPING = new RewriterConfigMapping() {

        @Override
        public String getConfigStringProperty() {
            return "config";
        }

        @Override
        public String getRewriterClassNameProperty() {
            return "class";
        }

        @Override
        public String getInfoLoggingProperty() {
            return "info_logging";
        }

        @Override
        public String getRewriterClassName(final String rewriterId, final Map<String, Object> source) {
            return (String) source.get(getRewriterClassNameProperty());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> getInfoLoggingConfig(final String rewriterId, final Map<String, Object> source) {
            return (Map<String, Object>) source.get(getInfoLoggingProperty());
        }

    };





    public abstract String getRewriterClassNameProperty();
    public abstract String getConfigStringProperty();
    public abstract String getInfoLoggingProperty();
    public abstract String getRewriterClassName(String rewriterId, Map<String, Object> source);
    public abstract Map<String, Object> getInfoLoggingConfig(String rewriterId, Map<String, Object> source);



    public static RewriterConfigMapping getMapping(final Map<String, Object> source) {

        final Integer version = (Integer) source.get(PROP_VERSION);

        if (version == null) {
            return PRE3_MAPPING;
        }

        if (version == CURRENT_MAPPING_VERSION) {
            return CURRENT;
        }

        throw new IllegalArgumentException("Unknown rewriter config version: " + version);

    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toLuceneSource(final Map<String, Object> putRequestContent) throws IOException {
        final Map<String, Object> source = new HashMap<>(putRequestContent.size() + 3);
        source.put(PROP_TYPE, "rewriter");
        source.put(PROP_VERSION, CURRENT_MAPPING_VERSION);
        source.put(CURRENT.getRewriterClassNameProperty(), putRequestContent.get("class"));

        final Map<String, Object> infoLoggingConfig = (Map<String, Object>) putRequestContent.get("info_logging");
        if (infoLoggingConfig != null) {
            source.put(CURRENT.getInfoLoggingProperty(), infoLoggingConfig);
        }

        final Map<String, Object> config = (Map<String, Object>) putRequestContent.get("config");
        if (config != null) {
            source.put(CURRENT.getConfigStringProperty(), mapToJsonString(config));
        }

        return source;
    }

    public Map<String, Object> getConfig(final String rewriterId, final Map<String, Object> source) {

        String configStr = (String) source.get(getConfigStringProperty());
        if (configStr != null) {
            configStr = configStr.trim();
        }

        final Map<String, Object> config;

        if (configStr != null && configStr.length() > 0) {

            final XContentParser parser;
            try {
                parser = XContentHelper.createParser(null, null, new BytesArray(configStr),
                        XContentType.JSON);
            } catch (final IOException e) {
                throw new OpenSearchException(e);
            }
            try {
                config = parser.map();
            } catch (final IOException e) {
                throw new ParsingException(parser.getTokenLocation(), "Could not load 'config' of rewriter "
                        + rewriterId);
            }

        } else {
            config = Collections.emptyMap();
        }

        return config;
    }

    private static String mapToJsonString(final Map<String, Object> map) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            final XContentBuilder builder = new XContentBuilder(XContentType.JSON.xContent(), bos);
            builder.value(map);
            builder.flush();
            builder.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

}
