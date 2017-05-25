package com.indeed.squall.iql2.server.web.servlets;

import com.google.common.collect.Lists;
import com.indeed.flamdex.writer.FlamdexDocument;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.List;

class FieldEqualDataset {
    static {
        DateTimeZone.setDefault(DateTimeZone.forOffsetHours(-6));
    }

    // fields [time, s1, s2, i1, i2]
    public static Dataset create() {
        final List<Dataset.DatasetShard> shards = Lists.newArrayList();
        final Dataset.DatasetFlamdex flamdex = new Dataset.DatasetFlamdex();
        flamdex.addDocument(makeDocument(new DateTime(2015, 1, 1, 0, 0, 0), 1, 1, "a", "a"));
        flamdex.addDocument(makeDocument(new DateTime(2015, 1, 1, 0, 0, 30), 1, 1, "a", "b"));
        flamdex.addDocument(makeDocument(new DateTime(2015, 1, 1, 0, 1, 15), 1, 2, "a", "b"));
        flamdex.addDocument(makeDocument(new DateTime(2015, 1, 1, 0, 10, 0), 2, 2, "b", "b"));
        shards.add(new Dataset.DatasetShard("organic", "index20150101.00", flamdex));
        return new Dataset(shards);
    }

    private static FlamdexDocument makeDocument(DateTime timestamp, int i1, int i2, String s1, String s2) {
        final FlamdexDocument doc = new FlamdexDocument();
        doc.addIntTerm("unixtime", timestamp.getMillis() / 1000);
        doc.addIntTerm("i1", i1);
        doc.addIntTerm("i2", i2);
        doc.addStringTerm("s1", s1);
        doc.addStringTerm("s2", s2);

        // TODO: This is a work-around for MemoryFlamdex not handling missing fields.
        doc.addIntTerm("fakeField", 0);
        return doc;
    }
}
