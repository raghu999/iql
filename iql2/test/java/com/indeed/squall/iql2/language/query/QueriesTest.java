package com.indeed.squall.iql2.language.query;

import com.google.common.collect.ImmutableList;
import com.indeed.common.util.time.DefaultWallClock;
import com.indeed.common.util.time.ResettableWallClock;
import com.indeed.squall.iql2.language.JQLParser;
import com.indeed.squall.iql2.language.metadata.DatasetsMetadata;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 *
 */
public class QueriesTest {

    @Test
    public void testSplitQuery() {
        final ResettableWallClock clock = new ResettableWallClock(DateTime.parse("2015-01-02").withZone(DateTimeZone.forOffsetHours(-6)).getMillis());
        {
            final String query = "FROM jobsearch 1d 0d";
            Assert.assertEquals(
                    new SplitQuery("jobsearch 1d 0d", "", "", "", "", ImmutableList.of("", "count()"),
                            ImmutableList.of(), ImmutableList.of(), "jobsearch",
                            "2015-01-01T00:00:00.000-06:00", "1d", "2015-01-02T00:00:00.000-06:00", "0d",
                            extractDatasetsHelper(query, false)),
                    Queries.parseSplitQuery(query, true, clock));
        }
        {
            String query = "FROM jobsearch(ojc < 10 tk='a') 1d 0d WHERE ojc > 10 AND country='us' GROUP BY country[TOP BY ojc HAVING oji > 0], ctk SELECT count(), oji";
            Assert.assertEquals(
                    new SplitQuery("jobsearch(ojc < 10 tk='a') 1d 0d",
                            "ojc > 10 AND country='us'", "country[TOP BY ojc HAVING oji > 0], ctk", "count(), oji", "",
                            extractHeadersHelper(query, false),
                            ImmutableList.of("country[TOP BY ojc HAVING oji > 0]", "ctk"), ImmutableList.of("count()", "oji"), "jobsearch",
                            "2015-01-01T00:00:00.000-06:00", "1d", "2015-01-02T00:00:00.000-06:00", "0d",
                            extractDatasetsHelper(query, false)),
                    Queries.parseSplitQuery(query, false, clock));
        }

        {
            final String query = "FROM jobsearch 1d 0d /* mid */, mobsearch /* after */ " +
                    "WHERE /* before */ ojc > 10 /* mid */ OR country='us' /* after */ " +
                    "GROUP BY /* before */ country /* mid */, ctk /* after */ " +
                    "SELECT /* before */ count() /* num */, oji /* impression */";
            Assert.assertEquals(
                    new SplitQuery("jobsearch 1d 0d /* mid */, mobsearch /* after */",
                            "/* before */ ojc > 10 /* mid */ OR country='us' /* after */",
                            "/* before */ country /* mid */, ctk /* after */",
                            "/* before */ count() /* num */, oji /* impression */", "",
                            ImmutableList.of("country", "ctk", "count()", "oji"),
                            ImmutableList.of("country", "ctk"), ImmutableList.of("count()", "oji"),
                            "", "", "", "", "",
                            extractDatasetsHelper(query, false)),
                    Queries.parseSplitQuery(query, false, clock));
        }
    }

    @Test
    public void extractHeaders() throws Exception {
        final boolean useLegacy = false;
        Assert.assertEquals(ImmutableList.of("", "count()"),
                extractHeadersHelper("FROM jobsearch 1d 0d", useLegacy));
        Assert.assertEquals(ImmutableList.of("oji", "count()"),
                extractHeadersHelper("FROM jobsearch 1d 0d GROUP BY oji", useLegacy));
        Assert.assertEquals(ImmutableList.of("oji", "total_count", "o1.count()", "add"),
                extractHeadersHelper("FROM jobsearch 1d 0d as o1, mobsearch as o2 GROUP BY oji " +
                        "SELECT count() as total_count, o1.count(), o1.a+o2.b as add", useLegacy));

    }

    private List<String> extractHeadersHelper(final String q, final boolean useLegacy) {
        final Query query = Queries.parseQuery(
                q, useLegacy, DatasetsMetadata.empty(), new DefaultWallClock()).query;
        final JQLParser.QueryContext queryContext = Queries.parseQueryContext(q, useLegacy);
        return Queries.extractHeaders(query, queryContext.start.getInputStream());
    }

    @Test
    public void extractDatasets() {
        Assert.assertEquals(
                ImmutableList.of(new SplitQuery.Dataset("jobsearch", "", "1d", "0d", "j1", "")),
                extractDatasetsHelper("FROM jobsearch 1d 0d as j1", true));
        Assert.assertEquals(
                ImmutableList.of(new SplitQuery.Dataset("jobsearch", "oji=1 tk='1'", "1d", "0d", "", "ALIASING(oji as o, ojc as c)")),
                extractDatasetsHelper("FROM jobsearch(oji=1 tk='1') 1d 0d ALIASING(oji as o, ojc as c)", false));
        Assert.assertEquals(
                ImmutableList.of(
                        new SplitQuery.Dataset("jobsearch", "oji=1", "1d", "0d", "j1", ""),
                        new SplitQuery.Dataset("jobsearch", "", "2d", "1d", "j2", ""),
                        new SplitQuery.Dataset("mobsearch", "", "1d", "0d", "", "ALIASING(ojc as c)")
                ),
                extractDatasetsHelper("FROM /* dumb */ jobsearch(oji=1) 1d 0d as j1, jobsearch 2d 1d as j2 /* dumb */, mobsearch ALIASING(ojc as c) /* dumb */ GROUP BY oji", false)
        );
    }

    private List<SplitQuery.Dataset> extractDatasetsHelper(final String q, final boolean useLegacy) {
        final JQLParser.QueryContext queryContext = Queries.parseQueryContext(q, useLegacy);
        return Queries.extractDatasets(queryContext.fromContents(), queryContext.start.getInputStream());
    }
}