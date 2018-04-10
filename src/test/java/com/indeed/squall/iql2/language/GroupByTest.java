/*
 * Copyright (C) 2018 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.indeed.squall.iql2.language;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.indeed.util.core.time.StoppedClock;
import com.indeed.util.core.time.WallClock;
import com.indeed.squall.iql2.language.compat.Consumer;
import com.indeed.squall.iql2.language.query.GroupBy;
import com.indeed.squall.iql2.language.query.GroupBys;
import com.indeed.squall.iql2.language.query.Queries;
import com.indeed.squall.iql2.language.metadata.DatasetsMetadata;
import junit.framework.Assert;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.Collections;

public class GroupByTest {
    public static final Consumer<String> WARN = new Consumer<String>() {
        @Override
        public void accept(String s) {
            System.out.println("PARSE WARNING: " + s);
        }
    };

    private static final WallClock CLOCK = new StoppedClock(new DateTime(2015, 2, 1, 0, 0, DateTimeZone.forOffsetHours(-6)).getMillis());

    public static final Function<JQLParser, GroupByMaybeHaving> PARSE_IQL1_GROUP_BY = new Function<JQLParser, GroupByMaybeHaving>() {
        @Override
        public GroupByMaybeHaving apply(JQLParser input) {
            return GroupBys.parseGroupByMaybeHaving(input.groupByElementWithHaving(true), Collections.emptyList(), DatasetsMetadata.empty(), WARN, CLOCK);
        }
    };

    public static final Function<JQLParser, GroupByMaybeHaving> PARSE_IQL2_GROUP_BY = new Function<JQLParser, GroupByMaybeHaving>() {
        @Override
        public GroupByMaybeHaving apply(JQLParser input) {
            return GroupBys.parseGroupByMaybeHaving(input.groupByElementWithHaving(false), Collections.emptyList(), DatasetsMetadata.empty(), WARN, CLOCK);
        }
    };

    @Test
    public void groupByMetric() throws Exception {
        final GroupByMaybeHaving bucketOji1to10by1 = new GroupByMaybeHaving(new GroupBy.GroupByMetric(new DocMetric.Field("OJI"), 1, 10, 1, false, false), Optional.<AggregateFilter>absent(), Optional.<String>absent());
        Assert.assertEquals(bucketOji1to10by1, Queries.runParser("bucket(oji, 1, 10, 1)", PARSE_IQL1_GROUP_BY));
        Assert.assertEquals(bucketOji1to10by1, Queries.runParser("bucket(oji, 1, 10, 1)", PARSE_IQL2_GROUP_BY));
        Assert.assertEquals(bucketOji1to10by1, Queries.runParser("oji from 1 to 10 by 1)", PARSE_IQL2_GROUP_BY));

        final GroupByMaybeHaving bucketOji1to10by2NoGutter = new GroupByMaybeHaving(new GroupBy.GroupByMetric(new DocMetric.Field("OJI"), 1, 10, 2, true, false), Optional.<AggregateFilter>absent(), Optional.<String>absent());
        Assert.assertEquals(bucketOji1to10by2NoGutter, Queries.runParser("BUCKETS(oji, 1, 10, 2, true)", PARSE_IQL1_GROUP_BY));
        Assert.assertEquals(bucketOji1to10by2NoGutter, Queries.runParser("BUCKETS(oji, 1, 10, 2, true)", PARSE_IQL2_GROUP_BY));

        final GroupByMaybeHaving withNegatives = new GroupByMaybeHaving(new GroupBy.GroupByMetric(new DocMetric.Field("OJI"), -10, 10, 1, false, false), Optional.<AggregateFilter>absent(), Optional.<String>absent());
        Assert.assertEquals(withNegatives, Queries.runParser("BUCKETS(oji, -10, 10, 1)", PARSE_IQL1_GROUP_BY));
        Assert.assertEquals(withNegatives, Queries.runParser("BUCKETS(oji, -10, 10, 1)", PARSE_IQL2_GROUP_BY));
        Assert.assertEquals(withNegatives, Queries.runParser("oji FROM -10 TO 10 BY 1", PARSE_IQL2_GROUP_BY));

        final GroupByMaybeHaving withDefault = new GroupByMaybeHaving(new GroupBy.GroupByMetric(new DocMetric.Field("OJI"), -10, 10, 1, true, true), Optional.<AggregateFilter>absent(), Optional.<String>absent());
        Assert.assertEquals(withDefault, Queries.runParser("BUCKET(oji, -10, 10, 1) WITH DEFAULT", PARSE_IQL2_GROUP_BY));
        Assert.assertEquals(withDefault, Queries.runParser("oji FROM -10 TO 10 BY 1 WITH DEFAULT", PARSE_IQL2_GROUP_BY));
    }
}
