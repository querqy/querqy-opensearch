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

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

import org.junit.After;
import org.opensearch.ResourceNotFoundException;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory;
import querqy.opensearch.rewriterstore.GetRewriterAction;
import querqy.opensearch.rewriterstore.GetRewriterRequest;
import querqy.opensearch.rewriterstore.GetRewriterResponse;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;
import querqy.opensearch.rewriterstore.RewriterInfo;
import querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class RewriterConfigReadIntegrationTest extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    @After
    public void deleteRewriterIndex() {
        try {
            client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
        } catch (final IndexNotFoundException e) {
            // Ignore
        }
    }

    public void testThatRewriterIsReturnedWithConfigRevisionAndHash() throws Exception {

        putRewriter("common_rules", "k =>\n  SYNONYM: c", "release-2026-07-29");

        final GetRewriterResponse response = getRewriter("common_rules");

        assertNull(response.getRewriters());

        final RewriterInfo rewriter = response.getRewriter();
        assertNotNull(rewriter);
        assertEquals("common_rules", rewriter.getRewriterId());
        assertEquals(SimpleCommonRulesRewriterFactory.class.getName(), rewriter.getRewriterClassName());
        assertEquals("release-2026-07-29", rewriter.getRevision());
        assertTrue("Not a SHA-256 hex string: " + rewriter.getConfigHash(),
                rewriter.getConfigHash().matches("[0-9a-f]{64}"));

        final Map<String, Object> config = rewriter.getConfig();
        assertNotNull(config);
        assertEquals("k =>\n  SYNONYM: c", config.get("rules"));
        assertEquals(Boolean.TRUE, config.get("ignoreCase"));
    }

    public void testThatRevisionIsEmptyIfItWasNotSupplied() throws Exception {

        putRewriter("common_rules", "k =>\n  SYNONYM: c", null);

        final RewriterInfo rewriter = getRewriter("common_rules").getRewriter();

        assertNull(rewriter.getRevision());
        assertNotNull(rewriter.getConfigHash());
    }

    public void testThatConfigHashOnlyDependsOnTheConfiguration() throws Exception {

        putRewriter("rewriter1", "k =>\n  SYNONYM: c", "revision-1");
        putRewriter("rewriter2", "k =>\n  SYNONYM: c", "revision-2");
        putRewriter("rewriter3", "k =>\n  SYNONYM: d", null);

        final String hash1 = getRewriter("rewriter1").getRewriter().getConfigHash();
        final String hash2 = getRewriter("rewriter2").getRewriter().getConfigHash();
        final String hash3 = getRewriter("rewriter3").getRewriter().getConfigHash();

        // same configuration, different revision property
        assertEquals(hash1, hash2);
        // different rules
        assertNotEquals(hash1, hash3);
    }

    public void testThatConfigHashCoversConfigPropertiesBesidesTheRules() throws Exception {

        final String rules = "k =>\n  SYNONYM: c";

        putRewriter("rewriter1", rules, true, "the-same-revision");
        putRewriter("rewriter2", rules, false, "the-same-revision");

        assertNotEquals(getRewriter("rewriter1").getRewriter().getConfigHash(),
                getRewriter("rewriter2").getRewriter().getConfigHash());
    }

    public void testThatConfigHashDoesNotChangeWhenTheSameConfigIsSavedAgain() throws Exception {

        putRewriter("common_rules", "k =>\n  SYNONYM: c", null);
        final String hash = getRewriter("common_rules").getRewriter().getConfigHash();

        putRewriter("common_rules", "k =>\n  SYNONYM: c", "a-new-revision");

        assertEquals(hash, getRewriter("common_rules").getRewriter().getConfigHash());
    }

    public void testThatRewritersAreListedWithoutTheirConfig() throws Exception {

        putRewriter("rewriter_b", "k =>\n  SYNONYM: c", "revision-b");
        putRewriter("rewriter_a", "k =>\n  SYNONYM: d", null);

        final GetRewriterResponse response = client()
                .execute(GetRewriterAction.INSTANCE, new GetRewriterRequest()).get();

        assertNull(response.getRewriter());

        final List<RewriterInfo> rewriters = response.getRewriters();
        assertEquals(2, rewriters.size());

        // rewriters are listed in the order of their IDs
        final RewriterInfo rewriterA = rewriters.get(0);
        assertEquals("rewriter_a", rewriterA.getRewriterId());
        assertNull(rewriterA.getRevision());
        assertNull(rewriterA.getConfig());
        assertNotNull(rewriterA.getConfigHash());
        assertEquals(SimpleCommonRulesRewriterFactory.class.getName(), rewriterA.getRewriterClassName());

        final RewriterInfo rewriterB = rewriters.get(1);
        assertEquals("rewriter_b", rewriterB.getRewriterId());
        assertEquals("revision-b", rewriterB.getRevision());
        assertNull(rewriterB.getConfig());

        // the hash of the listed rewriter must be the same as the one we get for the single rewriter
        assertEquals(getRewriter("rewriter_b").getRewriter().getConfigHash(), rewriterB.getConfigHash());
    }

    public void testThatUnknownRewriterIsNotFound() throws Exception {

        putRewriter("common_rules", "k =>\n  SYNONYM: c", null);

        final ExecutionException executionException = expectThrows(ExecutionException.class,
                () -> getRewriter("does_not_exist"));

        assertTrue(executionException.getCause() instanceof ResourceNotFoundException);
        assertEquals(RestStatus.NOT_FOUND, ((ResourceNotFoundException) executionException.getCause()).status());
    }

    public void testThatUnknownRewriterIsNotFoundIfRewriterIndexDoesNotExist() {

        final ExecutionException executionException = expectThrows(ExecutionException.class,
                () -> getRewriter("does_not_exist"));

        assertTrue(executionException.getCause() instanceof ResourceNotFoundException);
    }

    public void testThatNoRewritersAreListedIfRewriterIndexDoesNotExist() throws Exception {

        final GetRewriterResponse response = client()
                .execute(GetRewriterAction.INSTANCE, new GetRewriterRequest()).get();

        assertNull(response.getRewriter());
        assertTrue(response.getRewriters().isEmpty());
    }

    private GetRewriterResponse getRewriter(final String rewriterId) throws Exception {
        return client().execute(GetRewriterAction.INSTANCE, new GetRewriterRequest(rewriterId)).get();
    }

    private void putRewriter(final String rewriterId, final String rules, final String revision) throws Exception {
        putRewriter(rewriterId, rules, true, revision);
    }

    private void putRewriter(final String rewriterId, final String rules, final boolean ignoreCase,
                             final String revision) throws Exception {

        final Map<String, Object> config = new HashMap<>();
        config.put("rules", rules);
        config.put("ignoreCase", ignoreCase);
        config.put("querqyParser", WhiteSpaceQuerqyParserFactory.class.getName());

        final Map<String, Object> content = new HashMap<>();
        content.put("class", SimpleCommonRulesRewriterFactory.class.getName());
        content.put("config", config);

        if (revision != null) {
            content.put("revision", revision);
        }

        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest(rewriterId, content)).get();
    }

}