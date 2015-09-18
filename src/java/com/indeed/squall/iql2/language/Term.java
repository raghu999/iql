package com.indeed.squall.iql2.language;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;

public class Term implements JsonSerializable {
    public final String stringTerm;
    public final long intTerm;
    public final boolean isIntTerm;

    private Term(String stringTerm, long intTerm, boolean isIntTerm) {
        this.stringTerm = stringTerm;
        this.intTerm = intTerm;
        this.isIntTerm = isIntTerm;
    }

    public static Term term(String term) {
        return new Term(term, 0, false);
    }

    public static Term term(long term) {
        return new Term(null, term, true);
    }

    public static Term parseJqlTerm(JQLParser.JqlTermValContext jqlTermValContext) {
        final Term[] ref = new Term[1];

        jqlTermValContext.enterRule(new JQLBaseListener() {
            private void accept(Term value) {
                if (ref[0] != null) {
                    throw new IllegalArgumentException("Can't accept multiple times!");
                }
                ref[0] = value;
            }

            public void enterJqlIntTerm(JQLParser.JqlIntTermContext ctx) {
                accept(term(Long.parseLong(ctx.INT().getText())));
            }

            public void enterJqlStringTerm(JQLParser.JqlStringTermContext ctx) {
                if (ctx.STRING_LITERAL() != null) {
                    accept(term(ParserCommon.unquote(ctx.STRING_LITERAL().getText())));
                }
            }
        });

        if (ref[0] == null) {
            throw new UnsupportedOperationException("Unhandled term value: [" + jqlTermValContext.getText() + "]");
        }

        return ref[0];
    }

    public static Term parseLegacyTerm(JQLParser.LegacyTermValContext legacyTermValContext) {
        final Term[] ref = new Term[1];

        legacyTermValContext.enterRule(new JQLBaseListener() {
            private void accept(Term value) {
                if (ref[0] != null) {
                    throw new IllegalArgumentException("Can't accept multiple times!");
                }
                ref[0] = value;
            }

            public void enterLegacyIntTerm(JQLParser.LegacyIntTermContext ctx) {
                accept(term(Long.parseLong(ctx.INT().getText())));
            }

            public void enterLegacyStringTerm(JQLParser.LegacyStringTermContext ctx) {
                if (ctx.STRING_LITERAL() != null) {
                    accept(term(ParserCommon.unquote(ctx.STRING_LITERAL().getText())));
                } else if (ctx.identifier() != null) {
                    accept(term(ctx.identifier().getText()));
                }
            }
        });

        if (ref[0] == null) {
            throw new UnsupportedOperationException("Unhandled term value: [" + legacyTermValContext.getText() + "]");
        }

        return ref[0];
    }

    public static Term parseTerm(JQLParser.TermValContext termValContext) {
        if (termValContext.jqlTermVal() != null) {
            return parseJqlTerm(termValContext.jqlTermVal());
        } else if (termValContext.legacyTermVal() != null) {
            return parseLegacyTerm(termValContext.legacyTermVal());
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void serialize(JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (isIntTerm) {
            gen.writeObject(ImmutableMap.of("type", "int", "value", intTerm));
        } else {
            gen.writeObject(ImmutableMap.of("type", "string", "value", stringTerm));
        }
    }

    @Override
    public void serializeWithType(JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
        this.serialize(gen, serializers);
    }

    public com.indeed.flamdex.query.Term toFlamdex(String field) {
        return new com.indeed.flamdex.query.Term(field, isIntTerm, intTerm, stringTerm == null ? "" : stringTerm);
    }

    @Override
    public String toString() {
        return "Term{" +
                "stringTerm='" + stringTerm + '\'' +
                ", intTerm=" + intTerm +
                ", isIntTerm=" + isIntTerm +
                '}';
    }
}
