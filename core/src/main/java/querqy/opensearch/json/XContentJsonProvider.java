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

package querqy.opensearch.json;

import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import querqy.json.JsonProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link JsonProvider} implementation backed by OpenSearch's own {@code XContent}, so this module never needs
 * a third-party JSON library of its own - this is what lets the AWS-installable {@code core} plugin pass AWS
 * OpenSearch Service's plugin-upload CVE scanner, which previously kept flagging Jackson CVEs bundled
 * transitively via {@code querqy-core}/{@code querqy-lucene}.
 *
 * <p>{@link #readMap(String)} and {@link #writeValueAsString(Object)} reuse the same {@code XContent} idioms
 * already used elsewhere in this module (see {@code RewriterConfigMapping}). {@link #readValue(String, Class)}
 * has no existing {@code XContent} analog (OpenSearch's own code never deserializes arbitrary JSON into a
 * POJO this way), so it parses to a generic {@code Map}/{@code List} tree first and then binds that into the
 * target class via reflection - the binding targets querqy-core passes here (e.g. its NumberUnit rewriter's
 * config POJOs) are package-private, so constructors/setters are made accessible explicitly.</p>
 */
public class XContentJsonProvider implements JsonProvider {

    public static final JsonProvider INSTANCE = new XContentJsonProvider();

    @Override
    public Map<String, Object> readMap(final String json) throws IOException {
        try (XContentParser parser = XContentType.JSON.xContent()
                .createParser(null, null, json.getBytes(StandardCharsets.UTF_8))) {
            // mapOrdered() (not map()), so round-tripping a value through writeValueAsString + readMap/
            // readValue (as QueryParsingController's rewrite-logging conversion does) preserves field order
            // instead of scrambling it into map()'s plain, unordered HashMap.
            return parser.mapOrdered();
        }
    }

    @Override
    public <T> T readValue(final String json, final Class<T> type) throws IOException {
        final Object parsed = parseGeneric(json);
        try {
            return bind(parsed, type);
        } catch (final ReflectiveOperationException e) {
            throw new IOException("Could not bind JSON into " + type.getName(), e);
        }
    }

    /**
     * Parses arbitrary JSON text (object or array root) into a generic {@code Map}/{@code List} tree,
     * preserving object field order ({@code mapOrdered()}/{@code listOrderedMap()}, not {@code map()}/
     * {@code list()} - see {@link #readMap(String)}). The root type is determined from the JSON text
     * itself, since {@code mapOrdered()} only handles an object root.
     */
    private Object parseGeneric(final String json) throws IOException {
        final boolean isArray = json.stripLeading().startsWith("[");
        try (XContentParser parser = XContentType.JSON.xContent()
                .createParser(null, null, json.getBytes(StandardCharsets.UTF_8))) {
            return isArray ? parser.listOrderedMap() : parser.mapOrdered();
        }
    }

    @Override
    public String writeValueAsString(final Object value) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            final XContentBuilder builder = new XContentBuilder(XContentType.JSON.xContent(), bos);
            builder.value(toGenericValue(value));
            builder.flush();
            builder.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@link XContentBuilder#value(Object)} only knows how to serialize a fixed set of built-in types
     * (Map, List, String, Number, Boolean, ...) - unlike Jackson, it does no reflection-based POJO
     * serialization of its own. Arbitrary POJOs (e.g. querqy-core's rewrite-chain log/debug model classes)
     * are converted here into that generic Map/List/primitive shape first, via their declared instance
     * fields (read directly, not via getters, so property order matches source declaration order the way
     * Jackson's default introspection does), with null and empty (String/Collection/Map) values dropped -
     * replicating the old {@code ObjectMapper}'s {@code NON_NULL}/{@code NON_EMPTY} serialization inclusion
     * this rewrite-chain logging path relied on.
     */
    private Object toGenericValue(final Object value) throws IOException {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Map) {
            final Map<Object, Object> result = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                putIfNotEmpty(result, entry.getKey(), toGenericValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Collection) {
            final List<Object> result = new ArrayList<>();
            for (final Object element : (Collection<?>) value) {
                result.add(toGenericValue(element));
            }
            return result;
        }

        final Map<String, Object> result = new LinkedHashMap<>();
        for (final Field field : value.getClass().getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                putIfNotEmpty(result, field.getName(), toGenericValue(field.get(value)));
            } catch (final ReflectiveOperationException e) {
                throw new IOException("Could not serialize field '" + field.getName() + "' of " + value.getClass(), e);
            }
        }
        return result;
    }

    private static <K> void putIfNotEmpty(final Map<K, Object> result, final K key, final Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String && ((String) value).isEmpty()) {
            return;
        }
        if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
            return;
        }
        if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) {
            return;
        }
        result.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private <T> T bind(final Object parsed, final Class<T> type) throws ReflectiveOperationException, IOException {
        if (parsed == null) {
            return null;
        }
        if (type.isInstance(parsed)) {
            return (T) parsed;
        }
        if (!(parsed instanceof Map)) {
            throw new IOException("Cannot bind " + parsed + " into " + type.getName());
        }
        final Map<?, ?> map = (Map<?, ?>) parsed;
        final Constructor<T> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true); // binding targets like NumberUnitConfigObject are package-private
        final T instance = constructor.newInstance();
        for (final Method method : type.getMethods()) {
            if (!method.getName().startsWith("set") || method.getParameterCount() != 1) {
                continue;
            }
            final String propertyName = decapitalize(method.getName().substring(3));
            if (!map.containsKey(propertyName)) {
                continue;
            }
            final Object coerced = coerce(map.get(propertyName), method.getGenericParameterTypes()[0]);
            method.setAccessible(true);
            method.invoke(instance, coerced);
        }
        return instance;
    }

    private Object coerce(final Object rawValue, final Type targetType)
            throws ReflectiveOperationException, IOException {
        if (rawValue == null) {
            return null;
        }
        if (targetType instanceof Class) {
            final Class<?> targetClass = (Class<?>) targetType;
            if (targetClass == String.class) {
                return rawValue.toString();
            }
            if ((targetClass == Integer.class || targetClass == int.class) && rawValue instanceof Number) {
                return ((Number) rawValue).intValue();
            }
            if ((targetClass == Float.class || targetClass == float.class) && rawValue instanceof Number) {
                return ((Number) rawValue).floatValue();
            }
            if ((targetClass == Double.class || targetClass == double.class) && rawValue instanceof Number) {
                return ((Number) rawValue).doubleValue();
            }
            if (targetClass.isInstance(rawValue)) {
                return rawValue;
            }
            return bind(rawValue, targetClass); // nested POJO
        }
        if (targetType instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) targetType;
            if (parameterizedType.getRawType() == List.class && rawValue instanceof List) {
                final Type elementType = parameterizedType.getActualTypeArguments()[0];
                final List<Object> result = new ArrayList<>();
                for (final Object element : (List<?>) rawValue) {
                    result.add(coerce(element, elementType));
                }
                return result;
            }
        }
        return rawValue;
    }

    private static String decapitalize(final String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
