package com.indeed.squall.iql2.execution.commands;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.indeed.common.util.Pair;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.ImhotepSession;
import com.indeed.squall.iql2.execution.Session;
import com.indeed.squall.iql2.execution.compat.Consumer;

import java.util.List;
import java.util.Map;

public class MetricRegroup implements Command {
    public final ImmutableMap<String, ImmutableList<String>> perDatasetMetric;
    public final long min;
    public final long max;
    public final long interval;
    public final boolean excludeGutters;

    public MetricRegroup(Map<String, List<String>> perDatasetMetric, long min, long max, long interval, boolean excludeGutters) {
        this.excludeGutters = excludeGutters;
        final ImmutableMap.Builder<String, ImmutableList<String>> copy = ImmutableMap.builder();
        for (final Map.Entry<String, List<String>> entry : perDatasetMetric.entrySet()) {
            copy.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
        }
        this.perDatasetMetric = copy.build();
        this.min = min;
        this.max = max;
        this.interval = interval;
    }

    @Override
    public void execute(final Session session, Consumer<String> out) throws ImhotepOutOfMemoryException {
        final long max = this.max;
        final long min = this.min;
        final long interval = this.interval;
        final Map<String, ? extends List<String>> perDatasetMetrics = this.perDatasetMetric;

        final int numBuckets = (excludeGutters ? 0 : 2) + (int) Math.ceil(((double) max - min) / interval);

        // TODO: Do these in parallel?
        for (final Map.Entry<String, ? extends List<String>> entry : perDatasetMetrics.entrySet()) {
            final String name = entry.getKey();
            final List<String> pushes = entry.getValue();
            final ImhotepSession s = session.sessions.get(name).session;
            session.timer.push("pushStats");
            final int numStats = s.pushStats(pushes);
            session.timer.pop();
            if (numStats != 1) {
                throw new IllegalStateException("Pushed more than one stat!: " + pushes);
            }

            session.timer.push("metricRegroup");
            s.metricRegroup(0, min, max, interval, excludeGutters);
            session.timer.pop();

            session.timer.push("popStat");
            s.popStat();
            session.timer.pop();
        }

        session.densify(new Function<Integer, Pair<String, Session.GroupKey>>() {
            public Pair<String, Session.GroupKey> apply(Integer group) {
                final int oldGroup = 1 + (group - 1) / numBuckets;
                final int innerGroup = (group - 1) % numBuckets;
                final String key;
                if (!excludeGutters && innerGroup == numBuckets - 1) {
                    key = "[" + (min + interval * (numBuckets - 2)) + ", " + Session.INFINITY_SYMBOL + ")";
                } else if (!excludeGutters && innerGroup == numBuckets - 2) {
                    key = "[-" + Session.INFINITY_SYMBOL + ", " + min + ")";
                } else if (interval == 1) {
                    key = String.valueOf(min + innerGroup);
                } else {
                    final long minInclusive = min + innerGroup * interval;
                    final long maxExclusive = min + (innerGroup + 1) * interval;
                    key = "[" + minInclusive + ", " + maxExclusive + ")";
                }
                return Pair.of(key, session.groupKeys.get(oldGroup));
            }
        });

        session.currentDepth += 1;

        out.accept("success");
    }
}
