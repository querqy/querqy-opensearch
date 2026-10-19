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

import org.opensearch.client.Request;

import java.io.IOException;

/**
 * Adds whatever authentication a {@link Request} needs right before it is sent. Basic Auth (if any) is
 * configured once, on the {@link org.opensearch.client.RestClient} itself; AWS SigV4 ({@link Sigv4RequestSigner})
 * instead has to sign every request individually, since the signature is bound to the request's method, path,
 * query, headers, and body.
 */
interface RequestSigner {

    RequestSigner NONE = request -> { };

    void sign(Request request) throws IOException;
}
