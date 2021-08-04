package querqy.opensearch.rewriterstore;

import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static querqy.opensearch.rewriterstore.RestPutRewriterAction.PARAM_REWRITER_ID;

import org.opensearch.client.node.NodeClient;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.test.rest.FakeRestRequest;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.mockito.runners.MockitoJUnitRunner;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class RestPutRewriterActionTest {

    @Test(expected = IllegalArgumentException.class)
    public void testThatNullRewriterIdIsRejected() {

        final NodeClient client = mock(NodeClient.class);
        final FakeRestRequest restRequest = new FakeRestRequest.Builder(null)
                .withParams(Collections.emptyMap()).build();

        new RestPutRewriterAction().prepareRequest(restRequest, client);

    }

    @Test(expected = IllegalArgumentException.class)
    public void testThatEmptyRewriterIdIsRejected() {

        final NodeClient client = mock(NodeClient.class);
        final Map<String, String> params = new HashMap<>();
        params.put(PARAM_REWRITER_ID, " ");
        final FakeRestRequest restRequest = new FakeRestRequest.Builder(null)
                .withParams(params).build();

        new RestPutRewriterAction().prepareRequest(restRequest, client);

    }

    @SuppressWarnings("unchecked")
    @Test
    public void testThatRequestIsParsed() throws Exception{
        final NodeClient client = mock(NodeClient.class);

        final Map<String, String> params = new HashMap<>();
        params.put(PARAM_REWRITER_ID, "rewriter1");

        final ByteBuffer buffer = ByteBuffer.wrap("{\"config\": {\"name\":42}}".getBytes());
        final FakeRestRequest restRequest = new FakeRestRequest.Builder(null)
                .withParams(params)
                .withContent(BytesReference.fromByteBuffers(new ByteBuffer[] {buffer}), XContentType.JSON)
                .build();
        final RestPutRewriterAction.PutRewriterRequestBuilder requestBuilder = new RestPutRewriterAction()
                .createRequestBuilder(restRequest, client);

        final PutRewriterRequest putRewriterRequest = requestBuilder.request();
        assertNotNull(putRewriterRequest);

        assertEquals("rewriter1", putRewriterRequest.getRewriterId());

        final Map<String, Object> content = putRewriterRequest.getContent();
        assertNotNull(content);
        assertThat((Map<String, Object>) content.get("config"), hasEntry("name", 42));

    }

}