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

package querqy.opensearch.rewriterstore;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;

public class GetRewriterRequest extends ActionRequest {

    private final String rewriterId;

    public GetRewriterRequest(final StreamInput in) throws IOException {
        super(in);
        rewriterId = in.readOptionalString();
    }

    /**
     * Request the configuration of all rewriters.
     */
    public GetRewriterRequest() {
        this((String) null);
    }

    /**
     * @param rewriterId The ID of the rewriter to read, or null to read all rewriters.
     */
    public GetRewriterRequest(final String rewriterId) {
        super();
        this.rewriterId = rewriterId;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(rewriterId);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    /**
     * @return The ID of the rewriter to read, or null if all rewriters shall be listed.
     */
    public String getRewriterId() {
        return rewriterId;
    }

}