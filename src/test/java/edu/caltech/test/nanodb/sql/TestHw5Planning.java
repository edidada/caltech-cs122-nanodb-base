package edu.caltech.test.nanodb.sql;

import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.expressions.TupleLiteral;
import edu.caltech.nanodb.commands.SelectCommand;
import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.plannodes.PlanNode;
import edu.caltech.nanodb.server.CommandResult;

/** End-to-end subquery contracts; these cases require the actual SQL planner. */
@Test(groups={"hw5"})
public class TestHw5Planning extends SqlTestCase {
    private void setup(String prefix) throws Exception {
        tryDoCommand("CREATE TABLE "+prefix+"a (k INTEGER, v INTEGER);");
        tryDoCommand("CREATE TABLE "+prefix+"b (k INTEGER, v INTEGER);");
        for(int i=1;i<=3;i++)tryDoCommand("INSERT INTO "+prefix+"a VALUES ("+i+","+(i*10)+");");
        tryDoCommand("INSERT INTO "+prefix+"b VALUES (1,4);");
        tryDoCommand("INSERT INTO "+prefix+"b VALUES (1,7);");
        tryDoCommand("INSERT INTO "+prefix+"b VALUES (2,8);");
        tryDoCommand("ANALYZE "+prefix+"a;");tryDoCommand("ANALYZE "+prefix+"b;");
    }
    private void expected(String sql,TupleLiteral... rows) {
        CommandResult result=server.doCommand(sql,true);
        Assert.assertFalse(result.failed(),String.valueOf(result.getFailure()));
        Assert.assertTrue(checkSizeResults(rows,result),sql);
        Assert.assertTrue(checkUnorderedResults(rows,result),sql);
    }
    public void testUncorrelatedSelectWhereAndHaving() throws Exception {
        setup("plain");
        expected("SELECT (SELECT MAX(v) FROM plainb) AS m",new TupleLiteral(8));
        expected("SELECT k FROM plaina WHERE v>(SELECT MAX(v) FROM plainb)",
            new TupleLiteral(1),new TupleLiteral(2),new TupleLiteral(3));
        expected("SELECT k,COUNT(*) AS n FROM plaina GROUP BY k HAVING COUNT(*)<(SELECT MAX(v) FROM plainb)",
            new TupleLiteral(1,1),new TupleLiteral(2,1),new TupleLiteral(3,1));
    }
    public void testCorrelatedPredicatesAndScalarAggregation() throws Exception {
        setup("corr");
        expected("SELECT a.k,(SELECT MAX(b.v) FROM corrb b WHERE b.k=a.k) AS m FROM corra a",
            new TupleLiteral(1,7),new TupleLiteral(2,8),new TupleLiteral(3,null));
        expected("SELECT a.k FROM corra a WHERE EXISTS (SELECT b.k FROM corrb b WHERE b.k=a.k)",
            new TupleLiteral(1),new TupleLiteral(2));
        expected("SELECT a.k FROM corra a WHERE NOT EXISTS (SELECT b.k FROM corrb b WHERE b.k=a.k)",new TupleLiteral(3));
        expected("SELECT a.k FROM corra a WHERE a.k IN (SELECT b.k FROM corrb b WHERE b.v<a.v)",
            new TupleLiteral(1),new TupleLiteral(2));
        expected("SELECT a.k FROM corra a WHERE a.k NOT IN (SELECT b.k FROM corrb b WHERE b.v<a.v)",new TupleLiteral(3));
    }
    public void testGrandparentContextAndAliasShadowing() throws Exception {
        setup("scope");
        expected("SELECT a.k FROM scopea a WHERE EXISTS (SELECT b.k FROM scopeb b WHERE EXISTS "+
            "(SELECT c.k FROM scopeb c WHERE c.k=a.k AND c.v=b.v))",new TupleLiteral(1),new TupleLiteral(2));
        expected("SELECT a.k FROM scopea a WHERE EXISTS (SELECT a.k FROM scopeb a WHERE a.k=2)",
            new TupleLiteral(1),new TupleLiteral(2),new TupleLiteral(3));
    }
    public void testOuterProjectionTypingAndSameModelOptimizerCost() throws Exception {
        setup("typed");
        expected("SELECT a.k,(SELECT a.k+1) AS next FROM typeda a",
            new TupleLiteral(1,2),new TupleLiteral(2,3),new TupleLiteral(3,4));
        String sql="SELECT a.k FROM typeda a,typedb b WHERE a.k=b.k AND b.v=4 AND EXISTS "+
            "(SELECT c.k FROM typedb c WHERE c.k=a.k)";
        expected(sql,new TupleLiteral(1));
        SelectClause ast=((SelectCommand)server.parseCommand(sql)).getSelectClause();
        ast.computeSchema(server.getStorageManager().getTableManager(),null);
        String before=ast.toString();
        Planner simple=new SimplePlanner(),optimized=new CostBasedJoinPlanner();
        simple.setStorageManager(server.getStorageManager());optimized.setStorageManager(server.getStorageManager());
        PlanNode baseline=simple.makePlan(ast,null),best=optimized.makePlan(ast,null);
        Assert.assertTrue(best.getCost().cpuCost<baseline.getCost().cpuCost);
        Assert.assertEquals(ast.toString(),before,"Planning must preserve the parsed query");
        PlanNode again=optimized.makePlan(ast,null);
        Assert.assertNotSame(again,best);
        Assert.assertEquals(again.getCost().cpuCost,best.getCost().cpuCost,.001f);
    }

}
