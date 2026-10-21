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

package querqy.opensearch.searchpipelinespike;

import querqy.model.BooleanParent;
import querqy.model.BooleanQuery;
import querqy.model.BoostQuery;
import querqy.model.Clause.Occur;
import querqy.model.DisjunctionMaxClause;
import querqy.model.DisjunctionMaxQuery;
import querqy.model.ExpandedQuery;
import querqy.model.MatchAllQuery;
import querqy.model.Node;
import querqy.model.QuerqyQuery;
import querqy.model.Query;
import querqy.model.Term;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Phase-0 spike only (briefing section 5, "decisive serialization spike"): a hand-rolled, deliberately
 * narrow {@code querqy.model} (de)serializer, covering only the node types a representative Common Rules
 * rewrite (SYNONYM/UP/DOWN) produces - {@link Query}, {@link BooleanQuery}, {@link DisjunctionMaxQuery},
 * {@link Term}, {@link MatchAllQuery} - plus {@link ExpandedQuery}'s own filter/boost collections.
 * <p>
 * <b>Deliberately NOT covered</b> (would throw {@link UnsupportedOperationException}): {@code PhraseQuery},
 * {@code BoostedTerm}/{@code BoostedPhraseQuery}, any {@code RawQuery} subtype. A production serializer
 * would need to cover all of these. {@link Term#getValue()}'s concrete {@code ComparableCharSequence}
 * subtype (e.g. {@code LowerCaseCharSequence}) is also not preserved - only its character content is -
 * which is a documented simplification, not a validated non-issue; see the spike's findings.
 * <p>
 * This exists purely to answer one question: is the {@code querqy.model} object graph produced by a
 * rewrite free of anything that would make it fundamentally unserializable (Lucene objects, suppliers,
 * {@code IndexReader}/{@code IndexSearcher} references)? The parent back-pointers
 * ({@link querqy.model.AbstractNode#getParent()}) are handled by writing child-before-parent-is-known and
 * reconstructing top-down, passing each freshly-built parent into its children's constructors - exactly how
 * these classes are already built by parsers/rewriters themselves.
 */
public final class SpikeQuerqyModelCodec {

    private SpikeQuerqyModelCodec() {
    }

    private static final byte TAG_QUERY = 1;
    private static final byte TAG_BOOLEAN_QUERY = 2;
    private static final byte TAG_DMQ = 3;
    private static final byte TAG_TERM = 4;
    private static final byte TAG_MATCH_ALL = 5;

    public static byte[] serialize(final ExpandedQuery expandedQuery) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(bytes);

            writeQuerqyQuery(out, expandedQuery.getUserQuery());
            writeQuerqyQueryCollection(out, expandedQuery.getFilterQueries());
            writeBoostQueries(out, expandedQuery.getBoostUpQueries());
            writeBoostQueries(out, expandedQuery.getBoostDownQueries());
            writeBoostQueries(out, expandedQuery.getMultiplicativeBoostQueries());

            return bytes.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ExpandedQuery deserialize(final byte[] bytes) {
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

            final QuerqyQuery<?> userQuery = (QuerqyQuery<?>) readNode(in, null);
            final List<QuerqyQuery<?>> filterQueries = readQuerqyQueryCollection(in);
            final List<BoostQuery> boostUp = readBoostQueries(in);
            final List<BoostQuery> boostDown = readBoostQueries(in);
            final List<BoostQuery> multiplicative = readBoostQueries(in);

            return new ExpandedQuery(userQuery, filterQueries, boostUp, boostDown, multiplicative);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeQuerqyQueryCollection(final DataOutputStream out, final Collection<QuerqyQuery<?>> nodes)
            throws IOException {
        if (nodes == null) {
            out.writeInt(0);
            return;
        }
        out.writeInt(nodes.size());
        for (final QuerqyQuery<?> node : nodes) {
            writeQuerqyQuery(out, node);
        }
    }

    private static List<QuerqyQuery<?>> readQuerqyQueryCollection(final DataInputStream in) throws IOException {
        final int n = in.readInt();
        final List<QuerqyQuery<?>> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add((QuerqyQuery<?>) readNode(in, null));
        }
        return result;
    }

    private static void writeBoostQueries(final DataOutputStream out, final Collection<BoostQuery> boostQueries)
            throws IOException {
        if (boostQueries == null) {
            out.writeInt(0);
            return;
        }
        out.writeInt(boostQueries.size());
        for (final BoostQuery bq : boostQueries) {
            out.writeFloat(bq.getBoost());
            writeQuerqyQuery(out, bq.getQuery());
        }
    }

    private static List<BoostQuery> readBoostQueries(final DataInputStream in) throws IOException {
        final int n = in.readInt();
        final List<BoostQuery> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final float boost = in.readFloat();
            final QuerqyQuery<?> query = (QuerqyQuery<?>) readNode(in, null);
            result.add(new BoostQuery(query, boost));
        }
        return result;
    }

    private static void writeQuerqyQuery(final DataOutputStream out, final Node node) throws IOException {
        writeNode(out, node);
    }

    private static void writeNode(final DataOutputStream out, final Node node) throws IOException {
        if (node instanceof Query) {
            out.writeByte(TAG_QUERY);
            writeClauseHeader(out, (Query) node);
            writeChildren(out, ((Query) node).getClauses());
        } else if (node instanceof DisjunctionMaxQuery) {
            out.writeByte(TAG_DMQ);
            final DisjunctionMaxQuery dmq = (DisjunctionMaxQuery) node;
            writeClauseHeader(out, dmq);
            final boolean hasTieBreaker = dmq.getTieBreaker().isPresent();
            out.writeBoolean(hasTieBreaker);
            if (hasTieBreaker) {
                out.writeFloat(dmq.getTieBreaker().get());
            }
            writeChildren(out, dmq.getClauses());
        } else if (node instanceof BooleanQuery) {
            out.writeByte(TAG_BOOLEAN_QUERY);
            writeClauseHeader(out, (BooleanQuery) node);
            writeChildren(out, ((BooleanQuery) node).getClauses());
        } else if (node instanceof Term) {
            out.writeByte(TAG_TERM);
            final Term term = (Term) node;
            out.writeBoolean(term.isGenerated());
            final String field = term.getField();
            out.writeBoolean(field != null);
            if (field != null) {
                out.writeUTF(field);
            }
            out.writeUTF(term.getValue().toString());
        } else if (node instanceof MatchAllQuery) {
            out.writeByte(TAG_MATCH_ALL);
            writeClauseHeader(out, (MatchAllQuery) node);
        } else {
            throw new UnsupportedOperationException(
                    "This spike codec does not cover node type " + node.getClass().getName()
                            + " - a production serializer would need to");
        }
    }

    private static void writeClauseHeader(final DataOutputStream out, final querqy.model.Clause<?> clause)
            throws IOException {
        out.writeByte(clause.getOccur().ordinal());
        out.writeBoolean(clause.isGenerated());
    }

    private static void writeChildren(final DataOutputStream out, final List<? extends Node> children)
            throws IOException {
        out.writeInt(children.size());
        for (final Node child : children) {
            writeNode(out, child);
        }
    }

    @SuppressWarnings("unchecked")
    private static Node readNode(final DataInputStream in, final Object parent) throws IOException {
        final byte tag = in.readByte();
        switch (tag) {
            case TAG_QUERY: {
                final Occur occur = Occur.values()[in.readByte()];
                final boolean generated = in.readBoolean();
                final Query query = new Query(generated);
                readChildrenInto(in, query, c -> query.addClause((querqy.model.BooleanClause) c));
                return query;
            }
            case TAG_DMQ: {
                final Occur occur = Occur.values()[in.readByte()];
                final boolean generated = in.readBoolean();
                final boolean hasTieBreaker = in.readBoolean();
                final Float tieBreaker = hasTieBreaker ? in.readFloat() : null;
                final DisjunctionMaxQuery dmq = new DisjunctionMaxQuery((BooleanQuery) parent, occur, generated,
                        tieBreaker);
                readChildrenInto(in, dmq, c -> dmq.addClause((DisjunctionMaxClause) c));
                return dmq;
            }
            case TAG_BOOLEAN_QUERY: {
                final Occur occur = Occur.values()[in.readByte()];
                final boolean generated = in.readBoolean();
                final BooleanQuery bq = new BooleanQuery((BooleanParent) parent, occur, generated);
                readChildrenInto(in, bq, c -> bq.addClause((querqy.model.BooleanClause) c));
                return bq;
            }
            case TAG_TERM: {
                final boolean generated = in.readBoolean();
                final boolean hasField = in.readBoolean();
                final String field = hasField ? in.readUTF() : null;
                final String value = in.readUTF();
                return new Term((DisjunctionMaxQuery) parent, field, value, generated);
            }
            case TAG_MATCH_ALL: {
                final Occur occur = Occur.values()[in.readByte()];
                final boolean generated = in.readBoolean();
                return new MatchAllQuery((BooleanParent) parent, occur, generated);
            }
            default:
                throw new IllegalStateException("Unknown tag: " + tag);
        }
    }

    private interface ClauseAdder {
        void add(Node child);
    }

    private static void readChildrenInto(final DataInputStream in, final Object parent, final ClauseAdder adder)
            throws IOException {
        final int n = in.readInt();
        for (int i = 0; i < n; i++) {
            adder.add(readNode(in, parent));
        }
    }
}
