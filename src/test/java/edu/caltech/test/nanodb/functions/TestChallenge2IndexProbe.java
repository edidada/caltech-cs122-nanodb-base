package edu.caltech.test.nanodb.functions;

import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.commands.SelectCommand;
import edu.caltech.nanodb.server.CommandResult;
import edu.caltech.nanodb.expressions.TupleLiteral;
import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.indexes.IndexInfo;
import edu.caltech.nanodb.plannodes.PlanNode;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.storage.*;

@Test(groups={"challenge2"})
public class TestChallenge2IndexProbe extends SqlTestCase {
    private final Map<PlanNode,String> planTrees=new IdentityHashMap<>();
    private void command(String sql) throws Exception {
        CommandResult result=server.doCommand(sql,false);
        if(result.failed())throw result.getFailure();
    }
    private void fixture(String prefix) throws Exception {
        command("CREATE TABLE "+prefix+"_p(k INTEGER)");
        command("CREATE TABLE "+prefix+"_c(k INTEGER,n INTEGER,v INTEGER,PRIMARY KEY(k,n))");
        for(String key:new String[]{"1","1","2","NULL","9"})command("INSERT INTO "+prefix+"_p VALUES("+key+")");
        for(String row:new String[]{"1,1,1","1,2,2","1,3,3","2,1,4","3,1,5"})command("INSERT INTO "+prefix+"_c VALUES("+row+")");
        command("ANALYZE "+prefix+"_p");command("ANALYZE "+prefix+"_c");
    }
    private PlanNode plan(String sql,boolean reference) {
        SelectCommand select=(SelectCommand)server.parseCommand(sql);
        select.getSelectClause().computeSchema(server.getStorageManager().getTableManager(),null);
        StringBuilder tree=new StringBuilder();
        Planner planner=reference?new SimplePlanner():new CostBasedJoinPlanner() {
            @Override protected PlanNode buildFromWhere(SelectClause query) {
                PlanNode block=super.buildFromWhere(query);
                tree.append(PlanNode.printNodeTreeToString(block,true));
                return block;
            }
        };
        planner.setStorageManager(server.getStorageManager());
        PlanNode result=planner.makePlan(select.getSelectClause(),null);
        tree.append(PlanNode.printNodeTreeToString(result,true));
        planTrees.put(result,tree.toString());
        return result;
    }
    private List<TupleLiteral> run(PlanNode plan) {
        List<TupleLiteral> rows=new ArrayList<>();
        QueryEvaluator.executePlan(plan,new TupleProcessor() {
            public void setSchema(Schema schema) { }
            public void process(Tuple tuple) {rows.add(TupleLiteral.fromTuple(tuple));}
            public void finish() { }
        });
        return rows;
    }
    private List<TupleLiteral> compare(String sql,boolean probeExpected) {
        PlanNode optimized=plan(sql,false);
        String tree=planTrees.get(optimized);
        Assert.assertEquals(tree.contains("IndexProbe["),probeExpected,tree);
        List<TupleLiteral> actual=run(optimized);
        Assert.assertEquals(actual,run(plan(sql,true)));
        return actual;
    }
    public void testPrefixRebindingNullAndResiduals() throws Exception {
        fixture("probe_a");
        for(String condition:new String[]{"c.k=p.k","p.k=c.k AND c.n=2","c.k=p.k AND c.v>2"}) {
            List<TupleLiteral> rows=compare("SELECT p.k,(SELECT SUM(c.v) FROM probe_a_c c WHERE "+condition+") AS amount FROM probe_a_p p ORDER BY p.k",true);
            Assert.assertEquals(rows.size(),5);
            int duplicateOnes=0;
            for(TupleLiteral row:rows) {
                Object key=row.getColumnValue(0),value=row.getColumnValue(1);
                if(key==null || ((Number)key).intValue()==9)Assert.assertNull(value);
                if(key!=null && ((Number)key).intValue()==1) {
                    duplicateOnes++;
                    Assert.assertEquals(((Number)value).intValue(),condition.contains("c.n=2")?2:condition.contains("c.v>2")?3:6);
                }
            }
            Assert.assertEquals(duplicateOnes,2);
        }
    }
    public void testExistsEarlyExitAndAntiExistence() throws Exception {
        fixture("probe_b");
        compare("SELECT p.k,EXISTS(SELECT * FROM probe_b_c c WHERE c.k=p.k AND c.n<>p.k),NOT EXISTS(SELECT * FROM probe_b_c c WHERE c.k=p.k AND c.n>2) FROM probe_b_p p ORDER BY p.k",true);
    }
    public void testMissingLeadingKeyKeepsScan() throws Exception {
        fixture("probe_c");
        compare("SELECT p.k,(SELECT SUM(c.v) FROM probe_c_c c WHERE c.n=p.k) FROM probe_c_p p ORDER BY p.k",false);
    }
    private void singleOwnedPin(SequentialTupleFile file) {
        PageTuple tuple=(PageTuple)file.findFirstTupleEquals(new TupleLiteral(1));
        Assert.assertNotNull(tuple);
        DBPage page=tuple.getDBPage();
        Assert.assertEquals(page.getPinCount(),1);
        tuple.unpin();Assert.assertEquals(page.getPinCount(),0);
    }
    public void testCursorPinsAndFailureCleanup() throws Exception {
        fixture("probe_d");
        TableInfo table=server.getStorageManager().getTableManager().openTable("probe_d_c");
        IndexColumnRefs definition=table.getSchema().getIndexes().iterator().next();
        IndexInfo index=server.getStorageManager().getIndexManager().openIndex(table,definition.getIndexName());
        SequentialTupleFile file=(SequentialTupleFile)index.getTupleFile();
        for(int i=0;i<20;i++)singleOwnedPin(file);
        compare("SELECT p.k,EXISTS(SELECT * FROM probe_d_c c WHERE c.k=p.k) FROM probe_d_p p ORDER BY p.k",true);
        singleOwnedPin(file);
        PlanNode broken=plan("SELECT p.k FROM probe_d_p p WHERE EXISTS(SELECT * FROM probe_d_c c WHERE c.k=p.k AND 1/(c.n-c.n)>0)",false);
        Assert.assertTrue(planTrees.get(broken).contains("IndexProbe["));
        RuntimeException failure=null;
        try {run(broken);}catch(RuntimeException expected){failure=expected;}
        Assert.assertNotNull(failure);
        Assert.assertTrue(failure.toString().toLowerCase().contains("zero"),failure.toString());
        singleOwnedPin(file);
        Assert.assertTrue(index.getTupleFile().verify().isEmpty());
    }
}
