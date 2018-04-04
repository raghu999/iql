package com.indeed.squall.iql2.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.indeed.util.core.Pair;
import com.indeed.flamdex.query.Term;
import com.indeed.squall.iql2.execution.actions.Action;
import com.indeed.squall.iql2.execution.actions.Actions;
import com.indeed.squall.iql2.execution.commands.ApplyFilterActions;
import com.indeed.squall.iql2.execution.commands.ApplyGroupFilter;
import com.indeed.squall.iql2.execution.commands.Command;
import com.indeed.squall.iql2.execution.commands.ComputeAndCreateGroupStatsLookup;
import com.indeed.squall.iql2.execution.commands.ComputeAndCreateGroupStatsLookups;
import com.indeed.squall.iql2.execution.commands.ComputeBootstrap;
import com.indeed.squall.iql2.execution.commands.CreateGroupStatsLookup;
import com.indeed.squall.iql2.execution.commands.ExplodeByAggregatePercentile;
import com.indeed.squall.iql2.execution.commands.ExplodeDayOfWeek;
import com.indeed.squall.iql2.execution.commands.ExplodeMonthOfYear;
import com.indeed.squall.iql2.execution.commands.ExplodePerDocPercentile;
import com.indeed.squall.iql2.execution.commands.ExplodePerGroup;
import com.indeed.squall.iql2.execution.commands.ExplodeRandom;
import com.indeed.squall.iql2.execution.commands.ExplodeSessionNames;
import com.indeed.squall.iql2.execution.commands.ExplodeTimeBuckets;
import com.indeed.squall.iql2.execution.commands.FilterDocs;
import com.indeed.squall.iql2.execution.commands.GetFieldMax;
import com.indeed.squall.iql2.execution.commands.GetFieldMin;
import com.indeed.squall.iql2.execution.commands.GetGroupDistincts;
import com.indeed.squall.iql2.execution.commands.GetGroupPercentiles;
import com.indeed.squall.iql2.execution.commands.GetGroupStats;
import com.indeed.squall.iql2.execution.commands.GetNumGroups;
import com.indeed.squall.iql2.execution.commands.IntRegroupFieldIn;
import com.indeed.squall.iql2.execution.commands.IterateAndExplode;
import com.indeed.squall.iql2.execution.commands.MetricRegroup;
import com.indeed.squall.iql2.execution.commands.RegroupIntoLastSiblingWhere;
import com.indeed.squall.iql2.execution.commands.RegroupIntoParent;
import com.indeed.squall.iql2.execution.commands.SampleFields;
import com.indeed.squall.iql2.execution.commands.SimpleIterate;
import com.indeed.squall.iql2.execution.commands.StringRegroupFieldIn;
import com.indeed.squall.iql2.execution.commands.SumAcross;
import com.indeed.squall.iql2.execution.commands.TimePeriodRegroup;
import com.indeed.squall.iql2.execution.commands.misc.FieldIterateOpts;
import com.indeed.squall.iql2.execution.groupkeys.sets.GroupKeySet;
import com.indeed.squall.iql2.execution.metrics.aggregate.AggregateMetric;
import com.indeed.squall.iql2.execution.metrics.aggregate.AggregateMetrics;
import com.indeed.squall.iql2.execution.metrics.aggregate.PerGroupConstant;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.apache.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author jwolfe
 */
public class Commands {
    private static final Logger log = Logger.getLogger(Commands.class);

    public static Command parseCommand(JsonNode command, Function<String, PerGroupConstant> namedMetricLookup, GroupKeySet groupKeySet) {
        switch (command.get("command").textValue()) {
            case "simpleIterate": {
                final List<AggregateMetric> selecting = Lists.newArrayList();
                final JsonNode selects = command.get("selects");
                for (int i = 0; i < selects.size(); i++) {
                    selecting.add(AggregateMetrics.fromJson(selects.get(i), namedMetricLookup, groupKeySet));
                }
                final String field = command.get("field").textValue();
                final FieldIterateOpts opts = new FieldIterateOpts();
                opts.parseFrom(command.get("opts"), namedMetricLookup, groupKeySet, true);
                final boolean streamResult = command.get("streamResult").booleanValue();
                final List<Optional<String>> formatStrings = readFormatStrings(command, selecting.size());
                return new SimpleIterate(field, opts, selecting, formatStrings, streamResult, null);
            }
            case "filterDocs": {
                final Map<String, List<String>> perDatasetFilterMetric = Maps.newHashMap();
                final JsonNode filters = command.get("perDatasetFilter");
                final Iterator<String> filterNamesIterator = filters.fieldNames();
                while (filterNamesIterator.hasNext()) {
                    final String filterName = filterNamesIterator.next();
                    final List<String> pushes = readStringList(filters.get(filterName));
                    perDatasetFilterMetric.put(filterName, pushes);
                }
                return new FilterDocs(perDatasetFilterMetric);
            }
            case "getGroupStats": {
                final List<AggregateMetric> metrics = Lists.newArrayListWithCapacity(command.get("metrics").size());
                for (final JsonNode metric : command.get("metrics")) {
                    metrics.add(AggregateMetrics.fromJson(metric, namedMetricLookup, groupKeySet));
                }
                boolean returnGroupKeys = false;
                for (final JsonNode opt : command.get("opts")) {
                    switch (opt.get("type").textValue()) {
                        case "returnGroupKeys": {
                            returnGroupKeys = true;
                            break;
                        }
                    }
                }
                final List<Optional<String>> formatStrings = readFormatStrings(command, metrics.size());
                return new GetGroupStats(metrics, formatStrings, returnGroupKeys);
            }
            case "createGroupStatsLookup": {
                final JsonNode valuesNode = command.get("values");
                final double[] stats = new double[valuesNode.size() + 1];
                for (int i = 0; i < valuesNode.size(); i++) {
                    stats[i + 1] = valuesNode.get(i).doubleValue();
                }
                final Optional<String> name = getOptionalName(command);
                return new CreateGroupStatsLookup(stats, name);
            }
            case "computeBootstrap": {
                final Set<String> scope = parseScope(command);
                final String field = command.get("field").textValue();
                final String seed = command.get("seed").textValue();
                final AggregateMetric metric = AggregateMetrics.fromJson(command.get("metric"), namedMetricLookup, groupKeySet);
                final Optional<AggregateFilter> filter;
                if (command.get("filter").isNull()) {
                    filter = Optional.absent();
                } else {
                    filter = Optional.of(AggregateFilters.fromJson(command.get("filter"), namedMetricLookup, groupKeySet));
                }
                final int numBootstraps = command.get("numBootstraps").intValue();
                final List<String> varargs = readStringList(command.get("varargs"));
                return new ComputeBootstrap(scope, field, filter, seed, metric, numBootstraps, varargs);
            }
            case "getGroupDistincts": {
                final Set<String> scope = parseScope(command);
                final String field = command.get("field").textValue();
                final JsonNode filterNode = command.get("filter");
                final int windowSize = command.get("windowSize").intValue();

                final Optional<AggregateFilter> filter;
                if (filterNode.isNull()) {
                    filter = Optional.absent();
                } else {
                    filter = Optional.of(AggregateFilters.fromJson(filterNode, namedMetricLookup, groupKeySet));
                }
                return new GetGroupDistincts(scope, field, filter, windowSize);
            }
            case "getGroupPercentiles": {
                final String field = command.get("field").textValue();
                final Set<String> scope = parseScope(command);
                final JsonNode percentilesNode = command.get("percentiles");
                final double[] percentiles = new double[percentilesNode.size()];
                for (int i = 0; i < percentilesNode.size(); i++) {
                    percentiles[i] = percentilesNode.get(i).doubleValue();
                }
                return new GetGroupPercentiles(scope, field, percentiles);
            }
            case "metricRegroup": {
                final Map<String, List<String>> perDatasetMetric = Maps.newHashMap();
                final JsonNode metrics = command.get("perDatasetMetric");
                final Iterator<String> metricNameIterator = metrics.fieldNames();
                while (metricNameIterator.hasNext()) {
                    final String metricName = metricNameIterator.next();
                    final List<String> pushes = Lists.newArrayList();
                    final JsonNode metricList = metrics.get(metricName);
                    for (int i = 0; i < metricList.size(); i++) {
                        pushes.add(metricList.get(i).textValue());
                    }
                    perDatasetMetric.put(metricName, pushes);
                }
                boolean excludeGutters = false;
                if (command.has("opts")) {
                    for (final JsonNode opt : command.get("opts")) {
                        switch (opt.get("type").textValue()) {
                            case "excludeGutters": {
                                excludeGutters = true;
                                break;
                            }
                        }
                    }
                }
                return new MetricRegroup(
                        perDatasetMetric,
                        command.get("min").longValue(),
                        command.get("max").longValue(),
                        command.get("interval").longValue(),
                        excludeGutters,
                        command.get("withDefault").booleanValue(),
                        command.get("fromPredicate").booleanValue()
                );
            }
            case "getNumGroups": {
                return new GetNumGroups();
            }
            case "explodePerGroup": {
                final JsonNode fieldTermOpts = command.get("fieldTermOpts");
                final List<TermsWithExplodeOpts> termsWithExplodeOpts = Lists.newArrayListWithCapacity(fieldTermOpts.size() + 1);
                termsWithExplodeOpts.add(null);
                for (int i = 0; i < fieldTermOpts.size(); i++) {
                    final JsonNode pairNode = fieldTermOpts.get(i);
                    final JsonNode fieldTermsNode = pairNode.get(0);
                    final List<Term> terms = Lists.newArrayListWithCapacity(fieldTermsNode.size());
                    for (int j = 0; j < fieldTermsNode.size(); j++) {
                        final JsonNode fieldTermNode = fieldTermsNode.get(j);
                        final String field = fieldTermNode.get(0).textValue();
                        final JsonNode termNode = fieldTermNode.get(1);
                        final Term term;
                        if (termNode.get("type").textValue().equals("string")) {
                            term = Term.stringTerm(field, termNode.get("value").textValue());
                        } else {
                            term = Term.intTerm(field, termNode.get("value").longValue());
                        }
                        terms.add(term);
                    }
                    final JsonNode explodeOpts = pairNode.get(1);
                    final Optional<String> defaultName = parseExplodeOpts(explodeOpts);
                    termsWithExplodeOpts.add(new TermsWithExplodeOpts(terms, defaultName));
                }
                return new ExplodePerGroup(termsWithExplodeOpts);
            }
            case "explodeDayOfWeek": {
                return new ExplodeDayOfWeek();
            }
            case "explodeSessionNames": {
                return new ExplodeSessionNames();
            }
            case "iterateAndExplode": {
                final String field = command.get("field").textValue();
                final Optional<String> explodeDefaultName = parseExplodeOpts(command.get("explodeOpts"));

                final List<AggregateMetric> selecting = Lists.newArrayList();
                final FieldIterateOpts fieldOpts = new FieldIterateOpts();
                parseIterateOpts(namedMetricLookup, selecting, fieldOpts, command.get("iterOpts"), groupKeySet);

                return new IterateAndExplode(field, selecting, fieldOpts, explodeDefaultName, null);
            }
            case "computeAndCreateGroupStatsLookup": {
                final Command computation = parseCommand(command.get("computation"), namedMetricLookup, groupKeySet);
                validatePrecomputedCommand(computation);
                return new ComputeAndCreateGroupStatsLookup(computation, getOptionalName(command));
            }
            case "computeAndCreateGroupStatsLookups": {
                final List<Pair<Command, String>> namedComputations = Lists.newArrayList();
                for (final JsonNode namedComputation : command.get("computations")) {
                    final Command computation = parseCommand(namedComputation.get(0), namedMetricLookup, groupKeySet);
                    validatePrecomputedCommand(computation);
                    final String name = namedComputation.get(1).textValue();
                    namedComputations.add(Pair.of(computation, name));
                }
                return new ComputeAndCreateGroupStatsLookups(namedComputations);
            }
            case "explodeByAggregatePercentile": {
                final String field = command.get("field").textValue();
                final AggregateMetric metric = AggregateMetrics.fromJson(command.get("metric"), namedMetricLookup, groupKeySet);
                final int numBuckets = command.get("numBuckets").intValue();
                return new ExplodeByAggregatePercentile(field, metric, numBuckets);
            }
            case "explodePerDocPercentile": {
                final String field = command.get("field").textValue();
                final int numBuckets = command.get("numBuckets").intValue();
                return new ExplodePerDocPercentile(field, numBuckets);
            }
            case "sumAcross": {
                final Set<String> scope = parseScope(command);
                final String field = command.get("field").textValue();
                final AggregateMetric metric = AggregateMetrics.fromJson(command.get("metric"), namedMetricLookup, groupKeySet);
                final Optional<AggregateFilter> filter;
                if (command.get("filter").isNull()) {
                    filter = Optional.absent();
                } else {
                    filter = Optional.of(AggregateFilters.fromJson(command.get("filter"), namedMetricLookup, groupKeySet));
                }
                return new SumAcross(scope, field, metric, filter);
            }
            case "regroupIntoParent": {
                return new RegroupIntoParent(GroupLookupMergeType.parseJson(command.get("mergeType")));
            }
            case "regroupIntoLastSiblingWhere": {
                final AggregateFilter filter = AggregateFilters.fromJson(command.get("filter"), namedMetricLookup, groupKeySet);
                final GroupLookupMergeType mergeType = GroupLookupMergeType.parseJson(command.get("mergeType"));
                return new RegroupIntoLastSiblingWhere(filter, mergeType);
            }
            case "explodeMonthOfYear": {
                return new ExplodeMonthOfYear();
            }
            case "explodeTimeBuckets": {
                final int numBuckets = command.get("numBuckets").intValue();
                return new ExplodeTimeBuckets(
                        numBuckets,
                        Optional.fromNullable(command.get("timeField").textValue()),
                        Optional.fromNullable(command.get("timeFormat").textValue())
                );
            }
            case "timePeriodRegroup": {
                return new TimePeriodRegroup(
                        command.get("periodMillis").longValue(),
                        Optional.fromNullable(command.get("timeField").textValue()),
                        Optional.fromNullable(command.get("timeFormat").textValue()),
                        command.get("isRelative").booleanValue()
                );
            }
            case "sampleFields": {
                final JsonNode perDatasetSamples = command.get("perDatasetSamples");
                final Iterator<String> it = perDatasetSamples.fieldNames();
                final Map<String, List<SampleFields.SampleDefinition>> perDatasetDefinitions = Maps.newHashMap();
                while (it.hasNext()) {
                    final String dataset = it.next();
                    final JsonNode list = perDatasetSamples.get(dataset);
                    final List<SampleFields.SampleDefinition> definitions = Lists.newArrayListWithCapacity(list.size());
                    for (int i = 0; i < list.size(); i++) {
                        final JsonNode elem = list.get(i);
                        final String field = elem.get("field").textValue();
                        final double fraction = elem.get("fraction").doubleValue();
                        final String seed = elem.get("seed").textValue();
                        definitions.add(new SampleFields.SampleDefinition(field, fraction, seed));
                    }
                    perDatasetDefinitions.put(dataset, definitions);
                }
                return new SampleFields(perDatasetDefinitions);
            }
            case "applyFilterActions": {
                final List<Action> actions = new ArrayList<>();
                for (final JsonNode action : command.get("actions")) {
                    actions.add(Actions.parseFrom(action));
                }
                return new ApplyFilterActions(actions);
            }
            case "computeFieldMax": {
                return new GetFieldMax(parseScope(command), command.get("field").textValue());
            }
            case "computeFieldMin": {
                return new GetFieldMin(parseScope(command), command.get("field").textValue());
            }
            case "applyGroupFilter": {
                final AggregateFilter filter = AggregateFilters.fromJson(command.get("filter"), namedMetricLookup, groupKeySet);
                return new ApplyGroupFilter(filter);
            }
            case "regroupFieldIn": {
                final String field = command.get("field").textValue();
                final boolean withDefault = command.get("withDefault").booleanValue();
                if (command.get("isIntField").booleanValue()) {
                    final JsonNode termList = command.get("intTerms");
                    final LongList intTerms = new LongArrayList(termList.size());
                    for (int i = 0; i < termList.size(); i++) {
                        intTerms.add(termList.get(i).longValue());
                    }
                    return new IntRegroupFieldIn(field, intTerms, withDefault);
                } else {
                    final JsonNode termList = command.get("stringTerms");
                    final List<String> stringTerms = new ArrayList<>();
                    for (int i = 0; i < termList.size(); i++) {
                        stringTerms.add(termList.get(i).textValue());
                    }
                    return new StringRegroupFieldIn(field, stringTerms, withDefault);
                }
            }
            case "explodeRandom": {
                final String field = command.get("field").textValue();
                final int k = command.get("k").intValue();
                final String salt = command.get("salt").textValue();
                return new ExplodeRandom(field, k, salt);
            }
        }
        throw new RuntimeException("oops:" + command);
    }

    @Nonnull
    private static List<String> readStringList(JsonNode node) {
        final List<String> pushes = Lists.newArrayList();
        for (int i = 0; i < node.size(); i++) {
            pushes.add(node.get(i).textValue());
        }
        return pushes;
    }

    @Nonnull
    private static List<Optional<String>> readFormatStrings(JsonNode command, int numMetrics) {
        final List<Optional<String>> formatStrings;
        if (command.has("formatStrings")) {
            final JsonNode strings = command.get("formatStrings");
            formatStrings = new ArrayList<>(strings.size());
            for (int i = 0; i < strings.size(); i++) {
                final JsonNode string = strings.get(i);
                if (string.isNull()) {
                    formatStrings.add(Optional.<String>absent());
                } else {
                    formatStrings.add(Optional.of(string.textValue()));
                }
            }
        } else {
            formatStrings = Collections.nCopies(numMetrics, Optional.<String>absent());
        }
        return formatStrings;
    }

    public static Set<String> parseScope(JsonNode command) {
        final Set<String> scope = Sets.newHashSet();
        for (final JsonNode node : command.get("scope")) {
            scope.add(node.textValue());
        }
        return scope;
    }

    private static void validatePrecomputedCommand(Object computation) {
        if (computation instanceof GetGroupDistincts || computation instanceof SumAcross || computation instanceof GetFieldMin || computation instanceof GetFieldMax || computation instanceof ComputeBootstrap) {
        } else if (computation instanceof GetGroupPercentiles) {
            final GetGroupPercentiles getGroupPercentiles = (GetGroupPercentiles) computation;
            if (getGroupPercentiles.percentiles.length != 1) {
                throw new IllegalArgumentException("Cannot handle multi-percentile GetGroupPercentiles in ComputeAndCreateGroupStatsLookup");
            }
        } else if (computation instanceof GetGroupStats) {
            final GetGroupStats getGroupStats = (GetGroupStats) computation;
            if (getGroupStats.metrics.size() != 1) {
                throw new IllegalArgumentException("Cannot handle multiple metrics in ComputeAndCreateGroupStatsLookup");
            }
        } else {
            throw new IllegalArgumentException("Can only do group distincts, sum across, get field min, get field max, group percentiles, or group stats!");
        }
    }

    public static Optional<String> getOptionalName(JsonNode command) {
        final Optional<String> name;
        if (command.has("name")) {
            name = Optional.fromNullable(command.get("name").textValue());
        } else {
            name = Optional.absent();
        }
        return name;
    }

    public static void parseIterateOpts(Function<String, PerGroupConstant> namedMetricLookup, List<AggregateMetric> selecting, FieldIterateOpts defaultOpts, JsonNode globalOpts, GroupKeySet groupKeySet) {
        for (final JsonNode globalOpt : globalOpts) {
            switch (globalOpt.get("type").textValue()) {
                case "selecting": {
                    for (final JsonNode metric : globalOpt.get("metrics")) {
                        selecting.add(AggregateMetrics.fromJson(metric, namedMetricLookup, groupKeySet));
                    }
                    break;
                }
                case "defaultedFieldOpts": {
                    defaultOpts.parseFrom(globalOpt.get("opts"), namedMetricLookup, groupKeySet, false);
                    break;
                }
            }
        }
    }

    public static Optional<String> parseExplodeOpts(JsonNode opts) {
        if (opts == null) {
            return Optional.absent();
        }
        Optional<String> defaultName = Optional.absent();
        for (final JsonNode opt : opts) {
            switch (opt.get("type").textValue()) {
                case "addDefault":
                    defaultName = Optional.of(opt.get("name").textValue());
                    break;
            }
        }
        return defaultName;
    }

    public static class TermsWithExplodeOpts {
        public final List<Term> terms;
        public final Optional<String> defaultName;

        public TermsWithExplodeOpts(List<Term> terms, Optional<String> defaultName) {
            this.terms = terms;
            this.defaultName = defaultName;
        }
    }

}