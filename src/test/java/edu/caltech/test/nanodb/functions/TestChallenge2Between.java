package edu.caltech.test.nanodb.functions;

import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.server.CommandResult;

@Test(groups={"challenge2"})
public class TestChallenge2Between extends SqlTestCase {
    private Object value(String expression) throws Exception {
        CommandResult result = server.doCommand("SELECT " + expression, true);
        if (result.failed()) throw result.getFailure();
        return result.getTuples().get(0).getColumnValue(0);
    }
    public void testBooleanSeparatorAndArithmeticBounds() throws Exception {
        Assert.assertEquals(value("5 BETWEEN 1+1 AND 3*3 AND 2=2"), true);
        Assert.assertEquals(value("5 BETWEEN 1 AND 9 AND 1=2"), false);
        Assert.assertEquals(value("5 BETWEEN 1 AND 9 OR 1=2"), true);
        Assert.assertEquals(value("0.06 BETWEEN 0.06-0.01 AND 0.06+0.01 AND 1=1"), true);
        Assert.assertEquals(value("DATE '1995-05-01' BETWEEN DATE '1995-01-01' AND DATE '1996-12-31' AND 1=1"), true);
    }
    public void testNegationNullAndGroupedScalarBounds() throws Exception {
        Assert.assertEquals(value("0 NOT BETWEEN 1 AND 9"), true);
        Assert.assertEquals(value("10 NOT BETWEEN 1 AND 9"), true);
        Assert.assertEquals(value("5 NOT BETWEEN 1 AND 9"), false);
        Assert.assertNull(value("NULL BETWEEN 1 AND 9"));
        Assert.assertEquals(value("5 BETWEEN (CASE WHEN 1=1 THEN 1 ELSE 9 END) AND 9"), true);
    }
}
