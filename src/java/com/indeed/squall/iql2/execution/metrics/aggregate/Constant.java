package com.indeed.squall.iql2.execution.metrics.aggregate;

import com.indeed.squall.iql2.execution.QualifiedPush;
import com.indeed.squall.iql2.execution.groupkeys.GroupKeySet;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class Constant implements AggregateMetric {
    private final double value;

    public Constant(double value) {
        this.value = value;
    }

    @Override
    public Set<QualifiedPush> requires() {
        return Collections.emptySet();
    }

    @Override
    public void register(Map<QualifiedPush, Integer> metricIndexes, GroupKeySet groupKeySet) {
    }

    @Override
    public double[] getGroupStats(long[][] stats, int numGroups) {
        final double[] result = new double[numGroups + 1];
        Arrays.fill(result, value);
        return result;
    }

    @Override
    public double apply(String term, long[] stats, int group) {
        return value;
    }

    @Override
    public double apply(long term, long[] stats, int group) {
        return value;
    }
}
