package edu.caltech.test.nanodb.sql;

import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.expressions.TupleLiteral;
import edu.caltech.nanodb.relations.Tuple;
import edu.caltech.nanodb.plannodes.PlanNode;
import edu.caltech.nanodb.commands.SelectCommand;
import edu.caltech.nanodb.queryast.SelectClause;

@Test(groups={"hw3"})
public class TestHw3Analysis extends SqlTestCase {
    private TupleFile file(String name) { return server.getStorageManager().getTableManager().openTable(name).getTupleFile(); }
    private PlanNode plan(String sql) {
        SelectClause ast=((SelectCommand)server.parseCommand(sql)).getSelectClause();
        ast.computeSchema(server.getStorageManager().getTableManager(),null);
        return server.getQueryPlanner().makePlan(ast,null);
    }
    public void testExactStatsDeletesStringsAndMetadataReopen() throws Exception {
        tryDoCommand("CREATE TABLE analyzed (a INTEGER, b VARCHAR(20)) PROPERTIES (pagesize=512);");
        for(String values:new String[]{"1,'a'","2,NULL","2,'bbb'","NULL,'x'"})
            tryDoCommand("INSERT INTO analyzed VALUES ("+values+");");
        tryDoCommand("ANALYZE analyzed;");
        TableStats stats=file("analyzed").getStats();
        Assert.assertEquals(stats.numTuples,4);Assert.assertEquals(stats.numDataPages,1);
        Assert.assertEquals(stats.avgTupleSize,6.75f);
        Assert.assertEquals(stats.getColumnStats(0).getNumUniqueValues(),2);
        Assert.assertEquals(stats.getColumnStats(0).getNumNullValues(),1);
        Assert.assertEquals(stats.getColumnStats(0).getMinValue(),1);
        Assert.assertNull(stats.getColumnStats(1).getMinValue());
        tryDoCommand("DELETE FROM analyzed WHERE a=1;");
        tryDoCommand("ANALYZE analyzed;");
        server.getStorageManager().getTableManager().closeTable(server.getStorageManager().getTableManager().openTable("analyzed"));
        stats=file("analyzed").getStats();
        Assert.assertEquals(stats.numTuples,3);
        Assert.assertEquals(stats.avgTupleSize,19f/3,.00001f);
        Assert.assertEquals(stats.getColumnStats(0).getMinValue(),2);
        Assert.assertEquals(stats.getColumnStats(1).getNumUniqueValues(),2);
        Assert.assertEquals(stats.getColumnStats(1).getNumNullValues(),1);
    }
    public void testEmptyAndMultiPageDirectAnalysisPins() throws Exception {
        tryDoCommand("CREATE TABLE analyzed_empty (a INTEGER);");
        file("analyzed_empty").analyze();
        Assert.assertEquals(file("analyzed_empty").getStats().numTuples,0);
        Assert.assertEquals(file("analyzed_empty").getStats().avgTupleSize,0f);
        tryDoCommand("CREATE TABLE analyzed_pages (a INTEGER, b VARCHAR(450)) PROPERTIES (pagesize=512);");
        TupleFile file=file("analyzed_pages");
        for(int i=0;i<600;i++) {Tuple tuple=file.addTuple(new TupleLiteral(i,"x".repeat(450)));tuple.unpin();}
        server.getStorageManager().flushAllData();
        edu.caltech.nanodb.server.performance.PerformanceCounters.clearAll();
        file.analyze();
        Assert.assertTrue(edu.caltech.nanodb.server.performance.PerformanceCounters.get(
            edu.caltech.nanodb.server.performance.PerformanceCounters.STORAGE_PAGES_READ)<=602,
            "ANALYZE should read each data page once plus header access");
        Assert.assertEquals(file.getStats().numDataPages,600);
        Assert.assertEquals(file.getStats().numTuples,600);
        Assert.assertEquals(file.getStats().avgTupleSize,457f);
        server.getStorageManager().getBufferManager().writeAll(false);
        for(int page=0;page<file.getDBFile().getNumPages();page++) {
            DBPage data=server.getStorageManager().loadDBPage(file.getDBFile(),page);
            Assert.assertEquals(data.getPinCount(),1,"Leaked analysis pin on page "+page);data.unpin();
        }
    }
    public void testCostsAndNestedStatisticsDoNotMutateTables() throws Exception {
        tryDoCommand("CREATE TABLE costing (a INTEGER);");
        for(int i=0;i<=100;i++) tryDoCommand("INSERT INTO costing VALUES ("+i+");");
        tryDoCommand("ANALYZE costing;");
        PlanCost scan=plan("SELECT * FROM costing").getCost();
        Assert.assertEquals(scan.numTuples,101f);Assert.assertEquals(scan.cpuCost,101f);
        PlanCost filtered=plan("SELECT * FROM costing WHERE a>=20").getCost();
        Assert.assertEquals(filtered.numTuples,80.8f,.001f);Assert.assertEquals(filtered.cpuCost,101f);
        PlanCost nested=plan("SELECT * FROM (SELECT * FROM costing WHERE a>=20) x WHERE a<=80").getCost();
        Assert.assertEquals(nested.numTuples,60.6f,.01f);
        Assert.assertTrue(nested.cpuCost>filtered.cpuCost);
        Assert.assertEquals(file("costing").getStats().getColumnStats(0).getMinValue(),0);
        PlanCost join=plan("SELECT * FROM costing l JOIN costing r ON l.a=r.a").getCost();
        Assert.assertEquals(join.numTuples,101f,.01f);
        Assert.assertEquals(join.cpuCost,101+101*101+101*101,.1f);
        Assert.assertEquals(join.numBlockIOs,2L);
        PlanCost natural=plan("SELECT * FROM costing l NATURAL JOIN costing r").getCost();
        Assert.assertEquals(natural.numTuples,101f,.01f);
        PlanCost right=plan("SELECT * FROM costing l RIGHT JOIN costing r USING(a)").getCost();
        Assert.assertEquals(right.numTuples,101f,.01f);
        PlanCost emptySort=plan("SELECT * FROM costing WHERE 1=0 ORDER BY a").getCost();
        Assert.assertEquals(emptySort.numTuples,0f);
        Assert.assertTrue(Float.isFinite(emptySort.cpuCost));
    }
}
