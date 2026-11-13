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

package querqy.opensearch.rewriter;

import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.Map;

/**
 * {@link NumberUnitRewriterFactory#configure(Map)} must fail for every configuration that {@link
 * NumberUnitRewriterFactory#validateConfiguration(Map)} rejects, rather than leaving the factory unconfigured: the
 * rewriter loader only calls {@code configure}, and a configure that "succeeds" without a usable delegate would
 * replace a previously cached, valid rewriter with a broken one.
 */
public class NumberUnitRewriterFactoryConfigureTest extends OpenSearchTestCase {

    static final String VALID_CONFIG = "{\"numberUnitDefinitions\": [{"
            + "\"units\": [{\"term\": \"zoll\", \"multiplier\": 1}],"
            + "\"fields\": [{\"fieldName\": \"screen_size\", \"scale\": 2}]}]}";

    public void testValidConfigCreatesRewriterFactory() {
        final NumberUnitRewriterFactory factory = new NumberUnitRewriterFactory("nu");
        factory.configure(Collections.singletonMap("config", VALID_CONFIG));
        assertNotNull(factory.createRewriterFactory(null));
    }

    public void testInvalidJsonThrows() {
        assertConfigureThrows(Collections.singletonMap("config", "{ this is not json"));
    }

    public void testMissingConfigPropertyThrows() {
        assertConfigureThrows(Collections.emptyMap());
    }

    public void testNonStringConfigPropertyThrows() {
        assertConfigureThrows(Collections.singletonMap("config", Collections.singletonMap("numberUnitDefinitions",
                Collections.emptyList())));
    }

    public void testMissingUnitDefinitionsThrows() {
        assertConfigureThrows(Collections.singletonMap("config", "{\"numberUnitDefinitions\": [{"
                + "\"fields\": [{\"fieldName\": \"screen_size\"}]}]}"));
    }

    /**
     * Only {@code validateConfiguration} detects this - querqy-core's constructor alone would accept it.
     */
    public void testDuplicateUnitDefinitionThrows() {
        assertConfigureThrows(Collections.singletonMap("config", "{\"numberUnitDefinitions\": [{"
                + "\"units\": [{\"term\": \"zoll\"}, {\"term\": \"zoll\"}],"
                + "\"fields\": [{\"fieldName\": \"screen_size\"}]}]}"));
    }

    private static void assertConfigureThrows(final Map<String, Object> config) {
        final NumberUnitRewriterFactory factory = new NumberUnitRewriterFactory("nu");
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> factory.configure(config));
        assertTrue(e.getMessage(), e.getMessage().contains("'nu'"));
    }
}
