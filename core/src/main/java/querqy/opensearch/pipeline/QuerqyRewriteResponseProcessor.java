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

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.ingest.ConfigurationUtils;
import org.opensearch.search.SearchExtBuilder;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;
import org.opensearch.search.pipeline.StatefulSearchResponseProcessor;
import org.opensearch.search.profile.SearchProfileShardResults;
import querqy.json.JsonProvider;
import querqy.opensearch.infologging.LogPayloadType;
import querqy.opensearch.json.XContentJsonProvider;
import querqy.rewrite.logging.RewriteChainLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@link QuerqyRewriteCapture} list the {@code querqy_rewrite} request processor stashed on the
 * request's {@link PipelineProcessingContext} and, unless everything resolves to "nothing to report", attaches a
 * {@link QuerqyExtBuilder} to the response carrying rewrite-chain debug/log data plus any Common Rules DECORATE
 * output - under the {@code ext.querqy} key, mirroring the {@code searchnik/querqy-opensearch@querqy-debug-response}
 * branch's field shape so existing tooling built against it keeps working.
 * <p>
 * There is no in-place mutator for a {@code SearchResponse}'s ext builders, so adding one means rebuilding the
 * response's sections wholesale - the same pattern core itself uses for other response-section-touching
 * processors.
 * <p>
 * On/off control: this processor's own {@code info_logging} config (same shape as the query-DSL field) is the
 * pipeline-level default (absent = {@code "none"}, safe by default); a request's own {@code querqy} query
 * carrying its own {@code info_logging} field overrides that default for that one query specifically (see
 * {@link QuerqyRewriteCapture#getPerQueryPayloadTypeOverride()}). Common Rules DECORATE output is never gated by
 * this - it is emitted whenever present, independent of the rewrite-chain logging level.
 */
public class QuerqyRewriteResponseProcessor extends AbstractProcessor implements StatefulSearchResponseProcessor {

    public static final String TYPE = "querqy_rewrite";

    private static final JsonProvider JSON_PROVIDER = XContentJsonProvider.INSTANCE;

    private final LogPayloadType defaultPayloadType;

    QuerqyRewriteResponseProcessor(final String tag, final String description, final boolean ignoreFailure,
                                   final LogPayloadType defaultPayloadType) {
        super(tag, description, ignoreFailure);
        this.defaultPayloadType = defaultPayloadType;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SearchResponse processResponse(final SearchRequest request, final SearchResponse response,
                                          final PipelineProcessingContext responseContext) {

        final List<QuerqyRewriteCapture> captures =
                (List<QuerqyRewriteCapture>) responseContext.getAttribute(
                        QuerqyRewriteRequestProcessor.PIPELINE_CONTEXT_ATTRIBUTE);

        if (captures == null || captures.isEmpty()) {
            return response;
        }

        final Map<String, Object> payload = buildPayload(captures);
        if (payload.isEmpty()) {
            return response;
        }

        return withExtBuilder(response, new QuerqyExtBuilder(payload));
    }

    private Map<String, Object> buildPayload(final List<QuerqyRewriteCapture> captures) {

        final RewriteChainLog.RewriteChainLogBuilder combinedLog = RewriteChainLog.builder();
        boolean anyLogged = false;

        final Set<Object> combinedDecorations = new LinkedHashSet<>();
        final Map<String, List<Object>> combinedDecorationsMap = new LinkedHashMap<>();

        for (final QuerqyRewriteCapture capture : captures) {

            final LogPayloadType effectivePayloadType = capture.getPerQueryPayloadTypeOverride() != null
                    ? capture.getPerQueryPayloadTypeOverride() : defaultPayloadType;

            if (effectivePayloadType != LogPayloadType.NONE && capture.getRewriteChainLog() != null) {
                for (final RewriteChainLog.RewriteLogEntry entry : capture.getRewriteChainLog().getRewriteChain()) {
                    anyLogged = true;
                    if (effectivePayloadType == LogPayloadType.DETAIL) {
                        combinedLog.add(entry.getRewriterId(), entry.getActions());
                    } else {
                        combinedLog.add(entry.getRewriterId());
                    }
                }
            }

            combinedDecorations.addAll(capture.getDecorations());
            for (final Map.Entry<String, List<Object>> entry : capture.getDecorationsMap().entrySet()) {
                combinedDecorationsMap.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        final Map<String, Object> payload = new LinkedHashMap<>();
        if (anyLogged) {
            payload.putAll(toGenericMap(combinedLog.build()));
        }
        if (!combinedDecorations.isEmpty()) {
            payload.put("decorations", combinedDecorations);
        }
        if (!combinedDecorationsMap.isEmpty()) {
            payload.put("decorationsByKey", combinedDecorationsMap);
        }
        return payload;
    }

    private static Map<String, Object> toGenericMap(final Object value) {
        try {
            return JSON_PROVIDER.readMap(JSON_PROVIDER.writeValueAsString(value));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rebuilds {@code response} with {@code extBuilder} added to its ext section - mirrors core's own {@code
     * org.opensearch.search.pipeline.common.helpers.SearchResponseUtil#replaceHits} pattern (copy every section
     * across into a new {@code InternalSearchResponse}/{@code SearchResponse}), since there is no in-place mutator
     * for this on the existing objects.
     */
    private static SearchResponse withExtBuilder(final SearchResponse response, final SearchExtBuilder extBuilder) {

        final SearchResponseSections sections;
        if (response.getAggregations() == null || response.getAggregations() instanceof InternalAggregations) {
            sections = new InternalSearchResponse(
                    response.getHits(),
                    (InternalAggregations) response.getAggregations(),
                    response.getSuggest(),
                    new SearchProfileShardResults(response.getProfileResults()),
                    response.isTimedOut(),
                    response.isTerminatedEarly(),
                    response.getNumReducePhases(),
                    Collections.singletonList(extBuilder));
        } else {
            sections = new SearchResponseSections(
                    response.getHits(),
                    response.getAggregations(),
                    response.getSuggest(),
                    response.isTimedOut(),
                    response.isTerminatedEarly(),
                    new SearchProfileShardResults(response.getProfileResults()),
                    response.getNumReducePhases(),
                    Collections.singletonList(extBuilder));
        }

        return new SearchResponse(
                sections,
                response.getScrollId(),
                response.getTotalShards(),
                response.getSuccessfulShards(),
                response.getSkippedShards(),
                response.getTook().millis(),
                response.getShardFailures(),
                response.getClusters(),
                response.pointInTimeId());
    }

    public static final class Factory implements Processor.Factory<SearchResponseProcessor> {

        @Override
        public SearchResponseProcessor create(
                final Map<String, Processor.Factory<SearchResponseProcessor>> processorFactories,
                final String tag, final String description, final boolean ignoreFailure,
                final Map<String, Object> config, final Processor.PipelineContext pipelineContext) {

            final Map<String, Object> infoLoggingConfig = ConfigurationUtils.readOptionalMap(TYPE, tag, config,
                    "info_logging");
            final LogPayloadType defaultPayloadType = parsePayloadType(infoLoggingConfig);

            return new QuerqyRewriteResponseProcessor(tag, description, ignoreFailure, defaultPayloadType);
        }

        private static LogPayloadType parsePayloadType(final Map<String, Object> infoLoggingConfig) {
            if (infoLoggingConfig == null) {
                return LogPayloadType.NONE;
            }
            final Object type = infoLoggingConfig.get("type");
            if (type == null) {
                return LogPayloadType.NONE;
            }
            try {
                return LogPayloadType.valueOf(type.toString().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("[" + TYPE + "] info_logging.type must be one of "
                        + "none/rewriter_id/detail, got: " + type);
            }
        }
    }
}
