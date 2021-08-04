package querqy.opensearch.rewriter;

import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.SearchHits;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.Collections.singletonList;

public class ReplaceRewriterIntegrationTest extends AbstractRewriterIntegrationTest {

    public void testReplaceRewriterRules() throws ExecutionException, InterruptedException {
        indexDocs(
                doc("id", "1", "field1", "test1"),
                doc("id", "2", "field1", "test2"),
                doc("id", "3", "field1", "test1")
        );

        final Map<String, Object> content = new HashMap<>();
        content.put("class", "querqy.opensearch.rewriter.ReplaceRewriterFactory");

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", "TEST => TEST1 \n TEST2 => TEST");
        content.put("config", config);

        final PutRewriterRequest request = new PutRewriterRequest("replace_rules", content);

        client().execute(PutRewriterAction.INSTANCE, request).get();

        QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(singletonList(new Rewriter("replace_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("test"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setQueryFieldsAndBoostings(singletonList("field1"));

        SearchRequestBuilder searchRequestBuilder = client().prepareSearch(getIndexName());
        searchRequestBuilder.setQuery(querqyQuery);

        SearchResponse response = client().search(searchRequestBuilder.request()).get();
        SearchHits hits = response.getHits();

        assertEquals(2L, hits.getTotalHits().value);

        querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(singletonList(new Rewriter("replace_rules")));
        querqyQuery.setMatchingQuery(new MatchingQuery("test2"));
        querqyQuery.setMinimumShouldMatch("1");
        querqyQuery.setQueryFieldsAndBoostings(singletonList("field1"));

        searchRequestBuilder = client().prepareSearch(getIndexName());
        searchRequestBuilder.setQuery(querqyQuery);

        response = client().search(searchRequestBuilder.request()).get();
        hits = response.getHits();

        assertEquals(0L, hits.getTotalHits().value);

    }

}
