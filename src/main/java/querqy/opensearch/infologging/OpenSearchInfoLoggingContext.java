package querqy.opensearch.infologging;

import querqy.infologging.InfoLogging;
import querqy.infologging.InfoLoggingContext;
import querqy.rewrite.SearchEngineRequestAdapter;

public class OpenSearchInfoLoggingContext extends InfoLoggingContext {


    public OpenSearchInfoLoggingContext(final InfoLogging infoLogging,
                                        final SearchEngineRequestAdapter searchEngineRequestAdapter) {
        super(infoLogging, searchEngineRequestAdapter);
    }

    @Override
    public void log(final Object message) {
        if (isEnabledForRewriter()) {
            super.log(message);
        }
    }


}
