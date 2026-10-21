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

import org.opensearch.OpenSearchException;

import org.opensearch.index.shard.IndexShard;
import querqy.opensearch.rewriterstore.LoadRewriterConfig;
import querqy.rewrite.RewriterFactory;

import org.opensearch.secure_sm.AccessController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class OpenSearchRewriterFactory {

    private static final Set<String> ALLOWED_CLASSES = loadAllowedClasses();

    private static Set<String> loadAllowedClasses() {
        // We cannot load ServiceLoader for the scan as OpenSearchRewriterFactory doesn't have a parameterless
        // constructor
        final String spiFile = "META-INF/services/" + OpenSearchRewriterFactory.class.getName();
        final ClassLoader cl = OpenSearchRewriterFactory.class.getClassLoader();
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
                            Class.forName(line, false, cl).asSubclass(OpenSearchRewriterFactory.class);
                            allowed.add(line);
                        }
                    }
                }
            }
        } catch (final Exception e) {
            throw new RuntimeException("Failed to load OpenSearchRewriterFactory service providers", e);
        }
        return Collections.unmodifiableSet(allowed);
    }

    private static void checkClassAllowed(final String className) {
        if (!ALLOWED_CLASSES.contains(className)) {
            throw new IllegalArgumentException(
                    "Class is not a registered OpenSearchRewriterFactory service provider: " + className);
        }
    }

    protected final String rewriterId;

    protected OpenSearchRewriterFactory(final String rewriterId) {
        this.rewriterId = rewriterId;
    }

    public abstract void configure(Map<String, Object> config) throws OpenSearchException;

    public abstract List<String> validateConfiguration(Map<String, Object> config);

    public abstract RewriterFactory createRewriterFactory(IndexShard indexShard) throws OpenSearchException;

    public String getRewriterId() {
        return rewriterId;
    }

    public static OpenSearchRewriterFactory loadConfiguredInstance(final LoadRewriterConfig instanceDescription) {

        final String classField = instanceDescription.getRewriterClassName();
        if (classField == null) {
            throw new IllegalArgumentException("Property not found: " + instanceDescription
                    .getConfigMapping().getRewriterClassNameProperty());
        }

        final String className = classField.trim();
        if (className.isEmpty()) {
            throw new IllegalArgumentException("Class name expected in property: " + instanceDescription
                    .getConfigMapping().getRewriterClassNameProperty());
        }

        final Map<String, Object> config = instanceDescription.getConfig();

        return AccessController.doPrivileged(
                () -> {

                    final OpenSearchRewriterFactory factory;

                    checkClassAllowed(className);

                    try {
                        factory = (OpenSearchRewriterFactory) Class.forName(className).getConstructor(String.class)
                                .newInstance(instanceDescription.getRewriterId());

                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }

                    factory.configure(config);
                    return factory;

                });

    }

    public static OpenSearchRewriterFactory loadInstance(final String rewriterId, final Map<String, Object> instanceDesc,
                                                         final String argName) {
        return AccessController.doPrivileged(
                () -> {
                    final String classField = (String) instanceDesc.get(argName);
                    if (classField == null) {
                        throw new IllegalArgumentException("Property not found: " + argName);
                    }

                    final String className = classField.trim();
                    if (className.isEmpty()) {
                        throw new IllegalArgumentException("Class name expected in property: " + argName);
                    }


                    checkClassAllowed(className);

                    try {
                        return (OpenSearchRewriterFactory) Class.forName(className)
                                .getConstructor(String.class).newInstance(rewriterId);
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                });





    }


}