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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.plugins.Plugin;
import org.opensearch.rest.RestStatus;
import org.opensearch.search.SearchHits;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import org.junit.After;
import org.junit.Test;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.DeleteRewriterAction;
import querqy.opensearch.rewriterstore.DeleteRewriterRequest;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;
import querqy.opensearch.rewriterstore.PutRewriterResponse;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

public class RewriterIntegrationTest extends OpenSearchSingleNodeTestCase {

    private final String INDEX_NAME = "test_index";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    public void testSearchWithConfig() throws Exception {

        index();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c");
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);

        client().execute(PutRewriterAction.INSTANCE, request).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter("common_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);

    }

    public void testRawQuery() throws Exception {
        index();
        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "a =>\nFILTER: * {\"term\":{\"field2\":\"c\" }}");
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);
        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);
        client().execute(PutRewriterAction.INSTANCE, request).get();
        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter("common_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);
        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        final SearchHits hits = response.getHits();
        assertEquals(1L, hits.getTotalHits().value);
        assertEquals("a c", hits.getHits()[0].getSourceAsMap().get("field2"));
    }

    @Test
    public void testLargeConfig() throws Exception {
        index();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());

        final String rules;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("commonrules/rules-large.txt")))) {

            rules = reader.lines().collect(Collectors.joining("\n"));
        }

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);

        final PutRewriterResponse response = client().execute(PutRewriterAction.INSTANCE, request).get();

        assertEquals(RestStatus.CREATED, response.status());

    }

    public void testSearchWithUpdatedConfig() throws Exception {

        index();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "");
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);

        client().execute(PutRewriterAction.INSTANCE, request).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter("common_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(1L, response.getHits().getTotalHits().value);


        final Map<String, Object> content2 = new HashMap<>();
        content2.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());

        final Map<String, Object> config2 = new HashMap<>();
        config2.put("rules", "k =>\nSYNONYM: c");
        config2.put("ignoreCase", true);
        config2.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content2.put("config", config2);

        final PutRewriterRequest request2 = new PutRewriterRequest("common_rules", content2);
        client().execute(PutRewriterAction.INSTANCE, request2).get();

        QuerqyQueryBuilder querqyQuery2 = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery2.setRewriters(Collections.singletonList(new Rewriter("common_rules")));
        querqyQuery2.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery2.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery2.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder2 = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder2.setQuery(querqyQuery2);

        SearchResponse response2 = client().search(searchRequestBuilder2.request()).get();
        assertEquals(2L, response2.getHits().getTotalHits().value);

    }


    public void testThatRewriterIsDeleted() throws Exception {

        index();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c");
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);

        client().execute(PutRewriterAction.INSTANCE, request).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter("common_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);


        final DeleteRewriterRequest delRequest = new DeleteRewriterRequest("common_rules");
        client().execute(DeleteRewriterAction.INSTANCE, delRequest).get();

        QuerqyQueryBuilder querqyQuery2 = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery2.setRewriters(Collections.singletonList(new Rewriter("common_rules")));
        querqyQuery2.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery2.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery2.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder2 = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder2.setQuery(querqyQuery2);

        try {
            client().search(searchRequestBuilder.request()).get();
            fail("Could use deleted rewriter in request");
        } catch (final ExecutionException e) {
            assertTrue(e.getMessage().contains("Rewriter not found: common_rules"));
        }

    }



    @After
    public void deleteRewriterIndex() {
        client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
    }


    public void index() {
        client().admin().indices().prepareCreate(INDEX_NAME).get();
        client().prepareIndex(INDEX_NAME)
                .setSource("field1", "a b", "field2", "a c")
                .get();
        client().prepareIndex(INDEX_NAME)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "b c")
                .get();
    }
}
