/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package querqy.opensearch.rewriterstore;

import org.opensearch.action.ActionResponse;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.StatusToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.rest.RestStatus;

import java.io.IOException;

public class PutRewriterResponse extends ActionResponse implements StatusToXContentObject  {

    private IndexResponse indexResponse;
    private NodesReloadRewriterResponse reloadResponse;

    public PutRewriterResponse(final IndexResponse indexResponse, final NodesReloadRewriterResponse reloadResponse) {
        this.indexResponse = indexResponse;
        this.reloadResponse = reloadResponse;
    }

    public PutRewriterResponse(final StreamInput in) throws IOException {
        super(in);
        indexResponse = new IndexResponse(in);
        reloadResponse = new NodesReloadRewriterResponse(in);
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        indexResponse.writeTo(out);
        reloadResponse.writeTo(out);
    }

    @Override
    public RestStatus status() {
        return indexResponse.status();
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {

        builder.startObject();
        builder.field("put", indexResponse);
        builder.field("reloaded", reloadResponse);
        builder.endObject();
        return builder;
    }

    public IndexResponse getIndexResponse() {
        return indexResponse;
    }

    public NodesReloadRewriterResponse getReloadResponse() {
        return reloadResponse;
    }
}
