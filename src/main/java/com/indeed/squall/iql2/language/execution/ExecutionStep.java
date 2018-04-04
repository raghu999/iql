package com.indeed.squall.iql2.language.execution;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.indeed.squall.iql2.language.AggregateFilter;
import com.indeed.squall.iql2.language.AggregateMetric;
import com.indeed.squall.iql2.language.DocFilter;
import com.indeed.squall.iql2.language.DocMetric;
import com.indeed.squall.iql2.language.Term;
import com.indeed.squall.iql2.language.actions.Action;
import com.indeed.squall.iql2.language.commands.ApplyFilterActions;
import com.indeed.squall.iql2.language.commands.ApplyGroupFilter;
import com.indeed.squall.iql2.language.commands.Command;
import com.indeed.squall.iql2.language.commands.ComputeAndCreateGroupStatsLookup;
import com.indeed.squall.iql2.language.commands.ComputeAndCreateGroupStatsLookups;
import com.indeed.squall.iql2.language.commands.FieldIterateOpts;
import com.indeed.squall.iql2.language.commands.IterateAndExplode;
import com.indeed.squall.iql2.language.commands.MetricRegroup;
import com.indeed.squall.iql2.language.commands.RegroupFieldIn;
import com.indeed.squall.iql2.language.commands.SimpleIterate;
import com.indeed.squall.iql2.language.commands.TimePeriodRegroup;
import com.indeed.squall.iql2.language.commands.TopK;
import com.indeed.squall.iql2.language.precomputed.Precomputed;
import com.indeed.util.core.Pair;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ExecutionStep {

    List<Command> commands();
    ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f);

    class ComputePrecomputed implements ExecutionStep {
        public final Set<String> scope;
        public final Precomputed computation;
        public final String name;

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
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return new ComputePrecomputed(scope, computation.traverse1(f), name);
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
        private final List<Pair<Precomputed, String>> computations;

        public ComputeManyPrecomputed(Set<String> scope, List<Pair<Precomputed, String>> computations) {
            this.scope = scope;
            this.computations = computations;
        }

        @Override
        public List<Command> commands() {
            final List<Pair<Command, String>> precomputeds = new ArrayList<>();

            for (final Pair<Precomputed, String> computation : computations) {
                final Precomputed.Precomputation precomputation = computation.getFirst().commands(scope);
                if (!precomputation.afterCommands.isEmpty() || !precomputation.beforeCommands.isEmpty()) {
                    return naiveExecutionCommands();
                }
                precomputeds.add(Pair.of(precomputation.computationCommand, computation.getSecond()));
            }

            return Collections.<Command>singletonList(new ComputeAndCreateGroupStatsLookups(precomputeds));
        }

        private List<Command> naiveExecutionCommands() {
            final List<Command> commands = new ArrayList<>();
            for (final Pair<Precomputed, String> computation : computations) {
                commands.addAll(new ComputePrecomputed(scope, computation.getFirst(), computation.getSecond()).commands());
            }
            return commands;
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            final List<Pair<Precomputed, String>> computations = new ArrayList<>();
            for (final Pair<Precomputed, String> computation : this.computations) {
                computations.add(Pair.of(computation.getFirst().traverse1(f), computation.getSecond()));
            }
            return new ComputeManyPrecomputed(scope, computations);
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
        public final Optional<AggregateMetric> metric;
        public final boolean withDefault;
        public final boolean forceNonStreaming;

        public ExplodeAndRegroup(String field, Optional<AggregateFilter> filter, Optional<Long> limit, Optional<AggregateMetric> metric, boolean withDefault, boolean forceNonStreaming) {
            this.field = field;
            this.filter = filter;
            this.limit = limit;
            this.metric = metric;
            this.withDefault = withDefault;
            this.forceNonStreaming = forceNonStreaming;
        }

        @Override
        public List<Command> commands() {
            final FieldIterateOpts opts = new FieldIterateOpts();
            if (filter.isPresent()) {
                opts.filter = Optional.of(filter.get());
            }
            if (limit.isPresent() || metric.isPresent()) {
                opts.topK = Optional.of(new TopK(limit, metric));
            }
            final Optional<String> withDefaultName;
            if (withDefault) {
                withDefaultName = Optional.of("DEFAULT");
            } else {
                withDefaultName = Optional.absent();
            }
            final Command command = new IterateAndExplode(field, opts, withDefaultName);
            return Collections.singletonList(command);
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            final Optional<AggregateFilter> filter;
            if (this.filter.isPresent()) {
                filter = Optional.of(this.filter.get().traverse1(f));
            } else {
                filter = Optional.absent();
            }
            final Optional<AggregateMetric> metric;
            if (this.metric.isPresent()) {
                metric = Optional.of(f.apply(this.metric.get()));
            } else {
                metric = Optional.absent();
            }
            return new ExplodeAndRegroup(field, filter, limit, metric, withDefault, forceNonStreaming);
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

    class ExplodeFieldIn implements ExecutionStep {
        public final Set<String> scope;
        public final String field;
        public final List<String> stringTerms;
        public final LongList intTerms;
        public final boolean isIntField;
        public final boolean withDefault;

        private ExplodeFieldIn(Set<String> scope, String field, List<String> stringTerms, LongList intTerms, boolean isIntField, boolean withDefault) {
            this.scope = scope;
            this.field = field;
            this.stringTerms = stringTerms;
            this.intTerms = intTerms;
            this.isIntField = isIntField;
            this.withDefault = withDefault;
        }

        public static ExplodeFieldIn intExplode(Set<String> scope, String field, LongList terms, boolean withDefault) {
            return new ExplodeFieldIn(scope, field, Collections.<String>emptyList(), terms, true, withDefault);
        }

        public static ExplodeFieldIn stringExplode(Set<String> scope, String field, List<String> terms, boolean withDefault) {
            return new ExplodeFieldIn(scope, field, terms, LongLists.EMPTY_LIST, false, withDefault);
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new RegroupFieldIn(scope, field, stringTerms, intTerms, isIntField, withDefault));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }


        public AggregateFilter termsAsFilter() {
            AggregateFilter result = new AggregateFilter.Never();
            if (isIntField) {
                for (final long term : intTerms) {
                    result = new AggregateFilter.Or(new AggregateFilter.TermIs(Term.term(term)), result);
                }
            } else {
                for (final String term : stringTerms) {
                    result = new AggregateFilter.Or(new AggregateFilter.TermIs(Term.term(term)), result);
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return "ExplodeFieldIn{" +
                    "scope=" + scope +
                    ", field='" + field + '\'' +
                    ", stringTerms=" + stringTerms +
                    ", intTerms=" + intTerms +
                    ", isIntField=" + isIntField +
                    ", withDefault=" + withDefault +
                    '}';
        }
    }

    class ExplodeMetric implements ExecutionStep {
        private final Map<String, DocMetric> perDatasetMetric;
        private final long lowerBound;
        private final long upperBound;
        private final long interval;
        private final Set<String> scope;
        private final boolean excludeGutters;
        private final boolean withDefault;
        private final boolean fromPredicate;

        public ExplodeMetric(Map<String, DocMetric> perDatasetMetric, long lowerBound, long upperBound, long interval, Set<String> scope, boolean excludeGutters, boolean withDefault, boolean fromPredicate) {
            this.perDatasetMetric = perDatasetMetric;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.interval = interval;
            this.scope = scope;
            this.excludeGutters = excludeGutters;
            this.withDefault = withDefault;
            this.fromPredicate = fromPredicate;
        }

        @Override
        public List<Command> commands() {
            final Map<String, List<String>> datasetToPushes = new HashMap<>();
            for (final String s : scope) {
                datasetToPushes.put(s, new DocMetric.PushableDocMetric(perDatasetMetric.get(s)).getPushes(s));
            }
            return Collections.<Command>singletonList(new MetricRegroup(datasetToPushes, lowerBound, upperBound, interval, excludeGutters, withDefault, fromPredicate));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "ExplodeMetric{" +
                    "perDatasetMetric=" + perDatasetMetric +
                    ", lowerBound=" + lowerBound +
                    ", upperBound=" + upperBound +
                    ", interval=" + interval +
                    ", scope=" + scope +
                    ", excludeGutters=" + excludeGutters +
                    ", withDefault=" + withDefault +
                    ", fromPredicate=" + fromPredicate +
                    '}';
        }
    }

    class ExplodeTimePeriod implements ExecutionStep {
        private final long periodMillis;
        private final Optional<String> timeField;
        private final Optional<String> timeFormat;
        private final boolean isRelative;

        public ExplodeTimePeriod(long periodMillis, Optional<String> timeField, Optional<String> timeFormat, boolean isRelative) {
            this.periodMillis = periodMillis;
            this.timeField = timeField;
            this.timeFormat = timeFormat;
            this.isRelative = isRelative;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new TimePeriodRegroup(periodMillis, timeField, timeFormat, isRelative));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "ExplodeTimePeriod{" +
                    "periodMillis=" + periodMillis +
                    ", timeField=" + timeField +
                    ", timeFormat=" + timeFormat +
                    ", isRelative=" + isRelative +
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
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.ExplodeTimeBuckets(numBuckets, timeField, timeFormat));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
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
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.ExplodeDayOfWeek());
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "ExplodeDayOfWeek{}";
        }
    }

    class ExplodeMonthOfYear implements ExecutionStep {
        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.ExplodeMonthOfYear());
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "ExplodeMonthOfYear{}";
        }
    }

    class ExplodeSessionNames implements ExecutionStep {
        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.ExplodeSessionNames());
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "ExplodeSessionNames{}";
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
            final Map<String, DocMetric.PushableDocMetric> perDatasetPushes = new HashMap<>();
            for (final String dataset : scope) {
                perDatasetPushes.put(dataset, new DocMetric.PushableDocMetric(filter.asZeroOneMetric(dataset)));
            }
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.FilterDocs(perDatasetPushes));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
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
        private final Optional<Integer> queryLimit;
        private final Optional<Long> limit;
        private final Optional<AggregateMetric> metric;

        private final Optional<Set<String>> stringTermSubset;
        private final Optional<Set<Long>> intTermSubset;

        private final List<AggregateMetric> stats;
        private final List<Optional<String>> formatStrings;

        private final boolean forceNonStreaming;

        public IterateStats(
                String field, Optional<AggregateFilter> filter, Optional<Long> limit, Optional<Integer> queryLimit,
                Optional<AggregateMetric> metric, Optional<Set<String>> stringTermSubset, Optional<Set<Long>> intTermSubset, List<AggregateMetric> stats, List<Optional<String>> formatStrings, boolean forceNonStreaming) {
            this.field = field;
            this.filter = filter;
            this.limit = limit;
            this.queryLimit = queryLimit;
            this.metric = metric;
            this.stringTermSubset = stringTermSubset;
            this.intTermSubset = intTermSubset;
            this.stats = stats;
            this.formatStrings = formatStrings;
            this.forceNonStreaming = forceNonStreaming;
        }

        @Override
        public List<Command> commands() {
            final FieldIterateOpts opts = new FieldIterateOpts();
            if (limit.isPresent() || metric.isPresent()) {
                opts.topK = Optional.of(new TopK(limit, metric));
            }
            opts.limit = queryLimit;
            opts.filter = filter;
            opts.intTermSubset = intTermSubset;
            opts.stringTermSubset = stringTermSubset;
            final SimpleIterate simpleIterate = new SimpleIterate(field, opts, stats, formatStrings, !limit.isPresent() && !metric.isPresent() && !forceNonStreaming);
            return Collections.<Command>singletonList(simpleIterate);
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            final Optional<AggregateFilter> filter;
            if (this.filter.isPresent()) {
                filter = Optional.of(this.filter.get().traverse1(f));
            } else {
                filter = Optional.absent();
            }
            final Optional<AggregateMetric> metric;
            if (this.metric.isPresent()) {
                metric = Optional.of(f.apply(this.metric.get()));
            } else {
                metric = Optional.absent();
            }
            final List<AggregateMetric> stats = new ArrayList<>();
            for (final AggregateMetric stat : this.stats) {
                stats.add(f.apply(stat));
            }
            return new IterateStats(field, filter, limit, queryLimit, metric, stringTermSubset, intTermSubset, stats, formatStrings, forceNonStreaming);
        }

        @Override
        public String toString() {
            return "IterateStats{" +
                    "field='" + field + '\'' +
                    ", filter=" + filter +
                    ", queryLimit=" + queryLimit +
                    ", limit=" + limit +
                    ", metric=" + metric +
                    ", stringTermSubset=" + stringTermSubset +
                    ", intTermSubset=" + intTermSubset +
                    ", stats=" + stats +
                    ", formatStrings=" + formatStrings +
                    ", forceNonStreaming=" + forceNonStreaming +
                    '}';
        }
    }

    class GetGroupStats implements ExecutionStep {
        public final List<AggregateMetric> stats;
        public final List<Optional<String>> formatStrings;

        public GetGroupStats(List<AggregateMetric> stats, List<Optional<String>> formatStrings) {
            this.stats = stats;
            this.formatStrings = formatStrings;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.GetGroupStats(stats, formatStrings, true));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            final List<AggregateMetric> stats = new ArrayList<>();
            for (final AggregateMetric stat : this.stats) {
                stats.add(f.apply(stat));
            }
            return new GetGroupStats(stats, formatStrings);
        }

        @Override
        public String toString() {
            return "GetGroupStats{" +
                    "stats=" + stats +
                    ", formatStrings=" + formatStrings +
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
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.ExplodePerDocPercentile(field, numBuckets));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "ExplodePerDocPercentile{" +
                    "field='" + field + '\'' +
                    ", numBuckets=" + numBuckets +
                    '}';
        }
    }

    class SampleFields implements ExecutionStep {
        private final Map<String, List<DocFilter.Sample>> perDatasetSamples;

        public SampleFields(Map<String, List<DocFilter.Sample>> perDatasetSamples) {
            this.perDatasetSamples = perDatasetSamples;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new com.indeed.squall.iql2.language.commands.SampleFields(perDatasetSamples));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            // TODO: Is this the right thing to do?
            return this;
        }


        @Override
        public String toString() {
            return "SampleFields{" +
                    "perDatasetSamples=" + perDatasetSamples +
                    '}';
        }
    }

    class FilterActions implements ExecutionStep {
        private final ImmutableList<Action> actions;

        public FilterActions(List<Action> actions) {
            this.actions = ImmutableList.copyOf(actions);
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new ApplyFilterActions(actions));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "FilterActions{" +
                    "actions=" + actions +
                    '}';
        }
    }

    class FilterGroups implements ExecutionStep {
        private final AggregateFilter filter;

        public FilterGroups(AggregateFilter filter) {
            this.filter = filter;
        }

        @Override
        public List<Command> commands() {
            return Collections.<Command>singletonList(new ApplyGroupFilter(filter));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return new FilterGroups(filter.traverse1(f));
        }

        @Override
        public String toString() {
            return "FilterGroups{" +
                    "filter=" + filter +
                    '}';
        }
    }

    class ExecuteMany implements ExecutionStep {
        private final List<ExecutionStep> steps;

        public ExecuteMany(List<ExecutionStep> steps) {
            this.steps = steps;
        }

        @Override
        public List<Command> commands() {
            final List<Command> commands = new ArrayList<>();
            for (final ExecutionStep step : steps) {
                commands.addAll(step.commands());
            }
            return commands;
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            final List<ExecutionStep> newSteps = new ArrayList<>(steps.size());
            for (final ExecutionStep step : steps) {
                newSteps.add(step.traverse1(f));
            }
            return new ExecuteMany(newSteps);
        }

        @Override
        public String toString() {
            return "ExecuteMany{" +
                    "steps=" + steps +
                    '}';
        }
    }

    class ExplodeRandom implements ExecutionStep {
        private final String field;
        private final int k;
        private final String salt;

        public ExplodeRandom(String field, int k, String salt) {
            this.field = field;
            this.k = k;
            this.salt = salt;
        }

        @Override
        public List<Command> commands() {
            return Collections.singletonList(new com.indeed.squall.iql2.language.commands.ExplodeRandom(field, k, salt));
        }

        @Override
        public ExecutionStep traverse1(Function<AggregateMetric, AggregateMetric> f) {
            return this;
        }

        @Override
        public String toString() {
            return "ExplodeRandom{" +
                    "field='" + field + '\'' +
                    ", k=" + k +
                    ", salt='" + salt + '\'' +
                    '}';
        }
    }
}