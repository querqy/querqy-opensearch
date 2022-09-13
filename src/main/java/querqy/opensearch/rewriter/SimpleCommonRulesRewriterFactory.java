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
import querqy.rewrite.commonrules.model.BoostInstruction.BoostMethod;
import querqy.rewrite.commonrules.select.ExpressionCriteriaSelectionStrategyFactory;
import querqy.rewrite.commonrules.select.SelectionStrategyFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SimpleCommonRulesRewriterFactory extends OpenSearchRewriterFactory {

    private static final SelectionStrategyFactory DEFAULT_SELECTION_STRATEGY_FACTORY =
            new ExpressionCriteriaSelectionStrategyFactory();

    private static final QuerqyParserFactory DEFAULT_RHS_QUERY_PARSER = new WhiteSpaceQuerqyParserFactory();

    private querqy.rewrite.commonrules.SimpleCommonRulesRewriterFactory delegate;

    public SimpleCommonRulesRewriterFactory(final String rewriterId) {
        super(rewriterId);
    }

    @Override
    public void configure(final Map<String, Object> config) {
        final boolean ignoreCase = ConfigUtils.getArg(config, "ignoreCase", true);
        final boolean allowBooleanInput = ConfigUtils.getArg(config, "allowBooleanInput", false);

        final QuerqyParserFactory querqyParser = ConfigUtils
                .getInstanceFromArg(config, "querqyParser", DEFAULT_RHS_QUERY_PARSER);

        final String rules = ConfigUtils.getStringArg(config, "rules", "");

        // TODO: we might want to configure named selection strategies in the future
        final Map<String, SelectionStrategyFactory> selectionStrategyFactories = Collections.emptyMap();

        try {
            delegate = new querqy.rewrite.commonrules.SimpleCommonRulesRewriterFactory(rewriterId,
                    new StringReader(rules), allowBooleanInput, BoostMethod.ADDITIVE, querqyParser, ignoreCase, selectionStrategyFactories,
                    DEFAULT_SELECTION_STRATEGY_FACTORY, false);
        } catch (final IOException e) {
            throw new OpenSearchException(e);
        }

    }

    @Override
    public List<String> validateConfiguration(final Map<String, Object> config) {
        final String rules = ConfigUtils.getStringArg(config, "rules",  null);
        if (rules == null) {
            return Collections.singletonList("Missing attribute 'rules'");
        }
        final QuerqyParserFactory querqyParser;
        try {
            querqyParser = ConfigUtils
                    .getInstanceFromArg(config, "querqyParser", DEFAULT_RHS_QUERY_PARSER);
        } catch (final Exception e) {
            return Collections.singletonList("Invalid attribute 'querqyParser': " + e.getMessage());
        }


        final boolean ignoreCase = ConfigUtils.getArg(config, "ignoreCase", true);
        final boolean allowBooleanInput = ConfigUtils.getArg(config, "allowBooleanInput", false);
        try {
            new querqy.rewrite.commonrules.SimpleCommonRulesRewriterFactory(rewriterId,
                    new StringReader(rules), allowBooleanInput, BoostMethod.ADDITIVE,querqyParser, ignoreCase, Collections.emptyMap(),
                    DEFAULT_SELECTION_STRATEGY_FACTORY, false);
        } catch (final IOException e) {
            return Collections.singletonList("Cannot create rewriter: " + e.getMessage());
        }

        return null;
    }

    @Override
    public RewriterFactory createRewriterFactory(final IndexShard indexShard) {
        return delegate;
    }


}
