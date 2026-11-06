package edu.caltech.test.nanodb.expressions;

import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.plannodes.PlanNode;
import edu.caltech.nanodb.queryast.*;
import edu.caltech.nanodb.relations.Tuple;

/** Operator contracts independent of SQL planning; SQL integration remains required. */
@Test(groups={"hw5"})
public class TestHw5SubqueryOperators {
    /** Track release of each tuple returned by the synthetic input. */
    private static final class Row extends TupleLiteral {
        int releases;
        Row(Object... values) {super(values);}
        @Override public void unpin() {releases++;}
    }
    /** Deterministic input with observable cleanup and injected read failure. */
    private static final class Input extends PlanNode {
        final Row[] rows;int position,cleans,starts;boolean fail;
        Input(Row... rows) {this.rows=rows;}
        @Override public List<OrderByExpression> resultsOrderedBy() {return Collections.emptyList();}
        @Override public void prepare() { }
        @Override public void initialize() {position=0;starts++;}
        @Override public Tuple getNextTuple() {
            if(fail)throw new ExpressionException("injected read failure");
            return position<rows.length?rows[position++]:null;
        }
        @Override public void cleanUp() {cleans++;}
        @Override public String toString() {return "TestInput";}
        @Override public boolean equals(Object other) {return this==other;}
        @Override public int hashCode() {return System.identityHashCode(this);}
    }
    /** Attach an input without needing a parsed or storage-backed SELECT. */
    private InSubqueryOperator in(boolean invert,Object[] left,Input input) {
        List<Expression> values=new ArrayList<>();
        for(Object value:left)values.add(new LiteralValue(value));
        InSubqueryOperator op=new InSubqueryOperator(values,new SelectClause());
        op.setInvert(invert);op.setSubqueryPlan(input);return op;
    }
    /** Verify failures while also checking mandatory cleanup. */
    private void fails(Expression op,Input input) {
        try {op.evaluate(new Environment());Assert.fail("Expected ExpressionException");}
        catch(ExpressionException expected) {Assert.assertEquals(input.cleans,1);}
    }
    public void testInThreeValuedRowsAndEmptySet() {
        for(boolean invert:new boolean[]{false,true}) {
            Input empty=new Input();
            Assert.assertEquals(in(invert,new Object[]{null},empty).evaluate(null),invert);
            Assert.assertEquals(empty.cleans,1);
            Input unknown=new Input(new Row((Object)null));
            Assert.assertNull(in(invert,new Object[]{2},unknown).evaluate(null));
            Assert.assertEquals(unknown.rows[0].releases,1);
            Input match=new Input(new Row((Object)null),new Row(2),new Row(9));
            Assert.assertEquals(in(invert,new Object[]{2L},match).evaluate(null),!invert);
            Assert.assertEquals(match.position,2);Assert.assertEquals(match.cleans,1);
            Assert.assertEquals(match.rows[0].releases,1);Assert.assertEquals(match.rows[1].releases,1);
            Input falseRow=new Input(new Row(9,null));
            Assert.assertEquals(in(invert,new Object[]{2,null},falseRow).evaluate(null),invert);
            Input unknownRow=new Input(new Row(2,null));
            Assert.assertNull(in(invert,new Object[]{2,3},unknownRow).evaluate(null));
        }
    }
    public void testInWidthAndReadFailuresCleanUp() {
        Input width=new Input(new Row(1,2));fails(in(false,new Object[]{1},width),width);
        Assert.assertEquals(width.rows[0].releases,1);
        Input broken=new Input();broken.fail=true;fails(in(false,new Object[]{1},broken),broken);
    }
    public void testScalarCardinalityAndFreshEvaluation() {
        Input one=new Input(new Row(7));ScalarSubquery scalar=new ScalarSubquery(new SelectClause());
        scalar.setSubqueryPlan(one);Assert.assertEquals(scalar.evaluate(null),7);
        one.rows[0].setColumnValue(0,9);Assert.assertEquals(scalar.evaluate(null),9);
        Assert.assertEquals(one.starts,2);Assert.assertEquals(one.cleans,2);
        Assert.assertEquals(one.rows[0].releases,2);
        for(Input input:new Input[]{new Input(),new Input(new Row(1,2)),new Input(new Row(1),new Row(2))}) {
            ScalarSubquery invalid=new ScalarSubquery(new SelectClause());invalid.setSubqueryPlan(input);fails(invalid,input);
            for(Row row:input.rows)Assert.assertEquals(row.releases,1);
        }
    }
    public void testExistsEarlyExitAndReadFailure() {
        Input rows=new Input(new Row(1),new Row(2));ExistsOperator exists=new ExistsOperator(new SelectClause());
        exists.setSubqueryPlan(rows);Assert.assertEquals(exists.evaluate(null),true);
        Assert.assertEquals(rows.position,1);Assert.assertEquals(rows.rows[0].releases,1);Assert.assertEquals(rows.cleans,1);
        Input empty=new Input();exists.setSubqueryPlan(empty);Assert.assertEquals(exists.evaluate(null),false);
        Assert.assertEquals(empty.cleans,1);
        Input broken=new Input();broken.fail=true;exists.setSubqueryPlan(broken);fails(exists,broken);
    }
    public void testInInvertIdentityAndDeepOperandCopy() {
        SelectClause query=new SelectClause();
        InSubqueryOperator original=new InSubqueryOperator(new ArithmeticOperator(ArithmeticOperator.Type.ADD,
            new LiteralValue(1),new LiteralValue(2)),query);
        InSubqueryOperator clone=(InSubqueryOperator)original.duplicate();
        Assert.assertEquals(original,clone);clone.setInvert(true);Assert.assertNotEquals(original,clone);
        clone.setInvert(false);
        clone.traverse(new ExpressionProcessor() {
            public void enter(Expression expression) { }
            public Expression leave(Expression expression) {
                return expression instanceof LiteralValue?new LiteralValue(8):expression;
            }
        });
        Assert.assertNotEquals(original,clone);
    }
    private InSubqueryOperator reusable(Object left, Input input) {
        SelectClause query = new SelectClause() {
            @Override public edu.caltech.nanodb.relations.Schema getSchema() {
                return new edu.caltech.nanodb.relations.Schema();
            }
        };
        InSubqueryOperator result = new InSubqueryOperator(new LiteralValue(left),query);
        result.setSubqueryPlan(input);
        return result;
    }
    public void testExecutionLocalReuseReleasesAndOverflowFallback() {
        Input input = new Input(new Row((Object)null), new Row(2));
        InSubqueryOperator op = reusable(2,input);
        try (SubqueryExecutionCache.Scope scope = SubqueryExecutionCache.open(true)) {
            Assert.assertEquals(op.evaluate(null),true);
            Assert.assertEquals(op.evaluate(null),true);
            Assert.assertEquals(input.starts,1);
            Assert.assertEquals(input.cleans,1);
            for (Row row : input.rows) Assert.assertEquals(row.releases,1);
        }
        input.rows[1].setColumnValue(0,9);
        try (SubqueryExecutionCache.Scope scope = SubqueryExecutionCache.open(true)) {
            Assert.assertNull(op.evaluate(null));
        }
        Assert.assertEquals(input.starts,2);
        Row[] many = new Row[4097];
        for (int i=0;i<many.length;i++) many[i]=new Row(i);
        Input large = new Input(many);
        InSubqueryOperator overflow = reusable(-1,large);
        try (SubqueryExecutionCache.Scope scope = SubqueryExecutionCache.open(true)) {
            Assert.assertEquals(overflow.evaluate(null),false);
            Assert.assertEquals(overflow.evaluate(null),false);
        }
        Assert.assertEquals(large.starts,3);
        Assert.assertEquals(large.cleans,3);
        for (Row row : many) Assert.assertEquals(row.releases,3);
        Input broken = new Input(); broken.fail=true;
        try (SubqueryExecutionCache.Scope scope = SubqueryExecutionCache.open(true)) {
            fails(reusable(1,broken),broken);
        }
        broken.fail=false;
        try (SubqueryExecutionCache.Scope scope = SubqueryExecutionCache.open(true)) {
            Assert.assertEquals(reusable(1,broken).evaluate(null),false);
        }
        Assert.assertEquals(broken.cleans,2);
    }
}
