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

import static org.opensearch.test.OpenSearchIntegTestCase.Scope.SUITE;
import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;
import static querqy.opensearch.rewriterstore.Constants.SETTINGS_QUERQY_INDEX_NUM_REPLICAS;

import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.delete.DeleteResponse;
import org.opensearch.action.search.SearchPhaseExecutionException;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.transport.RemoteTransportException;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.NodesClearRewriterCacheAction;
import querqy.opensearch.rewriterstore.NodesClearRewriterCacheRequest;
import querqy.opensearch.rewriterstore.NodesClearRewriterCacheResponse;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@OpenSearchIntegTestCase.ClusterScope(scope = SUITE, supportsDedicatedMasters = false, numClientNodes = 1, minNumDataNodes = 4,
        maxNumDataNodes = 6)
public class RewriterShardContextsTest extends OpenSearchIntegTestCase {

    private static final int NUM_DOT_QUERY_REPLICAS = 1;


    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }


    @Override
    protected Settings nodeSettings(final int nodeOrdinal) {

        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
                .put(SETTINGS_QUERQY_INDEX_NUM_REPLICAS, NUM_DOT_QUERY_REPLICAS)
                .build();
    }


    public void testClearRewritersFromCache() throws Exception {

        index();

        // create rewriter
        final Map<String, Object> payload1 = new HashMap<>();
        payload1.put("class", DummyOpenSearchRewriterFactory.class.getName());
        final Map<String, Object> config1 = new HashMap<>();
        config1.put("p1", 1L);
        payload1.put("config", config1);

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest("r2", payload1)).get();

        // assure we can use the rewriter in the query
        QuerqyQueryBuilder query = new QuerqyQueryBuilder();

        query.setMatchingQuery(new MatchingQuery("a"));
        query.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        query.setRewriters(Collections.singletonList(new Rewriter("r2")));
        final SearchResponse response1 = client().prepareSearch("idx").setQuery(query).execute().get();
        assertEquals(0, response1.getFailedShards());

        // clear loaded rewriters
        final NodesClearRewriterCacheResponse clearRewriterCacheResponse1 = client()
                .execute(NodesClearRewriterCacheAction.INSTANCE, new NodesClearRewriterCacheRequest()).get();
        assertFalse(clearRewriterCacheResponse1.hasFailures());

        // search with the rewriter again
        final SearchResponse response2 = client().prepareSearch("idx").setQuery(query).execute().get();
        assertEquals(0, response2.getFailedShards()); // rewriter probably reloaded

        // delete rewriter config from .query index - this should never be done directly (use a delete rewriter action)
        final DeleteResponse deleteResponse = client().prepareDelete(QUERQY_INDEX_NAME, "r2").execute().get();
        assertEquals(DocWriteResponse.Result.DELETED, deleteResponse.getResult());

        // query again - the rewriter should still be cached
        final SearchResponse response3 = client().prepareSearch("idx").setQuery(query).execute().get();
        assertEquals(0, response3.getFailedShards());

        // clear loaded rewriters
        final NodesClearRewriterCacheResponse clearRewriterCacheResponse2 = client().
                execute(NodesClearRewriterCacheAction.INSTANCE, new NodesClearRewriterCacheRequest()).get();
        assertFalse(clearRewriterCacheResponse2.hasFailures());

        // now we should crash: rewriters are neither loaded nor will there be a config in the .querqy index

        try {

            client().prepareSearch("idx").setQuery(query).execute().get();
            fail("Rewriter must not exist");

        } catch (final ExecutionException e) {

            final Throwable cause1 = e.getCause();
            assertTrue(cause1 instanceof SearchPhaseExecutionException);
            final Throwable cause2 = cause1.getCause();
            assertTrue(cause2 instanceof ResourceNotFoundException);
            assertEquals("Rewriter not found: r2", cause2.getMessage());

        }

    }

    public void index() {
        final String indexName = "idx";
        client().admin().indices().prepareCreate(indexName).setSettings(Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)).get();
        client().prepareIndex(indexName)
                .setSource("field1", "a b", "field2", "a c")
                .get();
        client().prepareIndex(indexName)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "b c")
                .get();
    }
}