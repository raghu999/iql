import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import com.google.common.math.DoubleMath;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.indeed.common.datastruct.BoundedPriorityQueue;
import com.indeed.common.util.Pair;
import com.indeed.flamdex.query.Term;
import com.indeed.imhotep.DatasetInfo;
import com.indeed.imhotep.GroupMultiRemapRule;
import com.indeed.imhotep.GroupRemapRule;
import com.indeed.imhotep.RegroupCondition;
import com.indeed.imhotep.api.FTGSIterator;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.ImhotepSession;
import com.indeed.imhotep.client.ImhotepClient;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleCollection;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Months;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

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

    private final Map<String, ImhotepSessionInfo> sessions;
    private int numGroups = 1;

    public static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        final SimpleModule module = new SimpleModule();
        module.addSerializer(TermSelects.class, new JsonSerializer<TermSelects>() {
            @Override
            public void serialize(TermSelects value, JsonGenerator jgen, SerializerProvider provider) throws IOException, JsonProcessingException {
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
            public void serialize(GroupKey value, JsonGenerator jgen, SerializerProvider provider) throws IOException, JsonProcessingException {
                jgen.writeObject(value.asList());
            }
        });
        MAPPER.registerModule(module);
        MAPPER.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
    }

    public static final String INFINITY_SYMBOL = "∞";

    public Session(Map<String, ImhotepSessionInfo> sessions) {
        this.sessions = sessions;
    }

    public static void main(String[] args) throws Exception {
        org.apache.log4j.BasicConfigurator.configure();
        Logger.getRootLogger().setLevel(Level.INFO);

        final Properties props = new Properties();
        final InputStream propsStream = Session.class.getResourceAsStream("config.properties");
        if (propsStream != null) {
            props.load(propsStream);
        }
        final String zkPath = (String) props.getOrDefault("zk_path", "***REMOVED***");
        log.info("zkPath = " + zkPath);

        final ImhotepClient client = new ImhotepClient(zkPath, true);

        final int wsSocketPort = Integer.parseInt((String) props.getOrDefault("ws_socket", "8001"));
        log.info("wsSocketPort = " + wsSocketPort);
        final int unixSocketPort = Integer.parseInt((String) props.getOrDefault("unix_socket", "28347"));
        log.info("unixSocketPort = " + unixSocketPort);
        try (WebSocketSessionServer wsServer = new WebSocketSessionServer(client, new InetSocketAddress(wsSocketPort))) {
            new Thread(() -> {
                while (true) {
                    wsServer.run();
                }
            }).start();

            final ServerSocket serverSocket = new ServerSocket(unixSocketPort);
            while (true) {
                final Socket clientSocket = serverSocket.accept();
                new Thread(() -> {
                    try (final PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                         final BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

                        final Supplier<JsonNode> nodeSupplier = () -> {
                            try {
                                final String line = in.readLine();
                                return line == null ? null : MAPPER.readTree(line);
                            } catch (final IOException e) {
                                throw Throwables.propagate(e);
                            }
                        };

                        final Consumer<String> resultConsumer = out::println;

                        processConnection(client, nodeSupplier, resultConsumer);
                    } catch (Throwable e) {
                        log.error("wat", e);
                        System.out.println("e = " + e);
                    }
                }).start();
            }
        }
    }

    public static void processConnection(ImhotepClient client, Supplier<JsonNode> in, Consumer<String> out) throws IOException, ImhotepOutOfMemoryException {
        final JsonNode sessionRequest = in.get();
        if (sessionRequest.has("describe")) {
            processDescribe(client, out, sessionRequest);
        } else {
            try (final Closer closer = Closer.create()) {
                final Optional<Session> session = createSession(client, sessionRequest, closer, out);
                if (session.isPresent()) {
                    // Not using ifPresent because of exceptions
                    final Session session1 = session.get();
                    JsonNode inputTree;
                    while ((inputTree = in.get()) != null) {
                        System.out.println("inputLine = " + inputTree);
                        session1.evaluateCommand(inputTree, out);
                        System.out.println("Evaluated.");
                    }
                }
            } catch (Exception e) {
                final String error = Session.MAPPER.writeValueAsString(ImmutableMap.of("error", "1", "message", ""+e.getMessage(), "cause", ""+e.getCause(), "stackTrace", ""+ Arrays.toString(e.getStackTrace())));
                System.out.println("error = " + error);
                out.accept(error);
                throw e;
            }
        }
    }

    public static void processDescribe(ImhotepClient client, Consumer<String> out, JsonNode sessionRequest) throws JsonProcessingException {
        final DatasetInfo datasetInfo = client.getDatasetToShardList().get(sessionRequest.get("describe").textValue());
        final DatasetDescriptor datasetDescriptor = DatasetDescriptor.from(datasetInfo);
        out.accept(MAPPER.writeValueAsString(datasetDescriptor));
    }

    public static Optional<Session> createSession(ImhotepClient client, JsonNode sessionRequest, Closer closer, Consumer<String> out) throws ImhotepOutOfMemoryException, IOException {
        final Map<String, ImhotepSessionInfo> sessions = Maps.newHashMap();
        if (sessionRequest.has("commands")) {
            createSubSessions(client, sessionRequest.get("datasets"), closer, sessions);
            final Session session = new Session(sessions);
            final JsonNode commands = sessionRequest.get("commands");
            for (int i = 0; i < commands.size(); i++) {
                final JsonNode command = commands.get(i);
                final boolean isLast = i == commands.size() - 1;
                if (isLast) {
                    session.evaluateCommandToTSV(command, out);
                } else {
                    session.evaluateCommand(command, x -> {});
                }
            }
            return Optional.empty();
        } else {
            createSubSessions(client, sessionRequest, closer, sessions);
            out.accept("opened");
            return Optional.of(new Session(sessions));
        }
    }

    private static void createSubSessions(ImhotepClient client, JsonNode sessionRequest, Closer closer, Map<String, ImhotepSessionInfo> sessions) {
        for (int i = 0; i < sessionRequest.size(); i++) {
            final JsonNode elem = sessionRequest.get(i);
            final String dataset = elem.get("dataset").textValue();
            final String start = elem.get("start").textValue();
            final String end = elem.get("end").textValue();
            final String name = elem.has("name") ? elem.get("name").textValue() : dataset;

            final DatasetInfo datasetInfo = client.getDatasetToShardList().get(dataset);
            final Collection<String> sessionIntFields = datasetInfo.getIntFields();
            final Collection<String> sessionStringFields = datasetInfo.getStringFields();
            final DateTime startDateTime = parseDateTime(start);
            final DateTime endDateTime = parseDateTime(end);
            final ImhotepSession session = closer.register(client.sessionBuilder(dataset, startDateTime, endDateTime).build());

            final boolean isRamsesIndex = datasetInfo.getIntFields().isEmpty();

            sessions.put(name, new ImhotepSessionInfo(session, sessionIntFields, sessionStringFields, startDateTime, endDateTime, isRamsesIndex ? "time" : "unixtime"));
        }
    }

    private static final Pattern relativePattern = Pattern.compile("(\\d+)([smhdwMy])");
    private static DateTime parseDateTime(String descriptor) {
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
        final Object command = Commands.parseCommand(commandTree, this::namedMetricLookup);
        evaluateCommandInternal(commandTree, out, command);
    }

    public void evaluateCommandToTSV(JsonNode commandTree, Consumer<String> out) throws ImhotepOutOfMemoryException, IOException {
        final Object command = Commands.parseCommand(commandTree, this::namedMetricLookup);
        if (command instanceof Commands.Iterate) {
            final List<List<List<TermSelects>>> results = performIterate((Commands.Iterate) command);
            final StringBuilder sb = new StringBuilder();
            for (final List<List<TermSelects>> groupFieldTerms : results) {
                final List<TermSelects> groupTerms = groupFieldTerms.get(0);
                for (final TermSelects termSelects : groupTerms) {
                    final List<String> keyColumns = termSelects.groupKey.asList();
                    keyColumns.forEach(k -> sb.append(k).append('\t'));
                    if (termSelects.isIntTerm) {
                        sb.append(termSelects.intTerm).append('\t');
                    } else {
                        sb.append(termSelects.stringTerm).append('\t');
                    }
                    for (final double stat : termSelects.selects) {
                        if (DoubleMath.isMathematicalInteger(stat)) {
                            sb.append((long)stat).append('\t');
                        } else {
                            sb.append(stat).append('\t');
                        }
                    }
                    sb.setLength(sb.length() - 1);
                    sb.append('\n');
                }
            }
            sb.setLength(sb.length() - 1);
            out.accept(MAPPER.writeValueAsString(Arrays.asList(sb.toString())));
        } else if (command instanceof Commands.GetGroupStats) {
            final Commands.GetGroupStats getGroupStats = (Commands.GetGroupStats) command;
            final List<GroupStats> results = getGroupStats(getGroupStats, groupKeys, getSessionsMapRaw(), numGroups, getGroupStats.returnGroupKeys);
            final StringBuilder sb = new StringBuilder();
            for (final GroupStats result : results) {
                final List<String> keyColumns = result.key.asList();
                keyColumns.forEach(k -> sb.append(k).append('\t'));
                for (final double stat : result.stats) {
                    if (DoubleMath.isMathematicalInteger(stat)) {
                        sb.append((long)stat).append('\t');
                    } else {
                        sb.append(stat).append('\t');
                    }
                }
                if (keyColumns.size() + result.stats.length > 0) {
                    sb.setLength(sb.length() - 1);
                }
                sb.append('\n');
            }
            if (results.size() > 0) {
                sb.setLength(sb.length() - 1);
            }
            out.accept(MAPPER.writeValueAsString(Arrays.asList(sb.toString())));
        } else {
            throw new IllegalArgumentException("Don't know how to evaluate [" + command + "] to TSV");
        }
    }

    private void evaluateCommandInternal(JsonNode commandTree, Consumer<String> out, Object command) throws ImhotepOutOfMemoryException, IOException {
        if (command instanceof Commands.Iterate) {
            final Commands.Iterate iterate = (Commands.Iterate) command;
            final List<List<List<TermSelects>>> allTermSelects = performIterate(iterate);
            out.accept(MAPPER.writeValueAsString(allTermSelects));
        } else if (command instanceof Commands.FilterDocs) {
            final Commands.FilterDocs filterDocs = (Commands.FilterDocs) command;
            // TODO: Do these in parallel?
            for (final Map.Entry<String,List<String>> entry : filterDocs.perDatasetFilterMetric.entrySet()) {
                final ImhotepSession session = sessions.get(entry.getKey()).session;
                final int index = session.pushStats(entry.getValue());
                if (index != 1) {
                    throw new IllegalArgumentException("Didn't end up with 1 stat after pushing in index named \"" + entry.getKey() + "\"");
                }
                session.metricFilter(0, 1, 1, false);
                session.popStat();
            }
            out.accept("{}");
        } else if (command instanceof Commands.ExplodeGroups) {
            final Commands.ExplodeGroups explodeGroups = (Commands.ExplodeGroups) command;
            if ((explodeGroups.intTerms == null) == (explodeGroups.stringTerms == null)) {
                throw new IllegalArgumentException("Exactly one type of term must be contained in ExplodeGroups.");
            }
            final boolean intType = explodeGroups.intTerms != null;
            final GroupMultiRemapRule[] rules = new GroupMultiRemapRule[numGroups];
            int nextGroup = 1;
            final List<GroupKey> nextGroupKeys = Lists.newArrayList((GroupKey) null);
            for (int i = 0; i < numGroups; i++) {
                final int group = i + 1;
                final List<RegroupCondition> regroupConditionsList = Lists.newArrayList();
                if (intType) {
                    final LongArrayList terms = explodeGroups.intTerms.get(i);
                    for (final long term : terms) {
                        regroupConditionsList.add(new RegroupCondition(explodeGroups.field, true, term, null, false));
                        nextGroupKeys.add(new GroupKey(String.valueOf(term), nextGroupKeys.size(), groupKeys.get(group)));
                    }
                } else {
                    final List<String> terms = explodeGroups.stringTerms.get(i);
                    for (final String term : terms) {
                        regroupConditionsList.add(new RegroupCondition(explodeGroups.field, false, 0, term, false));
                        nextGroupKeys.add(new GroupKey(term, nextGroupKeys.size(), groupKeys.get(group)));
                    }
                }
                final int[] positiveGroups = new int[regroupConditionsList.size()];
                for (int j = 0; j < regroupConditionsList.size(); j++) {
                    positiveGroups[j] = nextGroup++;
                }
                final RegroupCondition[] conditions = regroupConditionsList.toArray(new RegroupCondition[regroupConditionsList.size()]);
                final int negativeGroup;
                if (explodeGroups.defaultGroupTerm.isPresent()) {
                    negativeGroup = nextGroup++;
                    nextGroupKeys.add(new GroupKey(explodeGroups.defaultGroupTerm.get(), nextGroupKeys.size(), groupKeys.get(group)));
                } else {
                    negativeGroup = 0;
                }
                rules[i] = new GroupMultiRemapRule(group, negativeGroup, positiveGroups, conditions);
            }
            getSessionsMapRaw().values().forEach(session -> unchecked(() -> session.regroup(rules)));
            numGroups = nextGroup - 1;
            groupKeys = nextGroupKeys;
            currentDepth += 1;
            out.accept("success");
        } else if (command instanceof Commands.MetricRegroup) {
            final Commands.MetricRegroup metricRegroup = (Commands.MetricRegroup) command;
            final long max = metricRegroup.max;
            final long min = metricRegroup.min;
            final long interval = metricRegroup.interval;
            final Map<String, ? extends List<String>> perDatasetMetrics = metricRegroup.perDatasetMetric;

            final int numBuckets = 2 + (int) Math.ceil(((double) max - min) / interval);

            // TODO: Do these in parallel?
            perDatasetMetrics.forEach((name, pushes) -> unchecked(() -> {
                final ImhotepSession session = sessions.get(name).session;
                final int numStats = session.pushStats(pushes);
                if (numStats != 1) {
                    throw new IllegalStateException("Pushed more than one stat!: " + pushes);
                }
                session.metricRegroup(0, min, max, interval);
                session.popStat();
            }));

            densify(group -> {
                final int oldGroup = 1 + (group - 1) / numBuckets;
                final int innerGroup = (group - 1) % numBuckets;
                final String key;
                if (innerGroup == numBuckets - 1) {
                    key = "[" + (min + interval * (numBuckets - 2)) + ", " + INFINITY_SYMBOL + ")";
                } else if (innerGroup == numBuckets - 2) {
                    key = "[-" + INFINITY_SYMBOL + ", " + min + ")";
                } else if (interval == 1) {
                    key = String.valueOf(min + innerGroup);
                } else {
                    final long minInclusive = min + innerGroup * interval;
                    final long maxExclusive = min + (innerGroup + 1) * interval;
                    key = "[" + minInclusive + ", " + maxExclusive + ")";
                }
                return Pair.of(key, groupKeys.get(oldGroup));
            });

            out.accept("success");
        } else if (command instanceof Commands.TimeRegroup) {
            final Commands.TimeRegroup timeRegroup = (Commands.TimeRegroup) command;
            // TODO: This whole time thing needs a rethink.
            final long earliestStart = getEarliestStart();
            final long latestEnd = getLatestEnd();
            final TimeUnit timeUnit = TimeUnit.fromChar(timeRegroup.unit);

            final long unitSize;
            if (timeUnit == TimeUnit.MONTH) {
//                final DateTime startOfStartMonth = new DateTime(earliestStart).withDayOfMonth(1);
//                if (startOfStartMonth.getMillis() != earliestStart) {
//                    throw new IllegalArgumentException("Earliest time not aligned at start of month: " + new DateTime(earliestStart));
//                }
//                final DateTime startOfEndMonth = new DateTime(latestEnd).withDayOfMonth(1);
//                if (startOfEndMonth.getMillis() != latestEnd) {
//                    throw new IllegalArgumentException("Latest time not aligned with start of month: " + new DateTime(latestEnd));
//                }
//                if (!startOfStartMonth.isBefore(startOfEndMonth)) {
//                    throw new IllegalArgumentException("Start must come before end. start = [" + startOfStartMonth + "], end = [" + startOfEndMonth + "]");
//                }
                unitSize = TimeUnit.DAY.millis;
            } else {
                unitSize = timeRegroup.value * timeUnit.millis;
            }
            final long timeOffsetMinutes = timeRegroup.offsetMinutes - 360;
            final DateTimeZone zone = DateTimeZone.forOffsetHoursMinutes((int) timeOffsetMinutes / 60, (int) timeOffsetMinutes % 60);
            final long realStart;
            switch (timeUnit) {
                case SECOND:
                    realStart = new DateTime(earliestStart, zone).withMillisOfSecond(0).getMillis();
                    break;
                case MINUTE:
                    realStart = new DateTime(earliestStart, zone).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();
                    break;
                case HOUR:
                    realStart = new DateTime(earliestStart, zone).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0).getMillis();
                    break;
                case WEEK:
                    realStart = new DateTime(earliestStart, zone).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0).withDayOfWeek(1).getMillis();
                    break;
                case DAY:
                case MONTH:
                    realStart = new DateTime(earliestStart, zone).withTimeAtStartOfDay().getMillis();
                    break;
                default:
                    throw new IllegalStateException("Unhandled enum value: " + timeUnit);
            }
            final long shardsEnd = new DateTime(latestEnd).getMillis();
            final long difference = shardsEnd - realStart;
            final long realEnd;
            if (difference % timeUnit.millis == 0) {
                realEnd = shardsEnd;
            } else {
                realEnd = shardsEnd + (timeUnit.millis - difference % timeUnit.millis);
            }

            final int oldNumGroups = this.numGroups;
            final int numGroups = performTimeRegroup(realStart, realEnd, unitSize, timeRegroup.timeField);
            final int numBuckets = (int)Math.ceil(((double)realEnd - realStart) / unitSize);
            if (timeUnit == TimeUnit.MONTH) {
                final DateTimeFormatter formatter = DateTimeFormat.forPattern(TimeUnit.MONTH.formatString);
                final DateTime startMonth = new DateTime(earliestStart).withDayOfMonth(1).withTimeAtStartOfDay();
                final DateTime endMonthExclusive = new DateTime(latestEnd).minusDays(1).withDayOfMonth(1).withTimeAtStartOfDay().plusMonths(1);
                final int numMonths = Months.monthsBetween(
                        startMonth,
                        endMonthExclusive
                ).getMonths();

                final List<GroupRemapRule> rules = Lists.newArrayList();
                final RegroupCondition fakeCondition = new RegroupCondition("fakeField", true, 100, null, false);
                for (int outerGroup = 1; outerGroup <= oldNumGroups; outerGroup++) {
                    for (int innerGroup = 0; innerGroup < numBuckets; innerGroup++) {
                        final long start = realStart + innerGroup * unitSize;
                        final int base = 1 + (outerGroup - 1) * numBuckets + innerGroup;
                        final int newBase = 1 + (outerGroup - 1) * numMonths;

                        final DateTime date = new DateTime(start, zone).withDayOfMonth(1).withTimeAtStartOfDay();
                        final int newGroup = newBase + Months.monthsBetween(startMonth, date).getMonths();
                        rules.add(new GroupRemapRule(base, fakeCondition, newGroup, newGroup));
                    }
                }
                final GroupRemapRule[] rulesArray = rules.toArray(new GroupRemapRule[rules.size()]);
                sessions.values().forEach(sessionInfo -> unchecked(() -> sessionInfo.session.regroup(rulesArray)));
                final Function<Integer, Pair<String, GroupKey>> groupMapper = group -> {
                    final int originalGroup = 1 + (group - 1) / numMonths;
                    final int monthOffset = (group - 1) % numMonths;
                    final String key = formatter.print(startMonth.plusMonths(monthOffset));
                    return Pair.of(key, groupKeys.get(originalGroup));
                };
                if (oldNumGroups == 1) {
                    assumeDense(groupMapper, oldNumGroups * numMonths);
                } else {
                    densify(groupMapper);
                }
            } else {
                final String formatString = TimeUnit.SECOND.formatString; // timeUnit.formatString;
                final Function<Integer, Pair<String, GroupKey>> groupMapper = group -> {
                    final int oldGroup = 1 + (group - 1) / numBuckets;
                    final int timeBucket = (group - 1) % numBuckets;
                    final long startInclusive = realStart + timeBucket * unitSize;
                    final long endExclusive = realStart + (timeBucket + 1) * unitSize;
                    final String startString = new DateTime(startInclusive, zone).toString(formatString);
                    final String endString = new DateTime(endExclusive, zone).toString(formatString);
                    return Pair.of("[" + startString + ", " + endString + ")", groupKeys.get(oldGroup));
                };
                if (oldNumGroups == 1) {
                    assumeDense(groupMapper, numGroups);
                } else {
                    densify(groupMapper);
                }
            }
            this.currentDepth += 1;
            out.accept("success");
        } else if (command instanceof Commands.GetGroupStats) {
            final Commands.GetGroupStats getGroupStats = (Commands.GetGroupStats) command;
            final List<GroupStats> results = getGroupStats(getGroupStats, groupKeys, getSessionsMapRaw(), numGroups, getGroupStats.returnGroupKeys);
            out.accept(MAPPER.writeValueAsString(results));
        } else if (command instanceof Commands.CreateGroupStatsLookup) {
            final Commands.CreateGroupStatsLookup createGroupStatsLookup = (Commands.CreateGroupStatsLookup) command;
            final int depth = currentDepth;
            final double[] stats = createGroupStatsLookup.stats;
            final SavedGroupStats savedStats = new SavedGroupStats(depth, stats);
            final String lookupName;
            if (createGroupStatsLookup.name.isPresent()) {
                lookupName = createGroupStatsLookup.name.get();
                if (savedGroupStats.containsKey(lookupName)) {
                    throw new IllegalArgumentException("Name already in use!: [" + lookupName + "]");
                }
            } else {
                lookupName = String.valueOf(savedGroupStats.size());
            }
            savedGroupStats.put(lookupName, savedStats);
            out.accept(MAPPER.writeValueAsString(Arrays.asList(lookupName)));
        } else if (command instanceof Commands.GetGroupDistincts) {
            final Commands.GetGroupDistincts getGroupDistincts = (Commands.GetGroupDistincts) command;
            final String field = getGroupDistincts.field;
            final Map<String, ImhotepSession> sessionsSubset = Maps.newHashMap();
            getGroupDistincts.scope.forEach(s -> sessionsSubset.put(s, sessions.get(s).session));
            final List<AggregateFilter> filters = Lists.newArrayList();
            getGroupDistincts.filter.ifPresent(filters::add);
            final Set<QualifiedPush> pushes = Sets.newHashSet();
            filters.forEach(f -> pushes.addAll(f.requires()));
            final Map<QualifiedPush, Integer> metricIndexes = Maps.newHashMap();
            final Map<String, IntList> sessionMetricIndexes = Maps.newHashMap();
            pushMetrics(pushes, metricIndexes, sessionMetricIndexes);
            registerMetrics(metricIndexes, Arrays.asList(), filters);
            final long[] groupCounts = new long[numGroups];
            if (isIntField(field)) {
                final IntIterateCallback callback = new IntIterateCallback() {
                    private final BitSet groupSeen = new BitSet();
                    private boolean started = false;
                    private int lastGroup = 0;
                    private long currentTerm = 0;

                    @Override
                    public void term(long term, long[] stats, int group) {
                        if (started && currentTerm != term) {
                            while ((lastGroup = groupSeen.nextSetBit(lastGroup + 1)) != -1) {
                                groupCounts[lastGroup - 1]++;
                            }
                            groupSeen.clear();
                        }
                        currentTerm = term;
                        started = true;
                        lastGroup = group;
                        final GroupKey parent = groupKeys.get(group).parent;
                        if (getGroupDistincts.filter.isPresent()) {
                            if (getGroupDistincts.filter.get().allow(term, stats, group)) {
                                for (int offset = 0; offset < getGroupDistincts.windowSize; offset++) {
                                    if (group + offset < groupKeys.size() && groupKeys.get(group + offset).parent == parent) {
                                        groupSeen.set(group + offset);
                                    }
                                }
                            }
                        } else {
                            for (int offset = 0; offset < getGroupDistincts.windowSize; offset++) {
                                if (group + offset < groupKeys.size() && groupKeys.get(group + offset).parent == parent) {
                                    groupSeen.set(group + offset);
                                }
                            }
                        }
                        if (groupSeen.get(group)) {
                            groupCounts[group - 1]++;
                        }
                    }
                };
                iterateMultiInt(sessionsSubset, sessionMetricIndexes, field, callback);
            } else if (isStringField(field)) {
                final StringIterateCallback callback = new StringIterateCallback() {
                    private final BitSet groupSeen = new BitSet();
                    private boolean started = false;
                    private int lastGroup = 0;
                    private String currentTerm;

                    @Override
                    public void term(String term, long[] stats, int group) {
                        if (started && !currentTerm.equals(term)) {
                            while ((lastGroup = groupSeen.nextSetBit(lastGroup + 1)) != -1) {
                                groupCounts[lastGroup - 1]++;
                            }
                            groupSeen.clear();
                        }
                        currentTerm = term;
                        started = true;
                        lastGroup = group;
                        final GroupKey parent = groupKeys.get(group).parent;
                        if (getGroupDistincts.filter.isPresent()) {
                            if (getGroupDistincts.filter.get().allow(term, stats, group)) {
                                for (int offset = 0; offset < getGroupDistincts.windowSize; offset++) {
                                    if (group + offset < groupKeys.size() && groupKeys.get(group + offset).parent == parent) {
                                        groupSeen.set(group + offset);
                                    }
                                }
                            }
                        } else {
                            for (int offset = 0; offset < getGroupDistincts.windowSize; offset++) {
                                if (group + offset < groupKeys.size() && groupKeys.get(group + offset).parent == parent) {
                                    groupSeen.set(group + offset);
                                }
                            }
                        }
                        if (groupSeen.get(group)) {
                            groupCounts[group - 1]++;
                        }
                    }
                };
                iterateMultiString(sessionsSubset, sessionMetricIndexes, field, callback);
            } else {
                for (final Map.Entry<String, ImhotepSessionInfo> session : sessions.entrySet()) {
                    final String name = session.getKey();
                    final boolean isIntField = session.getValue().intFields.contains(field);
                    final boolean isStringField = session.getValue().stringFields.contains(field);
                    System.out.println("name = " + name + ", isIntField=" + isIntField + ", isStringField=" + isStringField);
                }
                throw new IllegalStateException("Field is neither all int nor all string field: " + field);
            }
            sessions.values().forEach(s -> {
                while (s.session.getNumStats() > 0) {
                    s.session.popStat();
                }
            });
            out.accept(MAPPER.writeValueAsString(groupCounts));
        } else if (command instanceof Commands.GetGroupPercentiles) {
            final Commands.GetGroupPercentiles getGroupPercentiles = (Commands.GetGroupPercentiles) command;
            final String field = getGroupPercentiles.field;
            final double[] percentiles = getGroupPercentiles.percentiles;
            final long[] counts = new long[numGroups + 1];
            final Map<String, ImhotepSession> sessionsSubset = Maps.newHashMap();
            getGroupPercentiles.scope.forEach(s -> sessionsSubset.put(s, sessions.get(s).session));
            sessionsSubset.values().forEach(s -> unchecked(() -> {
                s.pushStat("count()");
                final long[] stats = s.getGroupStats(0);
                for (int i = 0; i < stats.length; i++) {
                    counts[i] += stats[i];
                }
            }));
            final Map<String, IntList> metricMapping = Maps.newHashMap();
            int index = 0;
            for (final String sessionName : sessionsSubset.keySet()) {
                metricMapping.put(sessionName, IntLists.singleton(index++));
            }
            final double[][] requiredCounts = new double[counts.length][];
            for (int i = 1; i < counts.length; i++) {
                requiredCounts[i] = new double[percentiles.length];
                for (int j = 0; j < percentiles.length; j++) {
                    requiredCounts[i][j] = (percentiles[j] / 100.0) * (double)counts[i];
                }
            }
            final long[][] results = new long[percentiles.length][counts.length - 1];
            final long[] runningCounts = new long[counts.length];
            iterateMultiInt(sessionsSubset, metricMapping, field, (term, stats, group) -> {
                final long oldCount = runningCounts[group];
                final long termCount = LongStream.of(stats).sum();
                final long newCount = oldCount + termCount;

                final double[] groupRequiredCountsArray = requiredCounts[group];
                for (int i = 0; i < percentiles.length; i++) {
                    final double minRequired = groupRequiredCountsArray[i];
                    if (newCount >= minRequired && oldCount < minRequired) {
                        results[i][group - 1] = term;
                    }
                }

                runningCounts[group] = newCount;
            });

            sessionsSubset.values().forEach(ImhotepSession::popStat);

            out.accept(MAPPER.writeValueAsString(results));
        } else if (command instanceof Commands.GetNumGroups) {
            out.accept(MAPPER.writeValueAsString(Collections.singletonList(numGroups)));
        } else if (command instanceof Commands.ExplodePerGroup) {
            performExplodePerGroup(out, (Commands.ExplodePerGroup) command);
        } else if (command instanceof Commands.ExplodeDayOfWeek) {
            final String[] dayKeys = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };

            final long start = new DateTime(getEarliestStart()).withTimeAtStartOfDay().getMillis();
            final long end = new DateTime(getLatestEnd()).plusDays(1).withTimeAtStartOfDay().getMillis();
            final int numGroups = performTimeRegroup(start, end, TimeUnit.DAY.millis, Optional.empty());
            final int numBuckets = (int) ((end - start) / TimeUnit.DAY.millis);
            final List<GroupRemapRule> rules = Lists.newArrayList();
            final RegroupCondition fakeCondition = new RegroupCondition("fakeField", true, 100, null, false);
            for (int group = 1; group <= numGroups; group++) {
                final int oldGroup = 1 + (group - 1) / numBuckets;
                final int dayOffset = (group - 1) % numBuckets;
                final long groupStart = start + dayOffset * TimeUnit.DAY.millis;
                final int newGroup = 1 + ((oldGroup - 1) * dayKeys.length) + new DateTime(groupStart).getDayOfWeek() - 1;
                rules.add(new GroupRemapRule(group, fakeCondition, newGroup, newGroup));
            }
            final GroupRemapRule[] rulesArray = rules.toArray(new GroupRemapRule[rules.size()]);
            final int oldNumGroups = this.numGroups;
            sessions.values().forEach(sessionInfo -> unchecked(() -> sessionInfo.session.regroup(rulesArray)));
            assumeDense(group -> {
                final int originalGroup = 1 + (group - 1) / dayKeys.length;
                final int dayOfWeek = (group - 1) % dayKeys.length;
                return Pair.of(dayKeys[dayOfWeek], groupKeys.get(originalGroup));
            }, oldNumGroups * dayKeys.length);
            currentDepth += 1;
            out.accept("success");
        } else if (command instanceof Commands.ExplodeSessionNames) {
            final TreeSet<String> names = Sets.newTreeSet(sessions.keySet());
            // TODO: This
            throw new UnsupportedOperationException("Get around to implementing ExplodeSessionNames");
        } else if (command instanceof Commands.IterateAndExplode) {
            final Commands.IterateAndExplode iterateAndExplode = (Commands.IterateAndExplode) command;
            final List<Commands.Iterate.FieldWithOptions> fieldWithOpts = Arrays.asList(new Commands.Iterate.FieldWithOptions(iterateAndExplode.field, iterateAndExplode.fieldOpts));
            final List<List<List<TermSelects>>> iterationResults = performIterate(new Commands.Iterate(fieldWithOpts, iterateAndExplode.fieldLimits, iterateAndExplode.selecting));
            final List<Commands.TermsWithExplodeOpts> explodes = Lists.newArrayList((Commands.TermsWithExplodeOpts) null);
            final List<List<TermSelects>> fieldResults = iterationResults.stream().findFirst().get();
            for (final List<TermSelects> groupResults : fieldResults) {
                final List<Term> terms = Lists.newArrayListWithCapacity(groupResults.size());
                for (final TermSelects result : groupResults) {
                    terms.add(new Term(result.field, result.isIntTerm, result.intTerm, result.stringTerm));
                }
                explodes.add(new Commands.TermsWithExplodeOpts(terms, iterateAndExplode.explodeDefaultName));
            }
            performExplodePerGroup(out, new Commands.ExplodePerGroup(explodes));
        } else if (command instanceof Commands.ComputeAndCreateGroupStatsLookup) {
            // TODO: Seriously? Serializing to JSON and then back? To the same program?
            final Commands.ComputeAndCreateGroupStatsLookup computeAndCreateGroupStatsLookup = (Commands.ComputeAndCreateGroupStatsLookup) command;
            final AtomicReference<String> reference = new AtomicReference<>();
            final Object computation = computeAndCreateGroupStatsLookup.computation;
            evaluateCommandInternal(null, reference::set, computation);
            double[] results;
            if (computation instanceof Commands.GetGroupDistincts) {
                results = MAPPER.readValue(reference.get(), new TypeReference<double[]>(){});
            } else if (computation instanceof Commands.GetGroupPercentiles) {
                final List<double[]> intellijDoesntLikeInlining = MAPPER.readValue(reference.get(), new TypeReference<List<double[]>>(){});
                results = intellijDoesntLikeInlining.get(0);
            } else if (computation instanceof Commands.GetGroupStats) {
                final List<GroupStats> groupStats = MAPPER.readValue(reference.get(), new TypeReference<List<GroupStats>>(){});
                results = new double[groupStats.size()];
                for (int i = 0; i < groupStats.size(); i++) {
                    results[i] = groupStats.get(i).stats[0];
                }
            } else {
                throw new IllegalArgumentException("Shouldn't be able to reach here. Bug in ComputeAndCreateGroupStatsLookup parser.");
            }
            evaluateCommandInternal(null, out, new Commands.CreateGroupStatsLookup(prependZero(results), computeAndCreateGroupStatsLookup.name));
        } else if (command instanceof Commands.ExplodeByAggregatePercentile) {
            final Commands.ExplodeByAggregatePercentile explodeCommand = (Commands.ExplodeByAggregatePercentile) command;
            final String field = explodeCommand.field;
            final AggregateMetric metric = explodeCommand.metric;
            final int numBuckets = explodeCommand.numBuckets;
            final HashMap<QualifiedPush, Integer> metricIndexes = new HashMap<>();
            final HashMap<String, IntList> sessionMetricIndexes = new HashMap<>();
            pushMetrics(metric.requires(), metricIndexes, sessionMetricIndexes);
            metric.register(metricIndexes, groupKeys);

            final List<GroupKey> nextGroupKeys = Lists.newArrayListWithCapacity(1 + numGroups * numBuckets);
            nextGroupKeys.add(null);

            if (isIntField(field)) {
                final Int2ObjectOpenHashMap<Long2DoubleOpenHashMap> perGroupTermToValue = new Int2ObjectOpenHashMap<>();
                iterateMultiInt(getSessionsMapRaw(), sessionMetricIndexes, field,
                        (term, stats, group) -> perGroupTermToValue.computeIfAbsent(group, g -> new Long2DoubleOpenHashMap()).put(term, metric.apply(term, stats, group))
                );
                final List<GroupMultiRemapRule> rules = Lists.newArrayListWithCapacity(numGroups);
                for (int group = 1; group <= numGroups; group++) {
                    final int groupBase = 1 + (group - 1) * numBuckets;
                    final Long2DoubleOpenHashMap termToValue = perGroupTermToValue.get(group);
                    final double[] percentiles = getPercentiles(termToValue.values(), numBuckets);
                    final Int2ObjectOpenHashMap<LongArrayList> groupOffsetToTerms = new Int2ObjectOpenHashMap<>();
                    for (final Long2DoubleMap.Entry entry : termToValue.long2DoubleEntrySet()) {
                        final int groupOffset = findPercentile(entry.getDoubleValue(), percentiles);
                        groupOffsetToTerms.computeIfAbsent(groupOffset, k -> new LongArrayList()).add(entry.getLongKey());
                    }

                    final int arraySize = groupOffsetToTerms.values().stream().mapToInt(LongArrayList::size).sum();
                    final int[] positiveGroups = new int[arraySize];
                    final RegroupCondition[] conditions = new RegroupCondition[arraySize];
                    int arrayIndex = 0;
                    for (int i = 0; i < numBuckets; i++) {
                        final LongArrayList terms = groupOffsetToTerms.get(i);
                        if (terms != null) {
                            for (final long term : terms) {
                                positiveGroups[arrayIndex] = groupBase + i;
                                conditions[arrayIndex] = new RegroupCondition(field, true, term, null, false);
                                arrayIndex++;
                            }
                        }
                        final String keyTerm = "[" + (double) i / numBuckets + ", " + (double) (i + 1) / numBuckets + ")";
                        nextGroupKeys.add(new GroupKey(keyTerm, nextGroupKeys.size(), groupKeys.get(group)));
                    }
                    rules.add(new GroupMultiRemapRule(group, 0, positiveGroups, conditions));
                }
                final GroupMultiRemapRule[] rulesArr = rules.toArray(new GroupMultiRemapRule[rules.size()]);
                sessions.values().forEach(s -> unchecked(() -> s.session.regroup(rulesArr)));
            } else if (isStringField(field)) {
                final Int2ObjectOpenHashMap<Object2DoubleOpenHashMap<String>> perGroupTermToValue = new Int2ObjectOpenHashMap<>();
                iterateMultiString(getSessionsMapRaw(), sessionMetricIndexes, field,
                        (term, stats, group) -> perGroupTermToValue.computeIfAbsent(group, g -> new Object2DoubleOpenHashMap<>()).put(term, metric.apply(term, stats, group))
                );
                final List<GroupMultiRemapRule> rules = Lists.newArrayListWithCapacity(numGroups);
                for (int group = 1; group <= numGroups; group++) {
                    final int groupBase = 1 + (group - 1) * numBuckets;
                    final Object2DoubleOpenHashMap<String> termToValue = perGroupTermToValue.get(group);
                    final double[] percentiles = getPercentiles(termToValue.values(), numBuckets);
                    final Int2ObjectOpenHashMap<ArrayList<String>> groupOffsetToTerms = new Int2ObjectOpenHashMap<>();
                    for (final Map.Entry<String, Double> entry : termToValue.entrySet()) {
                        final int groupOffset = findPercentile(entry.getValue(), percentiles);
                        groupOffsetToTerms.computeIfAbsent(groupOffset, k -> new ArrayList<>()).add(entry.getKey());
                    }

                    final int arraySize = groupOffsetToTerms.values().stream().mapToInt(ArrayList::size).sum();
                    final int[] positiveGroups = new int[arraySize];
                    final RegroupCondition[] conditions = new RegroupCondition[arraySize];
                    int arrayIndex = 0;
                    for (int i = 0; i < numBuckets; i++) {
                        final ArrayList<String> terms = groupOffsetToTerms.get(i);
                        if (terms != null) {
                            for (final String term : terms) {
                                positiveGroups[arrayIndex] = groupBase + i;
                                conditions[arrayIndex] = new RegroupCondition(field, false, 0, term, false);
                                arrayIndex++;
                            }
                        }
                        final String keyTerm = "[" + (double) i / numBuckets + ", " + (double) (i + 1) / numBuckets + ")";
                        nextGroupKeys.add(new GroupKey(keyTerm, nextGroupKeys.size(), groupKeys.get(group)));
                    }
                    rules.add(new GroupMultiRemapRule(group, 0, positiveGroups, conditions));
                }
                final GroupMultiRemapRule[] rulesArr = rules.toArray(new GroupMultiRemapRule[rules.size()]);
                sessions.values().forEach(s -> unchecked(() -> s.session.regroup(rulesArr)));
            } else {
                throw new IllegalArgumentException("Field is neither int field nor string field: " + field);
            }
            sessions.values().forEach(v -> unchecked(() -> {
                while (v.session.getNumStats() > 0) {
                    v.session.popStat();
                }
            }));

            numGroups = nextGroupKeys.size() - 1;
            groupKeys = nextGroupKeys;
            currentDepth += 1;

            out.accept("ExlodedByAggregatePercentile");
        } else {
            throw new IllegalArgumentException("Invalid command: " + commandTree);
        }
    }

    private int findPercentile(double v, double[] percentiles) {
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
    private static double[] getPercentiles(DoubleCollection values, int k) {
        final DoubleArrayList list = new DoubleArrayList(values);
        list.sort(Double::compare);
        final double[] result = new double[k];
        for (int i = 0; i < k; i++) {
            result[i] = list.get((int) Math.ceil((double) list.size() * i / k));
        }
        return result;
    }

    // TODO: Any call sites of this could be optimized.
    private static double[] prependZero(double[] in) {
        final double[] out = new double[in.length + 1];
        System.arraycopy(in, 0, out, 1, in.length);
        return out;
    }

    private void performExplodePerGroup(Consumer<String> out, Commands.ExplodePerGroup explodePerGroup) throws ImhotepOutOfMemoryException {
        final GroupMultiRemapRule[] rules = new GroupMultiRemapRule[numGroups];
        int nextGroup = 1;
        final List<GroupKey> nextGroupKeys = Lists.newArrayList((GroupKey) null);
        for (int group = 1; group <= numGroups; group++) {
            final Commands.TermsWithExplodeOpts termsWithExplodeOpts = explodePerGroup.termsWithExplodeOpts.get(group);

            final List<RegroupCondition> regroupConditionsList = Lists.newArrayList();

            final List<Term> terms = termsWithExplodeOpts.terms;
            if (terms.isEmpty()) {
                rules[group - 1] = new GroupMultiRemapRule(group, 0, new int[]{0}, new RegroupCondition[]{new RegroupCondition("fake", true, 152, null, false)});
                continue;
            }

            for (final Term term : terms) {
                if (term.isIntField()) {
                    regroupConditionsList.add(new RegroupCondition(term.getFieldName(), true, term.getTermIntVal(), null, false));
                    nextGroupKeys.add(new GroupKey(String.valueOf(term.getTermIntVal()), nextGroupKeys.size(), groupKeys.get(group)));
                } else {
                    regroupConditionsList.add(new RegroupCondition(term.getFieldName(), false, 0, term.getTermStringVal(), false));
                    nextGroupKeys.add(new GroupKey(term.getTermStringVal(), nextGroupKeys.size(), groupKeys.get(group)));
                }
            }

            final int[] positiveGroups = new int[regroupConditionsList.size()];
            for (int j = 0; j < regroupConditionsList.size(); j++) {
                positiveGroups[j] = nextGroup++;
            }
            final RegroupCondition[] conditions = regroupConditionsList.toArray(new RegroupCondition[regroupConditionsList.size()]);
            final int negativeGroup;
            if (termsWithExplodeOpts.defaultName.isPresent()) {
                negativeGroup = nextGroup++;
                nextGroupKeys.add(new GroupKey(termsWithExplodeOpts.defaultName.get(), nextGroupKeys.size(), groupKeys.get(group)));
            } else {
                negativeGroup = 0;
            }
            rules[group - 1] = new GroupMultiRemapRule(group, negativeGroup, positiveGroups, conditions);
        }
        getSessionsMapRaw().values().forEach(session -> unchecked(() -> session.regroup(rules)));
        numGroups = nextGroup - 1;
        groupKeys = nextGroupKeys;
        currentDepth += 1;
        out.accept("success");
    }

    private List<List<List<TermSelects>>> performIterate(final Commands.Iterate iterate) throws ImhotepOutOfMemoryException, IOException {
        final Set<QualifiedPush> allPushes = Sets.newHashSet();
        final List<AggregateMetric> metrics = Lists.newArrayList();
        iterate.fields.forEach(field -> field.opts.topK.ifPresent(topK -> metrics.add(topK.metric)));
        metrics.addAll(iterate.selecting);
        for (final AggregateMetric metric : metrics) {
            allPushes.addAll(metric.requires());
        }
        iterate.fields.forEach(field -> field.opts.filter.ifPresent(filter -> allPushes.addAll(filter.requires())));
        final Map<QualifiedPush, Integer> metricIndexes = Maps.newHashMap();
        final Map<String, IntList> sessionMetricIndexes = Maps.newHashMap();
        pushMetrics(allPushes, metricIndexes, sessionMetricIndexes);
        registerMetrics(metricIndexes, metrics, Arrays.asList());
        iterate.fields.forEach(field -> field.opts.filter.ifPresent(filter -> filter.register(metricIndexes, groupKeys)));

        final DenseInt2ObjectMap<Queue<Queue<TermSelects>>> qqs = new DenseInt2ObjectMap<>();
        if (iterate.fieldLimitingOpts.isPresent()) {
            final Pair<Integer, Commands.Iterate.FieldLimitingMechanism> p = iterate.fieldLimitingOpts.get();
            final int fieldLimitCount = p.getFirst();
            final Commands.Iterate.FieldLimitingMechanism mechanism = p.getSecond();
            final Comparator<Queue<TermSelects>> queueComparator;
            switch (mechanism) {
                case MinimalMin:
                    queueComparator = new Comparator<Queue<TermSelects>>() {
                        @Override
                        public int compare(Queue<TermSelects> o1, Queue<TermSelects> o2) {
                            double o1Min = Double.POSITIVE_INFINITY;
                            for (final TermSelects termSelects : o1) {
                                o1Min = Math.min(termSelects.topMetric, o1Min);
                            }
                            double o2Min = Double.POSITIVE_INFINITY;
                            for (final TermSelects termSelects : o2) {
                                o2Min = Math.min(termSelects.topMetric, o2Min);
                            }
                            return Doubles.compare(o1Min, o2Min);
                        }
                    };
                    break;
                case MaximalMax:
                    queueComparator = new Comparator<Queue<TermSelects>>() {
                        @Override
                        public int compare(Queue<TermSelects> o1, Queue<TermSelects> o2) {
                            double o1Max = Double.POSITIVE_INFINITY;
                            for (final TermSelects termSelects : o1) {
                                o1Max = Math.max(termSelects.topMetric, o1Max);
                            }
                            double o2Max = Double.POSITIVE_INFINITY;
                            for (final TermSelects termSelects : o2) {
                                o2Max = Math.max(termSelects.topMetric, o2Max);
                            }
                            return Doubles.compare(o2Max, o1Max);
                        }
                    };
                    break;
                default:
                    throw new IllegalStateException();
            }
            for (int i = 1; i <= numGroups; i++) {
                qqs.put(i, BoundedPriorityQueue.newInstance(fieldLimitCount, queueComparator));
            }
        } else {
            for (int i = 1; i <= numGroups; i++) {
                qqs.put(i, new ArrayDeque<>());
            }
        }

        for (final Commands.Iterate.FieldWithOptions fieldWithOpts : iterate.fields) {
            final String field = fieldWithOpts.field;
            final Commands.Iterate.FieldIterateOpts opts = fieldWithOpts.opts;

            final DenseInt2ObjectMap<Queue<TermSelects>> pqs = new DenseInt2ObjectMap<>();
            if (opts.topK.isPresent()) {
                final Comparator<TermSelects> comparator = Comparator.comparing(x -> Double.isNaN(x.topMetric) ? Double.NEGATIVE_INFINITY : x.topMetric);
                for (int i = 1; i <= numGroups; i++) {
                    // TODO: If this type changes, then a line below with an instanceof check will break.
                    pqs.put(i, BoundedPriorityQueue.newInstance(opts.topK.get().limit, comparator));
                }
            } else {
                for (int i = 1; i <= numGroups; i++) {
                    pqs.put(i, new ArrayDeque<>());
                }
            }
            final AggregateMetric topKMetricOrNull;
            if (opts.topK.isPresent()) {
                topKMetricOrNull = opts.topK.get().metric;
                topKMetricOrNull.register(metricIndexes, groupKeys);
            } else {
                topKMetricOrNull = null;
            }
            final AggregateFilter filterOrNull = opts.filter.orElse(null);

            if (isIntField(field)) {
                iterateMultiInt(getSessionsMapRaw(), sessionMetricIndexes, field, new IntIterateCallback() {
                    @Override
                    public void term(long term, long[] stats, int group) {
                        if (filterOrNull != null && !filterOrNull.allow(term, stats, group)) {
                            return;
                        }
                        final double[] selectBuffer = new double[iterate.selecting.size()];
                        final double value;
                        if (topKMetricOrNull != null) {
                            value = topKMetricOrNull.apply(term, stats, group);
                        } else {
                            value = 0.0;
                        }
                        final List<AggregateMetric> selecting = iterate.selecting;
                        for (int i = 0; i < selecting.size(); i++) {
                            selectBuffer[i] = selecting.get(i).apply(term, stats, group);
                        }
                        pqs.get(group).offer(new TermSelects(field, true, null, term, selectBuffer, value, groupKeys.get(group)));
                    }
                });
            } else if (isStringField(field)) {
                iterateMultiString(getSessionsMapRaw(), sessionMetricIndexes, field, new StringIterateCallback() {
                    @Override
                    public void term(String term, long[] stats, int group) {
                        if (filterOrNull != null && !filterOrNull.allow(term, stats, group)) {
                            return;
                        }
                        final double[] selectBuffer = new double[iterate.selecting.size()];
                        final double value;
                        if (topKMetricOrNull != null) {
                            value = topKMetricOrNull.apply(term, stats, group);
                        } else {
                            value = 0.0;
                        }
                        final List<AggregateMetric> selecting = iterate.selecting;
                        for (int i = 0; i < selecting.size(); i++) {
                            selectBuffer[i] = selecting.get(i).apply(term, stats, group);
                        }
                        pqs.get(group).offer(new TermSelects(field, false, term, 0, selectBuffer, value, groupKeys.get(group)));
                    }
                });
            } else {
                throw new IllegalArgumentException("Field is neither all int nor all string field: " + field);
            }

            for (int i = 1; i <= numGroups; i++) {
                final Queue<TermSelects> pq = pqs.get(i);
                if (!pq.isEmpty()) {
                    qqs.get(i).offer(pq);
                }
            }
        }

        getSessionsMapRaw().values().forEach(session -> {
            while (session.getNumStats() != 0) {
                session.popStat();
            }
        });

        final List<List<List<TermSelects>>> allTermSelects = Lists.newArrayList();
        for (int group = 1; group <= numGroups; group++) {
            final Queue<Queue<TermSelects>> qq = qqs.get(group);
            final List<List<TermSelects>> groupTermSelects = Lists.newArrayList();
            while (!qq.isEmpty()) {
                final Queue<TermSelects> pq = qq.poll();
                final List<TermSelects> listTermSelects = Lists.newArrayList();
                while (!pq.isEmpty()) {
                    listTermSelects.add(pq.poll());
                }
                // TODO: This line is very fragile
                if (pq instanceof BoundedPriorityQueue) {
                    groupTermSelects.add(Lists.reverse(listTermSelects));
                } else {
                    groupTermSelects.add(listTermSelects);
                }
            }
            allTermSelects.add(groupTermSelects);
        }
        return allTermSelects;
    }

    private void registerMetrics(Map<QualifiedPush, Integer> metricIndexes, Iterable<AggregateMetric> metrics, Iterable<AggregateFilter> filters) {
        metrics.forEach(m -> m.register(metricIndexes, groupKeys));
        filters.forEach(f -> f.register(metricIndexes, groupKeys));
    }

    private void pushMetrics(Set<QualifiedPush> allPushes, Map<QualifiedPush, Integer> metricIndexes, Map<String, IntList> sessionMetricIndexes) throws ImhotepOutOfMemoryException {
        int numStats = 0;
        for (final QualifiedPush push : allPushes) {
            final int index = numStats++;
            metricIndexes.put(push, index);
            final String sessionName = push.sessionName;
            sessions.get(sessionName).session.pushStats(push.pushes);
            sessionMetricIndexes.computeIfAbsent(sessionName, k -> new IntArrayList()).add(index);
        }
    }

    private long getLatestEnd() {
        return sessions.values().stream().mapToLong(x -> x.endTime.getMillis()).max().getAsLong();
    }

    private long getEarliestStart() {
        return sessions.values().stream().mapToLong(x -> x.startTime.getMillis()).min().getAsLong();
    }

    private int performTimeRegroup(long start, long end, long unitSize, Optional<String> fieldOverride) {
        final int oldNumGroups = this.numGroups;
        sessions.values().forEach(sessionInfo -> unchecked(() -> {
            final ImhotepSession session = sessionInfo.session;
            session.pushStat(fieldOverride.orElseGet(() -> sessionInfo.timeFieldName));
            session.metricRegroup(0, start / 1000, end / 1000, unitSize / 1000, true);
            session.popStat();
        }));
        return (int) (oldNumGroups * Math.ceil(((double) end - start) / unitSize));
    }

    private void densify(Function<Integer, Pair<String, GroupKey>> indexedInfoProvider) throws ImhotepOutOfMemoryException {
        final BitSet anyPresent = new BitSet();
        getSessionsMapRaw().values().forEach(session -> unchecked(() -> {
            session.pushStat("count()");
            final long[] counts = session.getGroupStats(0);
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0L) {
                    anyPresent.set(i);
                }
            }
            session.popStat();
        }));

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
            getSessionsMapRaw().values().forEach(session -> unchecked(() -> session.regroup(ruleArray)));
        }

        numGroups = nextGroupKeys.size() - 1;
        groupKeys = nextGroupKeys;
    }

    private void assumeDense(Function<Integer, Pair<String, GroupKey>> indexedInfoProvider, int newNumGroups) throws ImhotepOutOfMemoryException {
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

    private Map<String, ImhotepSession> getSessionsMapRaw() {
        final Map<String, ImhotepSession> sessionMap = Maps.newHashMap();
        sessions.forEach((k,v) -> sessionMap.put(k, v.session));
        return sessionMap;
    }

    private Map<String, ImhotepSessionInfo> getSessionsMap() {
        return Maps.newHashMap(sessions);
    }

    private boolean isIntField(String field) {
        return sessions.values().stream().allMatch(x -> x.intFields.contains(field));
    }

    private boolean isStringField(String field) {
        return sessions.values().stream().allMatch(x -> x.stringFields.contains(field));
    }

    private AggregateMetric.PerGroupConstant namedMetricLookup(String name) {
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
        return new AggregateMetric.PerGroupConstant(stats);
    }

    public static void unchecked(RunnableWithException runnable) {
        try {
            runnable.run();
        } catch (final Throwable t) {
            log.error("unchecked error", t);
            throw Throwables.propagate(t);
        }
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
                return Optional.empty();
            }
            if (!it.nextTerm()) {
                return Optional.empty();
            }
            if (!it.nextGroup()) {
                return Optional.empty();
            }
            it.groupStats(statsBuff);
            return Optional.of(new SessionIntIterationState(it, sessionMetricIndexes, statsBuff, it.termIntVal(), it.group()));
        }
    }

    private interface IntIterateCallback {
        void term(long term, long[] stats, int group);
    }

    public static void iterateMultiInt(Map<String, ImhotepSession> sessions, Map<String, IntList> metricIndexes, String field, IntIterateCallback callback) throws IOException {
        final int numMetrics = metricIndexes.values().stream().mapToInt(IntList::size).sum();
        try (final Closer closer = Closer.create()) {
            final PriorityQueue<SessionIntIterationState> pq = new PriorityQueue<>((Comparator<SessionIntIterationState>)(x, y) -> {
                int r = Longs.compare(x.nextTerm, y.nextTerm);
                if (r != 0) return r;
                return Ints.compare(x.nextGroup, y.nextGroup);
            });
            for (final String sessionName : sessions.keySet()) {
                final ImhotepSession session = sessions.get(sessionName);
                final IntList sessionMetricIndexes = Objects.firstNonNull(metricIndexes.get(sessionName), new IntArrayList());
                SessionIntIterationState.construct(closer, session, field, sessionMetricIndexes).ifPresent(pq::add);
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
                return Optional.empty();
            }
            if (!it.nextTerm()) {
                return Optional.empty();
            }
            if (!it.nextGroup()) {
                return Optional.empty();
            }
            it.groupStats(statsBuff);
            return Optional.of(new SessionStringIterationState(it, sessionMetricIndexes, statsBuff, it.termStringVal(), it.group()));
        }
    }

    private interface StringIterateCallback {
        void term(String term, long[] stats, int group);
    }

    public static void iterateMultiString(Map<String, ImhotepSession> sessions, Map<String, IntList> metricIndexes, String field, StringIterateCallback callback) throws IOException {
        final int numMetrics = metricIndexes.values().stream().mapToInt(IntList::size).sum();
        try (final Closer closer = Closer.create()) {
            final PriorityQueue<SessionStringIterationState> pq = new PriorityQueue<>((Comparator<SessionStringIterationState>)(x, y) -> {
                int r = x.nextTerm.compareTo(y.nextTerm);
                if (r != 0) return r;
                return Ints.compare(x.nextGroup, y.nextGroup);
            });
            for (final String sessionName : sessions.keySet()) {
                final ImhotepSession session = sessions.get(sessionName);
                final IntList sessionMetricIndexes = Objects.firstNonNull(metricIndexes.get(sessionName), new IntArrayList());
                SessionStringIterationState.construct(closer, session, field, sessionMetricIndexes).ifPresent(pq::add);
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

    public static List<GroupStats> getGroupStats(Commands.GetGroupStats getGroupStats, List<GroupKey> groupKeys, Map<String, ImhotepSession> sessions, int numGroups, boolean returnGroupKeys) throws ImhotepOutOfMemoryException {
        final Set<QualifiedPush> pushesRequired = Sets.newHashSet();
        getGroupStats.metrics.forEach(metric -> pushesRequired.addAll(metric.requires()));
        final Map<QualifiedPush, Integer> metricIndexes = Maps.newHashMap();
        final Map<String, IntList> sessionMetricIndexes = Maps.newHashMap();
        int numStats = 0;
        for (final QualifiedPush push : pushesRequired) {
            final int index = numStats++;
            metricIndexes.put(push, index);
            final String sessionName = push.sessionName;
            sessions.get(sessionName).pushStats(push.pushes);
            sessionMetricIndexes.computeIfAbsent(sessionName, k -> new IntArrayList()).add(index);
        }

        getGroupStats.metrics.forEach(metric -> metric.register(metricIndexes, groupKeys));

        final long[][] allStats = new long[numStats][];
        sessionMetricIndexes.forEach((name, positions) -> {
            final ImhotepSession session = sessions.get(name);
            for (int i = 0; i < positions.size(); i++) {
                allStats[positions.get(i)] = session.getGroupStats(i);
            }
        });

        final List<AggregateMetric> selectedMetrics = getGroupStats.metrics;
        final double[][] results = new double[numGroups][selectedMetrics.size()];
        final long[] groupStatsBuf = new long[allStats.length];
        for (int group = 1; group <= numGroups; group++) {
            for (int j = 0; j < allStats.length; j++) {
                groupStatsBuf[j] = allStats[j].length > group ? allStats[j][group] : 0L;
            }
            for (int j = 0; j < selectedMetrics.size(); j++) {
                results[group - 1][j] = selectedMetrics.get(j).apply(0, groupStatsBuf, group);
            }
        }

        final List<GroupStats> groupStats = Lists.newArrayList();
        for (int i = 0; i < numGroups; i++) {
            final GroupKey groupKey;
            if (returnGroupKeys) {
                groupKey = groupKeys.get(i + 1);
            } else {
                groupKey = null;
            }
            groupStats.add(new GroupStats(groupKey, results[i]));
        }

        sessions.values().forEach(session -> {
            while (session.getNumStats() > 0) {
                session.popStat();
            }
        });

        return groupStats;
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

        private GroupKey(String term, int index, GroupKey parent) {
            this.term = term;
            this.index = index;
            this.parent = parent;
        }

        public List<String> asList() {
            final List<String> keys = Lists.newArrayList();
            GroupKey node = this;
            while (node != null && node.term != null) {
                keys.add(node.term);
                node = node.parent;
            }
            return Lists.reverse(keys);
        }
    }

    public interface RunnableWithException {
        void run() throws Throwable;
    }

    private static class SavedGroupStats {
        public final int depth;
        public final double[] stats;

        private SavedGroupStats(int depth, double[] stats) {
            this.depth = depth;
            this.stats = stats;
        }
    }

    static class ImhotepSessionInfo {
        public final ImhotepSession session;
        public final Collection<String> intFields;
        public final Collection<String> stringFields;
        public final DateTime startTime;
        public final DateTime endTime;
        public final String timeFieldName;

        private ImhotepSessionInfo(ImhotepSession session, Collection<String> intFields, Collection<String> stringFields, DateTime startTime, DateTime endTime, String timeFieldName) {
            this.session = session;
            this.intFields = Collections.unmodifiableCollection(intFields);
            this.stringFields = Collections.unmodifiableCollection(stringFields);
            this.startTime = startTime;
            this.endTime = endTime;
            this.timeFieldName = timeFieldName;
        }
    }
}
