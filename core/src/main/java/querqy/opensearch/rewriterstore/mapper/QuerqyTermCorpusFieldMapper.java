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
import org.opensearch.index.mapper.FieldMapper;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.Mapper;
import org.opensearch.index.mapper.MapperParsingException;
import org.opensearch.index.mapper.ParseContext;
import org.opensearch.core.xcontent.XContentParser;
import querqy.opensearch.rewriterstore.TermCorpusConfigMapping;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Field type for the combined {@code corpus} field of a {@code term_corpus} document (see
 * {@link TermCorpusConfigMapping}): {@code {"format": ..., "gzip": ..., "numDocs": ..., "hashFunctions": ...,
 * "data": ...}}.
 *
 * <p>Like {@code QuerqyRewriterConfigFieldMapper}, this mapper doesn't index or store a Lucene field of its own -
 * the raw value is always available via {@code _source}. Its only purpose is to validate a dictionary document at
 * write time: it base64-decodes (and gunzips, if {@code gzip: true}) {@code data} and actually builds the {@code
 * TermCorpus} it describes ({@code TsvDfTermCorpus}/{@code TsvDfCoocTermCorpus}, per {@code format}), rejecting the
 * write (throwing {@link MapperParsingException}) if the content doesn't parse as declared. This is what makes
 * write-time validation possible for a raw {@code PUT <index>/_doc/<id>}, the only way to write a dictionary
 * document on an install without the REST rewriter API ({@code ActionPlugin}).</p>
 *
 * <p><b>Known limitation:</b> like {@code QuerqyRewriterConfigFieldMapper}, this runs on every shard copy (primary
 * and replicas) and again during translog replay on recovery - here that means gunzipping and fully parsing a
 * dictionary that may be several MB uncompressed, once per shard copy. Acceptable because {@code term_corpus}
 * documents are written rarely and are immutable per revision (a change is a new document, not an update), so this
 * cost is paid once per write, not on any request-serving hot path.</p>
 */
public final class QuerqyTermCorpusFieldMapper extends FieldMapper {

    public static final String CONTENT_TYPE = "querqy_term_corpus";

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
        public QuerqyTermCorpusFieldMapper build(final BuilderContext context) {
            final MappedFieldType fieldType = new KeywordFieldMapper.KeywordFieldType(
                    buildFullName(context), false, false, Collections.emptyMap());
            return new QuerqyTermCorpusFieldMapper(name, FIELD_TYPE, fieldType,
                    multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    private QuerqyTermCorpusFieldMapper(final String simpleName, final FieldType fieldType,
                                        final MappedFieldType mappedFieldType, final MultiFields multiFields,
                                        final CopyTo copyTo) {
        super(simpleName, fieldType, mappedFieldType, multiFields, copyTo);
    }

    @Override
    public boolean parsesArrayValue() {
        return false;
    }

    @Override
    protected void parseCreateField(final ParseContext context) throws IOException {
        final String corpusJson = readFieldValueAsString(context.parser());
        if (corpusJson == null) {
            return;
        }
        validate(context.sourceToParse().id(), corpusJson);
        // No Lucene field is added: this field is validated here but otherwise only ever read from _source.
    }

    private static String readFieldValueAsString(final XContentParser parser) throws IOException {
        final String text = parser.textOrNull();
        return (text == null || text.trim().isEmpty()) ? null : text.trim();
    }

    private static void validate(final String documentId, final String corpusJson) {

        final Map<String, Object> corpus;
        try {
            corpus = TermCorpusConfigMapping.parseCorpus(corpusJson);
        } catch (final Exception e) {
            throw new MapperParsingException("Invalid term corpus JSON in document '" + documentId + "': "
                    + e.getMessage(), e);
        }

        try {
            TermCorpusConfigMapping.buildTermCorpus(corpus);
        } catch (final Exception e) {
            throw new MapperParsingException("Invalid term corpus content in document '" + documentId + "': "
                    + e.getMessage(), e);
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
