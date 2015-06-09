package com.indeed.jql.language.execution;

import com.google.common.base.Optional;
import com.indeed.jql.language.AggregateFilter;
import com.indeed.jql.language.AggregateMetric;
import com.indeed.jql.language.DocFilter;
import com.indeed.jql.language.DocMetric;
import com.indeed.jql.language.TimeUnit;
import com.indeed.jql.language.commands.Command;
import com.indeed.jql.language.commands.ComputeAndCreateGroupStatsLookup;
import com.indeed.jql.language.commands.ExplodeTimeBuckets;
import com.indeed.jql.language.commands.Iterate;
import com.indeed.jql.language.commands.IterateAndExplode;
import com.indeed.jql.language.commands.MetricRegroup;
import com.indeed.jql.language.commands.TimePeriodRegroup;
import com.indeed.jql.language.commands.TimeRegroup;
import com.indeed.jql.language.precomputed.Precomputed;
import com.indeed.util.core.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ExecutionStep {

    List<Command> commands();

    class ComputePrecomputed implements ExecutionStep {
        private final Set<String> scope;
        private final Precomputed computation;
        private final String name;

        public ComputePrecomputed(Set<String> scope, Precomputed computation, String name) {
            this.scope = scope;
            this.computation = computation;
            this.name = name;
        }

        @Override
        public List<Command> commands() {
            final Precomputed.Precomputation precomputation = computation.commands(scope);
            final List<Command> result = new ArrayList<>();
            result.addAll(precomputation.beforeCommands);
            result.add(new ComputeAndCreateGroupStatsLookup(precomputation.computationCommand, Optional.of(name)));
            result.addAll(precomputation.afterCommands);
            return result;
        }

        @Override
        public String toString() {
            return "ComputePrecomputed{" +
                    "scope=" + scope +
                    ", computation=" + computation +
                    ", name='" + name + '\'' +
                    '}';
        }
    }

    class ComputeManyPrecomputed implements ExecutionStep {
        private final Set<String> scope;
        private final List<Pair<Precomputed, String>>  computations;

        public ComputeManyPrecomputed(Set<String> scope, List<Pair<Precomputed, String>> computations) {
            this.scope = scope;
            this.computations = computations;
        }

        @Override
        public List<Command> commands() {
            throw new UnsupportedOperationException("c'mon man");
        }

        @Override
        public String toString() {
            return "ComputeManyPrecomputed{" +
                    "scope=" + scope +
                    ", computations=" + computations +
                    '}';
        }
    }

    class ExplodeAndRegroup implements ExecutionStep {
        public final String field;
        public final Optional<AggregateFilter> filter;
        public final Optional<Long> limit;
        public final AggregateMetric metric;
        public final boolean withDefault;

        public ExplodeAndRegroup(String field, Optional<AggregateFilter> filter, Optional<Long> limit, AggregateMetric metric, boolean withDefault) {
            this.field = field;
            this.filter = filter;
            this.limit = limit;
            this.metric = metric;
            this.withDefault = withDefault;
        }

        @Override
        public List<Command> commands() {
            final Iterate.FieldIterateOpts opts = new Iterate.FieldIterateOpts();
            if (filter.isPresent()) {
                opts.filter = Optional.of(filter.get());
            }
            if (limit.isPresent()) {
                opts.topK = Optional.of(new Iterate.TopK((int) (long) limit.get(), metric));
            }
            final Optional<String> withDefaultName;
            if (withDefault) {
                withDefaultName = Optional.of("DEFAULT");
            } else {
                withDefaultName = Optional.absent();
            }
            final Command command = new IterateAndExplode(field, Collections.<AggregateMetric>emptyList(), opts, Optional.<Pair<Integer, Iterate.FieldLimitingMechanism>>absent(), withDefaultName);
            return Collections.singletonList(command);
        }

        @Override
        public String toString() {
            return "ExplodeAndRegroup{" +
                    "field='" + field + '\'' +
                    ", filter=" + filter +
                    ", limit=" + limit +
                    ", metric=" + metric +
                    ", withDefault=" + withDefault +
                    '}';
        }
    }

    class ExplodeMetric implements ExecutionStep {
        private final DocMetric metric;
        private final long lowerBound;
        private final long upperBound;
        private final long interval;
        private final Set<String> scope;
        private final boolean excludeGutters;

        public ExplodeMetric(DocMetric metric, long lowerBound, long upperBound, long interval, Set<String> scope, boolean excludeGutters) {
            this.metric = metric;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.interval = interval;
            this.scope = scope;
            this.excludeGutters = excludeGutters;
        }

        @Override
        public List<Command> commands() {
            final Map<String, List<String>> datasetToPushes = new HashMap<>();
            for (final String s : scope) {
                datasetToPushes.put(s, metric.getPushes(s));
            }
            return Collections.<Command>singletonList(new MetricRegroup(datasetToPushes, lowerBound, upperBound, interval));
        }

        @Override
        public String toString() {
            return "ExplodeMetric{" +
                    "metric=" + metric +
                    ", lowerBound=" + lowerBound +
                    ", upperBound=" + upperBound +
                    ", interval=" + interval +
                    ", scope=" + scope +
                    ", excludeGutters=" + excludeGutters +
                    '}';
        }
    }

    class ExplodeTime implements ExecutionStep {
        private final long value;
        private final TimeUnit timeUnit;
        private final Optional<String> timeField;
        private final Optional<String> timeFormat;

        public ExplodeTime(long value, TimeUnit timeUnit, Optional<String> timeField, Optional<String> timeFormat) {
            this.value = value;
            this.timeUnit = timeUnit;
            this.timeField = timeField;
            this.timeFormat = timeFormat;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new TimeRegroup(value, timeUnit.character, 0, timeField));
        }

        @Override
        public String toString() {
            return "ExplodeTime{" +
                    "value=" + value +
                    ", timeUnit=" + timeUnit +
                    ", timeField=" + timeField +
                    ", timeFormat=" + timeFormat +
                    '}';
        }
    }

    class ExplodeTimePeriod implements ExecutionStep {
        private final long periodMillis;
        private final Optional<String> timeField;
        private final Optional<String> timeFormat;

        public ExplodeTimePeriod(long periodMillis, Optional<String> timeField, Optional<String> timeFormat) {
            this.periodMillis = periodMillis;
            this.timeField = timeField;
            this.timeFormat = timeFormat;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new TimePeriodRegroup(periodMillis, timeField, timeFormat));
        }

        @Override
        public String toString() {
            return "ExplodeTimePeriod{" +
                    "periodMillis=" + periodMillis +
                    ", timeField=" + timeField +
                    ", timeFormat=" + timeFormat +
                    '}';
        }
    }

    class ExplodeTimeBuckets implements ExecutionStep {
        private final int numBuckets;
        private final Optional<String> timeField;
        private final Optional<String> timeFormat;

        public ExplodeTimeBuckets(int numBuckets, Optional<String> timeField, Optional<String> timeFormat) {
            this.numBuckets = numBuckets;
            this.timeField = timeField;
            this.timeFormat = timeFormat;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.jql.language.commands.ExplodeTimeBuckets(numBuckets, timeField, timeFormat));
        }

        @Override
        public String toString() {
            return "ExplodeTimeBuckets{" +
                    "numBuckets=" + numBuckets +
                    ", timeField=" + timeField +
                    ", timeFormat=" + timeFormat +
                    '}';
        }
    }

    class ExplodeDayOfWeek implements ExecutionStep {
        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.jql.language.commands.ExplodeDayOfWeek());
        }

        @Override
        public String toString() {
            return "ExplodeDayOfWeek{}";
        }
    }

    class ExplodeMonthOfYear implements ExecutionStep {
        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.jql.language.commands.ExplodeMonthOfYear());
        }

        @Override
        public String toString() {
            return "ExplodeMonthOfYear{}";
        }
    }

    class ExplodeSessionNames implements ExecutionStep {
        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.jql.language.commands.ExplodeSessionNames());
        }

        @Override
        public String toString() {
            return "ExplodeDayOfWeek{}";
        }
    }

    class FilterDocs implements ExecutionStep {
        private final DocFilter filter;
        private final Set<String> scope;

        public FilterDocs(DocFilter filter, Set<String> scope) {
            this.filter = filter;
            this.scope = scope;
        }

        @Override
        public List<Command> commands() {
            final Map<String, List<String>> perDatasetPushes = new HashMap<>();
            for (final String dataset : scope) {
                perDatasetPushes.put(dataset, filter.asZeroOneMetric(dataset).getPushes(dataset));
            }
            return Collections.<Command>singletonList(new com.indeed.jql.language.commands.FilterDocs(perDatasetPushes));
        }

        @Override
        public String toString() {
            return "FilterDocs{" +
                    "filter=" + filter +
                    ", scope=" + scope +
                    '}';
        }
    }

    class IterateStats implements ExecutionStep {
        private final String field;
        private final Optional<AggregateFilter> filter;
        private final Optional<Long> limit;
        private final AggregateMetric metric;
        private final List<AggregateMetric> stats;

        public IterateStats(String field, Optional<AggregateFilter> filter, Optional<Long> limit, AggregateMetric metric, List<AggregateMetric> stats) {
            this.field = field;
            this.filter = filter;
            this.limit = limit;
            this.metric = metric;
            this.stats = stats;
        }

        @Override
        public List<Command> commands() {
            final Iterate.FieldIterateOpts opts = new Iterate.FieldIterateOpts();
            if (limit.isPresent()) {
                opts.topK = Optional.of(new Iterate.TopK((int) (long) limit.get(), metric));
            }
            opts.filter = filter;
            final Iterate iterate = new Iterate(Collections.singletonList(new Iterate.FieldWithOptions(field, opts)), Optional.<Pair<Integer, Iterate.FieldLimitingMechanism>>absent(), stats);
            return Collections.<Command>singletonList(iterate);
        }

        @Override
        public String toString() {
            return "IterateStats{" +
                    "field='" + field + '\'' +
                    ", filter=" + filter +
                    ", limit=" + limit +
                    ", metric=" + metric +
                    ", stats=" + stats +
                    '}';
        }
    }

    class GetGroupStats implements ExecutionStep {
        public final List<AggregateMetric> stats;

        public GetGroupStats(List<AggregateMetric> stats) {
            this.stats = stats;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.jql.language.commands.GetGroupStats(stats, true));
        }

        @Override
        public String toString() {
            return "GetGroupStats{" +
                    "stats=" + stats +
                    '}';
        }
    }

    class ExplodePerDocPercentile implements ExecutionStep {
        private final String field;
        private final int numBuckets;

        public ExplodePerDocPercentile(String field, int numBuckets) {
            this.field = field;
            this.numBuckets = numBuckets;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.jql.language.commands.ExplodePerDocPercentile(field, numBuckets));
        }

        @Override
        public String toString() {
            return "ExplodePerDocPercentile{" +
                    "field='" + field + '\'' +
                    ", numBuckets=" + numBuckets +
                    '}';
        }
    }
}
