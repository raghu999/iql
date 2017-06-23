package com.indeed.squall.iql2.server.print;

import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class PrettyPrintTest {
    @Test
    public void prettyPrint() throws Exception {
        Assert.assertEquals("FROM jobsearch 1d 0d\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch w2d t"));
        Assert.assertEquals("FROM jobsearch 2d 1d\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch 2d 1d select count()"));
        Assert.assertEquals("FROM jobsearch /* hi */ 2d 1d\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch /* hi */ 2d 1d select count()"));
        Assert.assertEquals("FROM jobsearch 2d 1d\nSELECT /*before*/ count() /* AFTER */", PrettyPrint.prettyPrint("from jobsearch 2d 1d select /*before*/count()/* AFTER */"));
        Assert.assertEquals("FROM jobsearch 2d 1d\nGROUP BY time(1week)\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch 2d 1d GROUP BY time(1week) select count()"));
        Assert.assertEquals("FROM jobsearch 2d 1d\nGROUP BY time(1day1hour)\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch 2d 1d GROUP BY time(1d1h) select count()"));
        Assert.assertEquals("FROM jobsearch 2d 1d\nGROUP BY time(1day1hour2seconds)\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch 2d 1d GROUP BY time(90002s) select count()"));
        Assert.assertEquals("FROM jobsearch yesterday today\nSELECT oji", PrettyPrint.prettyPrint("from jobsearch yesterday today select oji"));
        Assert.assertEquals("FROM jobsearch yesterday today\nSELECT (oji + ojc)", PrettyPrint.prettyPrint("from jobsearch yesterday today select oji+ojc"));
        Assert.assertEquals("FROM jobsearch yesterday today\nWHERE (country=\"us\") (oji=10)\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch yesterday today where country:\"us\" oji:10 select count()"));
        Assert.assertEquals("FROM jobsearch yesterday today\nWHERE (ctk=~\".*\")\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch yesterday today where ctk=~\".*\" select count()"));
        Assert.assertEquals("FROM jobsearch yesterday today\nWHERE (escaped=\"stuff\\\"\")\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch yesterday today where escaped='stuff\"' select count()"));
        Assert.assertEquals("FROM jobsearch 2d 1d\nWHERE (not(country=\"us\")) (not(country=\"us\"))\nSELECT count()", PrettyPrint.prettyPrint("from jobsearch 2d 1d WHERE -country:us and -country=us"));
    }

    @Test
    public void stringEscape() throws Exception {
        Assert.assertEquals("abc\\\"def", PrettyPrint.stringEscape("abc\"def"));
        Assert.assertEquals("abc\\ndef", PrettyPrint.stringEscape("abc\ndef"));
    }
}