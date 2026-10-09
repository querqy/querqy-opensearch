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
 * the cluster as plain JSON (via Jackson, see {@code HttpQuerqyIndexManager.putRewriter}) - it has no special-case
 * code of its own for a config large enough to be split into a {@code String[]} (see
 * {@code RewriterConfigMapping#stringToSourceValue}, triggered above 32766 UTF-8 bytes of the combined
 * class+config JSON). This confirms that hand-off actually round-trips: Jackson must serialize the array as a JSON
 * array (not, say, call {@code toString()} on it), and parsing that JSON back must let the array elements be
 * rejoined into the original string the same way the cluster-side field mapper and config loader do.
 */
public class LargeConfigSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    public void testLargeConfigSurvivesJsonSerializationAsAnArray() throws Exception {

        final StringBuilder rules = new StringBuilder();
        while (rules.length() < 40_000) {
            rules.append("term_").append(rules.length()).append(" =>\n  SYNONYM: x\n");
        }

        final Map<String, Object> content = new HashMap<>();
        content.put("class", "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory");
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules.toString());
        content.put("config", config);

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());
        final Object configV005 = source.get("config_v_005");
        assertThat(configV005).as("test assumption: the config must actually be split").isInstanceOf(String[].class);

        // this is exactly what HttpQuerqyIndexManager.putRewriter sends as the request body
        final String json = MAPPER.writeValueAsString(source);

        final Map<String, Object> roundTripped = MAPPER.readValue(json, Map.class);
        final Object roundTrippedValue = roundTripped.get("config_v_005");

        assertThat(roundTrippedValue)
                .as("Jackson must serialize the split value as a JSON array, not e.g. Arrays.toString() it")
                .isInstanceOf(List.class);

        final String rejoined = String.join("", (List<String>) roundTrippedValue);

        // rejoined is itself a JSON string (the canonical {"class":...,"config":{...}} document) - parse it to
        // compare the actual "rules" value, since JSON-escaping (e.g. "\n") makes a raw substring match unreliable
        final Map<String, Object> combined = MAPPER.readValue(rejoined, Map.class);
        final Map<String, Object> rewrittenConfig = (Map<String, Object>) combined.get("config");
        assertThat(rewrittenConfig.get("rules")).isEqualTo(rules.toString());
    }
}
