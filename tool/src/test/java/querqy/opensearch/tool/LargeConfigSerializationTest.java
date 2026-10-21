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

package querqy.opensearch.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code RewriterConfigTool} sends the document body built by {@code RewriterConfigMapping.toLuceneSource(...)} to
 * the cluster as plain JSON (via Jackson, see {@code HttpQuerqyIndexManager.putRewriter}) and has no special-case
 * code of its own for a large config: the config_v_005 field type never creates a Lucene field (validated at write
 * time, otherwise {@code _source}-only), so it has no 32766-byte term-length limit to respect, and
 * {@code toLuceneSource} stores even a large combined class+config JSON as a single string.
 */
public class LargeConfigSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String largeRules() {
        final StringBuilder rules = new StringBuilder();
        while (rules.length() < 40_000) {
            rules.append("term_").append(rules.length()).append(" =>\n  SYNONYM: x\n");
        }
        return rules.toString();
    }

    @Test
    public void testLargeConfigSurvivesJsonSerializationAsASingleString() throws Exception {

        final String rules = largeRules();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory");
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        content.put("config", config);

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());
        final Object configV005 = source.get("config_v_005");
        assertThat(configV005).as("a large config is no longer split on write").isInstanceOf(String.class);

        // this is exactly what HttpQuerqyIndexManager.putRewriter sends as the request body
        final String json = MAPPER.writeValueAsString(source);

        @SuppressWarnings("unchecked")
        final Map<String, Object> roundTripped = MAPPER.readValue(json, Map.class);
        assertThat(roundTripped.get("config_v_005")).isEqualTo(configV005);
    }

    /**
     * A document split by an older version of this code (or by a third party) must still be handled correctly if
     * the tool ever reads one back - confirms Jackson round-trips an already-split value as a proper JSON array
     * (not e.g. {@code toString()}'d), and that it rejoins to the original config.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testAlreadySplitConfigSurvivesJsonSerializationAsAnArray() throws Exception {

        final String rules = largeRules();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory");
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        content.put("config", config);

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());
        final String unsplitValue = (String) source.get("config_v_005");
        source.put("config_v_005", RewriterConfigMapping.stringToSourceValue(unsplitValue, 32766));
        assertThat(source.get("config_v_005")).as("test assumption: the config must actually be split")
                .isInstanceOf(String[].class);

        final String json = MAPPER.writeValueAsString(source);

        final Map<String, Object> roundTripped = MAPPER.readValue(json, Map.class);
        final Object roundTrippedValue = roundTripped.get("config_v_005");

        assertThat(roundTrippedValue)
                .as("Jackson must serialize the split value as a JSON array, not e.g. Arrays.toString() it")
                .isInstanceOf(List.class);

        final String rejoined = String.join("", (List<String>) roundTrippedValue);
        assertThat(rejoined).isEqualTo(unsplitValue);
    }
}
