package com.indeed.squall.iql2.language;

import com.indeed.common.util.time.WallClock;
import com.indeed.squall.iql2.language.compat.Consumer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.indeed.squall.iql2.language.Identifiers.parseIdentifier;

public class AggregateFilters {
    public static AggregateFilter parseAggregateFilter(JQLParser.AggregateFilterContext aggregateFilterContext, Map<String, Set<String>> datasetToKeywordAnalyzerFields, Map<String, Set<String>> datasetToIntFields, Consumer<String> warn, WallClock clock) {
        if (aggregateFilterContext.jqlAggregateFilter() != null) {
            return parseJQLAggregateFilter(aggregateFilterContext.jqlAggregateFilter(), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock);
        }
        throw new UnsupportedOperationException("Non-JQL aggregate filters don't exist. What did you do?!?!?!");
    }

    public static AggregateFilter parseJQLAggregateFilter(JQLParser.JqlAggregateFilterContext aggregateFilterContext, final Map<String, Set<String>> datasetToKeywordAnalyzerFields, final Map<String, Set<String>> datasetToIntFields, final Consumer<String> warn, final WallClock clock) {
        final AggregateFilter[] ref = new AggregateFilter[1];

        aggregateFilterContext.enterRule(new JQLBaseListener() {
            private void accept(AggregateFilter value) {
                if (ref[0] != null) {
                    throw new IllegalArgumentException("Can't accept multiple times!");
                }
                ref[0] = value;
            }

            public void enterAggregateRegex(JQLParser.AggregateRegexContext ctx) {
                accept(new AggregateFilter.Regex(parseIdentifier(ctx.field), ParserCommon.unquote(ctx.STRING_LITERAL().getText())));
            }

            public void enterAggregateFalse(JQLParser.AggregateFalseContext ctx) {
                accept(new AggregateFilter.Never());
            }

            public void enterAggregateTermIs(JQLParser.AggregateTermIsContext ctx) {
                accept(new AggregateFilter.TermIs(Term.parseJqlTerm(ctx.jqlTermVal())));
            }

            public void enterAggregateNotRegex(JQLParser.AggregateNotRegexContext ctx) {
                accept(new AggregateFilter.Not(new AggregateFilter.Regex(parseIdentifier(ctx.field), ParserCommon.unquote(ctx.STRING_LITERAL().getText()))));
            }

            public void enterAggregateTrue(JQLParser.AggregateTrueContext ctx) {
                accept(new AggregateFilter.Always());
            }

            public void enterAggregateFilterParens(JQLParser.AggregateFilterParensContext ctx) {
                accept(parseJQLAggregateFilter(ctx.jqlAggregateFilter(), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock));
            }

            public void enterAggregateAnd(JQLParser.AggregateAndContext ctx) {
                accept(new AggregateFilter.And(parseJQLAggregateFilter(ctx.jqlAggregateFilter(0), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock), parseJQLAggregateFilter(ctx.jqlAggregateFilter(1), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock)));
            }

            public void enterAggregateMetricInequality(JQLParser.AggregateMetricInequalityContext ctx) {
                final String operation = ctx.op.getText();
                final AggregateMetric arg1 = AggregateMetrics.parseJQLAggregateMetric(ctx.jqlAggregateMetric(0), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock);
                final AggregateMetric arg2 = AggregateMetrics.parseJQLAggregateMetric(ctx.jqlAggregateMetric(1), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock);
                final AggregateFilter result;
                switch (operation) {
                    case "=": {
                        result = new AggregateFilter.MetricIs(arg1, arg2);
                        break;
                    }
                    case "!=": {
                        result = new AggregateFilter.MetricIsnt(arg1, arg2);
                        break;
                    }
                    case "<": {
                        result = new AggregateFilter.Lt(arg1, arg2);
                        break;
                    }
                    case "<=": {
                        result = new AggregateFilter.Lte(arg1, arg2);
                        break;
                    }
                    case ">": {
                        result = new AggregateFilter.Gt(arg1, arg2);
                        break;
                    }
                    case ">=": {
                        result = new AggregateFilter.Gte(arg1, arg2);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("Unhandled inequality operation: [" + operation + "]");
                }
                accept(result);
            }

            public void enterAggregateNot(JQLParser.AggregateNotContext ctx) {
                accept(new AggregateFilter.Not(parseJQLAggregateFilter(ctx.jqlAggregateFilter(), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock)));
            }

            public void enterAggregateOr(JQLParser.AggregateOrContext ctx) {
                accept(new AggregateFilter.Or(parseJQLAggregateFilter(ctx.jqlAggregateFilter(0), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock), parseJQLAggregateFilter(ctx.jqlAggregateFilter(1), datasetToKeywordAnalyzerFields, datasetToIntFields, warn, clock)));
            }
        });

        if (ref[0] == null) {
            throw new UnsupportedOperationException("Unhandled aggregate filter: [" + aggregateFilterContext.getText() + "]");
        }

        return ref[0];
    }
}
