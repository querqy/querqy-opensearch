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

package querqy.opensearch.infologging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import querqy.lucene.rewrite.infologging.Sink;
import querqy.opensearch.query.InfoLoggingSpec;
import querqy.rewrite.SearchEngineRequestAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Log4jSink implements Sink {

    private static final String CONTEXT_KEY = Log4jSink.class.getName() + ".MESSAGES";
    private static final InfoLoggingSpec DEFAULT_SPEC = new InfoLoggingSpec();

    private final Logger logger = LogManager.getLogger(Log4jSink.class);
    private static final Marker MARKER_QUERQY_REWRITER = MarkerManager.getMarker("QUERQY");
    public static final Marker MARKER_QUERQY_REWRITER_ID = MarkerManager.getMarker("REWRITER_ID")
            .addParents(MARKER_QUERQY_REWRITER);
    public static final Marker MARKER_QUERQY_REWRITER_DETAIL = MarkerManager.getMarker("DETAIL")
            .addParents(MARKER_QUERQY_REWRITER);

    @Override
    @SuppressWarnings("unchecked")
    public void log(final Object message, final String rewriterId,
                    final SearchEngineRequestAdapter searchEngineRequestAdapter) {

        final Map<String, List<Object>> messages = (Map<String, List<Object>>) searchEngineRequestAdapter
                .getContext().computeIfAbsent(CONTEXT_KEY, key -> new TreeMap<>());

        messages.computeIfAbsent(rewriterId, key -> new ArrayList<>()).add(message);

    }

    @Override
    @SuppressWarnings("unchecked")
    public void endOfRequest(final SearchEngineRequestAdapter searchEngineRequestAdapter) {

        final Map<String, List<Object>> messages = (Map<String, List<Object>>) searchEngineRequestAdapter.getContext()
                .get(CONTEXT_KEY);

        if (messages != null && !messages.isEmpty()) {
            if (searchEngineRequestAdapter instanceof InfoLoggingSpecProvider) {
                final InfoLoggingSpec spec = ((InfoLoggingSpecProvider) searchEngineRequestAdapter).getInfoLoggingSpec()
                        .orElse(DEFAULT_SPEC);
                switch (spec.getPayloadType()) {
                        case REWRITER_ID:
                        logger.info(MARKER_QUERQY_REWRITER_ID,
                                new LogMessage.RewriterIdLogMessage(spec.getId().orElse(null), messages));
                        break;
                        case DETAIL:
                            logger.info(MARKER_QUERQY_REWRITER_DETAIL,
                                    new LogMessage.DetailLogMessage(spec.getId().orElse(null), messages));
                            break;

                    };
            }
        }
    }

}
