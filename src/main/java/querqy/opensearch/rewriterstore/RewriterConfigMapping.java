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

import org.apache.lucene.util.BytesRef;
import org.opensearch.OpenSearchException;
import org.opensearch.core.common.ParsingException;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public abstract class RewriterConfigMapping {

    public static final int CURRENT_MAPPING_VERSION = 4;

    public static final String PROP_VERSION = "version";
    public static final String PROP_TYPE = "type";

    /**
     * An optional, user-supplied identifier of the rewriter configuration revision. It is stored in the rewriter
     * document as it was supplied and it is not interpreted in any way.
     */
    public static final String PROP_REVISION = "revision";

    /**
     * A hash of the rewriter configuration payload. This property is never stored but always derived from the stored
     * rewriter document - see {@link #computeConfigHash(String, Map)}.
     */
    public static final String PROP_CONFIG_HASH = "config_hash";

    private static final String CONFIG_HASH_ALGORITHM = "SHA-256";

    public static final RewriterConfigMapping CURRENT = new RewriterConfigMapping() {

        @Override
        public String getConfigStringProperty() {
            return "config_v_003";
        }

        @Override
        public boolean isConfigStoredCanonically() {
            return true;
        }

    };

    public static final RewriterConfigMapping V3_MAPPING = new RewriterConfigMapping() {

        @Override
        public String getConfigStringProperty() {
            return "config_v_003";
        }

    };

    public static final RewriterConfigMapping PRE3_MAPPING = new RewriterConfigMapping() {

        @Override
        public String getConfigStringProperty() {
            return "config";
        }

    };


    public abstract String getConfigStringProperty();

    public String getRewriterClassNameProperty() {
        return "class";
    }

    public String getInfoLoggingProperty() {
        return "info_logging";
    }

    public String getRewriterClassName(final String rewriterId, final Map<String, Object> source) {
        return (String) source.get(getRewriterClassNameProperty());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getInfoLoggingConfig(final String rewriterId, final Map<String, Object> source) {
        return (Map<String, Object>) source.get(getInfoLoggingProperty());
    }

    /**
     * @return true iff this mapping stores the rewriter config in canonical form, which allows us to derive the config
     * hash from the stored value without parsing it again.
     */
    public boolean isConfigStoredCanonically() {
        return false;
    }


    public static RewriterConfigMapping getMapping(final Map<String, Object> source) {

        final Integer version = (Integer) source.get(PROP_VERSION);

        if (version == null) {
            return PRE3_MAPPING;
        }

        if (version == 3) {
            return V3_MAPPING;
        }

        if (version == CURRENT_MAPPING_VERSION) {
            return CURRENT;
        }

        throw new IllegalArgumentException("Unknown rewriter config version: " + version);

    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toLuceneSource(final Map<String, Object> putRequestContent) throws IOException {
        final Map<String, Object> source = new HashMap<>(putRequestContent.size() + 4);
        source.put(PROP_TYPE, "rewriter");
        source.put(PROP_VERSION, CURRENT_MAPPING_VERSION);
        source.put(CURRENT.getRewriterClassNameProperty(), putRequestContent.get("class"));

        final Map<String, Object> infoLoggingConfig = (Map<String, Object>) putRequestContent.get("info_logging");
        if (infoLoggingConfig != null) {
            source.put(CURRENT.getInfoLoggingProperty(), infoLoggingConfig);
        }

        final Object revision = putRequestContent.get(PROP_REVISION);
        if (revision != null) {
            source.put(PROP_REVISION, revision.toString());
        }

        final Map<String, Object> config = (Map<String, Object>) putRequestContent.get("config");
        if (config != null) {
            // We store the config in canonical form so that the config hash can be derived from the stored value
            // without parsing it again and so that it doesn't depend on map iteration order.
            final String jsonString = toCanonicalJsonString(config);
            // See constraints in org.elasticsearch.index.mapper.KeywordFieldMapper.indexValue()
            source.put(CURRENT.getConfigStringProperty(), stringToSourceValue(jsonString, 32766));
        }

        return source;
    }

    /**
     * Lucene doesn't allow us to save Strings in keywords fields if their UTF-8-encoded version exceeds a certain byte
     * array length. This method splits Strings into an array of Strings whose elements are guaranteed not to exceed
     * that limit. If the input string does not exceed the limit, the method returns the input string.
     *
     * @param string The input string
     * @param maxUTFByteLength The max length
     * @return A String or an array of Strings
     */
    public static Object stringToSourceValue(final String string, final int maxUTFByteLength) {
        final BytesRef binaryValue = new BytesRef(string);
        if (binaryValue.length <= maxUTFByteLength) {
            return string;
        }
        if (maxUTFByteLength < 3) {
            // max UTF encoding length for a single char is 3 bytes
            throw new IllegalArgumentException("maxUTFByteLength >=3 expected");
        }
        final List<String> splits = new ArrayList<>();
        String s = string;
        while (new BytesRef(s).length > maxUTFByteLength) {
            String split = s;
            do {
                int length = Math.max(1, Math.min((int) Math.floor(split.length() * 0.95), maxUTFByteLength));
                split = split.substring(0, length);
            } while (new BytesRef(split).length > maxUTFByteLength);
            splits.add(split);
            s = s.substring(split.length());
        }
        if (s.length() > 0) {
            splits.add(s);
        }
        return splits.toArray(new String[0]);
    }

    public Map<String, Object> getConfig(final String rewriterId, final Map<String, Object> source) {

        final String configStr = getConfigString(source);

        if (configStr == null) {
            return Collections.emptyMap();
        }

        final XContentParser parser;
        try {
            parser = XContentHelper.createParser(null, null, new BytesArray(configStr), XContentType.JSON);
        } catch (final IOException e) {
            throw new OpenSearchException(e);
        }
        try {
            return parser.map();
        } catch (final IOException e) {
            throw new ParsingException(parser.getTokenLocation(), "Could not load 'config' of rewriter " + rewriterId);
        }

    }

    /**
     * @param source The stored rewriter document
     * @return The revision that was supplied by the user when the rewriter was saved, or null if the user didn't
     * supply any revision information.
     */
    public String getRevision(final Map<String, Object> source) {
        final Object revision = source.get(PROP_REVISION);
        return revision == null ? null : revision.toString();
    }

    /**
     * <p>Computes a hash over the configuration payload of a stored rewriter: the rewriter class, the rewriter config
     * and the info logging config. {@link #PROP_REVISION} is not part of the hash.</p>
     *
     * <p>The hash is never stored but always derived from the stored document. This keeps it from becoming stale if a
     * rewriter document is written without using the rewriter API - by bulk indexing, reindexing or restoring a
     * snapshot, for example.</p>
     *
     * @param rewriterId The rewriter ID (used in error messages only)
     * @param source The stored rewriter document
     * @return The hash, as a lower-case hex string
     */
    public String computeConfigHash(final String rewriterId, final Map<String, Object> source) {

        final StringBuilder hashInput = new StringBuilder(256);

        try {

            hashInput.append("{\"class\":").append(toCanonicalJsonString(getRewriterClassName(rewriterId, source)));

            final String configString = getConfigString(source);
            if (configString != null) {
                hashInput.append(",\"config\":").append(isConfigStoredCanonically()
                        ? configString
                        : toCanonicalJsonString(getConfig(rewriterId, source)));
            }

            final Map<String, Object> infoLoggingConfig = getInfoLoggingConfig(rewriterId, source);
            if (infoLoggingConfig != null) {
                hashInput.append(",\"info_logging\":").append(toCanonicalJsonString(infoLoggingConfig));
            }

            hashInput.append('}');

        } catch (final IOException e) {
            throw new OpenSearchException("Could not compute the config hash of rewriter " + rewriterId, e);
        }

        return hash(hashInput.toString());
    }

    /**
     * @param source The stored rewriter document
     * @return The stored config as a JSON string, joining the parts of a config that had to be split up on saving, or
     * null if this document doesn't have a config.
     */
    @SuppressWarnings("unchecked")
    protected String getConfigString(final Map<String, Object> source) {

        final Object configStringValue = source.get(getConfigStringProperty());

        final String configStr;
        if (configStringValue == null) {
            configStr = null;
        } else if (configStringValue instanceof String) {
            configStr = ((String) configStringValue).trim();
        } else if (configStringValue instanceof List) {
            // this is what we get for a config that had to be split up when we read the document from the index
            configStr = String.join("", (Iterable<? extends CharSequence>) configStringValue).trim();
        } else if (configStringValue instanceof String[]) {
            // ... and this is what stringToSourceValue produces for such a config
            configStr = String.join("", (String[]) configStringValue).trim();
        } else {
            throw new IllegalArgumentException("Unexpected config value class: " + configStringValue);
        }

        return (configStr == null || configStr.isEmpty()) ? null : configStr;
    }

    /**
     * Renders a value as a JSON string, sorting the properties of all (nested) objects by property name so that the
     * output depends on the value alone and not on map iteration order.
     *
     * @param value The value to render
     * @return The JSON representation of the value
     * @throws IOException if the value cannot be rendered
     */
    static String toCanonicalJsonString(final Object value) throws IOException {
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            final XContentBuilder builder = new XContentBuilder(XContentType.JSON.xContent(), bos);
            builder.value(canonicalize(value));
            builder.flush();
            builder.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Object canonicalize(final Object value) {

        if (value instanceof Map) {

            final Map<String, Object> canonicalized = new TreeMap<>();
            for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                canonicalized.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return canonicalized;

        }

        if (value instanceof Collection) {

            final Collection<?> collection = (Collection<?>) value;
            final List<Object> canonicalized = new ArrayList<>(collection.size());
            for (final Object element : collection) {
                canonicalized.add(canonicalize(element));
            }
            return canonicalized;

        }

        if (value instanceof Object[]) {

            final Object[] array = (Object[]) value;
            final List<Object> canonicalized = new ArrayList<>(array.length);
            for (final Object element : array) {
                canonicalized.add(canonicalize(element));
            }
            return canonicalized;

        }

        return value;
    }

    private static String hash(final String input) {

        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(CONFIG_HASH_ALGORITHM);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(CONFIG_HASH_ALGORITHM + " is not available", e);
        }

        final byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

}