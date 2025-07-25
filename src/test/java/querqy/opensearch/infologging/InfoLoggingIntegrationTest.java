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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import querqy.opensearch.QuerqyPlugin;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.query.InfoLoggingSpec;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

public class InfoLoggingIntegrationTest extends OpenSearchSingleNodeTestCase  {

    private final String INDEX_NAME = "test_index";
    private static ListAppender APPENDER;

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    @BeforeClass
    public static void addAppender() {

        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();
        APPENDER = new ListAppender("list");
        APPENDER.start();
        config.addAppender(APPENDER);
        AppenderRef ref = AppenderRef.createAppenderRef("list", null, null);
        AppenderRef[] refs = new AppenderRef[] {ref};
        LoggerConfig loggerConfig = LoggerConfig.createLogger(false, Level.INFO, Log4jSink.class.getName(),
                "true", refs, null, config, null );
        loggerConfig.addAppender(APPENDER, null, null);
        config.addLogger(Log4jSink.class.getName(), loggerConfig);
        ctx.updateLoggers();

    }

    @AfterClass
    public static void removeAppender() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();
        config.removeLogger(Log4jSink.class.getName());
        ctx.updateLoggers();
    }

    @After
    @Before
    public void clearAppender() {
        APPENDER.clear();
    }

    @After
    public void deleteRewriterIndex() {
        try {
            client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
        } catch (final IndexNotFoundException e) {
            // Ignore
        }
    }

    @Test
    public void testOneRewriterLoggingDetails() throws Exception {
        index();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf = new HashMap<>();
        loggingConf.put("sinks", "log4j");
        content.put("info_logging", loggingConf);

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
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
        querqyQuery.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.DETAIL, "query-detail"));

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);

        final List<LogEvent> events = APPENDER.getEvents();
        assertNotNull(events);
        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("{\"id\":\"query-detail\",\"msg\":{\"common_rules\":[[{\"message\":\"msg1\",\"match\":" +
                        "{\"term\":\"k\",\"type\":\"exact\"},\"instructions\":" +
                        "[{\"type\":\"synonym\",\"value\":\"c\"}]}]]}}",
                event.getMessage().getFormattedMessage());

        assertEquals(Log4jSink.MARKER_QUERQY_REWRITER_DETAIL, event.getMarker());

    }

    @Test
    public void testInvalidSinkName() {

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf = new HashMap<>();
        loggingConf.put("sinks", "invalidSink");
        content.put("info_logging", loggingConf);

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
        config.put("ignoreCase", true);
        config.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("common_rules", content);

        try {
            client().execute(PutRewriterAction.INSTANCE, request).get();
            fail("Invalid sink must not be excepted");
        } catch (final Exception e) {
            assertTrue((e instanceof ActionRequestValidationException)
                    || (e.getCause() instanceof ActionRequestValidationException));
        }
    }

    @Test
    public void testTwoRewritersOfSameTypeLoggingDetails() throws Exception {

        index();

        final Map<String, Object> content1 = new HashMap<>();
        content1.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf1 = new HashMap<>();
        loggingConf1.put("sinks", Collections.singletonList("log4j"));
        content1.put("info_logging", loggingConf1);

        final Map<String, Object> config1 = new HashMap<>();
        config1.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
        config1.put("ignoreCase", true);
        config1.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content1.put("config", config1);

        final PutRewriterRequest request1 = new PutRewriterRequest("common_rules1", content1);

        client().execute(PutRewriterAction.INSTANCE, request1).get();

        final Map<String, Object> content2 = new HashMap<>();
        content2.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf2 = new HashMap<>();
        loggingConf2.put("sinks", "log4j");
        content2.put("info_logging", loggingConf2);

        final Map<String, Object> config2 = new HashMap<>();
        config2.put("rules", "k =>\nUP: q\n@_log: \"msg2\"");
        config2.put("ignoreCase", true);
        config2.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content2.put("config", config2);

        final PutRewriterRequest request2 = new PutRewriterRequest("common_rules2", content2);

        client().execute(PutRewriterAction.INSTANCE, request2).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Arrays.asList(new Rewriter("common_rules1"), new Rewriter("common_rules2")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.DETAIL, "query-detail"));

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);

        final List<LogEvent> events = APPENDER.getEvents();
        assertNotNull(events);
        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("{\"id\":\"query-detail\",\"msg\":{\"common_rules1\":[[{\"message\":\"msg1\",\"match\":" +
                        "{\"term\":\"k\",\"type\":\"exact\"},\"instructions\":[{\"type\":\"synonym\"," +
                        "\"value\":\"c\"}]}]],\"common_rules2\":[[{\"message\":\"msg2\",\"match\":{\"term\":\"k\"," +
                        "\"type\":\"exact\"},\"instructions\":[{\"type\":\"up\",\"value\":\"q\"}]}]]}}",
                event.getMessage().getFormattedMessage());

        assertEquals(Log4jSink.MARKER_QUERQY_REWRITER_DETAIL, event.getMarker());

    }

    @Test
    public void testTwoRewritersOfDifferentTypesLoggingDetails() throws Exception {

        index();

        final Map<String, Object> content1 = new HashMap<>();
        content1.put("class", querqy.opensearch.rewriter.ReplaceRewriterFactory.class.getName());
        final Map<String, Object> loggingConf1 = new HashMap<>();
        loggingConf1.put("sinks", Collections.singletonList("log4j"));
        content1.put("info_logging", loggingConf1);

        final Map<String, Object> config1 = new HashMap<>();
        config1.put("rules", "rr => k");
        content1.put("config", config1);

        final PutRewriterRequest request1 = new PutRewriterRequest("replace1", content1);

        client().execute(PutRewriterAction.INSTANCE, request1).get();

        final Map<String, Object> content2 = new HashMap<>();
        content2.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf2 = new HashMap<>();
        loggingConf2.put("sinks", "log4j");
        content2.put("info_logging", loggingConf2);

        final Map<String, Object> config2 = new HashMap<>();
        config2.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg2\"");
        config2.put("ignoreCase", true);
        config2.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content2.put("config", config2);

        final PutRewriterRequest request2 = new PutRewriterRequest("common_rules2", content2);

        client().execute(PutRewriterAction.INSTANCE, request2).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Arrays.asList(new Rewriter("replace1"), new Rewriter("common_rules2")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a rr"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.DETAIL, "query-detail"));

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();

        assertEquals(2L, response.getHits().getTotalHits().value);

        final List<LogEvent> events = APPENDER.getEvents();
        assertNotNull(events);
        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("{\"id\":\"query-detail\",\"msg\":{\"common_rules2\":[[{\"message\":\"msg2\",\"match\":" +
                        "{\"term\":\"k\",\"type\":\"exact\"},\"instructions\":[{\"type\":\"synonym\"," +
                        "\"value\":\"c\"}]}]],\"replace1\":[[{\"message\":\"rr => k\",\"match\":{\"term\":\"rr\"," +
                        "\"type\":\"exact\"},\"instructions\":[{\"type\":\"replace\",\"value\":\"k\"}]}]]}}",
                event.getMessage().getFormattedMessage());

        assertEquals(Log4jSink.MARKER_QUERQY_REWRITER_DETAIL, event.getMarker());

    }

    @Test
    public void testThatInfoLoggingTypeNoneIsDefault() throws Exception {
        index();

        final Map<String, Object> content1 = new HashMap<>();
        content1.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf1 = new HashMap<>();
        loggingConf1.put("sinks", Collections.singletonList("log4j"));
        content1.put("info_logging", loggingConf1);

        final Map<String, Object> config1 = new HashMap<>();
        config1.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
        config1.put("ignoreCase", true);
        config1.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content1.put("config", config1);

        final PutRewriterRequest request1 = new PutRewriterRequest("common_rules1", content1);

        assertEquals(201, client().execute(PutRewriterAction.INSTANCE, request1).get().status().getStatus());

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Collections.singletonList(new Rewriter("common_rules1")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);

        final List<LogEvent> events = APPENDER.getEvents();
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    public void testOneRewriterLoggingId() throws Exception {

        index();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf = new HashMap<>();
        loggingConf.put("sinks", "log4j");
        content.put("info_logging", loggingConf);

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
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
        querqyQuery.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.REWRITER_ID, "query-detail"));

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);

        final List<LogEvent> events = APPENDER.getEvents();
        assertNotNull(events);
        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("{\"id\":\"query-detail\",\"msg\":[\"common_rules\"]}", event.getMessage().getFormattedMessage());

        assertEquals(Log4jSink.MARKER_QUERQY_REWRITER_ID, event.getMarker());

    }

    @Test
    public void testLoggingWithOutRequestId() throws Exception {

        index();

        final Map<String, Object> content = new HashMap<>();
        content.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf = new HashMap<>();
        loggingConf.put("sinks", "log4j");
        content.put("info_logging", loggingConf);

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
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
        querqyQuery.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.REWRITER_ID));

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);

        final List<LogEvent> events = APPENDER.getEvents();
        assertNotNull(events);
        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("{\"msg\":[\"common_rules\"]}", event.getMessage().getFormattedMessage());

        assertEquals(Log4jSink.MARKER_QUERQY_REWRITER_ID, event.getMarker());

    }

    @Test
    public void testTwoRewritersLoggingIds() throws Exception {

        index();

        final Map<String, Object> content1 = new HashMap<>();
        content1.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf1 = new HashMap<>();
        loggingConf1.put("sinks", "log4j");
        content1.put("info_logging", loggingConf1);

        final Map<String, Object> config1 = new HashMap<>();
        config1.put("rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");
        config1.put("ignoreCase", true);
        config1.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content1.put("config", config1);

        final PutRewriterRequest request1 = new PutRewriterRequest("common_rules1", content1);

        client().execute(PutRewriterAction.INSTANCE, request1).get();

        final Map<String, Object> content2 = new HashMap<>();
        content2.put("class", querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory.class.getName());
        final Map<String, Object> loggingConf2 = new HashMap<>();
        loggingConf2.put("sinks", "log4j");
        content2.put("info_logging", loggingConf2);

        final Map<String, Object> config2 = new HashMap<>();
        config2.put("rules", "k =>\nUP: q\n@_log: \"msg2\"");
        config2.put("ignoreCase", true);
        config2.put("querqyParser", querqy.rewrite.commonrules.WhiteSpaceQuerqyParserFactory.class.getName());
        content2.put("config", config2);

        final PutRewriterRequest request2 = new PutRewriterRequest("common_rules2", content2);

        client().execute(PutRewriterAction.INSTANCE, request2).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Arrays.asList(new Rewriter("common_rules1"), new Rewriter("common_rules2")));
        querqyQuery.setMatchingQuery(new MatchingQuery("a k"));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setInfoLoggingSpec(new InfoLoggingSpec(LogPayloadType.REWRITER_ID, "query-detail"));

        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        assertEquals(2L, response.getHits().getTotalHits().value);

        final List<LogEvent> events = APPENDER.getEvents();
        assertNotNull(events);
        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("{\"id\":\"query-detail\",\"msg\":[\"common_rules1\",\"common_rules2\"]}",
                event.getMessage().getFormattedMessage());

        assertEquals(Log4jSink.MARKER_QUERQY_REWRITER_ID, event.getMarker());

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
