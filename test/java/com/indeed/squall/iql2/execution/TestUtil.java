package com.indeed.squall.iql2.execution;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closer;
import com.indeed.flamdex.MemoryFlamdex;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.ImhotepSession;
import com.indeed.imhotep.local.ImhotepLocalSession;
import com.indeed.squall.iql2.execution.commands.Command;
import com.indeed.squall.iql2.execution.commands.GetGroupDistincts;
import com.indeed.squall.iql2.execution.commands.GetGroupStats;
import com.indeed.squall.iql2.execution.compat.Consumer;
import com.indeed.squall.iql2.execution.dimensions.DatasetDimensions;
import com.indeed.squall.iql2.execution.dimensions.DimensionDetails;
import com.indeed.squall.iql2.execution.metrics.aggregate.AggregateMetric;
import com.indeed.squall.iql2.execution.metrics.aggregate.DocumentLevelMetric;
import com.indeed.squall.iql2.execution.progress.NoOpProgressCallback;
import com.indeed.util.core.TreeTimer;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Ignore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Ignore
public class TestUtil {
    public static void testOne(final List<Document> documents, final List<Command> commands, DateTime start, DateTime end) throws IOException, ImhotepOutOfMemoryException {
        try (final Closer closer = Closer.create()) {
            final Map<String, MemoryFlamdex> datasetFlamdexes = new HashMap<>();
            final Map<String, Set<String>> datasetIntFields = new HashMap<>();
            final Map<String, Set<String>> datasetStringFields = new HashMap<>();
            for (final Document document : documents) {
                if (!datasetFlamdexes.containsKey(document.dataset)) {
                    datasetFlamdexes.put(document.dataset, closer.register(new MemoryFlamdex()));
                    datasetIntFields.put(document.dataset, new HashSet<String>());
                    datasetStringFields.put(document.dataset, new HashSet<String>());
                }
                datasetFlamdexes.get(document.dataset).addDocument(document.asFlamdex());
                datasetIntFields.get(document.dataset).addAll(document.intFields.keySet());
                datasetStringFields.get(document.dataset).addAll(document.stringFields.keySet());
            }
            final DatasetDimensions dimensions = new DatasetDimensions(ImmutableMap.<String, DimensionDetails>of());
            final Map<String, Session.ImhotepSessionInfo> sessionInfoMap = new HashMap<>();
            for (final Map.Entry<String, MemoryFlamdex> entry : datasetFlamdexes.entrySet()) {
                final ImhotepSession session = new ImhotepLocalSession(entry.getValue());
                sessionInfoMap.put(entry.getKey(), new Session.ImhotepSessionInfo(session, dimensions, datasetIntFields.get(entry.getKey()), datasetStringFields.get(entry.getKey()), start, end, "unixtime"));
            }

            final Session session = new Session(sessionInfoMap, new TreeTimer(), new NoOpProgressCallback());
            final SimpleSession simpleSession = new SimpleSession(documents, start, end);

            for (final Command command : commands) {
                System.out.println("Running " + command);

                final SavingConsumer<String> out1 = new SavingConsumer<>();
                command.execute(session, out1);

                final SavingConsumer<String> out2 = new SavingConsumer<>();
                simpleSession.handleCommand(command, out2);

                final List<String> results1 = out1.getElements();
                final List<String> results2 = out2.getElements();
                Assert.assertEquals(results2, results1);

                System.out.println(results1);

                // TODO: Do some non-effectful operations to measure more equivalences.
            }
        }
    }

    static List<Command> makeVerificationCommands(List<Document> documents) {
        final Map<String, Set<String>> datasetIntFields = new HashMap<>();
        final Map<String, Set<String>> datasetStringFields = new HashMap<>();
        for (final Document document : documents) {
            if (!datasetIntFields.containsKey(document.dataset)) {
                datasetIntFields.put(document.dataset, new HashSet<String>());
                datasetStringFields.put(document.dataset, new HashSet<String>());
            }
            datasetIntFields.get(document.dataset).addAll(document.intFields.keySet());
            datasetStringFields.get(document.dataset).addAll(document.stringFields.keySet());
        }
        final List<Command> commands = new ArrayList<>();
        final List<AggregateMetric> metrics = new ArrayList<>();
        for (final String dataset : datasetIntFields.keySet()) {
            metrics.add(new DocumentLevelMetric(dataset, Collections.singletonList("count()")));
            for (final String field : datasetIntFields.get(dataset)) {
                metrics.add(new DocumentLevelMetric(dataset, Collections.singletonList(field)));
            }
            for (final String field : datasetStringFields.get(dataset)) {
                commands.add(new GetGroupDistincts(Collections.singleton(dataset), field, Optional.<AggregateFilter>absent(), 1));
            }
        }
        commands.add(new GetGroupStats(metrics, false));
        return commands;
    }

    public static class SavingConsumer<T> implements Consumer<T> {
        private final List<T> ts = new ArrayList<>();

        @Override
        public void accept(T t) {
            ts.add(t);
        }

        public List<T> getElements() {
            return Collections.unmodifiableList(ts);
        }

        public void clear() {
            ts.clear();
        }
    }
}
