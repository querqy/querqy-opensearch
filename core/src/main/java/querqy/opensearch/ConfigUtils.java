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

package querqy.opensearch;

import querqy.trie.TrieMap;

import org.opensearch.lucene.util.SPIClassIterator;
import org.opensearch.secure_sm.AccessController;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public interface ConfigUtils {

    Map<Class<?>, Set<String>> ALLOWED_INSTANCES_CACHE = new ConcurrentHashMap<>();

    /**
     * Delegates the actual {@code META-INF/services/<type>} classpath scan to OpenSearch's own {@link
     * SPIClassIterator} (vendored from Lucene) rather than hand-rolling it: it already does exactly this -
     * find every matching SPI file across the classpath, parse it line by line stripping {@code #} comments,
     * resolve each class name via {@code Class.forName(name, false, loader)} without initializing it - and
     * does so with its own {@code @SuppressForbidden}, so this class never needs to call {@code
     * URL#openStream()} itself at all.
     */
    static <V> Set<String> loadAllowedInstances(final Class<V> type) {
        return ALLOWED_INSTANCES_CACHE.computeIfAbsent(type, ignored -> {
            // Not typeClass (the lambda's own parameter, typed Class<?> by the map's declared key type) -
            // SPIClassIterator.get(Class<S>, ...) needs S to actually unify with V, which only the enclosing
            // method's own `type` parameter is typed precisely enough to do.
            final ClassLoader cl = ConfigUtils.class.getClassLoader();
            final Set<String> allowed = new HashSet<>();
            final SPIClassIterator<V> it = SPIClassIterator.get(type, cl);
            while (it.hasNext()) {
                allowed.add(it.next().getName());
            }
            return Collections.unmodifiableSet(allowed);
        });
    }

    static String getStringArg(final Map<String, Object> config, final String name, final String defaultValue) {
        final String value = (String) config.get(name);
        return value == null ? defaultValue : value;
    }

    static Optional<String> getStringArg(final Map<String, Object> config, final String name) {
        return Optional.ofNullable((String) config.get(name));
    }

    static <T extends Enum<T>> Optional<T> getEnumArg(final Map<String, Object> config, final String name,
                                                      final Class<T> enumClass) {
        final String value = (String) config.get(name);
        return (value == null) ? Optional.empty() : Optional.of(Enum.valueOf(enumClass, value));
    }

    @SuppressWarnings("unchecked")
    static <T> T getArg(final Map<String, Object> config, final String name, final T defaultValue) {
        return (T) config.getOrDefault(name, defaultValue);
    }

    @SuppressWarnings("unchecked")
    static TrieMap<Boolean> getTrieSetArg(final Map<String, Object> config, final String name) {
        final TrieMap<Boolean> result = new TrieMap<>();
        final Collection<String> collectionArg = (Collection<String>) config.get(name);
        if (collectionArg != null) {
            for (final String word : new HashSet<>(collectionArg)) {
                result.put(word, Boolean.TRUE);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static <V> V getInstanceFromArg(final Map<String, Object> config, final String name, final V defaultValue,
                                    final Class<V> allowedType) {
        return AccessController.doPrivileged(
                () -> {
                    final String classField = (String) config.get(name);
                    if (classField == null) {
                        return defaultValue;
                    }

                    final String className = classField.trim();
                    if (className.isEmpty()) {
                        return defaultValue;
                    }

                    if (!loadAllowedInstances(allowedType).contains(className)) {
                        throw new IllegalArgumentException(
                                "Class is not a registered " + allowedType.getSimpleName() +
                                " service provider: " + className);
                    }

                    try {
                        return (V) Class.forName(className).getConstructor().newInstance();
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

}