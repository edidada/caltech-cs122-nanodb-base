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
    private void rows() throws Exception {
        query("CREATE TABLE reuse_rows(k INTEGER)");
        for(int i=1;i<=3;i++)query("INSERT INTO reuse_rows VALUES("+i+")");
    }
    public void testReuseWithinSelectAndRefreshAfterWrite() throws Exception {
        rows(); server.getFunctionDirectory().addFunction("CACHE_PURE",PureProbe.class); VolatileProbe.calls=0;
        String sql="SELECT k IN (SELECT CACHE_PURE(k) FROM reuse_rows WHERE k=1) FROM reuse_rows ORDER BY k";
        CommandResult result=query(sql);
        Assert.assertEquals(result.getTuples().size(),3);
        Assert.assertEquals(result.getTuples().get(0).getColumnValue(0),true);
        Assert.assertEquals(result.getTuples().get(1).getColumnValue(0),false);
        Assert.assertEquals(VolatileProbe.calls,1);
        query("INSERT INTO reuse_rows VALUES(4)");
        Assert.assertEquals(query(sql).getTuples().size(),4);
        Assert.assertEquals(VolatileProbe.calls,2);
    }
    public void testVolatileFunctionNotReused() throws Exception {
        rows(); server.getFunctionDirectory().addFunction("CACHE_VOLATILE",VolatileProbe.class); VolatileProbe.calls=0;
        query("SELECT k IN (SELECT CACHE_VOLATILE(k) FROM reuse_rows WHERE k=1) FROM reuse_rows");
        Assert.assertEquals(VolatileProbe.calls,3);
    }
    public void testNestedExternalDependencyAndInternalCorrelation() throws Exception {
        rows();
        for(String predicate:new String[]{"c.k=a.k AND c.k=b.k","c.k=b.k"}) {
            CommandResult result=query("SELECT a.k IN (SELECT b.k FROM reuse_rows b WHERE EXISTS (SELECT 1 FROM reuse_rows c WHERE "+predicate+")) FROM reuse_rows a ORDER BY a.k");
            Assert.assertEquals(result.getTuples().size(),3);
            for(TupleLiteral row:result.getTuples())Assert.assertEquals(row.getColumnValue(0),true);
        }
    }
    public void testNullAndEmptySetSemantics() throws Exception {
        rows();query("CREATE TABLE reuse_set(k INTEGER)");query("INSERT INTO reuse_set VALUES(1)");query("INSERT INTO reuse_set VALUES(NULL)");
        CommandResult result=query("SELECT k IN (SELECT k FROM reuse_set),k NOT IN (SELECT k FROM reuse_set) FROM reuse_rows ORDER BY k");
        Assert.assertEquals(result.getTuples().get(0).getColumnValue(0),true);
        Assert.assertEquals(result.getTuples().get(0).getColumnValue(1),false);
        for(int i=1;i<3;i++)for(int j=0;j<2;j++)Assert.assertNull(result.getTuples().get(i).getColumnValue(j));
        Assert.assertEquals(query("SELECT NULL IN (SELECT k FROM reuse_set WHERE k<0)").getTuples().get(0).getColumnValue(0),false);
    }
}
