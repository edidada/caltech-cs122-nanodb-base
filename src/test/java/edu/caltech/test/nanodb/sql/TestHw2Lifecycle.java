package edu.caltech.test.nanodb.sql;

import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.commands.SelectCommand;
import edu.caltech.nanodb.plannodes.PlanNode;
import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.queryeval.SimplePlanner;
import edu.caltech.nanodb.queryeval.QueryEvaluator;
import edu.caltech.nanodb.queryeval.TupleProcessor;
import edu.caltech.nanodb.relations.Schema;
import edu.caltech.nanodb.relations.Tuple;
import edu.caltech.nanodb.storage.DBPage;
import edu.caltech.nanodb.storage.TupleFile;

/** Exercise plans directly, before command-level forced unpinning. */
@Test(groups = {"hw2"})
public class TestHw2Lifecycle extends SqlTestCase {
    private SelectClause select(String sql) {
        SelectClause select = ((SelectCommand)server.parseCommand(sql)).getSelectClause();
        select.computeSchema(server.getStorageManager().getTableManager(), null);
        return select;
    }
    private PlanNode plan(SelectClause select) {
        SimplePlanner planner = new SimplePlanner();
        planner.setStorageManager(server.getStorageManager());
        return planner.makePlan(select, null);
    }
    private String row(Tuple tuple) {
        List<Object> columns = new ArrayList<>();
        for (int i = 0; i < tuple.getColumnCount(); i++) columns.add(tuple.getColumnValue(i));
        tuple.unpin();
        return columns.toString();
    }
    private void noPins(String... tables) {
        server.getStorageManager().getBufferManager().writeAll(false);
        for (String table : tables) {
            TupleFile file = server.getStorageManager().getTableManager().openTable(table).getTupleFile();
            for (int page = 0; page < file.getDBFile().getNumPages(); page++) {
                DBPage data = server.getStorageManager().loadDBPage(file.getDBFile(), page);
                Assert.assertEquals(data.getPinCount(), 1, "Leaked page pin: " + table + "/" + page);
                data.unpin();
            }
        }
    }
    public void testRestartMarkResetAndCloneActiveJoin() throws Exception {
        tryDoCommand("CREATE TABLE lifecycle_l (k INTEGER);");
        tryDoCommand("CREATE TABLE lifecycle_r (k INTEGER);");
        for (int i = 0; i < 3; i++) {
            tryDoCommand("INSERT INTO lifecycle_l VALUES ("+i+");");
            tryDoCommand("INSERT INTO lifecycle_r VALUES ("+i+");");
        }
        PlanNode join = plan(select("SELECT * FROM lifecycle_l l CROSS JOIN lifecycle_r r"));
        join.initialize();
        String first = row(join.getNextTuple());
        join.markCurrentPosition();
        String second = row(join.getNextTuple());
        Assert.assertNotEquals(first, second);
        join.resetToLastMark();
        Assert.assertEquals(row(join.getNextTuple()), first);
        PlanNode copy = join.duplicate();
        copy.prepare(); copy.initialize();
        Assert.assertEquals(row(copy.getNextTuple()), first);
        copy.cleanUp();
        Assert.assertEquals(row(join.getNextTuple()), second);
        join.initialize(); // restart while an outer tuple is still held
        int rows = 0;
        for (Tuple tuple; (tuple=join.getNextTuple()) != null; rows++) tuple.unpin();
        Assert.assertEquals(rows, 9);
        Assert.assertNull(join.getNextTuple());
        join.cleanUp();
        noPins("lifecycle_l", "lifecycle_r");
    }

    public void testReplanningOneAggregateAstDoesNotMutateIt() throws Exception {
        tryDoCommand("CREATE TABLE lifecycle_agg (a INTEGER, b INTEGER);");
        for (String values : new String[]{"1,2", "2,1", "2,3"})
            tryDoCommand("INSERT INTO lifecycle_agg VALUES ("+values+");");
        SelectClause ast = select("SELECT a+b AS g, ABS(SUM(a*b)+MAX(a)) AS v " +
            "FROM lifecycle_agg GROUP BY a+b HAVING COUNT(*)>1 ORDER BY g");
        String original = ast.toString();
        for (int repeat=0; repeat<3; repeat++) {
            PlanNode plan = plan(ast);
            Assert.assertEquals(ast.toString(), original);
            plan.initialize();
            Assert.assertEquals(row(plan.getNextTuple()), "[3, 6]");
            Assert.assertNull(plan.getNextTuple());
            plan.cleanUp();
            Assert.assertEquals(ast.toString(), original);
            noPins("lifecycle_agg");
        }
    }

    public void testProcessorFailureCleansJoinAndProjectionPins() throws Exception {
        tryDoCommand("CREATE TABLE lifecycle_failure (a INTEGER);");
        for (int n=0; n<10; n++) tryDoCommand("INSERT INTO lifecycle_failure VALUES ("+n+");");
        final RuntimeException sentinel = new RuntimeException("processor failure sentinel");
        PlanNode plan = plan(select("SELECT l.a+1 FROM lifecycle_failure l " +
            "LEFT JOIN lifecycle_failure r ON l.a=r.a"));
        try {
            QueryEvaluator.executePlan(plan, new TupleProcessor() {
                public void setSchema(Schema schema) { }
                public void process(Tuple tuple) { throw sentinel; }
                public void finish() { Assert.fail("finish after failure"); }
            });
            Assert.fail("Expected processor failure");
        }
        catch (RuntimeException failure) { Assert.assertSame(failure, sentinel); }
        noPins("lifecycle_failure");
    }
}
