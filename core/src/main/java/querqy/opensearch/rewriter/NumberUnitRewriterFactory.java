/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2021 Querqy for OpenSearch Contributors
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

package querqy.opensearch.rewriter;

import org.opensearch.index.shard.IndexShard;
import querqy.opensearch.OpenSearchRewriterFactory;
import querqy.opensearch.rewriter.numberunit.NumberUnitQueryCreatorOpenSearch;
import querqy.rewrite.RewriterFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class NumberUnitRewriterFactory extends OpenSearchRewriterFactory {

    private static final String KEY_CONFIG_PROPERTY = "config";

    private querqy.rewriter.numberunit.NumberUnitRewriterFactory delegate;

    public NumberUnitRewriterFactory(String rewriterId) {
        super(rewriterId);
    }

    @Override
    public RewriterFactory createRewriterFactory(IndexShard indexShard) {
        return delegate;
    }

    @Override
    public void configure(Map<String, Object> config) {
        final Object numberUnitConfig = config.get(KEY_CONFIG_PROPERTY);

        try {
            this.delegate = new querqy.rewriter.numberunit.NumberUnitRewriterFactory(
                    rewriterId, (String) numberUnitConfig, NumberUnitQueryCreatorOpenSearch::new);
        } catch (IOException e) {
            // checked in this::validateConfiguration
        }
    }

    @Override
    public List<String> validateConfiguration(Map<String, Object> config) {

        final Object numberUnitConfig = config.get(KEY_CONFIG_PROPERTY);
        if (!(numberUnitConfig instanceof String)) {
            return Collections.singletonList("Property 'config' not or not properly configured");
        }

        return querqy.rewriter.numberunit.NumberUnitRewriterFactory.validateConfiguration((String) numberUnitConfig);
    }

}