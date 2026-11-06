package edu.caltech.test.nanodb.functions;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.server.CommandResult;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.functions.SimpleFunction;
import edu.caltech.nanodb.relations.*;

@Test(groups={"challenge2"})
public class TestChallenge2SubqueryReuse extends SqlTestCase {
    public static class VolatileProbe extends SimpleFunction {
        static int calls;
        public ColumnType getReturnType(List<Expression> args, Schema schema) { return new ColumnType(SQLDataType.INTEGER); }
        public Object evaluate(Environment env,List<Expression> args) { calls++; return args.get(0).evaluate(env); }
    }
    public static class PureProbe extends VolatileProbe {
        @Override public boolean isDeterministic() { return true; }
    }
    private CommandResult query(String sql) throws Exception {
        CommandResult result=server.doCommand(sql,true);
        if(result.failed())throw result.getFailure();
        return result;
    }
    private void rows(String table) throws Exception {
        query("CREATE TABLE "+table+"(k INTEGER)");
        for(int i=1;i<=3;i++)query("INSERT INTO "+table+" VALUES("+i+")");
    }
    public void testReuseWithinSelectAndRefreshAfterWrite() throws Exception {
        rows("reuse_rows_refresh"); server.getFunctionDirectory().addFunction("CACHE_PURE_REFRESH",PureProbe.class); VolatileProbe.calls=0;
        String sql="SELECT k,k IN (SELECT CACHE_PURE_REFRESH(k) FROM reuse_rows_refresh WHERE k=1) FROM reuse_rows_refresh ORDER BY k";
        CommandResult result=query(sql);
        Assert.assertEquals(result.getTuples().size(),3);
        Assert.assertEquals(result.getTuples().get(0).getColumnValue(1),true);
        Assert.assertEquals(result.getTuples().get(1).getColumnValue(1),false);
        Assert.assertEquals(VolatileProbe.calls,1);
        query("INSERT INTO reuse_rows_refresh VALUES(4)");
        Assert.assertEquals(query(sql).getTuples().size(),4);
        Assert.assertEquals(VolatileProbe.calls,2);
    }
    public void testVolatileFunctionNotReused() throws Exception {
        rows("reuse_rows_volatile"); server.getFunctionDirectory().addFunction("CACHE_VOLATILE_ONCE",VolatileProbe.class); VolatileProbe.calls=0;
        query("SELECT k IN (SELECT CACHE_VOLATILE_ONCE(k) FROM reuse_rows_volatile WHERE k=1) FROM reuse_rows_volatile");
        Assert.assertEquals(VolatileProbe.calls,3);
    }
    public void testNestedExternalDependencyAndInternalCorrelation() throws Exception {
        rows("reuse_rows_nested");
        for(String predicate:new String[]{"c.k=a.k AND c.k=b.k","c.k=b.k"}) {
            CommandResult result=query("SELECT a.k,a.k IN (SELECT b.k FROM reuse_rows_nested b WHERE EXISTS (SELECT 1 FROM reuse_rows_nested c WHERE "+predicate+")) FROM reuse_rows_nested a ORDER BY a.k");
            Assert.assertEquals(result.getTuples().size(),3);
            for(TupleLiteral row:result.getTuples())Assert.assertEquals(row.getColumnValue(1),true);
        }
    }
    public void testNullAndEmptySetSemantics() throws Exception {
        rows("reuse_rows_null");query("CREATE TABLE reuse_set(k INTEGER)");query("INSERT INTO reuse_set VALUES(1)");query("INSERT INTO reuse_set VALUES(NULL)");
        CommandResult result=query("SELECT k,k IN (SELECT k FROM reuse_set),k NOT IN (SELECT k FROM reuse_set) FROM reuse_rows_null ORDER BY k");
        Assert.assertEquals(result.getTuples().get(0).getColumnValue(1),true);
        Assert.assertEquals(result.getTuples().get(0).getColumnValue(2),false);
        for(int i=1;i<3;i++)for(int j=1;j<=2;j++)Assert.assertNull(result.getTuples().get(i).getColumnValue(j));
        Assert.assertEquals(query("SELECT NULL IN (SELECT k FROM reuse_set WHERE k<0)").getTuples().get(0).getColumnValue(0),false);
    }
    public void testCheapPredicateBeforeCorrelatedExists() throws Exception {
        rows("reuse_rows_cheap"); server.getFunctionDirectory().addFunction("CACHE_PURE_CHEAP",PureProbe.class); VolatileProbe.calls=0;
        CommandResult result=query("SELECT a.k FROM reuse_rows_cheap a WHERE EXISTS (SELECT CACHE_PURE_CHEAP(b.k) FROM reuse_rows_cheap b WHERE b.k=a.k) AND a.k<0");
        Assert.assertTrue(result.getTuples().isEmpty());
        Assert.assertEquals(VolatileProbe.calls,0);
    }
}
