package com.indeed.squall.iql2.server.web.servlets;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import java.util.List;

import static com.indeed.squall.iql2.server.web.servlets.QueryServletTestUtils.testAll;

/**
 * @author zheli
 */

public class LuceneTest extends BasicTest {
    final Dataset dataset = OrganicDataset.create();

    @Test
    public void testBasic() throws Exception {
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "1")), "from organic yesterday today select lucene(\"oji:3\")", false);
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "4")), "from organic yesterday today select lucene(\"tk:a\")", false);
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "2")), "from organic yesterday today select lucene(\"tk:b\")", false);
    }

    @Test
    public void testCaseInsensitiveLuceneFilters() throws Exception {
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "1")), "from organic yesterday today select lucene(\"OJI:3\")", false);
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "1")), "from organic yesterday today select lucene(\"Oji:3\")", false);
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "4")), "from organic yesterday today select lucene(\"tk:a\")", false);
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "2")), "from organic yesterday today select lucene(\"tK:b\")", false);
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "4")), "from organic yesterday today select lucene(\"Tk:c\")", false);
        testAll(dataset, ImmutableList.<List<String>>of(ImmutableList.of("", "143")), "from organic yesterday today select lucene(\"tk:d OR Tk:b\")", false);
    }
}
