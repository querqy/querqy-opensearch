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

package querqy.opensearch.pipeline;

import querqy.rewrite.logging.RewriteChainLog;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything {@link QuerqyRewriteRequestProcessor} captures from one {@code querqy} clause's coordinator-side
 * rewrite call, beyond the rewritten model itself - stashed on the request's {@code PipelineProcessingContext}
 * (keyed by {@link QuerqyRewriteRequestProcessor#PIPELINE_CONTEXT_ATTRIBUTE}) for the paired {@code querqy_rewrite}
 * response processor to read back and turn into an {@code ext.querqy} payload. Not itself a {@code Writeable}/
 * {@code ToXContent} - this is purely an in-JVM handoff between the two processors within one request's lifecycle,
 * never serialized.
 */
public final class QuerqyRewriteCapture {

    private final RewriteChainLog rewriteChainLog;
    private final Set<Object> decorations;
    private final Map<String, List<Object>> decorationsMap;

    public QuerqyRewriteCapture(final RewriteChainLog rewriteChainLog, final Set<Object> decorations,
                                final Map<String, List<Object>> decorationsMap) {
        this.rewriteChainLog = rewriteChainLog;
        this.decorations = decorations == null ? Collections.emptySet() : decorations;
        this.decorationsMap = decorationsMap == null ? Collections.emptyMap() : decorationsMap;
    }

    /** May be empty (no rewriter in the chain actually logged anything), but is never itself {@code null}. */
    public RewriteChainLog getRewriteChainLog() {
        return rewriteChainLog;
    }

    public Set<Object> getDecorations() {
        return decorations;
    }

    public Map<String, List<Object>> getDecorationsMap() {
        return decorationsMap;
    }
}
