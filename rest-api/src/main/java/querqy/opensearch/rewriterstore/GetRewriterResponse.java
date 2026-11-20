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

import org.opensearch.common.xcontent.StatusToXContentObject;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public class GetRewriterResponse extends ActionResponse implements StatusToXContentObject {

    public static final String FIELD_REWRITER = "rewriter";
    public static final String FIELD_REWRITERS = "rewriters";

    private final RewriterInfo rewriter;
    private final List<RewriterInfo> rewriters;

    /**
     * The response to a request for a single rewriter.
     *
     * @param rewriter The rewriter information
     */
    public GetRewriterResponse(final RewriterInfo rewriter) {
        this.rewriter = rewriter;
        this.rewriters = null;
    }

    /**
     * The response to a request for all rewriters.
     *
     * @param rewriters The rewriter information, one element per rewriter
     */
    public GetRewriterResponse(final List<RewriterInfo> rewriters) {
        this.rewriter = null;
        this.rewriters = rewriters;
    }

    public GetRewriterResponse(final StreamInput in) throws IOException {
        super(in);
        if (in.readBoolean()) {
            rewriter = new RewriterInfo(in);
            rewriters = null;
        } else {
            rewriter = null;
            rewriters = in.readList(RewriterInfo::new);
        }
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeBoolean(rewriter != null);
        if (rewriter != null) {
            rewriter.writeTo(out);
        } else {
            out.writeList(rewriters);
        }
    }

    @Override
    public RestStatus status() {
        return RestStatus.OK;
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {

        builder.startObject();

        if (rewriter != null) {

            builder.field(FIELD_REWRITER, rewriter);

        } else {

            builder.startArray(FIELD_REWRITERS);
            for (final RewriterInfo rewriterInfo : rewriters) {
                rewriterInfo.toXContent(builder, params);
            }
            builder.endArray();

        }

        builder.endObject();

        return builder;
    }

    /**
     * @return The requested rewriter, or null if this is the response to a request for all rewriters.
     */
    public RewriterInfo getRewriter() {
        return rewriter;
    }

    /**
     * @return All rewriters, or null if this is the response to a request for a single rewriter.
     */
    public List<RewriterInfo> getRewriters() {
        return rewriters;
    }

}