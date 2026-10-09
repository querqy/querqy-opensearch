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
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import querqy.opensearch.OpenSearchRewriterFactory;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Validates a rewriter configuration and writes it directly to the {@code .opensearch-querqy} index over HTTP - the
 * AWS-compatible replacement for {@code PUT /_plugins/_querqy/rewriter/{id}}, since an install without
 * {@code ActionPlugin} (e.g. querqy-opensearch-core on Amazon OpenSearch Service) has no REST action to do this.
 *
 * <pre>
 * java -jar querqy-opensearch-tool.jar \
 *     --url https://host:9200 --id my_rewriter --config-file ./my-rewriter.json \
 *     [--revision r42] [--username admin --password '***'] [--replicas 1] [--insecure] [--validate-only]
 * </pre>
 *
 * {@code --config-file} holds the same JSON shape as the REST PUT request body: {@code class}, {@code config}, and
 * optionally {@code info_logging}/{@code revision}.
 */
public final class RewriterConfigTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(final String[] args) {
        try {
            run(CliArgs.parse(args));
        } catch (final ValidationFailedException e) {
            System.err.println("Invalid rewriter configuration:");
            for (final String error : e.errors) {
                System.err.println("  - " + error);
            }
            System.exit(1);
        } catch (final ResponseException e) {
            System.err.println("The cluster rejected the request:");
            try {
                System.err.println(EntityUtils.toString(e.getResponse().getEntity()));
            } catch (final Exception ignored) {
                System.err.println(e.getMessage());
            }
            System.exit(1);
        } catch (final IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
        } catch (final Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    static void run(final CliArgs args) throws IOException {

        final Map<String, Object> content = MAPPER.readValue(new File(args.configFile), Map.class);

        if (args.revision != null) {
            content.put(RewriterConfigMapping.PROP_REVISION, args.revision);
        }

        validate(args.id, content);
        System.out.println("Configuration is valid.");

        if (args.validateOnly) {
            return;
        }

        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());

        try (RestClient client = buildClient(args)) {
            final HttpQuerqyIndexManager indexManager = new HttpQuerqyIndexManager(client);
            indexManager.ensureIndexExists(args.replicas);
            indexManager.putRewriter(args.id, source);
        }

        System.out.println("Saved rewriter '" + args.id + "'.");
        System.out.println("Note: on an install without the full plugin's REST API (e.g. core-only/AWS), this "
                + "change can take up to querqy.caches.rewriter.refresh_after_write (default 30s) to take effect on "
                + "each node - there is no push-based reload without ActionPlugin.");
    }

    /**
     * Local, no-network validation: the same check {@code PutRewriterRequest.validate()} and the write-time field
     * mapper both run - class allow-list plus {@code validateConfiguration}.
     */
    @SuppressWarnings("unchecked")
    static void validate(final String rewriterId, final Map<String, Object> content) {

        final OpenSearchRewriterFactory factory;
        try {
            factory = OpenSearchRewriterFactory.loadInstance(rewriterId, content, "class");
        } catch (final Exception e) {
            throw new ValidationFailedException(
                    Collections.singletonList("Invalid definition of rewriter 'class': " + e.getMessage()));
        }

        final Map<String, Object> config = (Map<String, Object>) content.getOrDefault("config",
                Collections.emptyMap());
        final List<String> errors = factory.validateConfiguration(config);

        if (errors != null && !errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
    }

    private static RestClient buildClient(final CliArgs args) {

        final HttpHost host;
        try {
            host = HttpHost.create(args.url);
        } catch (final java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid --url '" + args.url + "': " + e.getMessage());
        }
        final RestClientBuilder builder = RestClient.builder(host);

        if (args.username != null || args.insecure) {
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                if (args.username != null) {
                    final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(new AuthScope(host),
                            new UsernamePasswordCredentials(args.username,
                                    args.password == null ? new char[0] : args.password.toCharArray()));
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }
                if (args.insecure) {
                    configureInsecureSsl(httpClientBuilder);
                }
                return httpClientBuilder;
            });
        }

        return builder.build();
    }

    private static void configureInsecureSsl(final HttpAsyncClientBuilder httpClientBuilder) {
        try {
            final javax.net.ssl.SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                    .build();
            final TlsStrategy tlsStrategy = ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .build();
            httpClientBuilder.setConnectionManager(
                    PoolingAsyncClientConnectionManagerBuilder.create().setTlsStrategy(tlsStrategy).build());
        } catch (final Exception e) {
            throw new IllegalStateException("Could not configure insecure SSL context", e);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar querqy-opensearch-tool.jar --url <url> --id <rewriterId> "
                + "--config-file <file> [--revision <revision>] [--username <user> --password <password>] "
                + "[--replicas <n>] [--insecure] [--validate-only]");
    }

    static final class ValidationFailedException extends RuntimeException {
        final List<String> errors;

        ValidationFailedException(final List<String> errors) {
            super("Invalid rewriter configuration: " + errors);
            this.errors = errors;
        }
    }
}
