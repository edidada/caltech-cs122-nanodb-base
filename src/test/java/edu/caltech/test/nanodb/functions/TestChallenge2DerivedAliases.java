package edu.caltech.test.nanodb.functions;

import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.server.CommandResult;

@Test(groups={"challenge2"})
public class TestChallenge2DerivedAliases extends SqlTestCase {
    private CommandResult query(String sql) throws Exception {
        CommandResult result = server.doCommand(sql, true);
        if (result.failed()) throw result.getFailure();
        return result;
    }
    public void testPositionalNamesAndHiddenOriginalNames() throws Exception {
        Assert.assertEquals(((Number)query("SELECT d.x+d.y FROM (SELECT 1 AS original,2 AS other) AS d(x,y)").getTuples().get(0).getColumnValue(0)).intValue(),3);
        Assert.assertEquals(((Number)query("SELECT d.y FROM (SELECT 1 AS v,2 AS v) AS d(x,y)").getTuples().get(0).getColumnValue(0)).intValue(),2);
        Assert.assertTrue(server.doCommand("SELECT d.v FROM (SELECT 1 AS v,2 AS v) AS d",true).failed());
        Assert.assertTrue(server.doCommand("CREATE TABLE alias_bad(v INTEGER,v INTEGER)",false).failed());
        Assert.assertTrue(server.doCommand("SELECT original FROM (SELECT 1 AS original) AS d(x)",true).failed());
        Assert.assertTrue(server.doCommand("SELECT * FROM (SELECT 1,2) AS d(x)",true).failed());
        Assert.assertTrue(server.doCommand("SELECT * FROM (SELECT 1,2) AS d(x,x)",true).failed());
    }
    public void testInnerOrderAndLimitRemainInOriginalScope() throws Exception {
        query("CREATE TABLE alias_rows(k INTEGER)");
        for (int k=1;k<=3;k++) query("INSERT INTO alias_rows VALUES("+k+")");
        CommandResult rows = query("SELECT d.x FROM (SELECT k AS original FROM alias_rows ORDER BY original DESC LIMIT 2) AS d(x) ORDER BY d.x");
        Assert.assertEquals(rows.getTuples().size(),2);
        Assert.assertEquals(((Number)rows.getTuples().get(0).getColumnValue(0)).intValue(),2);
        Assert.assertEquals(((Number)rows.getTuples().get(1).getColumnValue(0)).intValue(),3);
    }
    public void testCustomerCountDistributionShape() throws Exception {
        query("CREATE TABLE alias_customers(k INTEGER)");
        query("CREATE TABLE alias_orders(k INTEGER,c INTEGER)");
        for (int k=1;k<=4;k++) query("INSERT INTO alias_customers VALUES("+k+")");
        query("INSERT INTO alias_orders VALUES(11,1)");
        query("INSERT INTO alias_orders VALUES(12,1)");
        query("INSERT INTO alias_orders VALUES(13,2)");
        CommandResult rows = query("SELECT c_count,COUNT(*) AS custdist FROM (SELECT c.k,COUNT(o.k) FROM alias_customers c LEFT OUTER JOIN alias_orders o ON c.k=o.c GROUP BY c.k) AS c_orders(c_key,c_count) GROUP BY c_count ORDER BY custdist DESC,c_count DESC");
        Assert.assertEquals(rows.getTuples().size(),3);
        long[][] expected={{0,2},{2,1},{1,1}};
        for(int r=0;r<3;r++) for(int c=0;c<2;c++)
            Assert.assertEquals(((Number)rows.getTuples().get(r).getColumnValue(c)).longValue(),expected[r][c]);
    }
}
