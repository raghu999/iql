package com.indeed.squall.iql2.language.execution.passes;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.indeed.squall.iql2.language.AggregateFilter;
import com.indeed.squall.iql2.language.AggregateFilters;
import com.indeed.squall.iql2.language.AggregateMetric;
import com.indeed.squall.iql2.language.DocFilter;
import com.indeed.squall.iql2.language.DocMetric;
import com.indeed.squall.iql2.language.execution.ExecutionStep;
import com.indeed.squall.iql2.language.query.GroupBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class OptimizeLast {
    public static List<ExecutionStep> optimize(List<ExecutionStep> steps) {
        steps = Collections.unmodifiableList(steps);
        if (steps.size() > 1) {
            final ExecutionStep last = steps.get(steps.size() - 1);
            final ExecutionStep penultimate = steps.get(steps.size() - 2);
            if (last instanceof ExecutionStep.GetGroupStats
                    && penultimate instanceof ExecutionStep.ExplodeAndRegroup) {
                final ExecutionStep.GetGroupStats getGroupStats = (ExecutionStep.GetGroupStats) last;
                final boolean selectIsOrdered = Iterables.any(getGroupStats.stats, new Predicate<AggregateMetric>() {
                    @Override
                    public boolean apply(AggregateMetric input) {
                        return input.isOrdered();
                    }
                });
                final ExecutionStep.ExplodeAndRegroup explodeAndRegroup = (ExecutionStep.ExplodeAndRegroup) penultimate;
                // TODO: Make query execution sort on .metric whether or not there's a limit, make .metric optional. Then change this to care if .metric.isPresent() also.
                // TODO: Figure out wtf the above TODO means.
                final boolean isReordered = explodeAndRegroup.limit.isPresent();
                final boolean hasDefault = explodeAndRegroup.withDefault;
                if (!selectIsOrdered && !hasDefault) { // If there's a filter and something that depends on order, we can't merge them.
                    final List<ExecutionStep> newSteps = new ArrayList<>();
                    newSteps.addAll(steps);
                    newSteps.remove(newSteps.size() - 1);
                    newSteps.remove(newSteps.size() - 1);
                    newSteps.add(new ExecutionStep.IterateStats(
                            explodeAndRegroup.field,
                            explodeAndRegroup.filter,
                            explodeAndRegroup.limit,
                            explodeAndRegroup.metric,
                            fixForIteration(getGroupStats.stats),
                            getGroupStats.formatStrings,
                            explodeAndRegroup.forceNonStreaming || isReordered
                    ));
                    return newSteps;
                }
            } else if (last instanceof ExecutionStep.GetGroupStats && penultimate instanceof ExecutionStep.ExplodeFieldIn) {
                final ExecutionStep.ExplodeFieldIn explodeFieldIn = (ExecutionStep.ExplodeFieldIn) penultimate;
                final ExecutionStep.GetGroupStats getGroupStats = (ExecutionStep.GetGroupStats) last;
                final List<ExecutionStep> newSteps = new ArrayList<>();
                newSteps.addAll(steps.subList(0, steps.size() - 2));
                newSteps.add(new ExecutionStep.IterateStats(
                        explodeFieldIn.field,
                        Optional.of(explodeFieldIn.termsAsFilter()),
                        Optional.<Long>absent(),
                        Optional.<AggregateMetric>absent(),
                        fixForIteration(getGroupStats.stats),
                        getGroupStats.formatStrings,
                        false
                ));
                return newSteps;
            }
        }
        return steps;
    }

    private static List<AggregateMetric> fixForIteration(List<AggregateMetric> stats) {
        final List<AggregateMetric> result = new ArrayList<>();
        for (final AggregateMetric stat : stats) {
            result.add(stat.transform(
                    PROCESS_METRIC,
                    Functions.<DocMetric>identity(),
                    Functions.<AggregateFilter>identity(),
                    Functions.<DocFilter>identity(),
                    Functions.<GroupBy>identity()
            ));
        }
        return result;
    }

    private static final Function<AggregateMetric,AggregateMetric> PROCESS_METRIC = new Function<AggregateMetric, AggregateMetric>() {
        @Override
        public AggregateMetric apply(AggregateMetric input) {
            if (input instanceof AggregateMetric.Running) {
                final AggregateMetric.Running running = (AggregateMetric.Running) input;
                return new AggregateMetric.Running(running.offset - 1, running.metric);
            } else if (input instanceof AggregateMetric.Lag) {
                final AggregateMetric.Lag lag = (AggregateMetric.Lag) input;
                return new AggregateMetric.IterateLag(lag.lag, lag.metric);
            } else {
                return input;
            }
        }
    };
}
