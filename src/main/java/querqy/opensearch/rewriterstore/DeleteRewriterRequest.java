package querqy.opensearch.rewriterstore;

import org.opensearch.OpenSearchParseException;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;

import java.io.IOException;

public class DeleteRewriterRequest extends ActionRequest {

    private final String rewriterId;

    public DeleteRewriterRequest(final StreamInput in) throws IOException {
        super(in);
        rewriterId = in.readString();
    }

    public DeleteRewriterRequest(final String rewriterId) {
        super();
        if (rewriterId == null) {
            throw new OpenSearchParseException("rewriterId must not be null");
        }
        this.rewriterId = rewriterId;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(rewriterId);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    public String getRewriterId() {
        return rewriterId;
    }


}
