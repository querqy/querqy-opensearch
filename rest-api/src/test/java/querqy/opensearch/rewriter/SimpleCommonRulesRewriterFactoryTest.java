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

import static java.util.Collections.singletonList;

import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class SimpleCommonRulesRewriterFactoryTest extends AbstractRewriterIntegrationTest {


    public void testBooleanInput() throws ExecutionException, InterruptedException {
        indexDocs(
                doc("id", "1", "field1", "a"),
                doc("id", "2", "field1", "a test1 some other tokens that bring down normalised tf")
        );

        final Map<String, Object> content = new HashMap<>();
        content.put("class", SimpleCommonRulesRewriterFactory.class.getName());

        final Map<String, Object> config = new HashMap<>();
        config.put("allowBooleanInput", true);
        config.put("rules", "a AND NOT b => \nUP(1000): test1");
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);

        client().execute(PutRewriterAction.INSTANCE, request).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(singletonList(new Rewriter("common_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setQueryFieldsAndBoostings(singletonList("field1"));

        SearchRequestBuilder searchRequestBuilder = client().prepareSearch(getIndexName());
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        SearchHits hits = response.getHits();

        assertEquals(2L, hits.getTotalHits().value());
        assertEquals("2", hits.getHits()[0].getSourceAsMap().get("id"));

        querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(singletonList(new Rewriter("common_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a b"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setQueryFieldsAndBoostings(singletonList("field1"));

        searchRequestBuilder = client().prepareSearch(getIndexName());
        searchRequestBuilder.setQuery(querqyQuery);

        response = client().search(searchRequestBuilder.request()).get();
        hits = response.getHits();

        assertEquals(2L, hits.getTotalHits().value());
        assertEquals("1", hits.getHits()[0].getSourceAsMap().get("id"));

    }

    public void testMultiplicativeBoostMethodMultipliesTheScore() throws ExecutionException, InterruptedException {
        indexDocs(
                doc("id", "1", "field1", "a"),
                doc("id", "2", "field1", "a", "field2", "b")
        );

        final Map<String, Object> content = new HashMap<>();
        content.put("class", SimpleCommonRulesRewriterFactory.class.getName());

        final Map<String, Object> config = new HashMap<>();
        // mixed case on purpose: the boostMethod value must be matched case-insensitively
        config.put("boostMethod", "Multiplicative");
        config.put("rules", "a =>\nUP(3): *{\"term\": {\"field2\":\"b\"}}");
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);

        client().execute(PutRewriterAction.INSTANCE, request).get();

        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(singletonList(new Rewriter("common_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setQueryFieldsAndBoostings(singletonList("field1"));

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(getIndexName());
        searchRequestBuilder.setQuery(querqyQuery);

        final SearchResponse response = client().search(searchRequestBuilder.request()).get();
        final SearchHits hits = response.getHits();

        assertEquals(2L, hits.getTotalHits().value());

        final Map<String, Float> scoreById = new HashMap<>();
        for (final SearchHit hit : hits.getHits()) {
            scoreById.put((String) hit.getSourceAsMap().get("id"), hit.getScore());
        }

        // doc "1" and doc "2" have the same field1 content, so they get the same score from the main query.
        // Doc "2" additionally matches the boost query on field2 and its score must be multiplied by the boost
        // factor 3, not added to.
        assertEquals(scoreById.get("1") * 3f, scoreById.get("2"), 0.001f);
    }

    public void testInvalidBoostMethodIsRejectedAsConfigurationError() {
        final SimpleCommonRulesRewriterFactory factory = new SimpleCommonRulesRewriterFactory("common_rules");

        final Map<String, Object> config = new HashMap<>();
        config.put("boostMethod", "unknown");
        config.put("rules", "a =>\nUP(3): b");

        final List<String> errors = factory.validateConfiguration(config);
        assertNotNull(errors);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("boostMethod"));
    }

}