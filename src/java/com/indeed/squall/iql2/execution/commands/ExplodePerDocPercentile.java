package com.indeed.squall.iql2.execution.commands;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.indeed.imhotep.GroupMultiRemapRule;
import com.indeed.imhotep.RegroupCondition;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.squall.iql2.execution.Session;
import com.indeed.squall.iql2.execution.compat.Consumer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.lang.ArrayUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ExplodePerDocPercentile implements Command {
    public final String field;
    public final int numBuckets;

    public ExplodePerDocPercentile(String field, int numBuckets) {
        this.field = field;
        this.numBuckets = numBuckets;
    }

    @Override
    public void execute(Session session, Consumer<String> out) throws ImhotepOutOfMemoryException, IOException {
        final String field = this.field;
        final int numBuckets = this.numBuckets;

        session.timer.push("get counts");
        final long[] counts = new long[session.numGroups + 1];
        for (final Session.ImhotepSessionInfo s : session.sessions.values()) {
            s.session.pushStat("count()");
            final long[] stats = s.session.getGroupStats(0);
            for (int i = 0; i < stats.length; i++) {
                counts[i] += stats[i];
            }
        }
        session.timer.pop();

        final long[] runningCounts = new long[session.numGroups + 1];
        final long[][] cutoffs = new long[session.numGroups + 1][numBuckets];
        final int[] soFar = new int[session.numGroups + 1];
        final Map<String, IntList> metricIndexes = Maps.newHashMap();
        for (final String k : session.sessions.keySet()) {
            metricIndexes.put(k, new IntArrayList(new int[]{0}));
        }
        session.timer.push("compute cutoffs (iterateMultiInt)");
        Session.iterateMultiInt(session.getSessionsMapRaw(), metricIndexes, field, new Session.IntIterateCallback() {
            @Override
            public void term(long term, long[] stats, int group) {
                runningCounts[group] += stats[0];
                final int fraction = (int) Math.floor((double) numBuckets * runningCounts[group] / counts[group]);
                for (int i = soFar[group] + 1; i <= fraction; i++) {
                    cutoffs[group][i - 1] = term;
                    soFar[group] = i;
                }
            }
        });
        session.timer.pop();

        for (int group = 1; group <= session.numGroups; group++) {
            for (int idx = soFar[group] + 1; idx < numBuckets; idx++) {
                cutoffs[group][idx] = Integer.MAX_VALUE;
            }
        }

        session.timer.push("compute bucket remaps");
        final List<GroupMultiRemapRule> rules = Lists.newArrayList();
        final List<Session.GroupKey> nextGroupKeys = Lists.newArrayList();
        nextGroupKeys.add(null);
        for (int group = 1; group <= session.numGroups; group++) {
            final IntArrayList positiveGroups = new IntArrayList();
            final List<RegroupCondition> conditions = Lists.newArrayList();
            for (int bucket = 0; bucket < numBuckets; bucket++) {
                if (bucket > 0 && cutoffs[group][bucket] == cutoffs[group][bucket - 1]) {
                    continue;
                }
                final int end = ArrayUtils.lastIndexOf(cutoffs[group], cutoffs[group][bucket]);
                final String keyTerm = "[" + (double) bucket / numBuckets + ", " + (double) (end + 1) / numBuckets + ")";
                final int newGroup = nextGroupKeys.size();
                nextGroupKeys.add(new Session.GroupKey(keyTerm, nextGroupKeys.size(), session.groupKeys.get(group)));
                positiveGroups.add(newGroup);
                conditions.add(new RegroupCondition(field, true, cutoffs[group][bucket], null, true));
            }
            final int[] positiveGroupsArr = positiveGroups.toIntArray(new int[positiveGroups.size()]);
            final RegroupCondition[] conditionsArr = conditions.toArray(new RegroupCondition[conditions.size()]);
            rules.add(new GroupMultiRemapRule(group, 0, positiveGroupsArr, conditionsArr));
        }

        final GroupMultiRemapRule[] rulesArr = rules.toArray(new GroupMultiRemapRule[rules.size()]);
        session.timer.pop();

        // TODO: Parallelize
        for (final Session.ImhotepSessionInfo s : session.sessions.values()) {
            session.timer.push("regroup");
            s.session.regroup(rulesArr);
            session.timer.pop();

            session.timer.push("popStat");
            s.session.popStat();
            session.timer.pop();
        }

        session.numGroups = nextGroupKeys.size() - 1;
        session.groupKeys = nextGroupKeys;
        session.currentDepth += 1;

        out.accept("ExplodedPerDocPercentile");
    }
}
