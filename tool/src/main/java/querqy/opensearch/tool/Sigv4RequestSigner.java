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

import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * Signs each request with AWS SigV4 so the tool can talk to an Amazon OpenSearch Service domain or OpenSearch
 * Serverless collection that authenticates via IAM (service name {@code es}/{@code aoss}) instead of - or
 * alongside - an internal user database. Unlike Basic Auth, which the underlying {@code RestClient} applies
 * transparently via its credentials provider, SigV4 binds the signature to the exact method/path/query/
 * headers/body of a single request, so it has to be computed per request rather than configured once.
 */
final class Sigv4RequestSigner implements RequestSigner {

    private final HttpHost host;
    private final Region region;
    private final String service;
    private final AwsCredentialsProvider credentialsProvider;
    private final Aws4Signer signer = Aws4Signer.create();

    Sigv4RequestSigner(final HttpHost host, final Region region, final String service,
                        final AwsCredentialsProvider credentialsProvider) {
        this.host = host;
        this.region = region;
        this.service = service;
        this.credentialsProvider = credentialsProvider;
    }

    @Override
    public void sign(final Request request) throws IOException {

        final byte[] body = request.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(request.getEntity());

        final SdkHttpFullRequest.Builder builder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(request.getMethod()))
                .uri(toUri(request))
                .contentStreamProvider(() -> new ByteArrayInputStream(body));
        for (final Map.Entry<String, String> param : request.getParameters().entrySet()) {
            if (isSignableQueryParameter(param.getKey())) {
                builder.putRawQueryParameter(param.getKey(), param.getValue());
            }
        }

        final SdkHttpFullRequest signed = signer.sign(builder.build(), Aws4SignerParams.builder()
                .signingRegion(region)
                .signingName(service)
                .awsCredentials(credentialsProvider.resolveCredentials())
                .build());

        final RequestOptions.Builder options = request.getOptions().toBuilder();
        for (final Map.Entry<String, List<String>> header : signed.headers().entrySet()) {
            if (isSignatureHeader(header.getKey())) {
                for (final String value : header.getValue()) {
                    options.addHeader(header.getKey(), value);
                }
            }
        }
        request.setOptions(options.build());
    }

    /**
     * {@code ignore} is a client-side-only directive (which response codes shouldn't throw a
     * {@link org.opensearch.client.ResponseException}) that {@code RestClient} strips before building the real
     * HTTP request ("ignore is a special parameter supported by the clients, shouldn't be sent to es" -
     * {@code RestClient}'s own source). Signing it would sign a request that was never actually sent over the
     * wire, and AWS rejects the resulting signature as a mismatch (its error message shows the real, ignore-less
     * canonical query string it computed from the request it actually received).
     */
    static boolean isSignableQueryParameter(final String name) {
        return !"ignore".equals(name);
    }

    private static boolean isSignatureHeader(final String name) {
        return "Authorization".equalsIgnoreCase(name)
                || "X-Amz-Date".equalsIgnoreCase(name)
                || "X-Amz-Security-Token".equalsIgnoreCase(name);
    }

    private URI toUri(final Request request) {
        try {
            return new URI(host.getSchemeName(), null, host.getHostName(), host.getPort(),
                    request.getEndpoint(), null, null);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Invalid request path '" + request.getEndpoint() + "'", e);
        }
    }
}
