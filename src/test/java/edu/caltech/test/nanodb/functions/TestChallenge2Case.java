package edu.caltech.test.nanodb.functions;

import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.server.CommandResult;

@Test(groups={"challenge2"})
public class TestChallenge2Case extends SqlTestCase {
    private Object value(String sql) throws Exception {
        CommandResult result=server.doCommand(sql,true);if(result.failed())throw result.getFailure();
        return result.getTuples().get(0).getColumnValue(0);
    }
    public void testSimpleSearchedNullAndShortCircuit() throws Exception {
        Assert.assertEquals(value("SELECT CASE WHEN 1=1 THEN 'chosen' ELSE 'unselected' END"),"chosen");
        Assert.assertEquals(value("SELECT CASE WHEN NULL THEN 'wrong' WHEN 2=2 THEN 'right' END"),"right");
        Assert.assertNull(value("SELECT CASE WHEN 1=2 THEN 5 END"));
        Assert.assertEquals(value("SELECT CASE NULL WHEN NULL THEN 'wrong' ELSE 'right' END"),"right");
        Assert.assertEquals(value("SELECT CASE 2 WHEN 1 THEN 'one' WHEN 2 THEN 'two' END"),"two");
        Assert.assertEquals(((Number)value("SELECT CASE WHEN 1=1 THEN 7 ELSE 1/0 END")).intValue(),7);
        Assert.assertEquals(((Number)value("SELECT CASE WHEN 1=2 THEN 1/0 ELSE 8 END")).intValue(),8);
    }
    public void testCaseInsideAggregateAndNumericPromotion() throws Exception {
        Assert.assertEquals(new java.math.BigDecimal(value("SELECT CASE WHEN 1=1 THEN 1 ELSE 2.5 END").toString()).compareTo(new java.math.BigDecimal("1")),0);
        CommandResult create=server.doCommand("CREATE TABLE case_rows(k INTEGER,v INTEGER)",false);
        Assert.assertFalse(create.failed());
        for(int k=0;k<3;k++)Assert.assertFalse(server.doCommand("INSERT INTO case_rows VALUES("+k+",10)",false).failed());
        Assert.assertEquals(((Number)value("SELECT SUM(CASE WHEN k>0 THEN v ELSE 0 END) FROM case_rows")).intValue(),20);
        Assert.assertEquals(((Number)value("SELECT CASE WHEN 1=1 THEN CASE 3 WHEN 3 THEN 9 END ELSE 0 END")).intValue(),9);
    }
}
