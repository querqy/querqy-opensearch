package querqy.opensearch.rewriterstore;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;
import static org.opensearch.action.ActionListener.wrap;

import org.opensearch.action.ActionListener;
import org.opensearch.action.delete.DeleteRequestBuilder;
import org.opensearch.action.delete.DeleteResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

public class TransportDeleteRewriterAction  extends HandledTransportAction<DeleteRewriterRequest, DeleteRewriterResponse> {

    private final Client client;
    private final ClusterService clusterService;

    @Inject
    public TransportDeleteRewriterAction(final TransportService transportService, final ActionFilters actionFilters,
                                      final ClusterService clusterService, final Client client) {
        super(DeleteRewriterAction.NAME, false, transportService, actionFilters, DeleteRewriterRequest::new);
        this.clusterService = clusterService;
        this.client = client;
    }
    @Override
    protected void doExecute(final Task task, final DeleteRewriterRequest request,
                             final ActionListener<DeleteRewriterResponse> listener) {

        final DeleteRequestBuilder deleteRequest = client.prepareDelete(QUERQY_INDEX_NAME, null,
                request.getRewriterId());

        deleteRequest.execute(new ActionListener<DeleteResponse>() {

            @Override
            public void onResponse(final DeleteResponse deleteResponse) {

                // TODO: exit if response status code is 404 (though is shouldn't harm to clear the rewriter from cache
                // regardless)

                client.execute(NodesClearRewriterCacheAction.INSTANCE,
                        new NodesClearRewriterCacheRequest(request.getRewriterId()),
                        wrap(
                                (clearResponse) -> listener.onResponse(new DeleteRewriterResponse(deleteResponse,
                                        clearResponse)),
                                listener::onFailure
                        ));
            }

            @Override
            public void onFailure(final Exception e) {
                listener.onFailure(e);
            }
        });


    }
}
