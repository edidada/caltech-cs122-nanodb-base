package edu.caltech.test.nanodb.sql;

import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.commands.SelectCommand;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.plannodes.*;
import edu.caltech.nanodb.queryast.*;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.*;

/** Differential execution and plan-shape checks against the retained HW2 planner. */
@Test(groups={"hw4"})
public class TestHw4Optimizer extends SqlTestCase {
    private PlanNode plan(String sql,boolean optimized) {
        SelectClause ast=((SelectCommand)server.parseCommand(sql)).getSelectClause();
        ast.computeSchema(server.getStorageManager().getTableManager(),null);
        Planner planner=optimized?new CostBasedJoinPlanner():new SimplePlanner();
        planner.setStorageManager(server.getStorageManager());return planner.makePlan(ast,null);
    }
    private List<String> rows(PlanNode plan) {
        List<String> result=new ArrayList<>();plan.initialize();
        try {
            for(Tuple tuple;(tuple=plan.getNextTuple())!=null;) {
                try {List<Object> values=new ArrayList<>();for(int i=0;i<tuple.getColumnCount();i++)values.add(tuple.getColumnValue(i));result.add(values.toString());}
                finally {tuple.unpin();}
            }
        }
        finally {plan.cleanUp();}
        Collections.sort(result);return result;
    }
    private void equivalent(String sql) {
        PlanNode baseline=plan(sql,false),optimized=plan(sql,true);
        Assert.assertEquals(rows(optimized),rows(baseline),sql);
        Assert.assertEquals(optimized.getSchema().toString(),baseline.getSchema().toString(),sql);
    }
    private void setup(String prefix) throws Exception {
        for(String suffix:new String[]{"a","b","c"}) {
            tryDoCommand("CREATE TABLE "+prefix+suffix+" (k INTEGER, v INTEGER);");
            for(int i=0;i<(suffix.equals("a")?40:suffix.equals("b")?12:3);i++)
                tryDoCommand("INSERT INTO "+prefix+suffix+" VALUES ("+(i%3)+","+i+");");
            tryDoCommand("INSERT INTO "+prefix+suffix+" VALUES (NULL,99);");
            tryDoCommand("ANALYZE "+prefix+suffix+";");
        }
    }
    public void testInnerOrderPredicatesAndPublicColumnOrder() throws Exception {
        setup("opt");
        String[] froms={"opta a,optb b,optc c","optc c,opta a,optb b","optb b,optc c,opta a"};
        Float cost=null;
        for(String from:froms) {
            String sql="SELECT a.v,b.v,c.v FROM "+from+" WHERE a.k=b.k AND b.k=c.k AND c.v=1";
            equivalent(sql);float actual=plan(sql,true).getCost().cpuCost;
            if(cost==null)cost=actual;else Assert.assertEquals(actual,cost.floatValue(),.001f);
            equivalent("SELECT * FROM "+from+" WHERE a.k=b.k AND b.k=c.k AND c.v=1");
        }
        equivalent("SELECT * FROM opta a JOIN optb b ON a.k=b.k JOIN optc c ON b.k=c.k AND c.v=1");
        PlanNode original=plan("SELECT a.v,b.v,c.v FROM opta a,optb b,optc c WHERE a.k=b.k AND b.k=c.k AND c.v=1",false);
        Assert.assertTrue(cost<original.getCost().cpuCost);
    }
    public void testOuterJoinWhereAndOnAreDifferentBoundaries() throws Exception {
        setup("outer");
        for(String kind:new String[]{"LEFT","RIGHT"})for(String where:new String[]{"a.v=1","b.v=1","b.k IS NULL","a.k IS NULL","a.v=1 OR b.v=2"}) {
            equivalent("SELECT * FROM outera a "+kind+" JOIN outerb b ON a.k=b.k AND b.v>2 WHERE "+where);
        }
        equivalent("SELECT * FROM (outera a LEFT JOIN outerb b ON a.k=b.k) JOIN outerc c ON a.k=c.k WHERE b.k IS NULL");
        equivalent("SELECT * FROM outera a LEFT JOIN (outerb b JOIN outerc c ON b.k=c.k) ON a.k=b.k WHERE a.v=2");
    }
    public void testDerivedAggregationDisjunctionAndDisconnectedInputs() throws Exception {
        setup("derived");
        equivalent("SELECT x.k,COUNT(*),SUM(b.v) FROM (SELECT * FROM deriveda WHERE v<6) x JOIN derivedb b ON x.k=b.k GROUP BY x.k HAVING COUNT(*)>2 ORDER BY x.k");
        equivalent("SELECT * FROM deriveda a,derivedb b WHERE a.k=b.k OR a.v=0");
        equivalent("SELECT * FROM deriveda a,derivedc c WHERE a.v<2 AND c.v<2");
        equivalent("SELECT * FROM deriveda a JOIN deriveda b ON a.k=b.k WHERE a.v=1 AND b.v<4");
        equivalent("SELECT * FROM deriveda a LEFT JOIN derivedb b USING(k) WHERE k=2");
    }
}
