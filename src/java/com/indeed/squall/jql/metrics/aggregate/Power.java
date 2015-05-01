package com.indeed.squall.jql.metrics.aggregate;

import com.google.common.collect.Sets;
import com.indeed.squall.jql.QualifiedPush;
import com.indeed.squall.jql.Session;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class Power implements AggregateMetric {
    public final AggregateMetric m1;
    public final AggregateMetric m2;

    public Power(AggregateMetric m1, AggregateMetric m2) {
        this.m1 = m1;
        this.m2 = m2;
    }

    @Override
    public Set<QualifiedPush> requires() {
        return Sets.union(m1.requires(), m2.requires());
    }

    @Override
    public void register(Map<QualifiedPush, Integer> metricIndexes, List<Session.GroupKey> groupKeys) {
        m1.register(metricIndexes, groupKeys);
        m2.register(metricIndexes, groupKeys);
    }

    @Override
    public double apply(String term, long[] stats, int group) {
        return Math.pow(m1.apply(term, stats, group), m2.apply(term, stats, group));
    }

    @Override
    public double apply(long term, long[] stats, int group) {
        return Math.pow(m1.apply(term, stats, group), m2.apply(term, stats, group));
    }
}
