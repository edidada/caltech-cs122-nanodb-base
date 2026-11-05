package edu.caltech.test.nanodb.functions;

import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.server.CommandResult;

@Test(groups={"challenge2"})
public class TestChallenge2Substring extends SqlTestCase {
    private void value(String expression,Object expected) throws Exception {
        CommandResult result=server.doCommand("SELECT "+expression,true);
        if(result.failed())throw result.getFailure();
        Assert.assertEquals(result.getTuples().get(0).getColumnValue(0),expected);
    }
    public void testStandardAndCommaSyntax() throws Exception {
        value("substring('31-918-335' from 1 for 2)","31");
        value("substring('abcdef',2,3)","bcd");
        value("substring('abcdef' from 3)","cdef");
        value("substring('abcdef',0,3)","ab");
        value("substring('abcdef',-2,4)","a");
        value("substring('abcdef',99,4)","");
        value("substring('abcdef',2,0)","");
        value("substring(NULL,1,2)",null);value("substring('abc',NULL,2)",null);
        value("substring('abc',1,NULL)",null);
    }
    public void testUnicodeAndInvalidLength() throws Exception {
        value("substring('A😀中Z' from 2 for 2)","😀中");
        Assert.assertTrue(server.doCommand("SELECT substring('abc',1,-1)",true).failed());
        Assert.assertTrue(server.doCommand("SELECT substring('abc',1.5,2)",true).failed());
    }
}
