package edu.caltech.nanodb.expressions;

import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.plannodes.PlanNode;

/**
 * <p>
 * This class is the superclass of all expressions that can hold subqueries,
 * such as the <tt>IN</tt> operator, the <tt>EXISTS</tt> operator, and scalar
 * subqueries.
 * </p>
 * <p>
 * Subqueries are different from other kinds of expressions in that they
 * require an execution plan to evaluate the subquery expression node.  Thus,
 * evaluating the subquery expression requires a recursive invocation of the
 * query evaluator.  In general, this recursive invocation approach will be
 * slow for larger queries, and a good planner/optimizer will replace subquery
 * operators with joins, semijoins, etc. wherever it can.
 * </p>
 * <p>
 * <b>Note that all subqueries are considered "black boxes" to the
 * expression-traversal mechanism.</b>  That is, traversing an expression
 * that contains a subquery will not descend into the subquery's expressions.
 * The reason for this is simple - the subquery may include column-references
 * to schemas that only make sense in the context of the subquery; thus,
 * the subquery should be processed separately from the expression traversal.
 * </p>
 *
 * @see edu.caltech.nanodb.queryast.SubquerySchemaComputer
 */
public abstract class SubqueryOperator extends Expression {

    /**
     * This is the parsed representation of the subquery that the operator
     * uses for its operation.
     */
    protected SelectClause subquery;


    /**
     * The execution plan for the subquery that is used by the operator.  This
     * will be {@code null} until the subquery has actually been analyzed and
     * planned by the query planner.
     */
    protected PlanNode subqueryPlan;

    /** Indirection from the subplan to the currently evaluating outer node. */
    private Environment outerContext;
    private boolean reusable;


    /**
     * Returns the parsed representation of the subquery that is used by this
     * operator.
     *
     * @return the parsed representation of the subquery that is used by this
     *         operator.
     */
    public SelectClause getSubquery() {
        return subquery;
    }


    /**
     * Sets the execution plan for evaluating the subquery.
     *
     * @param plan the execution plan for evaluating the subquery.
     */
    public void setSubqueryPlan(PlanNode plan) {
        subqueryPlan = plan;
        reusable = plan != null && edu.caltech.nanodb.queryast.SubqueryInvariance.isInvariant(subquery);
        outerContext = new Environment();
        if (plan != null) plan.addParentEnvironmentToPlanTree(outerContext);
    }


    /** Bind before each invocation so clones and successive outer rows resolve
     * against the current evaluator rather than a stale tuple environment. */
    protected final void bindOuterContext(Environment environment) {
        if (outerContext != null) outerContext.setParentEnvironment(environment);
    }


    /** Materialize only invariant subqueries and immutable values within a
     * per-SELECT 1 MiB estimated budget, 4096-row and 32-column entry caps.
     * Overflow disables reuse for this operator; normal streaming still runs. */
    protected final java.util.List<TupleLiteral> reusableRows(Environment env) {
        SubqueryExecutionCache.Context cache = SubqueryExecutionCache.current();
        if (cache == null || !reusable) return null;
        if (cache.rows.containsKey(this)) return cache.rows.get(this);
        if (!cache.disabled.add(this)) return null;
        bindOuterContext(env);
        java.util.List<TupleLiteral> rows = new java.util.ArrayList<>();
        long bytes = 64;
        subqueryPlan.initialize();
        try {
            edu.caltech.nanodb.relations.Tuple row;
            while ((row = subqueryPlan.getNextTuple()) != null) {
                try {
                    if (rows.size() >= 4096 || row.getColumnCount() > 32) return null;
                    Object[] values = new Object[row.getColumnCount()];
                    bytes += 64 + 8L * values.length;
                    for (int i = 0; i < values.length; i++) {
                        values[i] = row.getColumnValue(i);
                        long size = SubqueryExecutionCache.valueBytes(values[i]);
                        if (size < 0) return null;
                        bytes += size;
                    }
                    if (cache.bytes + bytes > 1048576) return null;
                    rows.add(new TupleLiteral(values));
                } finally { row.unpin(); }
            }
        } finally { subqueryPlan.cleanUp(); }
        cache.bytes += bytes;
        java.util.List<TupleLiteral> result = java.util.Collections.unmodifiableList(rows);
        cache.rows.put(this, result);
        return result;
    }

    /**
     * Retrieves the current execution plan for the subquery.
     *
     * @return the current execution plan for the subquery.
     */
    public PlanNode getSubqueryPlan() {
        return subqueryPlan;
    }
}
