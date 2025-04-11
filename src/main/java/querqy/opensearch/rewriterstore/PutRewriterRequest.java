/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package querqy.opensearch.rewriterstore;

import org.opensearch.SpecialPermission;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.ValidateActions;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import querqy.opensearch.OpenSearchRewriterFactory;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PutRewriterRequest extends ActionRequest {

    private final Map<String, Object> content;
    private final String rewriterId;

    public PutRewriterRequest(final StreamInput in) throws IOException {
        super(in);
        rewriterId = in.readString();
        content = in.readMap();
    }

    public PutRewriterRequest(final String rewriterId, final Map<String, Object> content) {
        super();
        this.rewriterId = rewriterId;
        this.content = content;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ActionRequestValidationException validate() {

        final OpenSearchRewriterFactory esRewriterFactory;
        try {
            esRewriterFactory = OpenSearchRewriterFactory.loadInstance(rewriterId, content, "class");
        } catch (final Exception e) {
            return ValidateActions.addValidationError("Invalid definition of rewriter 'class': " + e.getMessage(),
                    null);
        }

        final Map<String, Object> loggingConfig = (Map<String, Object>) content.get("info_logging");
        if (loggingConfig != null) {
            final Object sinksObj = loggingConfig.get("sinks");
            if (sinksObj != null) {
                if (sinksObj instanceof String) {
                    if (!sinksObj.equals("log4j")) {
                        final ActionRequestValidationException arve = new ActionRequestValidationException();
                        arve.addValidationError("Can only log to sink named 'log4j' but not to " + sinksObj);
                        return arve;
                    }
                } else if (sinksObj instanceof Collection) {
                    Collection<?> sinksCollection = (Collection<?>) sinksObj;
                    if (sinksCollection.size() > 0) {
                        if (sinksCollection.size() > 1 || !sinksCollection.iterator().next().equals("log4j")) {
                            final ActionRequestValidationException arve = new ActionRequestValidationException();
                            arve.addValidationError("Can only log to sink named 'log4j'");
                            return arve;
                        }
                    }
                }
            }
        }


        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }


        final List<String> errors =  AccessController.doPrivileged(
                (PrivilegedAction<List<String> >) () -> {

                    try {
                        final Map<String, Object> config = (Map<String, Object>) content.getOrDefault("config",
                                Collections.emptyMap());
                        return esRewriterFactory.validateConfiguration(config);

                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                });





        if (errors != null && !errors.isEmpty()) {
            final ActionRequestValidationException arve = new ActionRequestValidationException();
            arve.addValidationErrors(errors);
            return arve;
        }

        return null;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(rewriterId);
        out.writeMap(content);
    }

    public String getRewriterId() {
        return rewriterId;
    }

    public Map<String, Object> getContent() {
        return content;
    }

}
