package com.indeed.squall.iql2.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.Closer;
import com.google.common.math.DoubleMath;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.indeed.common.util.Pair;
import com.indeed.flamdex.query.Query;
import com.indeed.imhotep.DatasetInfo;
import com.indeed.imhotep.GroupRemapRule;
import com.indeed.imhotep.QueryRemapRule;
import com.indeed.imhotep.RegroupCondition;
import com.indeed.imhotep.ShardInfo;
import com.indeed.imhotep.api.FTGSIterator;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.ImhotepSession;
import com.indeed.imhotep.client.Host;
import com.indeed.imhotep.client.ImhotepClient;
import com.indeed.imhotep.client.ShardIdWithVersion;
import com.indeed.squall.iql2.execution.commands.SimpleIterate;
import com.indeed.squall.iql2.execution.compat.Consumer;
import com.indeed.squall.iql2.execution.dimensions.DimensionDetails;
import com.indeed.squall.iql2.execution.metrics.aggregate.AggregateMetric;
import com.indeed.squall.iql2.execution.commands.Command;
import com.indeed.squall.iql2.execution.commands.GetGroupStats;
import com.indeed.squall.iql2.execution.dimensions.DatasetDimensions;
import com.indeed.squall.iql2.execution.dimensions.DimensionsTranslator;
import com.indeed.squall.iql2.execution.metrics.aggregate.PerGroupConstant;
import com.indeed.util.core.TreeTimer;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleCollection;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author jwolfe
 */
public class Session {

    static {
        DateTimeZone.setDefault(DateTimeZone.forOffsetHours(-6));
    }

    private static final Logger log = Logger.getLogger(Session.class);

    public List<GroupKey> groupKeys = Lists.newArrayList(null, new GroupKey(null, 1, null));
    public final Map<String, SavedGroupStats> savedGroupStats = Maps.newHashMap();
    public int currentDepth = 0;

    public final Map<String, ImhotepSessionInfo> sessions;
    public final TreeTimer timer;
    public int numGroups = 1;

    public static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        final SimpleModule module = new SimpleModule();
        module.addSerializer(TermSelects.class, new JsonSerializer<TermSelects>() {
            @Override
            public void serialize(TermSelects value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
                jgen.writeStartObject();
                jgen.writeObjectField("field", value.field);
                if (value.isIntTerm) {
                    jgen.writeObjectField("intTerm", value.intTerm);
                } else {
                    jgen.writeObjectField("stringTerm", value.stringTerm);
                }
                jgen.writeObjectField("selects", value.selects);
                if (value.groupKey != null) {
                    jgen.writeObjectField("key", value.groupKey);
                }
                jgen.writeEndObject();
            }
        });
        module.addSerializer(GroupKey.class, new JsonSerializer<GroupKey>() {
            @Override
            public void serialize(GroupKey value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
                jgen.writeObject(value.asList(false));
            }
        });
        MAPPER.registerModule(module);
        MAPPER.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
    }

    public static final String INFINITY_SYMBOL = "∞";
    public static final Pattern SPECIAL_CHARACTERS_PATTERN = Pattern.compile("\\n|\\r|\\t");

    public Session(Map<String, ImhotepSessionInfo> sessions, TreeTimer timer) {
        this.sessions = sessions;
        this.timer = timer;
    }

    public static Optional<Session> createSession(ImhotepClient client, JsonNode sessionRequest, Closer closer, Consumer<String> out, Map<String, DatasetDimensions> dimensions, TreeTimer treeTimer) throws ImhotepOutOfMemoryException, IOException {
        final Map<String, ImhotepSessionInfo> sessions = Maps.newHashMap();
        if (sessionRequest.has("commands")) {
            treeTimer.push("createSubSessions");
            createSubSessions(client, sessionRequest.get("datasets"), closer, sessions, dimensions, treeTimer);
            treeTimer.pop();
            final Session session = new Session(sessions, treeTimer);
            treeTimer.push("readCommands");
            final JsonNode commands = sessionRequest.get("commands");
            treeTimer.pop();
            for (int i = 0; i < commands.size(); i++) {
                final JsonNode command = commands.get(i);
                System.out.println("Evaluating command: " + command);
                final boolean isLast = i == commands.size() - 1;
                if (isLast) {
                    session.evaluateCommandToTSV(command, out);
                } else {
                    session.evaluateCommand(command, new Consumer<String>() {
                        public void accept(String s) {
                        }
                    });
                }
            }
            return Optional.absent();
        } else {
            createSubSessions(client, sessionRequest, closer, sessions, dimensions, treeTimer);
            out.accept("opened");
            return Optional.of(new Session(sessions, treeTimer));
        }
    }

    public static DatasetInfo getDatasetShardList(ImhotepClient client, String dataset) {
        final Map<Host, List<DatasetInfo>> shardListMap = client.getShardList();
        final DatasetInfo ret = new DatasetInfo(dataset, new HashSet<ShardInfo>(), new HashSet<String>(), new HashSet<String>(), new HashSet<String>());
        for (final List<DatasetInfo> datasetList : shardListMap.values()) {
            for (final DatasetInfo d : datasetList) {
                if (d.getDataset().equals(dataset)) {
                    ret.getShardList().addAll(d.getShardList());
                    ret.getIntFields().addAll(d.getIntFields());
                    ret.getStringFields().addAll(d.getStringFields());
                    ret.getMetrics().addAll(d.getMetrics());
                }
            }
        }
        return ret;
    }

    private static void createSubSessions(ImhotepClient client, JsonNode sessionRequest, Closer closer, Map<String, ImhotepSessionInfo> sessions, Map<String, DatasetDimensions> dimensions, TreeTimer treeTimer) throws ImhotepOutOfMemoryException {
        for (int i = 0; i < sessionRequest.size(); i++) {
            final JsonNode elem = sessionRequest.get(i);
            final String dataset = elem.get("dataset").textValue();
            final String start = elem.get("start").textValue();
            final String end = elem.get("end").textValue();
            final String name = elem.has("name") ? elem.get("name").textValue() : dataset;

            treeTimer.push("get dataset info");
            treeTimer.push("getDatasetShardList");
            final DatasetInfo datasetInfo = getDatasetShardList(client, dataset);
            treeTimer.pop();
            final Collection<String> sessionIntFields = datasetInfo.getIntFields();
            final Collection<String> sessionStringFields = datasetInfo.getStringFields();
            final DateTime startDateTime = parseDateTime(start);
            final DateTime endDateTime = parseDateTime(end);
            treeTimer.pop();
            treeTimer.push("build session");
            treeTimer.push("create session builder");
            final ImhotepClient.SessionBuilder sessionBuilder = client.sessionBuilder(dataset, startDateTime, endDateTime);
            treeTimer.pop();
            treeTimer.push("get shards");
            final List<ShardIdWithVersion> shards = sessionBuilder.getChosenShards();
            treeTimer.pop();
            treeTimer.push("build session builder");
            final ImhotepSession build = sessionBuilder.build();
            treeTimer.pop();
            final DatasetDimensions datasetDimensions = dimensions.containsKey(dataset) ? dimensions.get(dataset) : new DatasetDimensions(ImmutableMap.<String, DimensionDetails>of());
            final ImhotepSession session = closer.register(new DimensionsTranslator(build, datasetDimensions));
            treeTimer.pop();

            treeTimer.push("determine time range");
            final DateTime earliestStart = Ordering.natural().min(Iterables.transform(shards, new Function<ShardIdWithVersion, DateTime>() {
                public DateTime apply(ShardIdWithVersion input) {
                    return input.getStart();
                }
            }));
            final DateTime latestEnd = Ordering.natural().max(Iterables.transform(shards, new Function<ShardIdWithVersion, DateTime>() {
                public DateTime apply(@Nullable ShardIdWithVersion input) {
                    return input.getEnd();
                }
            }));
            treeTimer.pop();
            final boolean isRamsesIndex = datasetInfo.getIntFields().isEmpty();
            final String timeField = isRamsesIndex ? "time" : "unixtime";
            if (earliestStart.isBefore(startDateTime) || latestEnd.isAfter(endDateTime)) {
                treeTimer.push("regroup time range");
                session.regroup(new QueryRemapRule(1, Query.newRangeQuery(timeField, startDateTime.getMillis() / 1000, endDateTime.getMillis() / 1000, false), 0, 1));
                treeTimer.pop();
            }
            sessions.put(name, new ImhotepSessionInfo(session, datasetDimensions, sessionIntFields, sessionStringFields, startDateTime, endDateTime, timeField));
        }
    }

    private static final Pattern relativePattern = Pattern.compile("(\\d+)([smhdwMy])");
    private static DateTime parseDateTime(String descriptor) {
        descriptor = descriptor.trim();
        final DateTime startOfToday = DateTime.now().withTimeAtStartOfDay();
        if (descriptor.equals("today")) {
            return startOfToday;
        } else if (descriptor.equals("yesterday")) {
            return startOfToday.minusDays(1);
        } else if (descriptor.equals("tomorrow")) {
            return startOfToday.plusDays(1);
        } else if (relativePattern.matcher(descriptor).matches()) {
            final Matcher matcher = relativePattern.matcher(descriptor);
            matcher.matches();
            final int offset = Integer.parseInt(matcher.group(1));
            final String unit = matcher.group(2);
            DateTime result = startOfToday;
            switch (unit) {
                case "s":
                    result = result.minusSeconds(offset);
                    break;
                case "m":
                    result = result.minusMinutes(offset);
                    break;
                case "h":
                    result = result.minusHours(offset);
                    break;
                case "d":
                    result = result.minusDays(offset);
                    break;
                case "w":
                    result = result.minusWeeks(offset);
                    break;
                case "M":
                    result = result.minusMonths(offset);
                    break;
                case "y":
                    result = result.minusYears(offset);
                    break;
                default:
                    throw new RuntimeException("Unrecognized unit: " + unit);
            }
            return result;
        } else {
            try {
                return DateTime.parse(descriptor.replaceAll(" ", "T"));
            } catch (final IllegalArgumentException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    public void evaluateCommand(JsonNode commandTree, Consumer<String> out) throws ImhotepOutOfMemoryException, IOException {
        timer.push("evaluateCommand " + commandTree);
        try {
            final Command command = Commands.parseCommand(commandTree, new Function<String, PerGroupConstant>() {
                public PerGroupConstant apply(String s) {
                    return namedMetricLookup(s);
                }
            });
            command.execute(this, out);
        } finally {
            timer.pop();
        }
    }

    public void evaluateCommandToTSV(JsonNode commandTree, Consumer<String> out) throws ImhotepOutOfMemoryException, IOException {
        timer.push("evaluateCommandToTSV " + commandTree);
        try {
            final Command command = Commands.parseCommand(commandTree, new Function<String, PerGroupConstant>() {
                @Override
                public PerGroupConstant apply(String s) {
                    return namedMetricLookup(s);
                }
            });
            if (command instanceof SimpleIterate) {
                final SimpleIterate simpleIterate = (SimpleIterate) command;
                final List<List<List<TermSelects>>> result = simpleIterate.evaluate(this, out);
                //noinspection StatementWithEmptyBody
                if (simpleIterate.streamResult) {
                    // result already sent
                } else {
                    for (final List<List<TermSelects>> groupFieldTerms : result) {
                        final List<TermSelects> groupTerms = groupFieldTerms.get(0);
                        for (final TermSelects termSelect : groupTerms) {
                            if (termSelect.isIntTerm) {
                                out.accept(SimpleIterate.createRow(termSelect.groupKey, termSelect.intTerm, termSelect.selects));
                            } else {
                                out.accept(SimpleIterate.createRow(termSelect.groupKey, termSelect.stringTerm, termSelect.selects));
                            }
                        }
                    }
                    out.accept("");
                }
            } else if (command instanceof GetGroupStats) {
                final GetGroupStats getGroupStats = (GetGroupStats) command;
                final List<GroupStats> results = getGroupStats.evaluate(this);
                final StringBuilder sb = new StringBuilder();
                for (final GroupStats result : results) {
                    final List<String> keyColumns = result.key.asList(false);
                    for (final String k : keyColumns) {
                        sb.append(SPECIAL_CHARACTERS_PATTERN.matcher(k).replaceAll("\uFFFD")).append('\t');
                    }
                    for (final double stat : result.stats) {
                        if (DoubleMath.isMathematicalInteger(stat)) {
                            sb.append(String.format("%.0f", stat)).append('\t');
                        } else {
                            sb.append(stat).append('\t');
                        }
                    }
                    if (keyColumns.size() + result.stats.length > 0) {
                        sb.setLength(sb.length() - 1);
                    }
                    out.accept(sb.toString());
                    sb.setLength(0);
                }
            } else {
                throw new IllegalArgumentException("Don't know how to evaluate [" + command + "] to TSV");
            }
        } finally {
            timer.pop();
        }
    }

    public static void writeTermSelectsJson(List<List<List<TermSelects>>> results, StringBuilder sb) {
        for (final List<List<TermSelects>> groupFieldTerms : results) {
            final List<TermSelects> groupTerms = groupFieldTerms.get(0);
            for (final TermSelects termSelects : groupTerms) {
                final List<String> keyColumns = termSelects.groupKey.asList(true);
                for (final String k : keyColumns) {
                    sb.append(k).append('\t');
                }
                if (termSelects.isIntTerm) {
                    sb.append(termSelects.intTerm).append('\t');
                } else {
                    sb.append(termSelects.stringTerm).append('\t');
                }
                for (final double stat : termSelects.selects) {
                    if (DoubleMath.isMathematicalInteger(stat)) {
                        sb.append((long) stat).append('\t');
                    } else {
                        sb.append(stat).append('\t');
                    }
                }
                sb.setLength(sb.length() - 1);
                sb.append('\n');
            }
        }
        sb.setLength(sb.length() - 1);
    }

    public int findPercentile(double v, double[] percentiles) {
        for (int i = 0; i < percentiles.length - 1; i++) {
            if (v <= percentiles[i + 1]) {
                return i;
            }
        }
        return percentiles.length - 1;
    }

    // Returns the start of the bucket.
    // result[0] will always be 0
    // result[1] will be the minimum value required to be greater than 1/k values.
    public static double[] getPercentiles(DoubleCollection values, int k) {
        final DoubleArrayList list = new DoubleArrayList(values);
        // TODO: Will this be super slow?
        Collections.sort(list);
        final double[] result = new double[k];
        for (int i = 0; i < k; i++) {
            result[i] = list.get((int) Math.ceil((double) list.size() * i / k));
        }
        return result;
    }

    // TODO: Any call sites of this could be optimized.
    public static double[] prependZero(double[] in) {
        final double[] out = new double[in.length + 1];
        System.arraycopy(in, 0, out, 1, in.length);
        return out;
    }

    public void registerMetrics(Map<QualifiedPush, Integer> metricIndexes, Iterable<AggregateMetric> metrics, Iterable<AggregateFilter> filters) {
        for (final AggregateMetric metric : metrics) {
            metric.register(metricIndexes, groupKeys);
        }
        for (final AggregateFilter filter : filters) {
            filter.register(metricIndexes, groupKeys);
        }
    }

    public void pushMetrics(Set<QualifiedPush> allPushes, Map<QualifiedPush, Integer> metricIndexes, Map<String, IntList> sessionMetricIndexes) throws ImhotepOutOfMemoryException {
        int numStats = 0;
        for (final QualifiedPush push : allPushes) {
            final int index = numStats++;
            metricIndexes.put(push, index);
            final String sessionName = push.sessionName;
            sessions.get(sessionName).session.pushStats(push.pushes);
            // TODO: Terrible variable names.
            IntList sessionMetricIndex = sessionMetricIndexes.get(sessionName);
            if (sessionMetricIndex == null) {
                sessionMetricIndex = new IntArrayList();
                sessionMetricIndexes.put(sessionName, sessionMetricIndex);
            }
            sessionMetricIndex.add(index);
        }
    }

    public long getLatestEnd() {
        return Ordering.natural().max(Iterables.transform(sessions.values(), new Function<ImhotepSessionInfo, Long>() {
            public Long apply(ImhotepSessionInfo input) {
                return input.endTime.getMillis();
            }
        }));
    }

    public long getEarliestStart() {
        return Ordering.natural().min(Iterables.transform(sessions.values(), new Function<ImhotepSessionInfo, Long>() {
            public Long apply(ImhotepSessionInfo input) {
                return input.startTime.getMillis();
            }
        }));
    }

    public int performTimeRegroup(long start, long end, long unitSize, final Optional<String> fieldOverride) throws ImhotepOutOfMemoryException {
        final int oldNumGroups = this.numGroups;
        for (final ImhotepSessionInfo sessionInfo : sessions.values()) {
            final ImhotepSession session = sessionInfo.session;
            final String fieldName;
            if (fieldOverride.isPresent()) {
                fieldName = fieldOverride.get();
            } else {
                fieldName = sessionInfo.timeFieldName;
            }
            session.pushStat(fieldName);
            session.metricRegroup(0, start / 1000, end / 1000, unitSize / 1000, true);
            session.popStat();
        }
        return (int) (oldNumGroups * Math.ceil(((double) end - start) / unitSize));
    }

    public void densify(Function<Integer, Pair<String, GroupKey>> indexedInfoProvider) throws ImhotepOutOfMemoryException {
        final BitSet anyPresent = new BitSet();
        for (final ImhotepSession session : getSessionsMapRaw().values()) {
            session.pushStat("count()");
            final long[] counts = session.getGroupStats(0);
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0L) {
                    anyPresent.set(i);
                }
            }
            session.popStat();
        }

        final List<GroupKey> nextGroupKeys = Lists.newArrayList((GroupKey) null);
        final List<GroupRemapRule> rules = Lists.newArrayList();
        boolean anyNonIdentity = false;
        for (int i = 0; i < anyPresent.size(); i++) {
            if (anyPresent.get(i)) {
                final int newGroup = nextGroupKeys.size();
                final Pair<String, GroupKey> p = indexedInfoProvider.apply(i);
                nextGroupKeys.add(new GroupKey(p.getFirst(), newGroup, p.getSecond()));
                rules.add(new GroupRemapRule(i, new RegroupCondition("fakeField", true, 23L, null, false), newGroup, newGroup));
                if (newGroup != i) {
                    anyNonIdentity = true;
                }
            }
        }

        if (anyNonIdentity) {
            final GroupRemapRule[] ruleArray = rules.toArray(new GroupRemapRule[rules.size()]);
            // TODO: Parallelize?
            for (final ImhotepSession session : getSessionsMapRaw().values()) {
                session.regroup(ruleArray);
            }
        }

        numGroups = nextGroupKeys.size() - 1;
        groupKeys = nextGroupKeys;
    }

    public void assumeDense(Function<Integer, Pair<String, GroupKey>> indexedInfoProvider, int newNumGroups) throws ImhotepOutOfMemoryException {
        final List<GroupKey> nextGroupKeys = Lists.newArrayList((GroupKey) null);
        final List<GroupRemapRule> rules = Lists.newArrayList();
        for (int i = 1; i <= newNumGroups; i++) {
            final int newGroup = nextGroupKeys.size();
            final Pair<String, GroupKey> p = indexedInfoProvider.apply(i);
            nextGroupKeys.add(new GroupKey(p.getFirst(), newGroup, p.getSecond()));
            rules.add(new GroupRemapRule(i, new RegroupCondition("fakeField", true, 23L, null, false), newGroup, newGroup));
        }

        numGroups = nextGroupKeys.size() - 1;
        groupKeys = nextGroupKeys;
    }

    public Map<String, ImhotepSession> getSessionsMapRaw() {
        final Map<String, ImhotepSession> sessionMap = Maps.newHashMap();
        for (final Map.Entry<String, ImhotepSessionInfo> entry : sessions.entrySet()) {
            sessionMap.put(entry.getKey(), entry.getValue().session);
        }
        return sessionMap;
    }

    private Map<String, ImhotepSessionInfo> getSessionsMap() {
        return Maps.newHashMap(sessions);
    }

    public boolean isIntField(String field) {
        for (final ImhotepSessionInfo x : sessions.values()) {
            if (x.intFields.contains(field) || x.datasetDimensions.contains(field)) {
                return true;
            }
        }
        return false;
    }

    public boolean isStringField(String field) {
        if (isIntField(field)) {
            return false;
        }
        for (final ImhotepSessionInfo x : sessions.values()) {
            if (x.stringFields.contains(field)) {
                return true;
            }
        }
        return false;
    }

    private PerGroupConstant namedMetricLookup(String name) {
        final SavedGroupStats savedStat = savedGroupStats.get(name);
        final int depthChange = currentDepth - savedStat.depth;
        final double[] stats = new double[numGroups + 1];
        for (int group = 1; group <= numGroups; group++) {
            GroupKey key = groupKeys.get(group);
            for (int i = 0; i < depthChange; i++) {
                key = key.parent;
            }
            stats[group] = savedStat.stats[key.index];
        }
        return new PerGroupConstant(stats);
    }

    public static void unchecked(RunnableWithException runnable) {
        try {
            runnable.run();
        } catch (final Throwable t) {
            log.error("unchecked error", t);
            throw Throwables.propagate(t);
        }
    }

    // TODO: Parallelize across sessions?
    public void popStats() {
        timer.push("popStats");
        for (final ImhotepSessionInfo imhotepSessionInfo : sessions.values()) {
            final ImhotepSession session = imhotepSessionInfo.session;
            final int numStats = session.getNumStats();
            for (int i = 0; i < numStats; i++) {
                session.popStat();
            }
        }
        timer.pop();
    }

    private static class SessionIntIterationState {
        public final FTGSIterator iterator;
        private final IntList metricIndexes;
        public final long[] statsBuff;
        public long nextTerm;
        public int nextGroup;

        private SessionIntIterationState(FTGSIterator iterator, IntList metricIndexes, long[] statsBuff, long nextTerm, int nextGroup) {
            this.iterator = iterator;
            this.metricIndexes = metricIndexes;
            this.statsBuff = statsBuff;
            this.nextTerm = nextTerm;
            this.nextGroup = nextGroup;
        }

        static Optional<SessionIntIterationState> construct(Closer closer, ImhotepSession session, String field, IntList sessionMetricIndexes) {
            final FTGSIterator it = closer.register(session.getFTGSIterator(new String[]{field}, new String[0]));
            final int numStats = session.getNumStats();
            final long[] statsBuff = new long[numStats];
            if (!it.nextField()) {
                return Optional.absent();
            }
            if (!it.nextTerm()) {
                return Optional.absent();
            }
            if (!it.nextGroup()) {
                return Optional.absent();
            }
            it.groupStats(statsBuff);
            return Optional.of(new SessionIntIterationState(it, sessionMetricIndexes, statsBuff, it.termIntVal(), it.group()));
        }
    }

    public interface IntIterateCallback {
        void term(long term, long[] stats, int group);
    }

    public static void iterateMultiInt(Map<String, ImhotepSession> sessions, Map<String, IntList> metricIndexes, String field, IntIterateCallback callback) throws IOException {
        int numMetrics = 0;
        for (final IntList metrics : metricIndexes.values()) {
            numMetrics += metrics.size();
        }
        try (final Closer closer = Closer.create()) {
            final Comparator<SessionIntIterationState> comparator = new Comparator<SessionIntIterationState>() {
                @Override
                public int compare(SessionIntIterationState x, SessionIntIterationState y) {
                    int r = Longs.compare(x.nextTerm, y.nextTerm);
                    if (r != 0) return r;
                    return Ints.compare(x.nextGroup, y.nextGroup);
                }
            };
            final PriorityQueue<SessionIntIterationState> pq = new PriorityQueue<>(sessions.size(), comparator);
            for (final String sessionName : sessions.keySet()) {
                final ImhotepSession session = sessions.get(sessionName);
                final IntList sessionMetricIndexes = Objects.firstNonNull(metricIndexes.get(sessionName), new IntArrayList());
                final Optional<SessionIntIterationState> constructed = SessionIntIterationState.construct(closer, session, field, sessionMetricIndexes);
                if (constructed.isPresent()) {
                    pq.add(constructed.get());
                }
            }
            final long[] realBuffer = new long[numMetrics];
            final List<SessionIntIterationState> toEnqueue = Lists.newArrayList();
            while (!pq.isEmpty()) {
                toEnqueue.clear();
                Arrays.fill(realBuffer, 0);
                final SessionIntIterationState state1 = pq.poll();
                final long term = state1.nextTerm;
                final int group = state1.nextGroup;
                copyStats(state1, realBuffer);
                toEnqueue.add(state1);
                while (!pq.isEmpty() && pq.peek().nextTerm == term && pq.peek().nextGroup == group) {
                    final SessionIntIterationState state = pq.poll();
                    copyStats(state, realBuffer);
                    toEnqueue.add(state);
                }
                callback.term(term, realBuffer, group);
                for (final SessionIntIterationState state : toEnqueue) {
                    advanceAndEnqueue(state, pq);
                }
            }
        }
    }

    private static void advanceAndEnqueue(SessionIntIterationState state, PriorityQueue<SessionIntIterationState> pq) {
        final FTGSIterator iterator = state.iterator;
        if (iterator.nextGroup()) {
            state.nextGroup = iterator.group();
            iterator.groupStats(state.statsBuff);
            pq.add(state);
        } else if (iterator.nextTerm() && iterator.nextGroup()) {
            state.nextTerm = iterator.termIntVal();
            state.nextGroup = iterator.group();
            iterator.groupStats(state.statsBuff);
            pq.add(state);
        }
    }

    private static void copyStats(SessionIntIterationState state, long[] dst) {
        for (int i = 0; i < state.metricIndexes.size(); i++) {
            dst[state.metricIndexes.getInt(i)] = state.statsBuff[i];
        }
    }

    private static class SessionStringIterationState {
        public final FTGSIterator iterator;
        private final IntList metricIndexes;
        public final long[] statsBuff;
        public String nextTerm;
        public int nextGroup;

        private SessionStringIterationState(FTGSIterator iterator, IntList metricIndexes, long[] statsBuff, String nextTerm, int nextGroup) {
            this.iterator = iterator;
            this.metricIndexes = metricIndexes;
            this.statsBuff = statsBuff;
            this.nextTerm = nextTerm;
            this.nextGroup = nextGroup;
        }

        static Optional<SessionStringIterationState> construct(Closer closer, ImhotepSession session, String field, IntList sessionMetricIndexes) {
            final FTGSIterator it = closer.register(session.getFTGSIterator(new String[0], new String[]{field}));
            final int numStats = session.getNumStats();
            final long[] statsBuff = new long[numStats];
            if (!it.nextField()) {
                return Optional.absent();
            }
            if (!it.nextTerm()) {
                return Optional.absent();
            }
            if (!it.nextGroup()) {
                return Optional.absent();
            }
            it.groupStats(statsBuff);
            return Optional.of(new SessionStringIterationState(it, sessionMetricIndexes, statsBuff, it.termStringVal(), it.group()));
        }
    }

    public interface StringIterateCallback {
        void term(String term, long[] stats, int group);
    }

    public static void iterateMultiString(Map<String, ImhotepSession> sessions, Map<String, IntList> metricIndexes, String field, StringIterateCallback callback) throws IOException {
        int numMetrics = 0;
        for (final IntList metrics : metricIndexes.values()) {
            numMetrics += metrics.size();
        }
        try (final Closer closer = Closer.create()) {
            final Comparator<SessionStringIterationState> comparator = new Comparator<SessionStringIterationState>() {
                public int compare(SessionStringIterationState x, SessionStringIterationState y) {
                    int r = x.nextTerm.compareTo(y.nextTerm);
                    if (r != 0) return r;
                    return Ints.compare(x.nextGroup, y.nextGroup);
                }
            };
            final PriorityQueue<SessionStringIterationState> pq = new PriorityQueue<>(sessions.size(), comparator);
            for (final String sessionName : sessions.keySet()) {
                final ImhotepSession session = sessions.get(sessionName);
                final IntList sessionMetricIndexes = Objects.firstNonNull(metricIndexes.get(sessionName), new IntArrayList());
                final Optional<SessionStringIterationState> constructed = SessionStringIterationState.construct(closer, session, field, sessionMetricIndexes);
                if (constructed.isPresent()) {
                    pq.add(constructed.get());
                }
            }
            final long[] realBuffer = new long[numMetrics];
            final List<SessionStringIterationState> toEnqueue = Lists.newArrayList();
            while (!pq.isEmpty()) {
                toEnqueue.clear();
                Arrays.fill(realBuffer, 0);
                final SessionStringIterationState state1 = pq.poll();
                final String term = state1.nextTerm;
                final int group = state1.nextGroup;
                copyStats(state1, realBuffer);
                toEnqueue.add(state1);
                while (!pq.isEmpty() && pq.peek().nextTerm.equals(term) && pq.peek().nextGroup == group) {
                    final SessionStringIterationState state = pq.poll();
                    copyStats(state, realBuffer);
                    toEnqueue.add(state);
                }
                callback.term(term, realBuffer, group);
                for (final SessionStringIterationState state : toEnqueue) {
                    advanceAndEnqueue(state, pq);
                }
            }
        }
    }

    private static void advanceAndEnqueue(SessionStringIterationState state, PriorityQueue<SessionStringIterationState> pq) {
        final FTGSIterator iterator = state.iterator;
        if (iterator.nextGroup()) {
            state.nextGroup = iterator.group();
            iterator.groupStats(state.statsBuff);
            pq.add(state);
        } else if (iterator.nextTerm() && iterator.nextGroup()) {
            state.nextTerm = iterator.termStringVal();
            state.nextGroup = iterator.group();
            iterator.groupStats(state.statsBuff);
            pq.add(state);
        }
    }

    private static void copyStats(SessionStringIterationState state, long[] dst) {
        for (int i = 0; i < state.metricIndexes.size(); i++) {
            dst[state.metricIndexes.getInt(i)] = state.statsBuff[i];
        }
    }

    public static class GroupStats {
        public final GroupKey key;
        public final double[] stats;

        @JsonCreator
        public GroupStats(@JsonProperty("key") GroupKey key, @JsonProperty("stats") double[] stats) {
            this.key = key;
            this.stats = stats;
        }
    }

    public static class GroupKey {
        public final String term;
        public final int index;
        public final GroupKey parent;

        public GroupKey(String term, int index, GroupKey parent) {
            this.term = term;
            this.index = index;
            this.parent = parent;
        }

        public List<String> asList(boolean appendingTerm) {
            if (term == null && !appendingTerm) {
                return Collections.singletonList("");
            } else {
                final List<String> keys = Lists.newArrayList();
                GroupKey node = this;
                while (node != null && node.term != null) {
                    keys.add(node.term);
                    node = node.parent;
                }
                return Lists.reverse(keys);
            }
        }
    }

    public interface RunnableWithException {
        void run() throws Throwable;
    }

    public static class SavedGroupStats {
        public final int depth;
        public final double[] stats;

        public SavedGroupStats(int depth, double[] stats) {
            this.depth = depth;
            this.stats = stats;
        }
    }

    public static class ImhotepSessionInfo {
        public final ImhotepSession session;
        public final DatasetDimensions datasetDimensions;
        public final Collection<String> intFields;
        public final Collection<String> stringFields;
        public final DateTime startTime;
        public final DateTime endTime;
        public final String timeFieldName;

        private ImhotepSessionInfo(ImhotepSession session, DatasetDimensions datasetDimensions, Collection<String> intFields, Collection<String> stringFields, DateTime startTime, DateTime endTime, String timeFieldName) {
            this.session = session;
            this.datasetDimensions = datasetDimensions;
            this.intFields = Collections.unmodifiableCollection(intFields);
            this.stringFields = Collections.unmodifiableCollection(stringFields);
            this.startTime = startTime;
            this.endTime = endTime;
            this.timeFieldName = timeFieldName;
        }
    }
}
