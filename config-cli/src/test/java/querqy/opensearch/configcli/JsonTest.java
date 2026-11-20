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

package querqy.opensearch.configcli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JsonTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testParsesNestedObjectsArraysAndScalars() throws Exception {

        final Map<String, Object> map = Json.parseMap(
                "{\"class\":\"x\",\"config\":{\"rules\":\"a\\nb\",\"n\":3,\"big\":12345678901,\"d\":1.5,"
                        + "\"b\":true,\"z\":null,\"l\":[1,2]}}");

        assertThat(map).containsEntry("class", "x");
        @SuppressWarnings("unchecked") final Map<String, Object> config = (Map<String, Object>) map.get("config");
        assertThat(config.get("rules")).isEqualTo("a\nb");
        assertThat(config.get("n")).isEqualTo(3);
        assertThat(config.get("big")).isEqualTo(12345678901L);
        assertThat(config.get("d")).isEqualTo(1.5);
        assertThat(config.get("b")).isEqualTo(true);
        assertThat(config).containsKey("z");
        assertThat(config.get("z")).isNull();
        assertThat(config.get("l")).isEqualTo(List.of(1, 2));
    }

    @Test
    public void testParsedMapIsMutable() throws Exception {
        // RewriterConfigTool adds the --revision to the parsed config
        final Map<String, Object> map = Json.parseMap("{\"a\":1}");
        map.put("revision", "r1");
        assertThat(map).containsEntry("revision", "r1");
    }

    @Test
    public void testParsesFile() throws Exception {
        final Path file = folder.newFile("config.json").toPath();
        Files.write(file, "{\"class\":\"ü\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(Json.parseMap(file)).containsEntry("class", "ü");
    }

    @Test
    public void testDuplicateKeysAreRejectedLikeInTheRestApi() {
        assertThatThrownBy(() -> Json.parseMap("{\"a\":1,\"a\":2}"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    public void testNotJsonAndNotAnObjectAreRejected() {
        // X-Content's own map() would silently return {} for each of the non-object inputs
        for (final String input : new String[]{"foo", "[1,2]", "", "   ", "3", "\"s\"", "null"}) {
            assertThatThrownBy(() -> Json.parseMap(input)).as("input '%s'", input).isInstanceOf(IOException.class);
        }
    }

    @Test
    public void testContentAfterTheObjectIsRejected() {
        // ...and would silently ignore everything after the first object (e.g. a truncated/concatenated file)
        assertThatThrownBy(() -> Json.parseMap("{\"a\":1}{\"b\":2}"))
                .isInstanceOf(IOException.class).hasMessageContaining("Unexpected content");
        assertThatThrownBy(() -> Json.parseMap("{\"a\":1} trailing")).isInstanceOf(IOException.class);
    }

    @Test
    public void testEmptyObjectIsAccepted() throws Exception {
        assertThat(Json.parseMap("{}")).isEmpty();
    }

    @Test
    public void testEmptyFileIsRejected() throws Exception {
        final Path file = folder.newFile("empty.json").toPath();
        assertThatThrownBy(() -> Json.parseMap(file)).isInstanceOf(IOException.class).hasMessageContaining("empty");
    }

    @Test
    public void testSerializesTheValueTypesOfAStoredSource() throws Exception {

        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("split", new String[]{"abc", "def"}); // a config split by an older version
        source.put("bytes", "hi".getBytes(StandardCharsets.UTF_8)); // Base64, as ObjectMapper wrote it
        source.put("n", 3);
        source.put("text", "quote\" backslash\\ newline\n ü");
        source.put("nul", null);

        assertThat(Json.toJson(source)).isEqualTo(
                "{\"split\":[\"abc\",\"def\"],\"bytes\":\"aGk=\",\"n\":3,"
                        + "\"text\":\"quote\\\" backslash\\\\ newline\\n ü\",\"nul\":null}");
    }

    @Test
    public void testRoundTrip() throws Exception {
        final String json = "{\"a\":{\"b\":[\"c\",{\"d\":1}]},\"e\":false}";
        assertThat(Json.toJson(Json.parseMap(json))).isEqualTo(json);
    }
}
