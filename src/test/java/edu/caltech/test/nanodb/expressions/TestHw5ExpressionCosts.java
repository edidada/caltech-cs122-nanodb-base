package edu.caltech.test.nanodb.expressions;

import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.plannodes.*;
import edu.caltech.nanodb.queryast.*;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.Schema;

/** Independent arithmetic checks for CPU, I/O, scaling and repeated prepare. */
@Test(groups={"hw5"})
public class TestHw5ExpressionCosts {
    /** Synthetic costed source: no database or expression evaluation is involved. */
    private static final class Costed extends ProjectNode {
        private final float rows;
        Costed(float rows) {super(Collections.emptyList());this.rows=rows;}
        @Override public void prepare() {
            schema=new Schema();stats=new ArrayList<>();cost=new PlanCost(rows,0,7,3,2);
        }
    }
    private ExistsOperator predicate() {
        Costed nested=new Costed(1);nested.prepare();
        ExistsOperator exists=new ExistsOperator(new SelectClause());exists.setSubqueryPlan(nested);return exists;
    }
    private void cost(PlanCost result,float cpu,long io,long seeks) {
        Assert.assertEquals(result.cpuCost,cpu,.001f);
        Assert.assertEquals(result.numBlockIOs,io);Assert.assertEquals(result.numLargeSeeks,seeks);
    }
    public void testComplexityNestedCostAndScaling() {
        ExpressionCostCalculator expressions=new ExpressionCostCalculator();
        expressions.add(new ArithmeticOperator(ArithmeticOperator.Type.ADD,new LiteralValue(2),new LiteralValue(3)));
        cost(expressions.getCost(),3,0,0);
        expressions.add(predicate());cost(expressions.getCost(),11,3,2);
        PlanCost fractional=new PlanCost(0,0,0,0,0);expressions.addTo(fractional,.5f);
        cost(fractional,5.5f,2,1);
        PlanCost zero=new PlanCost(0,0,0,0,0);expressions.addTo(zero,0);cost(zero,0,0,0);
        expressions.getCost().cpuCost=999;cost(expressions.getCost(),11,3,2);
    }
    public void testFilterAndProjectionChargeInputRowsWithoutAccumulation() {
        SimpleFilterNode filter=new SimpleFilterNode(new Costed(10),predicate());
        filter.prepare();cost(filter.getCost(),97,33,22);
        filter.prepare();cost(filter.getCost(),97,33,22);
        ProjectNode project=new ProjectNode(new Costed(10),Arrays.asList(new SelectValue(predicate(),"present")));
        project.prepare();cost(project.getCost(),97,33,22);
        project.prepare();cost(project.getCost(),97,33,22);
        ProjectNode constant=new ProjectNode(Arrays.asList(new SelectValue(predicate(),"present")));
        constant.prepare();cost(constant.getCost(),9,3,2);
        constant.prepare();cost(constant.getCost(),9,3,2);
        SimpleFilterNode empty=new SimpleFilterNode(new Costed(0),predicate());
        empty.prepare();cost(empty.getCost(),7,3,2);
    }
    @Test(expectedExceptions=IllegalStateException.class)
    public void testUnplannedSubqueryFailsInsteadOfLookingCheap() {
        new ExpressionCostCalculator().add(new ExistsOperator(new SelectClause()));
    }
    public void testClauseRejectionDoesNotLeakState() {
        for(String clause:new String[]{"GROUP BY","ORDER BY","JOIN ON"}) {
            try {
                ExpressionPlanner.rejectSubqueries(new ExistsOperator(new SelectClause()),clause);
                Assert.fail("Expected clause rejection");
            }
            catch(InvalidSQLException expected) {Assert.assertTrue(expected.getMessage().contains(clause));}
            ExpressionPlanner.rejectSubqueries(new LiteralValue(1),clause);
        }
    }

}
