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
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import querqy.opensearch.OpenSearchRewriterFactory;
import querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.opensearch.rewriterstore.RewriterInfo;
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Validates, lists, fetches, or deletes a rewriter configuration directly against the {@code .opensearch-querqy}
 * index over HTTP - the AWS-compatible replacement for the REST rewriter API
 * ({@code PUT}/{@code GET}/{@code DELETE /_plugins/_querqy/rewriter/{id}}), since an install without
 * {@code ActionPlugin} (e.g. querqy-opensearch-core on Amazon OpenSearch Service) has no REST action to do this.
 *
 * <pre>
 * java -jar querqy-opensearch-tool.jar put --url https://host:9200 --id my_rewriter --config-file ./my-rewriter.json \
 *     [--revision r42] [--dictionary-file ./dict.tsv [--format tsv_df|tsv_df_cooc] [--hash-functions N]] \
 *     [--username admin --password '***'] [--replicas 1] [--insecure] [--validate-only]
 * java -jar querqy-opensearch-tool.jar get --url https://host:9200 --id my_rewriter [--username ... --password ...]
 * java -jar querqy-opensearch-tool.jar list --url https://host:9200 [--username ... --password ...]
 * java -jar querqy-opensearch-tool.jar delete --url https://host:9200 --id my_rewriter [--username ... --password ...]
 * java -jar querqy-opensearch-tool.jar put-dictionary --url https://host:9200 --dictionary-name dutch-compounds \
 *     --dictionary-revision r1 --dictionary-file ./dutch-compounds.tsv [--format tsv_df|tsv_df_cooc] \
 *     [--hash-functions N] [--username ... --password ...] [--replicas 1]
 * </pre>
 *
 * {@code --config-file} (for {@code put}) holds the same JSON shape as the REST PUT request body: {@code class},
 * {@code config}, and optionally {@code info_logging}/{@code revision}. {@code --revision} (for {@code put} only)
 * sets the rewriter document's own optional revision tag - unrelated to a dictionary's revision, which is always
 * {@code --dictionary-revision}, to avoid exactly that confusion.
 *
 * <p><b>{@code put-dictionary}:</b> gzips {@code --dictionary-file} (a two-column {@code term\tdocFreq} TSV for
 * {@code --format tsv_df}, the default; a three-column {@code term\tdocFreq\tbloomHex} TSV plus {@code
 * --hash-functions} for {@code tsv_df_cooc}, which also enables collation) and writes it as a {@code term_corpus}
 * document, id {@code <dictionary-name>-<dictionary-revision>}. A {@code WordBreakCompoundRewriterFactory} rewriter
 * config references it via {@code "dictionary": {"name": ..., "revision": ...}} instead of a shard-side {@code
 * dictionaryField}; that makes it usable from the {@code querqy_rewrite} search pipeline, which has no shard/index
 * access. The document is immutable by convention - write a new revision rather than overwriting one already in
 * use.</p>
 *
 * <p><b>{@code put} with a {@code dictionary} reference:</b> if {@code --config-file}'s rewriter is a {@code
 * WordBreakCompoundRewriterFactory} configured with {@code "dictionary": {"name": ..., "revision": ...}}, {@code
 * put} checks that document already exists before saving the rewriter - rejecting the command instead of saving a
 * rewriter config that would only fail later, the first time a query tries to use it. Pass {@code
 * --dictionary-file} (plus {@code --format}/{@code --hash-functions} as needed, same as {@code put-dictionary}) to
 * upload the dictionary as part of the same command instead of requiring a separate {@code put-dictionary} call
 * first.</p>
 *
 * <p><b>AWS IAM auth:</b> pass {@code --sigv4} instead of {@code --username}/{@code --password} to sign every
 * request with AWS SigV4, for an Amazon OpenSearch Service domain or OpenSearch Serverless collection whose
 * access policy requires IAM rather than (or in addition to) an internal user database. Credentials come from
 * the standard AWS provider chain (env vars, shared config/credentials file, EC2/ECS/EKS instance role) or, with
 * {@code --aws-profile}, a specific named profile. {@code --aws-region} defaults to whatever the AWS SDK's
 * region provider chain resolves; {@code --aws-service} defaults to {@code es} (managed domains) and should be
 * set to {@code aoss} for OpenSearch Serverless, the only auth mode Serverless supports:
 * <pre>
 * java -jar querqy-opensearch-tool.jar list --url https://host.region.es.amazonaws.com --sigv4 \
 *     [--aws-region us-east-1] [--aws-service es|aoss] [--aws-profile my-profile]
 * </pre>
 *
 * <p><b>Delete note:</b> unlike the REST API's {@code DELETE}, this cannot clear the deleted rewriter from every
 * node's in-memory cache (that's an {@code ActionPlugin}-only transport action, unavailable over plain HTTP) - each
 * node notices the deletion on its own, within roughly {@code querqy.caches.rewriter.refresh_after_write} (default
 * 30s), the same freshness caveat that already applies to a {@code put}.</p>
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
        } catch (final RewriterNotFoundException e) {
            System.err.println(e.getMessage());
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
        } catch (final SdkClientException e) {
            System.err.println("Could not resolve AWS credentials/region for --sigv4: " + e.getMessage());
            System.err.println("Check --aws-region/--aws-profile and your AWS credentials (env vars, shared "
                    + "config/credentials file, or an EC2/ECS/EKS instance role).");
            System.exit(1);
        } catch (final Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(final CliArgs args) throws IOException {
        switch (args.action) {
            case PUT:
                runPut(args);
                break;
            case GET:
                runGet(args);
                break;
            case LIST:
                runList(args);
                break;
            case DELETE:
                runDelete(args);
                break;
            case PUT_DICTIONARY:
                runPutDictionary(args);
                break;
            default:
                throw new IllegalStateException("Unhandled action: " + args.action);
        }
    }

    @SuppressWarnings("unchecked")
    private static void runPut(final CliArgs args) throws IOException {

        final Map<String, Object> content = MAPPER.readValue(new File(args.configFile), Map.class);

        if (args.revision != null) {
            content.put(RewriterConfigMapping.PROP_REVISION, args.revision);
        }

        validate(args.id, content);
        System.out.println("Configuration is valid.");

        if (args.validateOnly) {
            return;
        }

        final DictionaryReference dictionaryReference = findWordBreakDictionaryReference(content);
        final Map<String, Object> source = RewriterConfigMapping.toLuceneSource(content, System.currentTimeMillis());

        final HttpHost host = parseHost(args);
        try (RestClient client = buildClient(args, host)) {
            final HttpQuerqyIndexManager indexManager = new HttpQuerqyIndexManager(client, buildSigner(args, host));
            indexManager.ensureIndexExists(args.replicas);

            if (dictionaryReference != null) {
                ensureDictionaryExists(indexManager, dictionaryReference, args);
            }

            indexManager.putRewriter(args.id, source);
        }

        System.out.println("Saved rewriter '" + args.id + "'.");
        printFreshnessNote();
    }

    /**
     * @return The {@code dictionary.name}/{@code dictionary.revision} a {@code WordBreakCompoundRewriterFactory}
     * config refers to, or {@code null} if {@code content} isn't that class, or is configured with a shard-side
     * {@code dictionaryField} instead (nothing to check in either case).
     */
    @SuppressWarnings("unchecked")
    private static DictionaryReference findWordBreakDictionaryReference(final Map<String, Object> content) {

        if (!WordBreakCompoundRewriterFactory.class.getName().equals(content.get("class"))) {
            return null;
        }

        final Map<String, Object> config = (Map<String, Object>) content.get("config");
        final Map<String, Object> dictionary = config == null ? null : (Map<String, Object>) config.get("dictionary");
        if (dictionary == null) {
            return null;
        }

        final Object name = dictionary.get("name");
        final Object revision = dictionary.get("revision");
        // malformed (missing name/revision) is already rejected by validate() above - nothing to check here
        return (name == null || revision == null) ? null : new DictionaryReference(name.toString(),
                revision.toString());
    }

    /**
     * For a {@code word_break_compound} rewriter configured with a {@code dictionary} reference: if {@code
     * --dictionary-file} was given, gzips and writes it as that dictionary's {@code term_corpus} document (same as
     * {@code put-dictionary}); otherwise checks the referenced document already exists, failing clearly - before
     * the rewriter config is saved - if it doesn't, rather than letting the rewriter save successfully only to
     * fail later, confusingly, the first time a live query tries to use it.
     */
    private static void ensureDictionaryExists(final HttpQuerqyIndexManager indexManager,
                                               final DictionaryReference dictionaryReference, final CliArgs args)
            throws IOException {

        final String docId = TermCorpusConfigMapping.docId(dictionaryReference.name, dictionaryReference.revision);

        if (args.dictionaryFile != null) {
            final Map<String, Object> source = buildDictionarySource(dictionaryReference.name,
                    dictionaryReference.revision, args.dictionaryFile, args.dictionaryFormat, args.hashFunctions);
            indexManager.putDictionary(docId, source);
            System.out.println("Saved dictionary '" + dictionaryReference.name + "' revision '"
                    + dictionaryReference.revision + "'.");
            return;
        }

        if (!indexManager.documentExists(docId)) {
            throw new IllegalArgumentException("Rewriter '" + args.id + "' references dictionary '"
                    + dictionaryReference.name + "' revision '" + dictionaryReference.revision + "', which does not "
                    + "exist in the index. Either run 'put-dictionary' first, or pass --dictionary-file to upload "
                    + "it as part of this command.");
        }
    }

    private record DictionaryReference(String name, String revision) {
    }

    /**
     * Gzips {@code --dictionary-file} and writes it as a {@code term_corpus} document (see {@link
     * TermCorpusConfigMapping}) - the dictionary a coordinator-safe {@code word_break} rewriter can reference by
     * {@code name}/{@code revision} instead of a shard-side {@code dictionaryField}. Always writes {@code gzip:
     * true}; someone hand-writing a small, uncompressed dictionary directly via a raw {@code PUT} (not through this
     * tool) can set {@code gzip: false} instead.
     */
    private static void runPutDictionary(final CliArgs args) throws IOException {

        final Map<String, Object> source = buildDictionarySource(args.dictionaryName, args.dictionaryRevision,
                args.dictionaryFile, args.dictionaryFormat, args.hashFunctions);

        final HttpHost host = parseHost(args);
        try (RestClient client = buildClient(args, host)) {
            final HttpQuerqyIndexManager indexManager = new HttpQuerqyIndexManager(client, buildSigner(args, host));
            indexManager.ensureIndexExists(args.replicas);
            indexManager.putDictionary(TermCorpusConfigMapping.docId(args.dictionaryName, args.dictionaryRevision),
                    source);
        }

        System.out.println("Saved dictionary '" + args.dictionaryName + "' revision '" + args.dictionaryRevision
                + "'.");
        printFreshnessNote();
    }

    private static Map<String, Object> buildDictionarySource(final String name, final String revision,
                                                              final String file, final String format,
                                                              final Integer hashFunctions) throws IOException {
        final byte[] tsvBytes = Files.readAllBytes(Path.of(file));
        final byte[] gzipped = gzip(tsvBytes);
        return TermCorpusConfigMapping.toLuceneSource(name, revision, format, true, null, hashFunctions, gzipped,
                System.currentTimeMillis());
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }

    private static void runGet(final CliArgs args) throws IOException {
        final HttpHost host = parseHost(args);
        try (RestClient client = buildClient(args, host)) {
            final RewriterInfo info = new HttpQuerqyIndexManager(client, buildSigner(args, host)).getRewriter(args.id);
            if (info == null) {
                throw new RewriterNotFoundException("Rewriter '" + args.id + "' not found.");
            }
            System.out.println(toJson(info));
        }
    }

    private static void runList(final CliArgs args) throws IOException {
        final HttpHost host = parseHost(args);
        try (RestClient client = buildClient(args, host)) {
            final List<RewriterInfo> rewriters = new HttpQuerqyIndexManager(client, buildSigner(args, host))
                    .listRewriters();
            System.out.println(toJson(rewriters));
        }
    }

    private static void runDelete(final CliArgs args) throws IOException {
        final HttpHost host = parseHost(args);
        try (RestClient client = buildClient(args, host)) {
            final boolean deleted = new HttpQuerqyIndexManager(client, buildSigner(args, host))
                    .deleteRewriter(args.id);
            System.out.println(deleted
                    ? "Deleted rewriter '" + args.id + "'."
                    : "Rewriter '" + args.id + "' was already absent.");
        }
        printFreshnessNote();
    }

    private static void printFreshnessNote() {
        System.out.println("Note: on an install without the full plugin's REST API (e.g. core-only/AWS), this "
                + "change can take up to querqy.caches.rewriter.refresh_after_write (default 30s) to take effect on "
                + "each node - there is no push-based reload/cache-clear without ActionPlugin.");
    }

    private static String toJson(final RewriterInfo info) throws IOException {
        final XContentBuilder builder = JsonXContent.contentBuilder();
        info.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return builder.toString();
    }

    private static String toJson(final List<RewriterInfo> infos) throws IOException {
        final XContentBuilder builder = JsonXContent.contentBuilder();
        builder.startArray();
        for (final RewriterInfo info : infos) {
            info.toXContent(builder, ToXContent.EMPTY_PARAMS);
        }
        builder.endArray();
        return builder.toString();
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

    private static HttpHost parseHost(final CliArgs args) {
        try {
            return HttpHost.create(args.url);
        } catch (final java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid --url '" + args.url + "': " + e.getMessage());
        }
    }

    private static RestClient buildClient(final CliArgs args, final HttpHost host) {

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

    private static RequestSigner buildSigner(final CliArgs args, final HttpHost host) {

        if (!args.sigv4) {
            return RequestSigner.NONE;
        }

        final Region region = args.awsRegion != null
                ? Region.of(args.awsRegion)
                : DefaultAwsRegionProviderChain.builder().build().getRegion();

        final AwsCredentialsProvider credentialsProvider = args.awsProfile != null
                ? ProfileCredentialsProvider.builder().profileName(args.awsProfile).build()
                : DefaultCredentialsProvider.create();

        return new Sigv4RequestSigner(host, region, args.awsService, credentialsProvider);
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
        final String authOptions = "[--username <user> --password <password> | --sigv4 [--aws-region <region>] "
                + "[--aws-service es|aoss] [--aws-profile <profile>]] [--insecure]";
        System.err.println("Usage:");
        System.err.println("  java -jar querqy-opensearch-tool.jar put --url <url> --id <rewriterId> "
                + "--config-file <file> [--revision <revision>] "
                + "[--dictionary-file <tsvFile> [--format tsv_df|tsv_df_cooc] [--hash-functions <n>]] "
                + authOptions + " [--replicas <n>] [--validate-only]");
        System.err.println("  java -jar querqy-opensearch-tool.jar get --url <url> --id <rewriterId> "
                + authOptions);
        System.err.println("  java -jar querqy-opensearch-tool.jar list --url <url> " + authOptions);
        System.err.println("  java -jar querqy-opensearch-tool.jar delete --url <url> --id <rewriterId> "
                + authOptions);
        System.err.println("  java -jar querqy-opensearch-tool.jar put-dictionary --url <url> "
                + "--dictionary-name <name> --dictionary-revision <revision> --dictionary-file <tsvFile> "
                + "[--format tsv_df|tsv_df_cooc] [--hash-functions <n>] " + authOptions + " [--replicas <n>]");
    }

    static final class ValidationFailedException extends RuntimeException {
        final List<String> errors;

        ValidationFailedException(final List<String> errors) {
            super("Invalid rewriter configuration: " + errors);
            this.errors = errors;
        }
    }

    static final class RewriterNotFoundException extends RuntimeException {
        RewriterNotFoundException(final String message) {
            super(message);
        }
    }
}
