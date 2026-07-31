/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2022 Querqy for OpenSearch Contributors
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

import static querqy.opensearch.rewriterstore.RewriterConfigMapping.CURRENT;
import static querqy.opensearch.rewriterstore.RewriterConfigMapping.CURRENT_MAPPING_VERSION;
import static querqy.opensearch.rewriterstore.RewriterConfigMapping.PROP_REVISION;
import static querqy.opensearch.rewriterstore.RewriterConfigMapping.PROP_TYPE;
import static querqy.opensearch.rewriterstore.RewriterConfigMapping.PROP_VERSION;
import static querqy.opensearch.rewriterstore.RewriterConfigMapping.PRE3_MAPPING;
import static querqy.opensearch.rewriterstore.RewriterConfigMapping.V3_MAPPING;
import static querqy.opensearch.rewriterstore.RewriterConfigMapping.getMapping;

import org.opensearch.test.OpenSearchTestCase;
import org.apache.lucene.util.BytesRef;
import org.hamcrest.Matchers;
import static org.hamcrest.Matchers.everyItem;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class RewriterConfigMappingTest extends OpenSearchTestCase {

    private static final String CLASS_NAME = "querqy.opensearch.DummyOpenSearchRewriterFactory";

    /** 2026-07-29T08:15:30.123Z */
    private static final long SAVED_AT_MILLIS = 1785312930123L;

    public void testStringToSourceValue() {
        String s = "123456789ä";
        if (new BytesRef(s).length != 11) {
            throw new IllegalStateException("Test assumptions are wrong: unexpected encoding size: " +
                    new BytesRef(s).length);
        }
        assertEquals(s, RewriterConfigMapping.stringToSourceValue(s, 20));
        assertEquals(s, RewriterConfigMapping.stringToSourceValue(s, 11));

        final Object o1 = RewriterConfigMapping.stringToSourceValue(s, 10);
        assertTrue(o1.getClass().isArray());
        final String[] splits = (String[]) o1;
        assertEquals(2, splits.length);
        assertEquals(s, splits[0] + splits[1]);
        assertTrue(new BytesRef(splits[0]).length <= 10);
        assertTrue(new BytesRef(splits[1]).length <= 10);

        final Object o2 = RewriterConfigMapping.stringToSourceValue(s, 3);
        assertTrue(o2.getClass().isArray());
        final String[] arr = (String[]) o2;
        assertThat(Arrays.stream(arr).map(BytesRef::new).map(bytesRef -> bytesRef.length).collect(Collectors.toList()),
                everyItem(Matchers.lessThanOrEqualTo(3)));
        assertEquals(s, String.join("", arr));

    }

    public void testStringToSourceValueWithIllegalLimit() {
        expectThrows(IllegalArgumentException.class, () -> RewriterConfigMapping.stringToSourceValue("12345", 2));
    }

    public void testThatMappingIsSelectedByVersion() {

        assertSame(PRE3_MAPPING, getMapping(Collections.emptyMap()));
        assertSame(V3_MAPPING, getMapping(Collections.singletonMap(PROP_VERSION, 3)));
        assertSame(CURRENT, getMapping(Collections.singletonMap(PROP_VERSION, 4)));

        expectThrows(IllegalArgumentException.class,
                () -> getMapping(Collections.singletonMap(PROP_VERSION, CURRENT_MAPPING_VERSION + 1)));
    }

    public void testThatDocumentIsSavedWithCurrentMappingVersion() throws IOException {

        final Map<String, Object> source = toLuceneSource(putContent(config(), null));

        assertEquals(CURRENT_MAPPING_VERSION, source.get(PROP_VERSION));
        assertEquals("rewriter", source.get(PROP_TYPE));
        assertSame(CURRENT, getMapping(source));
    }

    public void testThatConfigIsStoredCanonically() throws IOException {

        final String stored = (String) toLuceneSource(putContent(config(), null))
                .get(CURRENT.getConfigStringProperty());

        assertEquals("{\"a\":{\"c\":3,\"d\":2},\"b\":1,\"list\":[\"x\",\"y\"]}", stored);
    }

    public void testThatCanonicalConfigDoesNotDependOnPropertyOrder() throws IOException {

        final Object stored1 = toLuceneSource(putContent(config(), null))
                .get(CURRENT.getConfigStringProperty());
        final Object stored2 = toLuceneSource(putContent(configInDifferentOrder(), null))
                .get(CURRENT.getConfigStringProperty());

        assertEquals(stored1, stored2);
    }

    public void testThatRevisionIsSavedAndRead() throws IOException {

        final Map<String, Object> source = toLuceneSource(putContent(config(), "release-2026-07-29"));

        assertEquals("release-2026-07-29", source.get(PROP_REVISION));
        assertEquals("release-2026-07-29", getMapping(source).getRevision(source));
    }

    public void testThatRevisionIsNotSavedIfItWasNotSupplied() throws IOException {

        final Map<String, Object> source = toLuceneSource(putContent(config(), null));

        assertFalse(source.containsKey(PROP_REVISION));
        assertNull(getMapping(source).getRevision(source));
    }

    public void testThatConfigHashDoesNotDependOnPropertyOrder() throws IOException {

        assertEquals(configHashOf(putContent(config(), null)),
                configHashOf(putContent(configInDifferentOrder(), null)));
    }

    public void testThatConfigHashDoesNotDependOnRevision() throws IOException {

        assertEquals(configHashOf(putContent(config(), null)),
                configHashOf(putContent(config(), "release-2026-07-29")));
    }

    public void testThatConfigHashChangesWithConfig() throws IOException {

        final Map<String, Object> changedConfig = config();
        changedConfig.put("b", 2);

        assertNotEquals(configHashOf(putContent(config(), null)), configHashOf(putContent(changedConfig, null)));
    }

    public void testThatConfigHashChangesWithListOrder() throws IOException {

        final Map<String, Object> changedConfig = config();
        changedConfig.put("list", Arrays.asList("y", "x"));

        assertNotEquals(configHashOf(putContent(config(), null)), configHashOf(putContent(changedConfig, null)));
    }

    public void testThatConfigHashChangesWithRewriterClass() throws IOException {

        final Map<String, Object> content = putContent(config(), null);
        content.put("class", CLASS_NAME + "2");

        assertNotEquals(configHashOf(putContent(config(), null)), configHashOf(content));
    }

    public void testThatConfigHashChangesWithInfoLoggingConfig() throws IOException {

        final Map<String, Object> content = putContent(config(), null);
        content.put("info_logging", Collections.singletonMap("sinks", Collections.singletonList("log4j")));

        assertNotEquals(configHashOf(putContent(config(), null)), configHashOf(content));
    }

    public void testThatConfigHashIsIndependentOfConfigAbsence() throws IOException {

        // a rewriter without any config must still produce a hash, and a different one than a rewriter with a config
        final String hashWithoutConfig = configHashOf(putContent(null, null));

        assertNotNull(hashWithoutConfig);
        assertNotEquals(hashWithoutConfig, configHashOf(putContent(config(), null)));
    }

    public void testThatConfigHashOfV3DocumentEqualsHashOfCurrentDocument() throws IOException {

        final Map<String, Object> v4Source = toLuceneSource(putContent(config(), null));

        // a document as it was saved by mapping version 3: the config was not stored in canonical form
        final Map<String, Object> v3Source = new HashMap<>();
        v3Source.put(PROP_TYPE, "rewriter");
        v3Source.put(PROP_VERSION, 3);
        v3Source.put("class", CLASS_NAME);
        v3Source.put(V3_MAPPING.getConfigStringProperty(),
                "{\"b\": 1, \"list\": [\"x\", \"y\"], \"a\": {\"d\": 2, \"c\": 3}}");

        assertSame(V3_MAPPING, getMapping(v3Source));
        assertEquals(getMapping(v4Source).computeConfigHash("r1", v4Source),
                getMapping(v3Source).computeConfigHash("r1", v3Source));
    }

    public void testThatConfigHashIsComputedForConfigThatHadToBeSplitUp() throws IOException {

        final StringBuilder rules = new StringBuilder();
        while (rules.length() < 40000) {
            rules.append("k").append(rules.length()).append(" =>\n  SYNONYM: c\n");
        }

        final Map<String, Object> source = toLuceneSource(
                putContent(Collections.singletonMap("rules", rules.toString()), null));

        final Object configValue = source.get(CURRENT.getConfigStringProperty());
        if (!(configValue instanceof String[])) {
            throw new IllegalStateException("Test assumptions are wrong: config was not split up");
        }

        final String hashOfSplitConfig = CURRENT.computeConfigHash("r1", source);

        // the hash must not depend on how the config was split up...
        final Map<String, Object> unsplitSource = new HashMap<>(source);
        unsplitSource.put(CURRENT.getConfigStringProperty(), String.join("", (String[]) configValue));
        assertEquals(CURRENT.computeConfigHash("r1", unsplitSource), hashOfSplitConfig);

        // ... and not on whether the document was just built or read back from the index, where the parts of the
        // config arrive as a List
        final Map<String, Object> sourceFromIndex = new HashMap<>(source);
        sourceFromIndex.put(CURRENT.getConfigStringProperty(), Arrays.asList((String[]) configValue));
        assertEquals(CURRENT.computeConfigHash("r1", sourceFromIndex), hashOfSplitConfig);
    }

    public void testThatConfigHashIsAHexEncodedSha256() throws IOException {

        final String hash = configHashOf(putContent(config(), null));

        assertEquals(64, hash.length());
        assertTrue("Not a lower-case hex string: " + hash, hash.matches("[0-9a-f]{64}"));
    }

    public void testThatConfigCanBeReadBackFromCanonicalDocument() throws IOException {

        final Map<String, Object> source = toLuceneSource(putContent(config(), null));

        assertEquals(config(), getMapping(source).getConfig("r1", source));
    }

    public void testThatSavedAtIsSavedAsIso8601() throws IOException {

        final Map<String, Object> source = toLuceneSource(putContent(config(), null));

        assertEquals("2026-07-29T08:15:30.123Z", source.get(RewriterConfigMapping.PROP_SAVED_AT));
        assertEquals("2026-07-29T08:15:30.123Z", getMapping(source).getSavedAt(source));
    }

    public void testThatSavedAtIsNotSavedIfNoTimestampIsSupplied() throws IOException {

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(putContent(config(), null), null);

        assertFalse(source.containsKey(RewriterConfigMapping.PROP_SAVED_AT));
        assertNull(getMapping(source).getSavedAt(source));
    }

    public void testThatSavedAtSuppliedByTheUserIsIgnored() throws IOException {

        final Map<String, Object> content = putContent(config(), null);
        content.put(RewriterConfigMapping.PROP_SAVED_AT, "1970-01-01T00:00:00Z");

        assertEquals("2026-07-29T08:15:30.123Z",
                toLuceneSource(content).get(RewriterConfigMapping.PROP_SAVED_AT));
    }

    public void testThatSavedAtIsReadFromEpochMillis() {

        // the date field type also accepts epoch millis, which we could see in a document that wasn't written by the
        // rewriter API
        final Map<String, Object> source = new HashMap<>();
        source.put(PROP_VERSION, CURRENT_MAPPING_VERSION);
        source.put(RewriterConfigMapping.PROP_SAVED_AT, SAVED_AT_MILLIS);

        assertEquals("2026-07-29T08:15:30.123Z", CURRENT.getSavedAt(source));
    }

    public void testThatConfigHashDoesNotDependOnSavedAt() throws IOException {

        final Map<String, Object> source1 = RewriterConfigMapping
                .toLuceneSource(putContent(config(), null), SAVED_AT_MILLIS);
        final Map<String, Object> source2 = RewriterConfigMapping
                .toLuceneSource(putContent(config(), null), SAVED_AT_MILLIS + 86_400_000L);

        assertNotEquals(source1.get(RewriterConfigMapping.PROP_SAVED_AT),
                source2.get(RewriterConfigMapping.PROP_SAVED_AT));
        assertEquals(CURRENT.computeConfigHash("r1", source1), CURRENT.computeConfigHash("r1", source2));
    }

    private static String configHashOf(final Map<String, Object> putRequestContent) throws IOException {
        final Map<String, Object> source = toLuceneSource(putRequestContent);
        return getMapping(source).computeConfigHash("r1", source);
    }

    private static Map<String, Object> toLuceneSource(final Map<String, Object> putRequestContent) throws IOException {
        return RewriterConfigMapping.toLuceneSource(putRequestContent, SAVED_AT_MILLIS);
    }

    private static Map<String, Object> putContent(final Map<String, Object> config, final String revision) {

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("class", CLASS_NAME);

        if (config != null) {
            content.put("config", config);
        }

        if (revision != null) {
            content.put(PROP_REVISION, revision);
        }

        return content;
    }

    private static Map<String, Object> config() {

        final Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("d", 2);
        nested.put("c", 3);

        final Map<String, Object> config = new LinkedHashMap<>();
        config.put("b", 1);
        config.put("a", nested);
        config.put("list", Arrays.asList("x", "y"));

        return config;
    }

    /**
     * @return The same config as {@link #config()} but with the properties of all objects in a different order
     */
    private static Map<String, Object> configInDifferentOrder() {

        final Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("c", 3);
        nested.put("d", 2);

        final Map<String, Object> config = new LinkedHashMap<>();
        config.put("list", Arrays.asList("x", "y"));
        config.put("a", nested);
        config.put("b", 1);

        return config;
    }

}