package com.indeed.squall.iql2.execution.metrics.aggregate;

import com.indeed.squall.iql2.execution.QualifiedPush;
import com.indeed.squall.iql2.execution.groupkeys.sets.GroupKeySet;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

public class SumChildren implements AggregateMetric {
    private final AggregateMetric metric;

    private GroupKeySet groupKeySet;

    public SumChildren(final AggregateMetric metric) {
        this.metric = metric;
    }

    @Override
    public Set<QualifiedPush> requires() {
        return metric.requires();
    }

    @Override
    public void register(final Map<QualifiedPush, Integer> metricIndexes,
                         final GroupKeySet groupKeySet) {
        this.groupKeySet = groupKeySet;
        metric.register(metricIndexes, groupKeySet);
    }

    @Override
    public double[] getGroupStats(final long[][] stats, final int numGroups) {
        final double[] innerStats = metric.getGroupStats(stats, numGroups);
        final double[] result = new double[numGroups + 1];
        int currentParent = -1;
        int start = 1;
        double sum = 0;
        for (int i = 1; i <= numGroups; i++) {
            final int parent = groupKeySet.parentGroup(i);
            if (parent != currentParent) {
                if (start != -1) {
                    Arrays.fill(result, start, i, sum);
                }
                currentParent = parent;
                start = i;
                sum = 0;
            }
            sum += innerStats[i];
        }
        Arrays.fill(result, start, numGroups + 1, sum);
        return result;
    }

    @Override
    public double apply(final String term, final long[] stats, final int group) {
        throw new IllegalArgumentException("Cannot stream SumChildren");
    }

    @Override
    public double apply(final long term, final long[] stats, final int group) {
        throw new IllegalArgumentException("Cannot stream SumChildren");
    }

    @Override
    public boolean needGroup() {
        return false;
    }

    @Override
    public boolean needStats() {
        return false;
    }
}
