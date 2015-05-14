package com.indeed.squall.jql.metrics.aggregate;

import com.indeed.squall.jql.QualifiedPush;
import com.indeed.squall.jql.Session;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class Abs implements AggregateMetric {
    private final AggregateMetric value;

    public Abs(AggregateMetric value) {
        this.value = value;
    }

    @Override
    public Set<QualifiedPush> requires() {
        return value.requires();
    }

    @Override
    public void register(Map<QualifiedPush, Integer> metricIndexes, List<Session.GroupKey> groupKeys) {
        value.register(metricIndexes, groupKeys);
    }

    @Override
    public double[] getGroupStats(long[][] stats, int numGroups) {
        final double[] result = value.getGroupStats(stats, numGroups);
        for (int i = 0; i < result.length; i++) {
            result[i] = Math.abs(result[i]);
        }
        return result;
    }

    @Override
    public double apply(String term, long[] stats, int group) {
        return Math.abs(value.apply(term, stats, group));
    }

    @Override
    public double apply(long term, long[] stats, int group) {
        return Math.abs(value.apply(term, stats, group));
    }
}
