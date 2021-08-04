package querqy.opensearch.rewriterstore;

import org.opensearch.action.ActionType;

public class NodesReloadRewriterAction extends ActionType<NodesReloadRewriterResponse> {

    public static final String NAME = "cluster:admin/querqy/rewriter/_reload";
    public static final NodesReloadRewriterAction INSTANCE = new NodesReloadRewriterAction(NAME);


    protected NodesReloadRewriterAction(final String name) {
        super(name, NodesReloadRewriterResponse::new);
    }

}
