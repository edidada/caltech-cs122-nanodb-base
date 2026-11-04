package edu.caltech.test.nanodb.sql;

import java.util.Arrays;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.expressions.TupleLiteral;
import edu.caltech.nanodb.server.CommandResult;
import edu.caltech.nanodb.queryeval.InvalidSQLException;

@Test(groups = {"hw2"})
public class TestHw2Planner extends SqlTestCase {
    private List<TupleLiteral> query(String sql) throws Exception {
        return tryDoCommand(sql, true).getTuples();
    }
    private void reject(String sql, String message) {
        CommandResult result = server.doCommand(sql, true);
        Assert.assertTrue(result.failed(), sql);
        Assert.assertTrue(result.getFailure() instanceof InvalidSQLException,
            String.valueOf(result.getFailure()));
        Assert.assertTrue(result.getFailure().getMessage().contains(message),
            result.getFailure().getMessage());
    }

    public void testConstantOnlySelectWhereAndOrderAlias() throws Exception {
        Assert.assertEquals(query("SELECT 3+2 AS five").get(0).getColumnValue(0), 5);
        Assert.assertEquals(query("SELECT 7 WHERE 1=0").size(), 0);
        Assert.assertEquals(query("SELECT 7 WHERE 1=1").size(), 1);
        tryDoCommand("CREATE TABLE constants (a INTEGER);");
        for (int n : new int[]{4, 2, 8}) tryDoCommand("INSERT INTO constants VALUES ("+n+");");
        List<TupleLiteral> rows = query("SELECT a+1 AS n FROM constants ORDER BY n DESC");
        Assert.assertEquals(rows.get(0).getColumnValue(0), 9);
        Assert.assertEquals(rows.get(2).getColumnValue(0), 3);
    }

    public void testExpressionGroupsMultipleAggregatesAndHaving() throws Exception {
        tryDoCommand("CREATE TABLE expressions (a INTEGER, b INTEGER);");
        for (String values : Arrays.asList("1,2", "2,1", "2,3", "NULL,1"))
            tryDoCommand("INSERT INTO expressions VALUES ("+values+");");
        List<TupleLiteral> rows = query("SELECT a+b AS k, SUM(a*b)+MAX(a) AS total, " +
            "COUNT(*) AS n FROM expressions GROUP BY a+b HAVING COUNT(*)>1 ORDER BY k");
        Assert.assertEquals(rows.size(), 1);
        Assert.assertEquals(rows.get(0).getColumnValue(0), 3);
        Assert.assertEquals(((Number)rows.get(0).getColumnValue(1)).intValue(), 6);
        Assert.assertEquals(((Number)rows.get(0).getColumnValue(2)).intValue(), 2);
        Assert.assertEquals(query("SELECT a+b AS k FROM expressions GROUP BY a+b").size(), 3);
    }

    public void testGlobalAggregateOnEmptyInput() throws Exception {
        tryDoCommand("CREATE TABLE empty_agg (a INTEGER);");
        List<TupleLiteral> rows = query("SELECT COUNT(*), SUM(a), MIN(a), MAX(a), AVG(a) FROM empty_agg");
        Assert.assertEquals(rows.size(), 1);
        Assert.assertEquals(((Number)rows.get(0).getColumnValue(0)).intValue(), 0);
        for (int i = 1; i < 5; i++) Assert.assertNull(rows.get(0).getColumnValue(i));
        Assert.assertEquals(query("SELECT a, COUNT(*) FROM empty_agg GROUP BY a").size(), 0);
    }

    public void testIllegalAggregateLocationsAndNesting() throws Exception {
        tryDoCommand("CREATE TABLE invalid_agg (a INTEGER);");
        reject("SELECT * FROM invalid_agg WHERE SUM(a)>1", "WHERE");
        reject("SELECT a FROM invalid_agg GROUP BY SUM(a)", "GROUP BY");
        reject("SELECT SUM(MAX(a)) FROM invalid_agg", "Nested");
        reject("SELECT * FROM invalid_agg x JOIN invalid_agg y ON SUM(x.a)=y.a", "ON");
    }
}
