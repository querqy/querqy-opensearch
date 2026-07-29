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

package querqy.opensearch.rewriterstore;

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * The information about a stored rewriter that is returned by {@link GetRewriterAction}. Its properties are named
 * like the properties of the payload of a {@link PutRewriterRequest} so that the output can be fed back into a PUT
 * request, for example to copy a rewriter from one cluster to another.
 */
public class RewriterInfo implements Writeable, ToXContentObject {

    public static final String FIELD_REWRITER_ID = "rewriter_id";

    private final String rewriterId;
    private final String rewriterClassName;
    private final String revision;
    private final String configHash;
    private final Map<String, Object> config;
    private final Map<String, Object> infoLoggingConfig;

    /**
     * Reads the rewriter information from a stored rewriter document.
     *
     * @param rewriterId The ID of the rewriter
     * @param source The stored rewriter document
     * @param includeConfig Include the rewriter config in the information? Configs can be very large, which is why
     *                      they are not included when rewriters are listed.
     * @return The rewriter information
     */
    public static RewriterInfo fromSource(final String rewriterId, final Map<String, Object> source,
                                          final boolean includeConfig) {

        final RewriterConfigMapping mapping = RewriterConfigMapping.getMapping(source);

        return new RewriterInfo(rewriterId,
                mapping.getRewriterClassName(rewriterId, source),
                mapping.getRevision(source),
                mapping.computeConfigHash(rewriterId, source),
                includeConfig ? mapping.getConfig(rewriterId, source) : null,
                mapping.getInfoLoggingConfig(rewriterId, source));
    }

    public RewriterInfo(final String rewriterId, final String rewriterClassName, final String revision,
                        final String configHash, final Map<String, Object> config,
                        final Map<String, Object> infoLoggingConfig) {
        this.rewriterId = rewriterId;
        this.rewriterClassName = rewriterClassName;
        this.revision = revision;
        this.configHash = configHash;
        this.config = config;
        this.infoLoggingConfig = infoLoggingConfig;
    }

    public RewriterInfo(final StreamInput in) throws IOException {
        rewriterId = in.readString();
        rewriterClassName = in.readOptionalString();
        revision = in.readOptionalString();
        configHash = in.readString();
        config = in.readBoolean() ? in.readMap() : null;
        infoLoggingConfig = in.readBoolean() ? in.readMap() : null;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeString(rewriterId);
        out.writeOptionalString(rewriterClassName);
        out.writeOptionalString(revision);
        out.writeString(configHash);
        out.writeBoolean(config != null);
        if (config != null) {
            out.writeMap(config);
        }
        out.writeBoolean(infoLoggingConfig != null);
        if (infoLoggingConfig != null) {
            out.writeMap(infoLoggingConfig);
        }
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {

        builder.startObject();

        builder.field(FIELD_REWRITER_ID, rewriterId);
        builder.field(RewriterConfigMapping.CURRENT.getRewriterClassNameProperty(), rewriterClassName);

        if (revision != null) {
            builder.field(RewriterConfigMapping.PROP_REVISION, revision);
        }

        builder.field(RewriterConfigMapping.PROP_CONFIG_HASH, configHash);

        if (infoLoggingConfig != null) {
            builder.field(RewriterConfigMapping.CURRENT.getInfoLoggingProperty(), infoLoggingConfig);
        }

        if (config != null) {
            builder.field("config", config);
        }

        builder.endObject();

        return builder;
    }

    public String getRewriterId() {
        return rewriterId;
    }

    public String getRewriterClassName() {
        return rewriterClassName;
    }

    /**
     * @return The revision that was supplied by the user when the rewriter was saved, or null if the user didn't
     * supply any revision information.
     */
    public String getRevision() {
        return revision;
    }

    public String getConfigHash() {
        return configHash;
    }

    /**
     * @return The rewriter config, or null if this information was requested without the config.
     */
    public Map<String, Object> getConfig() {
        return config;
    }

    public Map<String, Object> getInfoLoggingConfig() {
        return infoLoggingConfig;
    }

}