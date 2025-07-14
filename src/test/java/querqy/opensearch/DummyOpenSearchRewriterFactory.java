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

package querqy.opensearch;

import org.opensearch.index.shard.IndexShard;
import querqy.model.ExpandedQuery;
import querqy.model.Term;
import querqy.rewrite.QueryRewriter;
import querqy.rewrite.RewriterFactory;
import querqy.rewrite.RewriterOutput;
import querqy.rewrite.SearchEngineRequestAdapter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DummyOpenSearchRewriterFactory extends OpenSearchRewriterFactory {

    public DummyOpenSearchRewriterFactory(final String rewriterId) {
        super(rewriterId);
    }

    @Override
    public void configure(final Map<String, Object> config) {

    }

    @Override
    public List<String> validateConfiguration(final Map<String, Object> config) {
        final Object error = config.get("error");
        return error == null ? null : Collections.singletonList(error.toString());
    }

    @Override
    public RewriterFactory createRewriterFactory(final IndexShard indexShard)  {
        return new RewriterFactory(rewriterId) {
            @Override
            public QueryRewriter createRewriter(final ExpandedQuery input,
                                                final SearchEngineRequestAdapter searchEngineRequestAdapter) {
                return (query, adapter) -> RewriterOutput.builder().expandedQuery(query).build();
            }

            @Override
            public Set<Term> getGenerableTerms() {
                return Collections.emptySet();
            }
        };
    }
}
