package edu.caltech.nanodb.queryeval;

import edu.caltech.nanodb.expressions.Expression;
import edu.caltech.nanodb.expressions.ExpressionProcessor;
import edu.caltech.nanodb.expressions.SubqueryOperator;
import edu.caltech.nanodb.plannodes.PlanNode;

/** Estimates one evaluation by charging one CPU unit per expression node and
 * the full prepared cost of each immediately nested subquery. Short-circuiting
 * and early EXISTS/IN termination are deliberately not predicted. */
public final class ExpressionCostCalculator implements ExpressionProcessor {
    /** Per-evaluation work; row count and tuple width have no meaning here. */
    private final PlanCost cost=new PlanCost(0,0,0,0,0);

    /** Start an independent accumulator for an expression or projection list. */
    public ExpressionCostCalculator() { }

    /** Accumulate one expression without descending into its query blocks. */
    public void add(Expression expression) {
        if(expression!=null)expression.traverse(this);
    }

    /** Count expression complexity and include an already prepared subquery. */
    @Override public void enter(Expression expression) {
        cost.cpuCost++;
        if(expression instanceof SubqueryOperator) {
            PlanNode plan=((SubqueryOperator)expression).getSubqueryPlan();
            if(plan==null || plan.getCost()==null)
                throw new IllegalStateException("Subquery must be planned and prepared before costing");
            PlanCost nested=plan.getCost();
            cost.cpuCost+=nested.cpuCost;
            cost.numBlockIOs+=nested.numBlockIOs;
            cost.numLargeSeeks+=nested.numLargeSeeks;
        }
    }

    /** Cost analysis preserves the expression tree. */
    @Override public Expression leave(Expression expression) {return expression;}

    /** Return a defensive copy so callers cannot corrupt this accumulator. */
    public PlanCost getCost() {return new PlanCost(cost);}

    /** Charge expected invocation count; round aggregate disk work upward once.
     * Zero rows perform no expression work, even if the child has a high cost. */
    public void addTo(PlanCost target,float evaluations) {
        if(evaluations<=0)return;
        target.cpuCost+=cost.cpuCost*evaluations;
        target.numBlockIOs+=(long)Math.ceil(cost.numBlockIOs*(double)evaluations);
        target.numLargeSeeks+=(long)Math.ceil(cost.numLargeSeeks*(double)evaluations);
    }
}
