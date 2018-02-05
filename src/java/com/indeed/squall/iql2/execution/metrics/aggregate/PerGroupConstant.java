package com.indeed.squall.iql2.execution.metrics.aggregate;

import com.indeed.squall.iql2.execution.QualifiedPush;
import com.indeed.squall.iql2.execution.groupkeys.sets.GroupKeySet;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class PerGroupConstant implements AggregateMetric {
    final double[] values;

    public PerGroupConstant(final double[] values) {
        this.values = values;
    }

    @Override
    public Set<QualifiedPush> requires() {
        return Collections.emptySet();
    }

    @Override
    public void register(final Map<QualifiedPush, Integer> metricIndexes, final GroupKeySet groupKeySet) {
    }

    @Override
    public double[] getGroupStats(final long[][] stats, final int numGroups) {
        return Arrays.copyOf(values, numGroups + 1);
    }

    @Override
    public double apply(final String term, final long[] stats, final int group) {
        return values[group];
    }

    @Override
    public double apply(final long term, final long[] stats, final int group) {
        return values[group];
    }

    @Override
    public boolean needGroup() {
        return true;
    }

    @Override
    public boolean needStats() {
        return false;
    }
}
