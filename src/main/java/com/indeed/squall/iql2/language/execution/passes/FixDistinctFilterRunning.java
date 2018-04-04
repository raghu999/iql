package com.indeed.squall.iql2.language.execution.passes;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.indeed.squall.iql2.language.AggregateFilter;
import com.indeed.squall.iql2.language.AggregateMetric;
import com.indeed.squall.iql2.language.execution.ExecutionStep;

import java.util.ArrayList;
import java.util.List;

public class FixDistinctFilterRunning {

    public static final Function<AggregateMetric, AggregateMetric> DECREMENT_RUNNING = new Function<AggregateMetric, AggregateMetric>() {
        @Override
        public AggregateMetric apply(AggregateMetric input) {
            if (input instanceof AggregateMetric.Running) {
                final AggregateMetric.Running running = (AggregateMetric.Running) input;
                return new AggregateMetric.Running(running.offset - 1, this.apply(running.metric));
            } else {
                return input.traverse1(this);
            }
        }
    };

    public static List<ExecutionStep> apply(List<ExecutionStep> steps) {
        final List<ExecutionStep> results = new ArrayList<>();
        for (final ExecutionStep step : steps) {
            // TODO: Does this need to apply for FIELD_MAX, FIELD_MIN, etc..?
            // TODO: Replace this entire thing with dependency graph computations
            results.add(step.traverse1(new Function<AggregateMetric, AggregateMetric>() {
                @Override
                public AggregateMetric apply(AggregateMetric input) {
                    if (input instanceof AggregateMetric.Distinct) {
                        final AggregateMetric.Distinct distinct = (AggregateMetric.Distinct) input;
                        final Optional<AggregateFilter> filter;
                        if (distinct.filter.isPresent()) {
                            filter = Optional.of(distinct.filter.get().traverse1(DECREMENT_RUNNING));
                        } else {
                            filter = Optional.absent();
                        }
                        return new AggregateMetric.Distinct(distinct.field, filter, distinct.windowSize);
                    } else {
                        return input.traverse1(this);
                    }
                }
            }));
        }
        return results;
    }
}