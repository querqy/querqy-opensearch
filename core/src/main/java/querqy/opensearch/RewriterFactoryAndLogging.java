/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Querqy for OpenSearch Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package querqy.opensearch;

import querqy.rewrite.RewriterFactory;

import java.util.Collections;
import java.util.Map;

/**
 * A built {@link RewriterFactory}, whether logging is enabled for it, the stored document's sequence number/primary
 * term at load time (used by {@link querqy.opensearch.query.RefreshMode#CHECK} to tell whether the document has
 * changed since, without re-parsing the rewriter when it hasn't), and the checksum of any {@code term_corpus}
 * dictionary/dictionaries it was built from (see {@link OpenSearchRewriterFactory#getDictionaryDependencyChecksums()},
 * used the same way to also notice a dictionary-only content change). Produced and cached by {@link
 * RewriterFactoryLoader}.
 */
public class RewriterFactoryAndLogging {

    public final RewriterFactory rewriterFactory;
    public final boolean loggingEnabled;
    final long loadedAtNanos;
    final long seqNo;
    final long primaryTerm;
    final Map<String, String> dictionaryDependencyChecksums;

    public RewriterFactoryAndLogging(final RewriterFactory rewriterFactory, final boolean loggingEnabled,
                                     final long seqNo, final long primaryTerm) {
        this(rewriterFactory, loggingEnabled, seqNo, primaryTerm, Collections.emptyMap());
    }

    public RewriterFactoryAndLogging(final RewriterFactory rewriterFactory, final boolean loggingEnabled,
                                     final long seqNo, final long primaryTerm,
                                     final Map<String, String> dictionaryDependencyChecksums) {
        this.rewriterFactory = rewriterFactory;
        this.loggingEnabled = loggingEnabled;
        this.loadedAtNanos = System.nanoTime();
        this.seqNo = seqNo;
        this.primaryTerm = primaryTerm;
        this.dictionaryDependencyChecksums = dictionaryDependencyChecksums;
    }
}
