package edu.caltech.test.nanodb.functions;

import java.math.BigDecimal;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.server.CommandResult;

@Test(groups={"challenge2"})
public class TestChallenge2Temporal extends SqlTestCase {
    private void number(String sql,String expected) throws Exception {
        CommandResult result=server.doCommand("SELECT "+sql,true);
        if(result.failed())throw result.getFailure();
        Assert.assertEquals(new BigDecimal(result.getTuples().get(0).getColumnValue(0).toString()).compareTo(new BigDecimal(expected)),0);
    }
    public void testExtractCalendarAndTimeFields() throws Exception {
        number("extract(year from date '1996-02-29')","1996");
        number("extract(month from date '1996-02-29')","2");
        number("extract(day from date '1996-02-29')","29");
        number("extract(hour from time '23:59:58.123456')","23");
        number("extract(minute from timestamp '2001-12-31 23:59:58.123456')","59");
        number("extract(second from time '23:59:58.123456')","58.123456");
        number("extract('year',date '2000-01-01')","2000");
    }
    public void testQualifiedIntervalsAndNulls() throws Exception {
        number("extract(year from date '1995-01-01' + interval '1' year)","1996");
        number("extract(month from date '1995-01-01' + interval '3' month)","4");
        number("extract(day from date '1998-12-01' - interval '90' day (3))","2");
        CommandResult result=server.doCommand("SELECT extract(year from NULL)",true);
        Assert.assertFalse(result.failed());Assert.assertNull(result.getTuples().get(0).getColumnValue(0));
        Assert.assertTrue(server.doCommand("SELECT extract(hour from date '2000-01-01')",true).failed());
        Assert.assertTrue(server.doCommand("SELECT extract(unknown from date '2000-01-01')",true).failed());
        Assert.assertTrue(server.doCommand("SELECT date '2001-02-29'",true).failed());
    }
}
