package edu.caltech.test.nanodb.functions;

import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.commands.SelectCommand;
import edu.caltech.nanodb.server.CommandResult;
import edu.caltech.nanodb.expressions.TupleLiteral;
import edu.caltech.nanodb.plannodes.PlanNode;
import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.indexes.*;

@Test(groups={"challenge2"})
public class TestChallenge2IndexJoin extends SqlTestCase {
    private void command(String sql) throws Exception {
        CommandResult result=server.doCommand(sql,false);
        if(result.failed())throw result.getFailure();
    }
    private void fixture(String prefix) throws Exception {
        command("CREATE TABLE "+prefix+"_p(k INTEGER)");
        command("CREATE TABLE "+prefix+"_c(k INTEGER,n INTEGER,v INTEGER,PRIMARY KEY(k,n))");
        for(String key:new String[]{"1","1","2","NULL","99"})command("INSERT INTO "+prefix+"_p VALUES("+key+")");
        for(int key=1;key<=30;key++)for(int n=1;n<=2;n++)
            command("INSERT INTO "+prefix+"_c VALUES("+key+","+n+","+(key*10+n)+")");
        command("ANALYZE "+prefix+"_p");command("ANALYZE "+prefix+"_c");
    }
    private PlanNode plan(String sql,boolean reference) {
        SelectClause select=((SelectCommand)server.parseCommand(sql)).getSelectClause();
        select.computeSchema(server.getStorageManager().getTableManager(),null);
        Planner planner=reference?new SimplePlanner():new CostBasedJoinPlanner();
        planner.setStorageManager(server.getStorageManager());return planner.makePlan(select,null);
    }
    private List<TupleLiteral> run(PlanNode plan) {
        List<TupleLiteral> rows=new ArrayList<>();
        QueryEvaluator.executePlan(plan,new TupleProcessor() {
            public void setSchema(Schema schema) { }
            public void process(Tuple tuple) {rows.add(TupleLiteral.fromTuple(tuple));}
            public void finish() { }
        });return rows;
    }
    private List<TupleLiteral> compare(String sql,boolean indexed) {
        PlanNode optimized=plan(sql,false),reference=plan(sql,true);
        String tree=PlanNode.printNodeTreeToString(optimized,true);
        Assert.assertEquals(tree.contains("IndexNestedLoop["),indexed,tree);
        Assert.assertEquals(optimized.getSchema().toString(),reference.getSchema().toString());
        List<TupleLiteral> expected=run(reference),actual=run(optimized);
        Assert.assertEquals(actual,expected,sql);
        Assert.assertEquals(run(optimized),expected,"reinitialize: "+sql);
        PlanNode copy=optimized.duplicate();copy.prepare();
        Assert.assertEquals(run(copy),expected,"duplicate: "+sql);
        return actual;
    }
    public void testPrefixCompositeResidualAndDuplicateRows() throws Exception {
        fixture("ij_a");
        String select="SELECT p.k,c.n,c.v FROM ij_a_p p JOIN ij_a_c c ON ";
        String order=" ORDER BY p.k,c.n";
        Assert.assertEquals(compare(select+"c.k=p.k"+order,true).size(),6);
        Assert.assertEquals(compare(select+"p.k=c.k AND c.n=2"+order,true).size(),3);
        Assert.assertEquals(compare(select+"c.k=p.k AND c.v>11"+order,true).size(),4);
    }
    public void testThreeTablesAndSelfJoin() throws Exception {
        fixture("ij_b");
        Assert.assertEquals(compare("SELECT p.k,c.n,d.v FROM ij_b_p p,ij_b_c c,ij_b_c d WHERE c.k=p.k AND d.k=c.k AND d.n=c.n ORDER BY p.k,c.n",true).size(),6);
    }
    public void testFallbackForOrMissingPrefixAndOuterJoin() throws Exception {
        fixture("ij_c");
        String select="SELECT p.k,c.n,c.v FROM ij_c_p p ";
        compare(select+"JOIN ij_c_c c ON c.n=p.k ORDER BY p.k,c.v,c.n",false);
        compare(select+"JOIN ij_c_c c ON c.k=p.k OR c.n=p.k ORDER BY p.k,c.v,c.n",false);
        for(String kind:new String[]{"LEFT","RIGHT"})
            compare(select+kind+" JOIN ij_c_c c ON c.k=p.k ORDER BY p.k,c.v,c.n",false);
    }
    public void testFailureReleasesBothInputs() throws Exception {
        fixture("ij_d");
        PlanNode broken=plan("SELECT p.k,c.n FROM ij_d_p p JOIN ij_d_c c ON c.k=p.k AND 1/(c.n-c.n)>0",false);
        Assert.assertTrue(PlanNode.printNodeTreeToString(broken,true).contains("IndexNestedLoop["));
        RuntimeException failure=null;
        try {run(broken);}catch(RuntimeException expected){failure=expected;}
        Assert.assertNotNull(failure);
        Assert.assertTrue(failure.toString().toLowerCase().contains("zero"),failure.toString());
        TableInfo table=server.getStorageManager().getTableManager().openTable("ij_d_c");
        IndexInfo index=server.getStorageManager().getIndexManager().openIndex(table,table.getSchema().getIndexes().iterator().next().getIndexName());
        PageTuple tuple=(PageTuple)((SequentialTupleFile)index.getTupleFile()).findFirstTupleEquals(new TupleLiteral(1));
        Assert.assertNotNull(tuple);DBPage page=tuple.getDBPage();
        Assert.assertEquals(page.getPinCount(),1);tuple.unpin();Assert.assertEquals(page.getPinCount(),0);
        Assert.assertTrue(index.getTupleFile().verify().isEmpty());
        Assert.assertEquals(compare("SELECT p.k,c.n,c.v FROM ij_d_p p JOIN ij_d_c c ON c.k=p.k ORDER BY p.k,c.n",true).size(),6);
    }

}
