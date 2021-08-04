package querqy.opensearch.infologging;

import querqy.opensearch.query.InfoLoggingSpec;

import java.util.Optional;

public interface InfoLoggingSpecProvider {

    Optional<InfoLoggingSpec> getInfoLoggingSpec();
}
