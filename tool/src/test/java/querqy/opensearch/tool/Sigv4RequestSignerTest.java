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
import org.apache.hc.core5.http.NameValuePair;
import org.junit.Test;
import org.opensearch.client.Request;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class Sigv4RequestSignerTest {

    private static Optional<String> header(final Request request, final String name) {
        return request.getOptions().getHeaders().stream()
                .filter(h -> h.getName().equalsIgnoreCase(name))
                .map(NameValuePair::getValue)
                .findFirst();
    }

    @Test
    public void testSignsWithStaticCredentials() throws Exception {

        final Sigv4RequestSigner signer = new Sigv4RequestSigner(
                HttpHost.create("https://search-my-domain.us-east-1.es.amazonaws.com"),
                Region.US_EAST_1, "es",
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")));

        final Request request = new Request("GET", "/.opensearch-querqy/_search");
        request.addParameter("ignore", "404");

        signer.sign(request);

        final Optional<String> authorization = header(request, "Authorization");
        assertThat(authorization).isPresent();
        assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/");
        assertThat(authorization.get()).contains("/us-east-1/es/aws4_request");
        assertThat(header(request, "X-Amz-Date")).isPresent();
        assertThat(header(request, "X-Amz-Security-Token")).isNotPresent(); // no session token with static creds
    }

    @Test
    public void testSignsWithSessionCredentialsAddsSecurityTokenHeader() throws Exception {

        final Sigv4RequestSigner signer = new Sigv4RequestSigner(
                HttpHost.create("https://my-collection.us-west-2.aoss.amazonaws.com"),
                Region.US_WEST_2, "aoss",
                StaticCredentialsProvider.create(
                        AwsSessionCredentials.create("AKIDEXAMPLE", "secret", "session-token")));

        final Request request = new Request("PUT", "/.opensearch-querqy/_doc/my_rewriter");
        request.setJsonEntity("{\"type\":\"rewriter\"}");

        signer.sign(request);

        assertThat(header(request, "Authorization")).isPresent();
        assertThat(header(request, "X-Amz-Security-Token")).contains("session-token");
    }

    @Test
    public void testSigningIsRepeatableAndDoesNotConsumeTheRequestBody() throws Exception {

        final Sigv4RequestSigner signer = new Sigv4RequestSigner(
                HttpHost.create("https://search-my-domain.us-east-1.es.amazonaws.com"),
                Region.US_EAST_1, "es",
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")));

        final Request request = new Request("PUT", "/.opensearch-querqy/_doc/my_rewriter");
        request.setJsonEntity("{\"type\":\"rewriter\"}");

        signer.sign(request);

        // the entity must still be fully readable afterwards, e.g. when the HTTP client later serializes it
        final List<String> body = java.util.Collections.singletonList(
                org.apache.hc.core5.http.io.entity.EntityUtils.toString(request.getEntity()));
        assertThat(body.get(0)).isEqualTo("{\"type\":\"rewriter\"}");
    }
}
