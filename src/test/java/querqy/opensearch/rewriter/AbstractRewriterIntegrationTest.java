package querqy.opensearch.rewriter;

import org.opensearch.action.index.IndexRequestBuilder;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.QuerqyPlugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singleton;

public abstract class AbstractRewriterIntegrationTest extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return singleton(QuerqyPlugin.class);
    }

    private static final String INDEX_NAME = "test_index";

    protected static String getIndexName() {
        return INDEX_NAME;
    }

    public IndexDocument doc(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new RuntimeException("Input size must be even");
        }

        final Map<String, Object> doc = new HashMap<>();
        for (int i = 0; i < kv.length; i = i + 2) {
            doc.put((String) kv[i], kv[i + 1]);
        }

        return IndexDocument.of(doc);
    }

    public final void indexDocs(final IndexDocument... docs) {
        client().admin().indices().prepareCreate(getIndexName()).get();

        Arrays.stream(docs).forEach(doc ->
                client().prepareIndex(getIndexName(), null)
                        .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                        .setSource(doc.getDoc())
                        .get());
    }

    public static class IndexDocument {
        final Map<String, Object> doc;

        private IndexDocument(final Map<String, Object> doc) {
            this.doc = doc;
        }

        public Map<String, Object> getDoc() {
            return doc;
        }

        public static IndexDocument of(final Map<String, Object> doc) {
            return new IndexDocument(doc);
        }
    }
}
