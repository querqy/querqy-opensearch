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

package querqy.opensearch.rewriter;

import org.opensearch.OpenSearchException;
import org.opensearch.index.shard.IndexShard;
import querqy.opensearch.ConfigUtils;
import querqy.opensearch.OpenSearchRewriterFactory;
import querqy.rewrite.RewriterFactory;
import querqy.rewrite.commonrules.QuerqyParserFactory;
import querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ReplaceRewriterFactory extends OpenSearchRewriterFactory {

    private static final QuerqyParserFactory DEFAULT_RHS_QUERY_PARSER = new WhiteSpaceQuerqyParserFactory();

    private static final boolean DEFAULT_IGNORE_CASE = true;

    private static final String DEFAULT_INPUT_DELIMITER = "\t";

    private querqy.rewrite.contrib.ReplaceRewriterFactory delegate;

    public ReplaceRewriterFactory(String rewriterId) {
        super(rewriterId);
    }

    @Override
    public void configure(Map<String, Object> config) {
        final String rules = (String) config.get("rules");
        final InputStreamReader rulesReader = new InputStreamReader(
                new ByteArrayInputStream(
                        rules.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        final boolean ignoreCase = ConfigUtils.getArg(config, "ignoreCase", DEFAULT_IGNORE_CASE);

        final String inputDelimiter = ConfigUtils.getArg(config, "inputDelimiter", DEFAULT_INPUT_DELIMITER);

        final QuerqyParserFactory querqyParser = ConfigUtils.getInstanceFromArg(
                config, "querqyParser", DEFAULT_RHS_QUERY_PARSER);

        try {
            delegate = new querqy.rewrite.contrib.ReplaceRewriterFactory(rewriterId, rulesReader, ignoreCase,
                    inputDelimiter, querqyParser.createParser());
        } catch (final IOException e) {
            throw new OpenSearchException(e);
        }
    }

    @Override
    public List<String> validateConfiguration(Map<String, Object> config) {

        final String rules = (String) config.get("rules");
        if (rules == null) {
            return Collections.singletonList("Property 'rules' not configured");
        }

        final InputStreamReader rulesReader = new InputStreamReader(
                new ByteArrayInputStream(rules.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        final boolean ignoreCase = ConfigUtils.getArg(config, "ignoreCase", DEFAULT_IGNORE_CASE);

        final String inputDelimiter = ConfigUtils.getArg(config, "inputDelimiter", DEFAULT_INPUT_DELIMITER);


        final QuerqyParserFactory querqyParser;
        try {
            querqyParser = ConfigUtils.getInstanceFromArg(config, "querqyParser", DEFAULT_RHS_QUERY_PARSER);
        } catch (final Exception e) {
            return Collections.singletonList("Invalid attribute 'querqyParser': " + e.getMessage());
        }

        try {
            new querqy.rewrite.contrib.ReplaceRewriterFactory(rewriterId, rulesReader, ignoreCase, inputDelimiter,
                    querqyParser.createParser());
        } catch (final IOException e) {
            return Collections.singletonList("Cannot create rewriter: " + e.getMessage());
        }

        return null;
    }

    @Override
    public RewriterFactory createRewriterFactory(IndexShard indexShard) {
        return delegate;
    }
}
