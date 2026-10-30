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

import org.opensearch.test.OpenSearchTestCase;
import querqy.rewriter.wordbreak.TermCorpus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class TermCorpusConfigMappingTest extends OpenSearchTestCase {

    private static byte[] gzip(final String content) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    public void testDocId() {
        assertEquals("dutch-compounds-r1", TermCorpusConfigMapping.docId("dutch-compounds", "r1"));
    }

    public void testRoundTripTsvDfGzipped() throws Exception {

        final byte[] data = gzip("haus\t10\nboot\t5\n");
        final Map<String, Object> source = TermCorpusConfigMapping.toLuceneSource("d", "r1",
                TermCorpusConfigMapping.FORMAT_TSV_DF, true, 1000, null, data, System.currentTimeMillis());

        assertEquals(TermCorpusConfigMapping.TYPE_VALUE, source.get(TermCorpusConfigMapping.PROP_TYPE));
        assertEquals("d", source.get(TermCorpusConfigMapping.PROP_NAME));
        assertEquals("r1", source.get(TermCorpusConfigMapping.PROP_REVISION));

        final Map<String, Object> corpus = TermCorpusConfigMapping.getCorpus("d-r1", source);
        assertEquals(TermCorpusConfigMapping.FORMAT_TSV_DF, TermCorpusConfigMapping.getFormat(corpus));
        assertTrue(TermCorpusConfigMapping.isGzip(corpus));
        assertEquals(Integer.valueOf(1000), TermCorpusConfigMapping.getNumDocs(corpus));

        final TermCorpus termCorpus = TermCorpusConfigMapping.buildTermCorpus(corpus);
        assertTrue(termCorpus.exists("haus"));
        assertEquals(10, termCorpus.docFreq("haus"));
        assertFalse(termCorpus.exists("unknown"));
        assertEquals(1000, termCorpus.numDocs());
        assertFalse(termCorpus.isCollationSupported());
    }

    public void testRoundTripUncompressed() throws Exception {

        final byte[] data = "haus\t10\n".getBytes(StandardCharsets.UTF_8);
        final Map<String, Object> source = TermCorpusConfigMapping.toLuceneSource("d", "r1",
                TermCorpusConfigMapping.FORMAT_TSV_DF, false, null, null, data, null);

        final Map<String, Object> corpus = TermCorpusConfigMapping.getCorpus("d-r1", source);
        assertFalse(TermCorpusConfigMapping.isGzip(corpus));

        final TermCorpus termCorpus = TermCorpusConfigMapping.buildTermCorpus(corpus);
        assertTrue(termCorpus.exists("haus"));
    }

    public void testTsvDfCoocRequiresHashFunctions() throws Exception {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(TermCorpusConfigMapping.FIELD_FORMAT, TermCorpusConfigMapping.FORMAT_TSV_DF_COOC);
        corpus.put(TermCorpusConfigMapping.FIELD_GZIP, false);
        corpus.put(TermCorpusConfigMapping.FIELD_DATA,
                java.util.Base64.getEncoder().encodeToString("a\t1\t00\n".getBytes(StandardCharsets.UTF_8)));

        final IOException e = expectThrows(IOException.class, () -> TermCorpusConfigMapping.buildTermCorpus(corpus));
        assertTrue(e.getMessage().contains("hashFunctions"));
    }

    public void testTsvDfCoocWithHashFunctionsSupportsCollation() throws Exception {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(TermCorpusConfigMapping.FIELD_FORMAT, TermCorpusConfigMapping.FORMAT_TSV_DF_COOC);
        corpus.put(TermCorpusConfigMapping.FIELD_GZIP, false);
        corpus.put(TermCorpusConfigMapping.FIELD_HASH_FUNCTIONS, 2);
        // bloom hex must be a multiple of 16 hex chars (see querqy.BloomFilter.fromHex)
        corpus.put(TermCorpusConfigMapping.FIELD_DATA, java.util.Base64.getEncoder()
                .encodeToString("a\t1\t0000000000000000\n".getBytes(StandardCharsets.UTF_8)));

        final TermCorpus termCorpus = TermCorpusConfigMapping.buildTermCorpus(corpus);
        assertTrue(termCorpus.isCollationSupported());
    }

    /**
     * {@code buildTermCorpus} validates a {@code tsv_df_cooc} dictionary by actually building a real {@code
     * TsvDfCoocTermCorpus}, which parses every line's bloom-filter hex via {@code BloomFilter.fromHex} - a hex
     * string whose length isn't a multiple of 16 is rejected there, with no separate check needed here. This is
     * what the write-time field mapper relies on to reject a bad dictionary document before it's ever saved.
     */
    public void testTsvDfCoocRejectsBloomHexWithInvalidLength() {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(TermCorpusConfigMapping.FIELD_FORMAT, TermCorpusConfigMapping.FORMAT_TSV_DF_COOC);
        corpus.put(TermCorpusConfigMapping.FIELD_GZIP, false);
        corpus.put(TermCorpusConfigMapping.FIELD_HASH_FUNCTIONS, 2);
        // "00" is 2 hex chars - not a multiple of 16, so BloomFilter.fromHex must reject it
        corpus.put(TermCorpusConfigMapping.FIELD_DATA, java.util.Base64.getEncoder()
                .encodeToString("a\t1\t00\n".getBytes(StandardCharsets.UTF_8)));

        final IOException e = expectThrows(IOException.class, () -> TermCorpusConfigMapping.buildTermCorpus(corpus));
        assertTrue(e.getMessage(), e.getMessage().contains("multiple of 16"));
    }

    public void testUnknownFormatFailsClearly() {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(TermCorpusConfigMapping.FIELD_FORMAT, "nonsense");
        corpus.put(TermCorpusConfigMapping.FIELD_GZIP, false);
        corpus.put(TermCorpusConfigMapping.FIELD_DATA, java.util.Base64.getEncoder().encodeToString(new byte[0]));

        final IOException e = expectThrows(IOException.class, () -> TermCorpusConfigMapping.buildTermCorpus(corpus));
        assertTrue(e.getMessage().contains("nonsense"));
    }

    public void testMalformedTsvFailsClearly() {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(TermCorpusConfigMapping.FIELD_FORMAT, TermCorpusConfigMapping.FORMAT_TSV_DF);
        corpus.put(TermCorpusConfigMapping.FIELD_GZIP, false);
        corpus.put(TermCorpusConfigMapping.FIELD_DATA,
                java.util.Base64.getEncoder().encodeToString("no-tab-here".getBytes(StandardCharsets.UTF_8)));

        expectThrows(IOException.class, () -> TermCorpusConfigMapping.buildTermCorpus(corpus));
    }

    public void testClaimedGzipButNotActuallyGzippedFailsClearly() {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(TermCorpusConfigMapping.FIELD_FORMAT, TermCorpusConfigMapping.FORMAT_TSV_DF);
        corpus.put(TermCorpusConfigMapping.FIELD_GZIP, true);
        corpus.put(TermCorpusConfigMapping.FIELD_DATA,
                java.util.Base64.getEncoder().encodeToString("haus\t10\n".getBytes(StandardCharsets.UTF_8)));

        expectThrows(IOException.class, () -> TermCorpusConfigMapping.buildTermCorpus(corpus));
    }

    public void testInvalidBase64FailsClearly() {

        final Map<String, Object> corpus = new HashMap<>();
        corpus.put(TermCorpusConfigMapping.FIELD_FORMAT, TermCorpusConfigMapping.FORMAT_TSV_DF);
        corpus.put(TermCorpusConfigMapping.FIELD_GZIP, false);
        corpus.put(TermCorpusConfigMapping.FIELD_DATA, "not valid base64!!");

        expectThrows(IOException.class, () -> TermCorpusConfigMapping.buildTermCorpus(corpus));
    }
}
