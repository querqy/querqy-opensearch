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

import static org.hamcrest.collection.IsMapContaining.hasEntry;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GetRewriterResponseTest extends OpenSearchTestCase {

    private static final String SAVED_AT = "2026-07-29T08:15:30.123Z";

    public void testThatStatusIsOk() {
        assertEquals(RestStatus.OK, new GetRewriterResponse(Collections.emptyList()).status());
        assertEquals(RestStatus.OK, new GetRewriterResponse(rewriterInfo("r1", "rev1", true)).status());
    }

    public void testStreamSerializationOfSingleRewriter() throws IOException {

        final GetRewriterResponse response = deserialize(new GetRewriterResponse(rewriterInfo("r1", "rev1", true)));

        assertNull(response.getRewriters());

        final RewriterInfo rewriter = response.getRewriter();
        assertNotNull(rewriter);
        assertEquals("r1", rewriter.getRewriterId());
        assertEquals("some.RewriterFactory", rewriter.getRewriterClassName());
        assertEquals("rev1", rewriter.getRevision());
        assertEquals("hash-of-r1", rewriter.getConfigHash());
        assertEquals(SAVED_AT, rewriter.getSavedAt());
        assertEquals(config(), rewriter.getConfig());
        assertEquals(Collections.singletonMap("sinks", Collections.singletonList("log4j")),
                rewriter.getInfoLoggingConfig());
    }

    public void testStreamSerializationOfRewriterWithoutOptionalProperties() throws IOException {

        final RewriterInfo rewriterInfo = new RewriterInfo("r1", "some.RewriterFactory", null, "hash-of-r1", null,
                null, null);

        final RewriterInfo deserialized = deserialize(new GetRewriterResponse(rewriterInfo)).getRewriter();

        assertNull(deserialized.getRevision());
        assertNull(deserialized.getSavedAt());
        assertNull(deserialized.getConfig());
        assertNull(deserialized.getInfoLoggingConfig());
        assertEquals("hash-of-r1", deserialized.getConfigHash());
    }

    public void testStreamSerializationOfRewriterList() throws IOException {

        final GetRewriterResponse response = deserialize(new GetRewriterResponse(
                Arrays.asList(rewriterInfo("r1", "rev1", false), rewriterInfo("r2", null, false))));

        assertNull(response.getRewriter());

        final List<RewriterInfo> rewriters = response.getRewriters();
        assertEquals(2, rewriters.size());
        assertEquals("r1", rewriters.get(0).getRewriterId());
        assertEquals("rev1", rewriters.get(0).getRevision());
        assertEquals("r2", rewriters.get(1).getRewriterId());
        assertNull(rewriters.get(1).getRevision());
        assertNull(rewriters.get(1).getConfig());
    }

    @SuppressWarnings("unchecked")
    public void testToJsonForSingleRewriter() throws IOException {

        final Map<String, Object> parsed = toMap(new GetRewriterResponse(rewriterInfo("r1", "rev1", true)));

        assertEquals(1, parsed.size());

        final Map<String, Object> rewriter = (Map<String, Object>) parsed.get(GetRewriterResponse.FIELD_REWRITER);
        assertNotNull(rewriter);

        assertThat(rewriter, hasEntry(RewriterInfo.FIELD_REWRITER_ID, "r1"));
        assertThat(rewriter, hasEntry("class", "some.RewriterFactory"));
        assertThat(rewriter, hasEntry(RewriterConfigMapping.PROP_REVISION, "rev1"));
        assertThat(rewriter, hasEntry(RewriterConfigMapping.PROP_CONFIG_HASH, "hash-of-r1"));
        assertThat(rewriter, hasEntry(RewriterConfigMapping.PROP_SAVED_AT, SAVED_AT));
        assertEquals(config(), rewriter.get("config"));
        assertNotNull(rewriter.get("info_logging"));
    }

    @SuppressWarnings("unchecked")
    public void testThatOptionalPropertiesAreOmittedInJson() throws IOException {

        final Map<String, Object> parsed = toMap(new GetRewriterResponse(
                new RewriterInfo("r1", "some.RewriterFactory", null, "hash-of-r1", null, null, null)));

        final Map<String, Object> rewriter = (Map<String, Object>) parsed.get(GetRewriterResponse.FIELD_REWRITER);

        assertFalse(rewriter.containsKey(RewriterConfigMapping.PROP_REVISION));
        assertFalse(rewriter.containsKey(RewriterConfigMapping.PROP_SAVED_AT));
        assertFalse(rewriter.containsKey("config"));
        assertFalse(rewriter.containsKey("info_logging"));
        assertThat(rewriter, hasEntry(RewriterConfigMapping.PROP_CONFIG_HASH, "hash-of-r1"));
    }

    @SuppressWarnings("unchecked")
    public void testToJsonForRewriterList() throws IOException {

        final Map<String, Object> parsed = toMap(new GetRewriterResponse(
                Arrays.asList(rewriterInfo("r1", "rev1", false), rewriterInfo("r2", null, false))));

        assertEquals(1, parsed.size());

        final List<Map<String, Object>> rewriters = (List<Map<String, Object>>) parsed
                .get(GetRewriterResponse.FIELD_REWRITERS);
        assertEquals(2, rewriters.size());

        assertThat(rewriters.get(0), hasEntry(RewriterInfo.FIELD_REWRITER_ID, "r1"));
        assertThat(rewriters.get(0), hasEntry(RewriterConfigMapping.PROP_CONFIG_HASH, "hash-of-r1"));
        assertThat(rewriters.get(0), hasEntry(RewriterConfigMapping.PROP_SAVED_AT, SAVED_AT));
        assertFalse(rewriters.get(0).containsKey("config"));

        assertThat(rewriters.get(1), hasEntry(RewriterInfo.FIELD_REWRITER_ID, "r2"));
        assertFalse(rewriters.get(1).containsKey(RewriterConfigMapping.PROP_REVISION));
    }

    private static GetRewriterResponse deserialize(final GetRewriterResponse response) throws IOException {
        final BytesStreamOutput output = new BytesStreamOutput();
        response.writeTo(output);
        output.flush();
        return new GetRewriterResponse(output.bytes().streamInput());
    }

    private static Map<String, Object> toMap(final GetRewriterResponse response) throws IOException {
        try (InputStream stream = XContentHelper.toXContent(response, XContentType.JSON, true).streamInput()) {
            return XContentHelper.convertToMap(XContentType.JSON.xContent(), stream, false);
        }
    }

    private static RewriterInfo rewriterInfo(final String rewriterId, final String revision,
                                            final boolean includeConfig) {
        return new RewriterInfo(rewriterId, "some.RewriterFactory", revision, "hash-of-" + rewriterId,
                SAVED_AT, includeConfig ? config() : null,
                Collections.singletonMap("sinks", Collections.singletonList("log4j")));
    }

    private static Map<String, Object> config() {
        final Map<String, Object> config = new LinkedHashMap<>();
        config.put("rules", "k =>\nSYNONYM: c");
        config.put("ignoreCase", true);
        return config;
    }

}