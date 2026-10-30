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

package querqy.opensearch.pipeline;

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import querqy.model.BooleanParent;
import querqy.model.BooleanQuery;
import querqy.model.BoostQuery;
import querqy.model.Clause;
import querqy.model.Clause.Occur;
import querqy.model.DisjunctionMaxClause;
import querqy.model.DisjunctionMaxQuery;
import querqy.model.ExpandedQuery;
import querqy.model.MatchAllQuery;
import querqy.model.Node;
import querqy.model.ParametrizedRawQuery;
import querqy.model.ParametrizedRawQuery.Part;
import querqy.model.ParsedRawQuery;
import querqy.model.PhraseQuery;
import querqy.model.BoostedPhraseQuery;
import querqy.model.QuerqyQuery;
import querqy.model.Query;
import querqy.model.StringRawQuery;
import querqy.model.Term;
import querqy.model.BoostedTerm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a rewritten {@code querqy.model.ExpandedQuery} - the output of running
 * {@code QueryParsingController.createExpandedQuery()} + a {@code RewriteChain}'s {@code rewrite(...)} - across the
 * wire, so the coordinator-side {@code querqy_rewrite} search pipeline processor can run the rewrite once and ship
 * the result to shards, which rebuild Lucene queries from it via the existing {@code LuceneQueryBuilder} stage
 * ({@code QueryParsingController.transformUserQuery}/{@code transformFilterQueries}/{@code
 * getAdditiveQuerqyBoostQueries}) without re-parsing or re-rewriting.
 * <p>
 * Covers every concrete {@code querqy.model} node type reachable from a rewrite's output, per {@link
 * querqy.model.NodeVisitor}'s 7 {@code visit(X)} overloads plus the subtypes that dispatch through a supertype
 * overload rather than getting their own ({@link BoostedTerm} extends {@link Term}; {@link BoostedPhraseQuery}
 * extends {@link PhraseQuery}; {@link RawQuery}'s three concrete subtypes dispatch via {@code visit(RawQuery)}):
 * {@link Query}, {@link BooleanQuery}, {@link DisjunctionMaxQuery}, {@link Term}, {@link BoostedTerm}, {@link
 * MatchAllQuery}, {@link PhraseQuery}, {@link BoostedPhraseQuery}, {@link StringRawQuery}, {@link
 * ParametrizedRawQuery}. {@link ParsedRawQuery} is explicitly unsupported (an opaque, unbounded generic payload with
 * no concrete subclass anywhere in querqy-core - an extension point for third parties, not something any built-in
 * rewriter produces) - rather than guess at a serialization strategy for it, this throws a clear exception.
 * <p>
 * Carries an explicit format version so a rolling upgrade or mixed-version cluster fails clearly (a coordinator
 * running a newer version than a shard) instead of silently misbehaving.
 */
public final class ExpandedQueryWriteable {

    /**
     * Bump this - and add an explicit migration/compatibility note here - whenever the wire shape below changes.
     */
    public static final int CURRENT_VERSION = 1;

    private static final byte TAG_QUERY = 1;
    private static final byte TAG_BOOLEAN_QUERY = 2;
    private static final byte TAG_DMQ = 3;
    private static final byte TAG_TERM = 4;
    private static final byte TAG_BOOSTED_TERM = 5;
    private static final byte TAG_MATCH_ALL = 6;
    private static final byte TAG_PHRASE_QUERY = 7;
    private static final byte TAG_BOOSTED_PHRASE_QUERY = 8;
    private static final byte TAG_STRING_RAW_QUERY = 9;
    private static final byte TAG_PARAMETRIZED_RAW_QUERY = 10;

    private ExpandedQueryWriteable() {
    }

    public static void writeExpandedQuery(final StreamOutput out, final ExpandedQuery expandedQuery)
            throws IOException {
        out.writeVInt(CURRENT_VERSION);
        writeNode(out, expandedQuery.getUserQuery());
        writeQuerqyQueryCollection(out, expandedQuery.getFilterQueries());
        writeBoostQueries(out, expandedQuery.getBoostUpQueries());
        writeBoostQueries(out, expandedQuery.getBoostDownQueries());
        writeBoostQueries(out, expandedQuery.getMultiplicativeBoostQueries());
    }

    /**
     * Writes a bare {@link QuerqyQuery} - not a full {@link ExpandedQuery} - with the same versioning and node-type
     * coverage as {@link #writeExpandedQuery}. Used for the *unrewritten* user query that must travel alongside a
     * rewritten {@link ExpandedQuery}, since {@code QueryParsingController.buildFromRewrittenQuery} needs both (see
     * its javadoc for why request-level boosts need the original, unrewritten query).
     */
    public static void writeQuerqyQuery(final StreamOutput out, final QuerqyQuery<?> query) throws IOException {
        out.writeVInt(CURRENT_VERSION);
        writeNode(out, query);
    }

    public static QuerqyQuery<?> readQuerqyQuery(final StreamInput in) throws IOException {
        final int version = in.readVInt();
        checkVersion(version);
        return (QuerqyQuery<?>) readNode(in, null);
    }

    public static ExpandedQuery readExpandedQuery(final StreamInput in) throws IOException {
        final int version = in.readVInt();
        checkVersion(version);

        final QuerqyQuery<?> userQuery = (QuerqyQuery<?>) readNode(in, null);
        final List<QuerqyQuery<?>> filterQueries = readQuerqyQueryCollection(in);
        final List<BoostQuery> boostUp = readBoostQueries(in);
        final List<BoostQuery> boostDown = readBoostQueries(in);
        final List<BoostQuery> multiplicative = readBoostQueries(in);

        return new ExpandedQuery(userQuery, filterQueries, boostUp, boostDown, multiplicative);
    }

    private static void checkVersion(final int version) throws IOException {
        if (version > CURRENT_VERSION) {
            throw new IOException("Cannot read a rewritten Querqy model of version " + version
                    + " - this node only supports up to version " + CURRENT_VERSION
                    + ". This usually means a mixed-version cluster during a rolling upgrade; retry once every "
                    + "node is upgraded.");
        }
    }

    private static void writeQuerqyQueryCollection(final StreamOutput out, final Iterable<QuerqyQuery<?>> nodes)
            throws IOException {
        final List<QuerqyQuery<?>> list = new ArrayList<>();
        if (nodes != null) {
            nodes.forEach(list::add);
        }
        out.writeVInt(list.size());
        for (final QuerqyQuery<?> node : list) {
            writeNode(out, node);
        }
    }

    private static List<QuerqyQuery<?>> readQuerqyQueryCollection(final StreamInput in) throws IOException {
        final int n = in.readVInt();
        final List<QuerqyQuery<?>> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add((QuerqyQuery<?>) readNode(in, null));
        }
        return result;
    }

    private static void writeBoostQueries(final StreamOutput out, final Iterable<BoostQuery> boostQueries)
            throws IOException {
        final List<BoostQuery> list = new ArrayList<>();
        if (boostQueries != null) {
            boostQueries.forEach(list::add);
        }
        out.writeVInt(list.size());
        for (final BoostQuery bq : list) {
            out.writeFloat(bq.getBoost());
            writeNode(out, bq.getQuery());
        }
    }

    private static List<BoostQuery> readBoostQueries(final StreamInput in) throws IOException {
        final int n = in.readVInt();
        final List<BoostQuery> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final float boost = in.readFloat();
            final QuerqyQuery<?> query = (QuerqyQuery<?>) readNode(in, null);
            result.add(new BoostQuery(query, boost));
        }
        return result;
    }

    private static void writeNode(final StreamOutput out, final Node node) throws IOException {
        if (node instanceof BoostedTerm) {
            out.writeByte(TAG_BOOSTED_TERM);
            final BoostedTerm term = (BoostedTerm) node;
            writeTermBody(out, term);
            out.writeFloat(term.getBoost());
        } else if (node instanceof Term) {
            out.writeByte(TAG_TERM);
            writeTermBody(out, (Term) node);
        } else if (node instanceof Query) {
            out.writeByte(TAG_QUERY);
            writeGeneratedFlag(out, (Node) node);
            writeChildren(out, ((Query) node).getClauses());
        } else if (node instanceof DisjunctionMaxQuery) {
            out.writeByte(TAG_DMQ);
            final DisjunctionMaxQuery dmq = (DisjunctionMaxQuery) node;
            writeOccurAndGenerated(out, dmq);
            final boolean hasTieBreaker = dmq.getTieBreaker().isPresent();
            out.writeBoolean(hasTieBreaker);
            if (hasTieBreaker) {
                out.writeFloat(dmq.getTieBreaker().get());
            }
            writeChildren(out, dmq.getClauses());
        } else if (node instanceof BooleanQuery) {
            out.writeByte(TAG_BOOLEAN_QUERY);
            writeOccurAndGenerated(out, (Clause<?>) node);
            writeChildren(out, ((BooleanQuery) node).getClauses());
        } else if (node instanceof BoostedPhraseQuery) {
            out.writeByte(TAG_BOOSTED_PHRASE_QUERY);
            final BoostedPhraseQuery pq = (BoostedPhraseQuery) node;
            writePhraseQueryBody(out, pq);
            out.writeFloat(pq.getBoost());
        } else if (node instanceof PhraseQuery) {
            out.writeByte(TAG_PHRASE_QUERY);
            writePhraseQueryBody(out, (PhraseQuery) node);
        } else if (node instanceof MatchAllQuery) {
            out.writeByte(TAG_MATCH_ALL);
            writeOccurAndGenerated(out, (Clause<?>) node);
        } else if (node instanceof StringRawQuery) {
            out.writeByte(TAG_STRING_RAW_QUERY);
            final StringRawQuery raw = (StringRawQuery) node;
            writeOccurAndGenerated(out, raw);
            out.writeString(raw.getQueryString());
        } else if (node instanceof ParametrizedRawQuery) {
            out.writeByte(TAG_PARAMETRIZED_RAW_QUERY);
            final ParametrizedRawQuery raw = (ParametrizedRawQuery) node;
            writeOccurAndGenerated(out, raw);
            final List<Part> parts = raw.getParts();
            out.writeVInt(parts.size());
            for (final Part part : parts) {
                out.writeString(part.part);
                out.writeByte((byte) part.type.ordinal());
            }
        } else if (node instanceof ParsedRawQuery) {
            throw new UnsupportedOperationException(
                    "ParsedRawQuery carries an opaque, unbounded payload and cannot be serialized for a "
                            + "coordinator-to-shard rewritten query. No built-in rewriter produces one; if a "
                            + "third-party rewriter did, it cannot be used with the querqy_rewrite search "
                            + "pipeline processor.");
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported querqy.model node type for a rewritten-query payload: " + node.getClass().getName());
        }
    }

    private static void writeTermBody(final StreamOutput out, final Term term) throws IOException {
        out.writeBoolean(term.isGenerated());
        final String field = term.getField();
        out.writeBoolean(field != null);
        if (field != null) {
            out.writeString(field);
        }
        out.writeString(term.getValue().toString());
    }

    private static void writePhraseQueryBody(final StreamOutput out, final PhraseQuery pq) throws IOException {
        writeOccurAndGenerated(out, pq);
        final String field = pq.getField();
        out.writeBoolean(field != null);
        if (field != null) {
            out.writeString(field);
        }
        out.writeStringCollection(pq.getTerms());
        out.writeVInt(pq.getSlop());
    }

    private static void writeOccurAndGenerated(final StreamOutput out, final Clause<?> clause) throws IOException {
        out.writeByte((byte) clause.getOccur().ordinal());
        out.writeBoolean(clause.isGenerated());
    }

    /** For {@link Query}, whose {@code Occur} is always {@code SHOULD} and not worth persisting. */
    private static void writeGeneratedFlag(final StreamOutput out, final Node node) throws IOException {
        out.writeBoolean(node.isGenerated());
    }

    private static void writeChildren(final StreamOutput out, final List<? extends Node> children)
            throws IOException {
        out.writeVInt(children.size());
        for (final Node child : children) {
            writeNode(out, child);
        }
    }

    @SuppressWarnings("unchecked")
    private static Node readNode(final StreamInput in, final Object parent) throws IOException {
        final byte tag = in.readByte();
        switch (tag) {
            case TAG_QUERY: {
                final boolean generated = in.readBoolean();
                final Query query = new Query(generated);
                readChildrenInto(in, query, c -> query.addClause((querqy.model.BooleanClause) c));
                return query;
            }
            case TAG_DMQ: {
                final Occur occur = readOccur(in);
                final boolean generated = in.readBoolean();
                final boolean hasTieBreaker = in.readBoolean();
                final Float tieBreaker = hasTieBreaker ? in.readFloat() : null;
                final DisjunctionMaxQuery dmq = new DisjunctionMaxQuery((BooleanQuery) parent, occur, generated,
                        tieBreaker);
                readChildrenInto(in, dmq, c -> dmq.addClause((DisjunctionMaxClause) c));
                return dmq;
            }
            case TAG_BOOLEAN_QUERY: {
                final Occur occur = readOccur(in);
                final boolean generated = in.readBoolean();
                final BooleanQuery bq = new BooleanQuery((BooleanParent) parent, occur, generated);
                readChildrenInto(in, bq, c -> bq.addClause((querqy.model.BooleanClause) c));
                return bq;
            }
            case TAG_TERM: {
                final boolean generated = in.readBoolean();
                final boolean hasField = in.readBoolean();
                final String field = hasField ? in.readString() : null;
                final String value = in.readString();
                return new Term((DisjunctionMaxQuery) parent, field, value, generated);
            }
            case TAG_BOOSTED_TERM: {
                in.readBoolean(); // generated - BoostedTerm's constructor always forces true
                final boolean hasField = in.readBoolean();
                final String field = hasField ? in.readString() : null;
                final String value = in.readString();
                final float boost = in.readFloat();
                return new BoostedTerm((DisjunctionMaxQuery) parent, field, value, boost);
            }
            case TAG_MATCH_ALL: {
                final Occur occur = readOccur(in);
                final boolean generated = in.readBoolean();
                return new MatchAllQuery((BooleanParent) parent, occur, generated);
            }
            case TAG_PHRASE_QUERY: {
                final Occur occur = readOccur(in);
                final boolean generated = in.readBoolean();
                final boolean hasField = in.readBoolean();
                final String field = hasField ? in.readString() : null;
                final List<String> terms = in.readStringList();
                final int slop = in.readVInt();
                return new PhraseQuery((BooleanParent) parent, occur, generated, field, terms, slop);
            }
            case TAG_BOOSTED_PHRASE_QUERY: {
                final Occur occur = readOccur(in);
                in.readBoolean(); // generated - BoostedPhraseQuery's constructor always forces true
                final boolean hasField = in.readBoolean();
                final String field = hasField ? in.readString() : null;
                final List<String> terms = in.readStringList();
                final int slop = in.readVInt();
                final float boost = in.readFloat();
                return new BoostedPhraseQuery((BooleanParent) parent, occur, field, terms, slop, boost);
            }
            case TAG_STRING_RAW_QUERY: {
                final Occur occur = readOccur(in);
                final boolean generated = in.readBoolean();
                final String queryString = in.readString();
                return new StringRawQuery((BooleanParent) parent, queryString, occur, generated);
            }
            case TAG_PARAMETRIZED_RAW_QUERY: {
                final Occur occur = readOccur(in);
                final boolean generated = in.readBoolean();
                final int n = in.readVInt();
                final List<Part> parts = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    final String part = in.readString();
                    final Part.Type type = Part.Type.values()[in.readByte()];
                    parts.add(new Part(part, type));
                }
                return new ParametrizedRawQuery((BooleanParent) parent, parts, occur, generated);
            }
            default:
                throw new IOException("Unknown querqy.model node tag: " + tag);
        }
    }

    private static Occur readOccur(final StreamInput in) throws IOException {
        return Occur.values()[in.readByte()];
    }

    private interface ClauseAdder {
        void add(Node child);
    }

    private static void readChildrenInto(final StreamInput in, final Object parent, final ClauseAdder adder)
            throws IOException {
        final int n = in.readVInt();
        for (int i = 0; i < n; i++) {
            adder.add(readNode(in, parent));
        }
    }
}
