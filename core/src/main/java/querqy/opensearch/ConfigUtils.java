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

import org.opensearch.common.SuppressForbidden;
import org.opensearch.secure_sm.AccessController;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public interface ConfigUtils {

    Map<Class<?>, Set<String>> ALLOWED_INSTANCES_CACHE = new ConcurrentHashMap<>();

    /**
     * Hand-rolled {@code META-INF/services/<type>} classpath scan rather than {@code
     * java.util.ServiceLoader} (whose binding targets commonly lack a parameterless constructor) or {@code
     * org.opensearch.lucene.util.SPIClassIterator}, which does the identical scan but lives in the full
     * {@code org.opensearch:opensearch} server jar - a {@code compileOnly} dependency of {@code core} that
     * never propagates to downstream consumers. {@code config-cli} calls into this method (via rewriter
     * config validation) with only {@code opensearch-core}/{@code opensearch-x-content} on its runtime
     * classpath, deliberately avoiding the full server jar - referencing {@code SPIClassIterator} here would
     * throw {@code NoClassDefFoundError} the first time {@code config-cli} touched this class (confirmed:
     * this is exactly what broke when this method briefly delegated to it).
     */
    @SuppressForbidden(reason = "Reads a classpath SPI registration file, never a real network URL - "
            + "see the javadoc above for why org.opensearch.lucene.util.SPIClassIterator (which already "
            + "has this same suppression) can't be used here instead.")
    static <V> Set<String> loadAllowedInstances(final Class<V> type) {
        return ALLOWED_INSTANCES_CACHE.computeIfAbsent(type, typeClass -> {
            final String spiFile = "META-INF/services/" + typeClass.getName();
            final ClassLoader cl = ConfigUtils.class.getClassLoader();
            final Set<String> allowed = new HashSet<>();
            try {
                final Enumeration<URL> resources = cl.getResources(spiFile);
                while (resources.hasMoreElements()) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            final int commentIdx = line.indexOf('#');
                            if (commentIdx >= 0) {
                                line = line.substring(0, commentIdx);
                            }
                            line = line.trim();
                            if (!line.isEmpty()) {
                                Class.forName(line, false, cl).asSubclass(typeClass);
                                allowed.add(line);
                            }
                        }
                    }
                }
            } catch (final Exception e) {
                throw new RuntimeException("Failed to load service providers for " + typeClass.getName(), e);
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