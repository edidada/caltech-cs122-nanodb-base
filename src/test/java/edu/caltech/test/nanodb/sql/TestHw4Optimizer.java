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
    /** Compare the core join rather than the optional public-order projection. */
    private float joinCpu(PlanNode plan) {
        String tree=PlanNode.printNodeTreeToString(plan,true);
        java.util.regex.Matcher match=java.util.regex.Pattern.compile(
            "NestedLoop[^\\n]*cpuCost=([0-9.E+-]+)").matcher(tree);
        Assert.assertTrue(match.find(),tree);
        return Float.parseFloat(match.group(1));
    }
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
            equivalent(sql);float actual=joinCpu(plan(sql,true));
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

    /** Opaque USING leaves retain their public positions after reordering. */
    public void testIndependentUsingJoinOutputPositions() throws Exception {
        for(int i=0;i<4;i++) {
            String column=i<2?"k":"q";
            tryDoCommand("CREATE TABLE merged"+i+" ("+column+" INTEGER, v"+i+" INTEGER);");
            int key=i<2?7:19;
            for(int row=0;row<(i<2?3:1);row++)
                tryDoCommand("INSERT INTO merged"+i+" VALUES ("+key+","+(i*100+row)+");");
            tryDoCommand("ANALYZE merged"+i+";");
        }
        equivalent("SELECT * FROM (merged0 JOIN merged1 USING(k)) CROSS JOIN (merged2 JOIN merged3 USING(q))");
        equivalent("SELECT * FROM (merged2 JOIN merged3 USING(q)) CROSS JOIN (merged0 JOIN merged1 USING(k))");
    }

    /** An independent scalar oracle enumerates all 24 four-table orders.
     * No optimizer helper or DP implementation is called by the oracle. */
    public void testFourTableOrderMatchesExhaustiveCostOracle() throws Exception {
        int[] counts={11,17,5,3};
        for(int table=0;table<4;table++) {
            tryDoCommand("CREATE TABLE exhaustive"+table+" (k INTEGER);");
            for(int row=0;row<counts[table];row++)
                tryDoCommand("INSERT INTO exhaustive"+table+" VALUES ("+(row%3)+");");
            tryDoCommand("ANALYZE exhaustive"+table+";");
        }
        // Every table has exactly three non-NULL distinct keys. The three
        // predicates form a chain; an edge becomes available when both of
        // its endpoints have been joined. CPU = L.cpu + L.rows*R.cpu + pairs.
        double best=Double.POSITIVE_INFINITY;
        int permutations=0;
        for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int c=0;c<4;c++)for(int d=0;d<4;d++) {
            if(a==b || a==c || a==d || b==c || b==d || c==d)continue;
            int[] order={a,b,c,d};boolean[] seen=new boolean[4];seen[a]=true;
            double rows=counts[a],cpu=counts[a];
            for(int i=1;i<4;i++) {
                int next=order[i],edges=0;
                if(next>0 && seen[next-1])edges++;
                if(next<3 && seen[next+1])edges++;
                double pairs=rows*counts[next];
                cpu+=rows*counts[next]+pairs;
                rows=pairs/Math.pow(3,edges);seen[next]=true;
            }
            best=Math.min(best,cpu);permutations++;
        }
        Assert.assertEquals(permutations,24);
        String sql="SELECT * FROM exhaustive0 a,exhaustive1 b,exhaustive2 c,exhaustive3 d "+
            "WHERE a.k=b.k AND b.k=c.k AND c.k=d.k";
        Assert.assertEquals(joinCpu(plan(sql,true)),(float)best,.2f);
        equivalent(sql);
    }
}
