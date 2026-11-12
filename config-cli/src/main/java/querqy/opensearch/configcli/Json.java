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

import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Minimal JSON helpers on top of OpenSearch's own X-Content, which this module needs anyway (and which brings its
 * own Jackson core), so that no separate {@code jackson-databind} has to be bundled into the CLI.
 *
 * <p>Parsing is strict: duplicate keys in an object are rejected rather than silently resolved to the last
 * occurrence, and the input must be exactly one JSON object. (X-Content's own {@code XContentParser#map()} is
 * lenient - it returns an empty map for an empty input, an array or a scalar, and ignores anything after the first
 * object - which would turn an empty or truncated {@code --config-file} into a silent {@code {}}.) Serialization is
 * byte-for-byte what {@code ObjectMapper} produced for the value types that occur here ({@code String}, {@code
 * String[]}, {@code byte[]} as Base64, numbers, booleans, {@code null}, nested maps and lists).</p>
 */
final class Json {

    private Json() {
    }

    /**
     * @return The top-level JSON object as a mutable map. Nested objects are maps, arrays are lists.
     * @throws IOException if {@code json} is not valid JSON, is not exactly one JSON object, or has duplicate keys.
     */
    static Map<String, Object> parseMap(final String json) throws IOException {
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION, json)) {
            return readSingleObject(parser);
        }
    }

    /**
     * @return The top-level JSON object in {@code file} as a mutable map.
     * @throws IOException if the file can't be read, isn't valid JSON, is not exactly one JSON object, or has
     *                     duplicate keys.
     */
    static Map<String, Object> parseMap(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             XContentParser parser = JsonXContent.jsonXContent.createParser(NamedXContentRegistry.EMPTY,
                     DeprecationHandler.THROW_UNSUPPORTED_OPERATION, in)) {
            return readSingleObject(parser);
        }
    }

    private static Map<String, Object> readSingleObject(final XContentParser parser) throws IOException {

        final XContentParser.Token first = parser.nextToken();
        if (first == null) {
            throw new IOException("Expected a JSON object but the input is empty");
        }
        if (first != XContentParser.Token.START_OBJECT) {
            throw new IOException("Expected a JSON object but found " + first);
        }

        final Map<String, Object> map = parser.map();

        if (parser.nextToken() != null) {
            throw new IOException("Unexpected content after the end of the JSON object");
        }
        return map;
    }

    /**
     * @return {@code map} as a compact JSON object.
     */
    static String toJson(final Map<String, ?> map) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // the builder must be closed (which flushes) before the bytes are read
        try (XContentBuilder builder = new XContentBuilder(JsonXContent.jsonXContent, out)) {
            builder.map(map);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
