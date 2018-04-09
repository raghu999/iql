package com.indeed.squall.iql2.server.web.servlets;

import com.google.common.collect.ImmutableList;
import com.indeed.squall.iql2.server.web.servlets.dataset.Dataset;
import com.indeed.squall.iql2.server.web.servlets.dataset.OrganicDataset;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.indeed.squall.iql2.server.web.servlets.QueryServletTestUtils.addConstantColumn;
import static com.indeed.squall.iql2.server.web.servlets.QueryServletTestUtils.testAll;
import static com.indeed.squall.iql2.server.web.servlets.QueryServletTestUtils.testIQL2;

public class FieldRegroupTest extends BasicTest {
    final Dataset dataset = OrganicDataset.create();

    @Test
    public void testBasicGroupBy() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("0", "2", "0"));
        expected.add(ImmutableList.of("1", "84", "84"));
        expected.add(ImmutableList.of("2", "1", "2"));
        expected.add(ImmutableList.of("3", "60", "180"));
        expected.add(ImmutableList.of("5", "1", "5"));
        expected.add(ImmutableList.of("10", "2", "20"));
        expected.add(ImmutableList.of("15", "1", "15"));
        testAll(dataset, expected, "from organic yesterday today group by ojc select count(), ojc", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc, allbit select count(), ojc", true);
    }

    @Test
    public void testFirstK() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("0", "2", "0"));
        expected.add(ImmutableList.of("1", "84", "84"));
        expected.add(ImmutableList.of("2", "1", "2"));
        testAll(dataset, expected, "from organic yesterday today group by ojc select count(), ojc LIMIT 3", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc, allbit select count(), ojc LIMIT 3", true);
    }

    @Test
    public void testImplicitOrdering() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("1", "84", "84"));
        expected.add(ImmutableList.of("3", "60", "180"));
        // TODO: Introduce fully deterministic ordering for ties and increase to top 3?
//        expected.add(ImmutableList.of("0", "2", "0"));
        testAll(dataset, expected, "from organic yesterday today group by ojc[2] select count(), ojc", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc[2], allbit select count(), ojc", true);
    }

    // IF THIS BREAKS, READ THE TODO BEFORE TRYING TO FIGURE OUT WHAT YOU DID
    @Test
    public void testImplicitOrderingBackwards() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        // TODO: Determinism amongst ties? This will almost certainly break
        expected.add(ImmutableList.of("15", "1", "15"));
        expected.add(ImmutableList.of("5", "1", "5"));
        expected.add(ImmutableList.of("2", "1", "2"));
        testAll(dataset, expected, "from organic yesterday today group by ojc[BOTTOM 3] select count(), ojc", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc[BOTTOM 3], allbit select count(), ojc", true);
    }

    @Test
    public void testTopKOrdering() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("15", "1", "15"));
        expected.add(ImmutableList.of("10", "2", "20"));
        expected.add(ImmutableList.of("5", "1", "5"));
        expected.add(ImmutableList.of("3", "60", "180"));
        expected.add(ImmutableList.of("2", "1", "2"));
        expected.add(ImmutableList.of("1", "84", "84"));
        expected.add(ImmutableList.of("0", "2", "0"));
        testAll(dataset, expected, "from organic yesterday today group by ojc[100 BY ojc/count()] select count(), ojc", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc[100 BY ojc/count()], allbit select count(), ojc", true);
    }

    @Test
    public void testTopKBottomOrdering() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("0", "2", "0"));
        expected.add(ImmutableList.of("2", "1", "2"));
        expected.add(ImmutableList.of("5", "1", "5"));
        testAll(dataset, expected, "from organic yesterday today group by ojc[BOTTOM 3 BY ojc] select count(), ojc", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc[BOTTOM 3 BY ojc], allbit select count(), ojc", true);
    }

    @Test
    public void testOrderingOnly() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("15", "1", "15"));
        expected.add(ImmutableList.of("10", "2", "20"));
        expected.add(ImmutableList.of("5", "1", "5"));
        expected.add(ImmutableList.of("3", "60", "180"));
        expected.add(ImmutableList.of("2", "1", "2"));
        expected.add(ImmutableList.of("1", "84", "84"));
        expected.add(ImmutableList.of("0", "2", "0"));
        testAll(dataset, expected, "from organic yesterday today group by ojc[BY ojc/count()] select count(), ojc", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc[BY ojc/count()], allbit select count(), ojc", true);
    }

    @Test
    public void testImplicitOrderingLimit() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("1", "84", "84"));
        expected.add(ImmutableList.of("3", "60", "180"));
        // TODO: Introduce fully deterministic ordering for ties and increase to top 3?
        testAll(dataset, expected, "from organic yesterday today group by ojc[5] select count(), ojc limit 2", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc[5], allbit select count(), ojc limit 2", true);
    }

    @Test
    public void testTopKOrderingLimit() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("15", "1", "15"));
        expected.add(ImmutableList.of("10", "2", "20"));
        testAll(dataset, expected, "from organic yesterday today group by ojc[100 BY ojc/count()] select count(), ojc limit 2", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected), "from organic yesterday today group by ojc[100 BY ojc/count()], allbit select count(), ojc limit 2", true);
    }

    @Test
    public void testRandomRegroup() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("No term", "0"));
        expected.add(ImmutableList.of("1", "8"));
        expected.add(ImmutableList.of("2", "3"));
        expected.add(ImmutableList.of("3", "140"));
        testIQL2(dataset, expected, "from organic yesterday today group by random(oji, 3, \"SomeRandomSalt\") select count()", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected.subList(1, expected.size())), "from organic yesterday today group by random(oji, 3, \"SomeRandomSalt\"), allbit select count()", true);
    }

    @Test
    public void testRandomRegroupByDocId() throws Exception {
        final List<List<String>> expected = new ArrayList<>();
        expected.add(ImmutableList.of("No term", "0"));
        expected.add(ImmutableList.of("1", "68"));
        expected.add(ImmutableList.of("2", "38"));
        expected.add(ImmutableList.of("3", "45"));
        testIQL2(dataset, expected, "from organic yesterday today group by random(docId(), 3, \"SomeRandomSalt\") select count()", true);
        testIQL2(dataset, addConstantColumn(1, "1", expected.subList(1, expected.size())), "from organic yesterday today group by random(docId(), 3, \"SomeRandomSalt\"), allbit select count()", true);
    }
}
