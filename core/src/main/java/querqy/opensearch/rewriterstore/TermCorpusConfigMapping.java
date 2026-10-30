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

import org.opensearch.OpenSearchException;
import org.opensearch.core.common.ParsingException;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.XContentParser;
import querqy.rewriter.wordbreak.TermCorpus;
import querqy.rewriter.wordbreak.TsvDfCoocTermCorpus;
import querqy.rewriter.wordbreak.TsvDfTermCorpus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The document format for a {@code term_corpus} document in {@value Constants#QUERQY_INDEX_NAME}: a named,
 * revisioned, immutable word-frequency dictionary that a coordinator-safe rewriter (currently only
 * {@code WordBreakCompoundRewriterFactory}, when configured with a {@code dictionary} reference instead of a
 * shard-side {@code dictionaryField}) can use without any index/shard access. See
 * {@code docs/adr/0003-coordinator-side-word-break-dictionaries.md}.
 *
 * <p>Document shape:
 * <pre>
 * {
 *   "type": "term_corpus",
 *   "version": 1,
 *   "name": "dutch-compounds",
 *   "revision": "2026-01",
 *   "saved_at": "2026-10-30T12:00:00Z",
 *   "corpus": "{\"format\":\"tsv_df\",\"gzip\":true,\"numDocs\":409000,\"data\":\"&lt;base64&gt;\"}"
 * }
 * </pre>
 * {@code corpus} is a single JSON-string-valued field (like {@code config_v_005} on a rewriter document), so a
 * write-time field mapper ({@code QuerqyTermCorpusFieldMapper}) can see {@code format}/{@code gzip}/
 * {@code numDocs}/{@code hashFunctions}/{@code data} together and actually build the corpus to validate it. Unlike
 * {@code config_v_005}, whose version suffix exists because of real migration history (it started as plain
 * {@code config} and only got a version baked into the field name once a later, incompatible format needed to
 * coexist with documents already written under the old one - an OpenSearch mapping can't change a field's type in
 * place), this is a brand-new document type with no such history yet: the field is named plainly ({@code corpus}),
 * and the top-level {@code version} integer tracks the document schema generation instead. If an incompatible
 * change to the field's shape is ever needed, a new field ({@code corpus_v2}) should be minted and {@code version}
 * bumped, the same way the rewriter document's own history did.
 */
public final class TermCorpusConfigMapping {

    public static final int CURRENT_MAPPING_VERSION = 1;

    public static final String TYPE_VALUE = "term_corpus";

    public static final String PROP_TYPE = "type";
    public static final String PROP_VERSION = "version";
    public static final String PROP_NAME = "name";
    public static final String PROP_REVISION = "revision";
    public static final String PROP_SAVED_AT = "saved_at";
    public static final String PROP_CORPUS = "corpus";

    public static final String FIELD_FORMAT = "format";
    public static final String FIELD_GZIP = "gzip";
    public static final String FIELD_NUM_DOCS = "numDocs";
    public static final String FIELD_HASH_FUNCTIONS = "hashFunctions";
    public static final String FIELD_DATA = "data";

    public static final String FORMAT_TSV_DF = "tsv_df";
    public static final String FORMAT_TSV_DF_COOC = "tsv_df_cooc";

    private TermCorpusConfigMapping() {
    }

    /** The {@code .opensearch-querqy} document id for a given dictionary name and revision. */
    public static String docId(final String name, final String revision) {
        return name + "-" + revision;
    }

    /**
     * @param name The dictionary name (uninterpreted)
     * @param revision The dictionary revision (uninterpreted)
     * @param format {@link #FORMAT_TSV_DF} or {@link #FORMAT_TSV_DF_COOC}
     * @param gzip Whether {@code data} is gzip-compressed
     * @param numDocs Optional, as in {@code TsvDfTermCorpus}/{@code TsvDfCoocTermCorpus}
     * @param hashFunctions Required iff {@code format} is {@link #FORMAT_TSV_DF_COOC}
     * @param data The raw TSV bytes, gzip-compressed iff {@code gzip}
     * @param savedAtMillis The point in time at which the document is saved, in epoch millis, or null
     * @return The document to save in {@value Constants#QUERQY_INDEX_NAME}
     */
    public static Map<String, Object> toLuceneSource(final String name, final String revision, final String format,
                                                      final boolean gzip, final Integer numDocs,
                                                      final Integer hashFunctions, final byte[] data,
                                                      final Long savedAtMillis) throws IOException {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(FIELD_FORMAT, format);
        corpus.put(FIELD_GZIP, gzip);
        if (numDocs != null) {
            corpus.put(FIELD_NUM_DOCS, numDocs);
        }
        if (hashFunctions != null) {
            corpus.put(FIELD_HASH_FUNCTIONS, hashFunctions);
        }
        corpus.put(FIELD_DATA, Base64.getEncoder().encodeToString(data));

        final Map<String, Object> source = new HashMap<>();
        source.put(PROP_TYPE, TYPE_VALUE);
        source.put(PROP_VERSION, CURRENT_MAPPING_VERSION);
        source.put(PROP_NAME, name);
        source.put(PROP_REVISION, revision);
        if (savedAtMillis != null) {
            source.put(PROP_SAVED_AT, Instant.ofEpochMilli(savedAtMillis).toString());
        }
        // Reuses RewriterConfigMapping's canonical-JSON renderer (package-private, same package) so both documents
        // serialize nested JSON the same deterministic way.
        source.put(PROP_CORPUS, RewriterConfigMapping.toCanonicalJsonString(corpus));

        return source;
    }

    /**
     * @param source The stored {@code term_corpus} document (or the combined value parsed from the field mapper's
     *               own input while a document is being written - same shape either way)
     * @return The parsed {@code {"format": ..., "gzip": ..., ...}} map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseCorpus(final String corpusJson) {
        final XContentParser parser;
        try {
            parser = XContentType.JSON.xContent().createParser(null, null,
                    corpusJson.getBytes(StandardCharsets.UTF_8));
            return parser.map();
        } catch (final IOException e) {
            throw new OpenSearchException(e);
        }
    }

    public static String getFormat(final Map<String, Object> corpus) {
        return (String) corpus.get(FIELD_FORMAT);
    }

    public static boolean isGzip(final Map<String, Object> corpus) {
        return Boolean.TRUE.equals(corpus.get(FIELD_GZIP));
    }

    public static Integer getNumDocs(final Map<String, Object> corpus) {
        final Object numDocs = corpus.get(FIELD_NUM_DOCS);
        return numDocs == null ? null : ((Number) numDocs).intValue();
    }

    public static Integer getHashFunctions(final Map<String, Object> corpus) {
        final Object hashFunctions = corpus.get(FIELD_HASH_FUNCTIONS);
        return hashFunctions == null ? null : ((Number) hashFunctions).intValue();
    }

    /**
     * @return The raw TSV bytes: base64-decoded, then gunzipped iff {@link #isGzip(Map)}.
     */
    public static byte[] readData(final Map<String, Object> corpus) throws IOException {

        final String base64 = (String) corpus.get(FIELD_DATA);
        if (base64 == null) {
            throw new IOException("Missing '" + FIELD_DATA + "'");
        }

        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (final IllegalArgumentException e) {
            throw new IOException("'" + FIELD_DATA + "' is not valid base64: " + e.getMessage(), e);
        }

        if (!isGzip(corpus)) {
            return decoded;
        }

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
            gzipIn.transferTo(bos);
            return bos.toByteArray();
        }
    }

    /**
     * Builds the {@link TermCorpus} described by a parsed {@code corpus} map, dispatching on {@link
     * #FIELD_FORMAT}. Used both to actually load a dictionary ({@code DictionaryCorpusLoader}) and to validate one
     * at write time ({@code QuerqyTermCorpusFieldMapper}) - the latter discards the result, it only cares whether
     * this throws.
     *
     * @throws IOException if {@code data} isn't valid base64/gzip (when claimed), the TSV is malformed, {@code
     * format} is unknown, or {@code format} is {@link #FORMAT_TSV_DF_COOC} with no {@link #FIELD_HASH_FUNCTIONS}
     */
    public static TermCorpus buildTermCorpus(final Map<String, Object> corpus) throws IOException {

        final String format = getFormat(corpus);
        if (format == null) {
            throw new IOException("Missing '" + FIELD_FORMAT + "'");
        }

        final byte[] data = readData(corpus);
        final Integer numDocs = getNumDocs(corpus);

        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {

            switch (format) {

                case FORMAT_TSV_DF: {
                    final TsvDfTermCorpus.Builder builder = TsvDfTermCorpus.builder().reader(reader);
                    if (numDocs != null) {
                        builder.numDocs(numDocs);
                    }
                    return builder.build();
                }

                case FORMAT_TSV_DF_COOC: {
                    final Integer hashFunctions = getHashFunctions(corpus);
                    if (hashFunctions == null) {
                        throw new IOException("Missing '" + FIELD_HASH_FUNCTIONS + "', required for format '"
                                + FORMAT_TSV_DF_COOC + "'");
                    }
                    final TsvDfCoocTermCorpus.Builder builder = TsvDfCoocTermCorpus.builder().reader(reader)
                            .hashFunctions(hashFunctions);
                    if (numDocs != null) {
                        builder.numDocs(numDocs);
                    }
                    return builder.build();
                }

                default:
                    throw new IOException("Unknown '" + FIELD_FORMAT + "': " + format);
            }

        } catch (final IllegalStateException | IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public static String getRevision(final Map<String, Object> source) {
        final Object revision = source.get(PROP_REVISION);
        return revision == null ? null : revision.toString();
    }

    public static String getName(final Map<String, Object> source) {
        final Object name = source.get(PROP_NAME);
        return name == null ? null : name.toString();
    }

    /**
     * @throws ParsingException if {@link #PROP_CORPUS} is present but isn't valid JSON
     */
    public static Map<String, Object> getCorpus(final String rewriterOrDictionaryId, final Map<String, Object> source) {
        final Object corpus = source.get(PROP_CORPUS);
        if (!(corpus instanceof String)) {
            throw new IllegalArgumentException("Missing '" + PROP_CORPUS + "' in document: " + rewriterOrDictionaryId);
        }
        return parseCorpus((String) corpus);
    }
}
