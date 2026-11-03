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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The document format for a {@code term_corpus} document in {@value Constants#QUERQY_INDEX_NAME}: a word-frequency
 * dictionary, addressed by a single {@code id}, that a coordinator-safe rewriter (currently only
 * {@code WordBreakCompoundRewriterFactory}, when configured with a {@code dictionaryId} instead of a shard-side
 * {@code dictionaryField}) can use without any index/shard access. See
 * {@code docs/adr/0005-mutable-checksum-verified-dictionaries.md}.
 *
 * <p>Document shape:
 * <pre>
 * {
 *   "type": "term_corpus",
 *   "version": 1,
 *   "name": "dutch-compounds",
 *   "saved_at": "2026-11-02T12:00:00Z",
 *   "corpus": "{\"format\":\"tsv_df\",\"gzip\":true,\"numDocs\":409000,\"checksum\":\"sha256:...\",\"data\":\"&lt;base64&gt;\"}"
 * }
 * </pre>
 * {@code corpus} is a single JSON-string-valued field (like {@code config_v_005} on a rewriter document), so a
 * write-time field mapper ({@code QuerqyTermCorpusFieldMapper}) can see {@code format}/{@code gzip}/
 * {@code numDocs}/{@code hashFunctions}/{@code checksum}/{@code data} together, actually build the corpus to
 * validate it, and verify {@code checksum} matches the content. Unlike {@code config_v_005}, whose version suffix
 * exists because of real migration history (it started as plain {@code config} and only got a version baked into
 * the field name once a later, incompatible format needed to coexist with documents already written under the old
 * one - an OpenSearch mapping can't change a field's type in place), this is a brand-new document type with no such
 * history yet: the field is named plainly ({@code corpus}), and the top-level {@code version} integer tracks the
 * <em>document schema generation</em> instead - it is always {@link #CURRENT_MAPPING_VERSION} today and has nothing
 * to do with a dictionary's own content: it never changes just because a dictionary is updated, only if the
 * {@code corpus} field's own JSON shape ever needs an incompatible change (which would mint a new field, e.g.
 * {@code corpus_v2}, and bump {@code version}, the same way the rewriter document's own history did). Content
 * identity/change detection is {@code checksum}'s job (see below), not {@code version}'s.
 *
 * <p>The document is intentionally mutable: updating a dictionary's content in place (same id, new content, new
 * checksum) is the supported way to change it - see {@code DictionaryCorpusLoader}, which re-verifies the checksum
 * on every load rather than assuming the content never changes. If an old version needs to stay addressable, save
 * it under a different id instead.
 */
public final class TermCorpusConfigMapping {

    public static final int CURRENT_MAPPING_VERSION = 1;

    public static final String TYPE_VALUE = "term_corpus";

    public static final String PROP_TYPE = "type";
    public static final String PROP_VERSION = "version";
    public static final String PROP_NAME = "name";
    public static final String PROP_SAVED_AT = "saved_at";
    public static final String PROP_CORPUS = "corpus";

    public static final String FIELD_FORMAT = "format";
    public static final String FIELD_GZIP = "gzip";
    public static final String FIELD_NUM_DOCS = "numDocs";
    public static final String FIELD_HASH_FUNCTIONS = "hashFunctions";
    public static final String FIELD_CHECKSUM = "checksum";
    public static final String FIELD_DATA = "data";

    /**
     * {@code data} is a TSV file with lines of the form {@code term<TAB>docFreq} (see {@code TsvDfTermCorpus}).
     * No co-occurrence information is available, so {@code TermCorpus#isCollationSupported()} is {@code false} -
     * only usable when the {@code word_break_compound} rewriter is configured with {@code verifyCollation: false}
     * (the default). {@link #FIELD_HASH_FUNCTIONS} is not used with this format.
     */
    public static final String FORMAT_TSV_DF = "tsv_df";

    /**
     * {@code data} is a TSV file with lines of the form {@code term<TAB>docFreq<TAB>bloomHex} (see {@code
     * TsvDfCoocTermCorpus}), where {@code bloomHex} is a lowercase hex string encoding a Bloom filter representing
     * that term's co-occurrence with other terms. Requires {@link #FIELD_HASH_FUNCTIONS} (the number of hash
     * functions the Bloom filter was built with, needed to interpret it) - the only format that supports
     * co-occurrence/collation, i.e. the only one usable when {@code verifyCollation: true} is configured.
     */
    public static final String FORMAT_TSV_DF_COOC = "tsv_df_cooc";

    private static final String CHECKSUM_ALGORITHM = "SHA-256";
    private static final String CHECKSUM_PREFIX = "sha256:";

    private TermCorpusConfigMapping() {
    }

    /**
     * The {@code .opensearch-querqy} document id for a dictionary id - currently just the id itself. Kept as a
     * named indirection point (rather than using the id directly at every call site) in case that ever changes.
     */
    public static String docId(final String id) {
        return id;
    }

    /**
     * SHA-256 over the raw, uncompressed TSV bytes, formatted {@code "sha256:<lowercase-hex>"}. Called identically
     * by whoever writes a dictionary (before any gzip compression, so compression settings never affect it) and by
     * {@code QuerqyTermCorpusFieldMapper} (to verify the written checksum matches the actual content) - the one
     * shared source of truth for both, so the two can never disagree on algorithm or encoding.
     */
    public static String computeChecksum(final byte[] rawData) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(CHECKSUM_ALGORITHM + " not available", e);
        }
        final byte[] hash = digest.digest(rawData);
        final StringBuilder hex = new StringBuilder(CHECKSUM_PREFIX.length() + hash.length * 2);
        hex.append(CHECKSUM_PREFIX);
        for (final byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * @param id The dictionary id (uninterpreted)
     * @param format {@link #FORMAT_TSV_DF} or {@link #FORMAT_TSV_DF_COOC}
     * @param gzip Whether to gzip-compress {@code rawData} before storing it
     * @param numDocs Optional, as in {@code TsvDfTermCorpus}/{@code TsvDfCoocTermCorpus}
     * @param hashFunctions Required iff {@code format} is {@link #FORMAT_TSV_DF_COOC}
     * @param rawData The raw, uncompressed TSV bytes - gzip compression (if requested) and the checksum are both
     *                derived from this exact buffer, so they can never end up describing different content
     * @param savedAtMillis The point in time at which the document is saved, in epoch millis, or null
     * @return The document to save in {@value Constants#QUERQY_INDEX_NAME}
     */
    public static Map<String, Object> toLuceneSource(final String id, final String format, final boolean gzip,
                                                      final Integer numDocs, final Integer hashFunctions,
                                                      final byte[] rawData, final Long savedAtMillis)
            throws IOException {

        final byte[] stored = gzip ? gzip(rawData) : rawData;

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(FIELD_FORMAT, format);
        corpus.put(FIELD_GZIP, gzip);
        if (numDocs != null) {
            corpus.put(FIELD_NUM_DOCS, numDocs);
        }
        if (hashFunctions != null) {
            corpus.put(FIELD_HASH_FUNCTIONS, hashFunctions);
        }
        corpus.put(FIELD_CHECKSUM, computeChecksum(rawData));
        corpus.put(FIELD_DATA, Base64.getEncoder().encodeToString(stored));

        final Map<String, Object> source = new HashMap<>();
        source.put(PROP_TYPE, TYPE_VALUE);
        source.put(PROP_VERSION, CURRENT_MAPPING_VERSION);
        source.put(PROP_NAME, id);
        if (savedAtMillis != null) {
            source.put(PROP_SAVED_AT, Instant.ofEpochMilli(savedAtMillis).toString());
        }
        // Reuses RewriterConfigMapping's canonical-JSON renderer (package-private, same package) so both documents
        // serialize nested JSON the same deterministic way.
        source.put(PROP_CORPUS, RewriterConfigMapping.toCanonicalJsonString(corpus));

        return source;
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(bos)) {
            gzipOut.write(data);
        }
        return bos.toByteArray();
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

    public static String getChecksum(final Map<String, Object> corpus) {
        return (String) corpus.get(FIELD_CHECKSUM);
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
     * @throws IOException if {@link #FIELD_CHECKSUM} is missing, or doesn't match {@link #computeChecksum(byte[])}
     * of {@code rawData} - i.e. the writer's claimed checksum doesn't match the content it was supposedly computed
     * from.
     */
    public static void verifyChecksum(final Map<String, Object> corpus, final byte[] rawData) throws IOException {
        final String claimed = getChecksum(corpus);
        if (claimed == null) {
            throw new IOException("Missing '" + FIELD_CHECKSUM + "'");
        }
        final String actual = computeChecksum(rawData);
        if (!actual.equals(claimed)) {
            throw new IOException("'" + FIELD_CHECKSUM + "' does not match the content: expected " + actual
                    + ", got " + claimed);
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
        return buildTermCorpus(corpus, readData(corpus));
    }

    /**
     * Like {@link #buildTermCorpus(Map)}, but takes already-decoded raw bytes - avoids decoding {@link #FIELD_DATA}
     * twice when the caller ({@code QuerqyTermCorpusFieldMapper}) already has them in hand from {@link
     * #verifyChecksum}.
     */
    public static TermCorpus buildTermCorpus(final Map<String, Object> corpus, final byte[] rawData)
            throws IOException {

        final String format = getFormat(corpus);
        if (format == null) {
            throw new IOException("Missing '" + FIELD_FORMAT + "'");
        }

        final Integer numDocs = getNumDocs(corpus);

        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(rawData), StandardCharsets.UTF_8)) {

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
