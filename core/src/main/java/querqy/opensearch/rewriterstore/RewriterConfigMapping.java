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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public abstract class RewriterConfigMapping {

    public static final int CURRENT_MAPPING_VERSION = 5;

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

    /**
     * The point in time at which the rewriter configuration was saved, set by the node that received the
     * configuration. Saved and returned as an ISO-8601 timestamp in UTC.
     */
    public static final String PROP_SAVED_AT = "saved_at";

    private static final String CONFIG_HASH_ALGORITHM = "SHA-256";

    /**
     * Mapping version 5: class and config are combined into one canonical-JSON field
     * ({@code config_v_005}, holding {@code {"class": ..., "config": {...}}}) instead of
     * separate {@code class} and {@code config_v_003} fields. This lets a write-time
     * validator (a custom field mapper, see {@code QuerqyIndexManager}) see class and config
     * together. See the class and config of a document using this mapping must always be
     * read through {@link #rejoinStoredString(Map, String)} first, since the stored value may
     * have been split across multiple array elements by {@link #stringToSourceValue}.
     */
    public static final RewriterConfigMapping CURRENT = new RewriterConfigMapping() {

        @Override
        public String getConfigStringProperty() {
            return "config_v_005";
        }

        @Override
        public boolean isConfigStoredCanonically() {
            return true;
        }

        @Override
        public String getRewriterClassName(final String rewriterId, final Map<String, Object> source) {
            final Map<String, Object> combined = parseCombinedConfig(source);
            return combined == null ? null : (String) combined.get("class");
        }

        @Override
        protected String getConfigString(final Map<String, Object> source) {
            final Map<String, Object> combined = parseCombinedConfig(source);
            if (combined == null) {
                return null;
            }
            final Object config = combined.get("config");
            if (config == null) {
                return null;
            }
            try {
                return toCanonicalJsonString(config);
            } catch (final IOException e) {
                throw new OpenSearchException("Could not read config", e);
            }
        }

    };

    /**
     * Mapping version 4: like {@link #V3_MAPPING}, but marks the stored config as canonical
     * JSON (see {@link #isConfigStoredCanonically()}) and adds {@link #PROP_REVISION} and
     * {@link #PROP_SAVED_AT}. Kept for reading documents written before mapping version 5;
     * new documents are always written using {@link #CURRENT}.
     */
    public static final RewriterConfigMapping V4_MAPPING = new RewriterConfigMapping() {

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

        if (version == 4) {
            return V4_MAPPING;
        }

        if (version == CURRENT_MAPPING_VERSION) {
            return CURRENT;
        }

        throw new IllegalArgumentException("Unknown rewriter config version: " + version);

    }

    /**
     * @param putRequestContent The payload of the request to save the rewriter
     * @param savedAtMillis The point in time at which the rewriter is saved, in epoch millis, or null to save the
     *                      rewriter without this information
     * @return The document to save in the rewriter index
     * @throws IOException if the rewriter config cannot be serialized
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toLuceneSource(final Map<String, Object> putRequestContent,
                                                     final Long savedAtMillis) throws IOException {
        final Map<String, Object> source = new HashMap<>(putRequestContent.size() + 5);
        source.put(PROP_TYPE, "rewriter");
        source.put(PROP_VERSION, CURRENT_MAPPING_VERSION);

        if (savedAtMillis != null) {
            source.put(PROP_SAVED_AT, toIsoInstant(savedAtMillis));
        }

        final Map<String, Object> infoLoggingConfig = (Map<String, Object>) putRequestContent.get("info_logging");
        if (infoLoggingConfig != null) {
            source.put(CURRENT.getInfoLoggingProperty(), infoLoggingConfig);
        }

        final Object revision = putRequestContent.get(PROP_REVISION);
        if (revision != null) {
            source.put(PROP_REVISION, revision.toString());
        }

        // Class and config are combined into a single canonical-JSON field so that a write-time validator can see
        // both together. We store it in canonical form so that the config hash can be derived from the stored value
        // without parsing it again and so that it doesn't depend on map iteration order.
        final Map<String, Object> combined = new HashMap<>(2);
        combined.put("class", putRequestContent.get("class"));
        final Map<String, Object> config = (Map<String, Object>) putRequestContent.get("config");
        if (config != null) {
            combined.put("config", config);
        }
        final String jsonString = toCanonicalJsonString(combined);
        // Older mapping versions stored this as a real (index:false, doc_values:false) Lucene keyword field, which
        // still enforced Lucene's 32766-byte term-length limit - hence stringToSourceValue() below, used to split
        // such values. The config_v_005 field (CURRENT) uses a custom field type that never creates a Lucene field
        // at all (validated at write time, otherwise _source-only), so that limit no longer applies and the value is
        // stored as a single string, however large. Reading still supports an already-split value (for documents
        // written by an older version of this code, or by V3_MAPPING/V4_MAPPING), see rejoinStoredString().
        source.put(CURRENT.getConfigStringProperty(), jsonString);

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
     * @param source The stored rewriter document
     * @return The point in time at which the rewriter was saved, as an ISO-8601 timestamp in UTC, or null if the
     * document doesn't carry this information - which is the case for rewriters that were saved before mapping
     * version 4.
     */
    public String getSavedAt(final Map<String, Object> source) {

        final Object savedAt = source.get(PROP_SAVED_AT);

        if (savedAt == null) {
            return null;
        }

        // We always save the timestamp as an ISO-8601 string but the date field type also accepts epoch millis,
        // which is what we could see in a document that wasn't written by the rewriter API.
        return (savedAt instanceof Number) ? toIsoInstant(((Number) savedAt).longValue()) : savedAt.toString();
    }

    private static String toIsoInstant(final long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
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
    protected String getConfigString(final Map<String, Object> source) {
        return rejoinStoredString(source, getConfigStringProperty());
    }

    /**
     * Reads the (possibly split-up) string value of the given property and rejoins it into a single string.
     * Lucene doesn't allow keyword field values whose UTF-8-encoded length exceeds a certain byte length (see
     * {@link #stringToSourceValue}), so a long string may have been split into an array on write; this method
     * reassembles the original string regardless of which shape it comes back as.
     *
     * @param source The stored rewriter document
     * @param property The property to read
     * @return The rejoined string, or null if the property isn't present or is empty
     */
    @SuppressWarnings("unchecked")
    protected static String rejoinStoredString(final Map<String, Object> source, final String property) {

        final Object value = source.get(property);

        final String str;
        if (value == null) {
            str = null;
        } else if (value instanceof String) {
            str = ((String) value).trim();
        } else if (value instanceof List) {
            // this is what we get for a value that had to be split up when we read the document from the index
            str = String.join("", (Iterable<? extends CharSequence>) value).trim();
        } else if (value instanceof String[]) {
            // ... and this is what stringToSourceValue produces for such a value
            str = String.join("", (String[]) value).trim();
        } else {
            throw new IllegalArgumentException("Unexpected value class: " + value);
        }

        return (str == null || str.isEmpty()) ? null : str;
    }

    /**
     * Parses the combined {@code {"class": ..., "config": {...}}} document stored under this mapping's
     * {@link #getConfigStringProperty()} (mapping version 5 and later).
     *
     * @param source The stored rewriter document
     * @return The parsed {@code {"class": ..., "config": {...}}} map, or null if the property isn't present
     */
    protected Map<String, Object> parseCombinedConfig(final Map<String, Object> source) {
        final String combinedJson = rejoinStoredString(source, getConfigStringProperty());
        if (combinedJson == null) {
            return null;
        }
        final XContentParser parser;
        try {
            parser = XContentHelper.createParser(null, null, new BytesArray(combinedJson), XContentType.JSON);
            return parser.map();
        } catch (final IOException e) {
            throw new OpenSearchException(e);
        }
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