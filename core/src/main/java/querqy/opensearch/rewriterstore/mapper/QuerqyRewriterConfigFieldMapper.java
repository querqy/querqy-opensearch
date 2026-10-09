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

package querqy.opensearch.rewriterstore.mapper;

import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.mapper.FieldMapper;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.Mapper;
import org.opensearch.index.mapper.MapperParsingException;
import org.opensearch.index.mapper.ParseContext;
import querqy.opensearch.OpenSearchRewriterFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Field type for the combined {@code class}+{@code config} rewriter document field (see
 * {@code RewriterConfigMapping.CURRENT}, mapping version 5): {@code {"class": ..., "config": {...}}}.
 *
 * <p>This mapper doesn't index or store a Lucene field of its own - the raw value is always available via
 * {@code _source}, as for any other field. Its only purpose is to validate the rewriter configuration at write time
 * (the same check {@code PutRewriterRequest.validate()} and the config CLI tool already run client-side), using
 * {@link OpenSearchRewriterFactory#loadInstance(String, Map, String)} and
 * {@link OpenSearchRewriterFactory#validateConfiguration(Map)}, and reject the write (throwing
 * {@link MapperParsingException}) if the configuration doesn't resolve to an allow-listed, valid rewriter. This is
 * what makes write-time validation possible for a raw {@code PUT <index>/_doc/<id>}, which is the only way to write
 * rewriter configs on an install without the REST rewriter API ({@code ActionPlugin}).</p>
 *
 * <p><b>Known limitation:</b> this runs on every shard copy (primary and replicas) and again during translog replay
 * on recovery. A document that was valid when written will always re-validate the same way as long as the class
 * allow-list and {@code validateConfiguration} logic haven't changed since - but if either is later tightened, a
 * previously-valid document still queued in the translog could fail to replay during recovery. Treat allow-list or
 * validation-logic changes as needing a data migration, not a casual change.</p>
 */
public final class QuerqyRewriterConfigFieldMapper extends FieldMapper {

    public static final String CONTENT_TYPE = "querqy_rewriter_config";

    private static final FieldType FIELD_TYPE = new FieldType();

    static {
        FIELD_TYPE.setIndexOptions(IndexOptions.NONE);
        FIELD_TYPE.setTokenized(false);
        FIELD_TYPE.setStored(false);
        FIELD_TYPE.freeze();
    }

    public static final class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder<?> parse(final String name, final Map<String, Object> node,
                                       final ParserContext parserContext) throws MapperParsingException {
            return new Builder(name);
        }
    }

    public static final class Builder extends FieldMapper.Builder<Builder> {

        public Builder(final String name) {
            super(name, FIELD_TYPE);
            builder = this;
        }

        @Override
        public QuerqyRewriterConfigFieldMapper build(final BuilderContext context) {
            final MappedFieldType fieldType = new KeywordFieldMapper.KeywordFieldType(
                    buildFullName(context), false, false, Collections.emptyMap());
            return new QuerqyRewriterConfigFieldMapper(name, FIELD_TYPE, fieldType,
                    multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    private QuerqyRewriterConfigFieldMapper(final String simpleName, final FieldType fieldType,
                                            final MappedFieldType mappedFieldType, final MultiFields multiFields,
                                            final CopyTo copyTo) {
        super(simpleName, fieldType, mappedFieldType, multiFields, copyTo);
    }

    @Override
    public boolean parsesArrayValue() {
        // the stored value may have been split into several array elements by
        // RewriterConfigMapping.stringToSourceValue() to stay under Lucene's keyword-field byte limit - we need to
        // see (and rejoin) the whole array ourselves rather than being called once per element.
        return true;
    }

    @Override
    protected void parseCreateField(final ParseContext context) throws IOException {
        final String combinedJson = readFieldValueAsString(context.parser());
        if (combinedJson == null) {
            return;
        }
        validate(context.sourceToParse().id(), combinedJson);
        // No Lucene field is added: this field is validated here but otherwise only ever read from _source.
    }

    private static String readFieldValueAsString(final XContentParser parser) throws IOException {

        final XContentParser.Token token = parser.currentToken();

        if (token == XContentParser.Token.VALUE_NULL) {
            return null;
        }

        if (token == XContentParser.Token.START_ARRAY) {
            final StringBuilder sb = new StringBuilder();
            XContentParser.Token t;
            while ((t = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                if (t == XContentParser.Token.VALUE_STRING) {
                    sb.append(parser.text());
                } else if (t != XContentParser.Token.VALUE_NULL) {
                    throw new MapperParsingException("Expected a string value in rewriter config array");
                }
            }
            final String joined = sb.toString().trim();
            return joined.isEmpty() ? null : joined;
        }

        final String text = parser.textOrNull();
        return (text == null || text.trim().isEmpty()) ? null : text.trim();
    }

    @SuppressWarnings("unchecked")
    private static void validate(final String rewriterId, final String combinedJson) {

        final Map<String, Object> combined;
        try {
            final XContentParser parser = XContentHelper.createParser(null, null, new BytesArray(combinedJson),
                    XContentType.JSON);
            combined = parser.map();
        } catch (final IOException e) {
            throw new MapperParsingException("Invalid rewriter config JSON: " + e.getMessage(), e);
        }

        final OpenSearchRewriterFactory factory;
        try {
            factory = OpenSearchRewriterFactory.loadInstance(rewriterId, combined, "class");
        } catch (final Exception e) {
            throw new MapperParsingException("Invalid definition of rewriter 'class': " + e.getMessage(), e);
        }

        final Map<String, Object> config = (Map<String, Object>) combined.getOrDefault("config",
                Collections.emptyMap());
        final List<String> errors;
        try {
            errors = factory.validateConfiguration(config);
        } catch (final Exception e) {
            throw new MapperParsingException("Could not validate rewriter configuration: " + e.getMessage(), e);
        }

        if (errors != null && !errors.isEmpty()) {
            throw new MapperParsingException("Invalid rewriter configuration: " + String.join("; ", errors));
        }
    }

    @Override
    protected void mergeOptions(final FieldMapper other, final List<String> conflicts) {
        // no configurable parameters
    }

    @Override
    protected boolean indexedByDefault() {
        // must match the KeywordFieldType(..., isSearchable=false, ...) built in Builder#build - otherwise
        // FieldMapper's default doXContentBody() would emit a redundant "index": false, which our bare TypeParser
        // (no Parameter machinery, since this type has no configurable options) can't consume on a later reparse of
        // that canonical mapping source (e.g. OpenSearch re-parses the merged mapping while creating an index).
        return false;
    }

    @Override
    protected boolean docValuesByDefault() {
        // see indexedByDefault() - must match the KeywordFieldType's hasDocValues=false
        return false;
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }
}
