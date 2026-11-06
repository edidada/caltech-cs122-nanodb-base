package edu.caltech.nanodb.queryast;

import java.util.*;
import edu.caltech.nanodb.expressions.*;

/** Conservative dependency/purity analysis of an entire nested SELECT tree. */
public final class SubqueryInvariance {
    private final Set<SelectClause> queries = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean pure = true;
    private void expression(Expression expression) {
        if (expression == null) return;
        expression.traverse(new ExpressionProcessor() {
            public void enter(Expression node) {
                if (node instanceof SubqueryOperator) collect(((SubqueryOperator) node).getSubquery());
                if (node instanceof FunctionCall) {
                    try { if (!((FunctionCall) node).getFunction().isDeterministic()) pure = false; }
                    catch (IllegalStateException unresolved) { pure = false; }
                }
            }
            public Expression leave(Expression node) { return node; }
        });
    }
    private void from(FromClause clause) {
        if (clause == null) return;
        if (clause.isDerivedTable()) collect(clause.getSelectClause());
        else if (clause.isJoinExpr()) {
            from(clause.getLeftChild()); from(clause.getRightChild());
            expression(clause.getComputedJoinExpr());
        } else if (!clause.isBaseTable()) pure = false; // Unknown table functions.
    }
    private void collect(SelectClause query) {
        if (!queries.add(query)) return;
        if (query.getSchema() == null) pure = false;
        from(query.getFromClause());
        for (SelectValue value : query.getSelectValues()) if (!value.isWildcard()) expression(value.getExpression());
        expression(query.getWhereExpr()); expression(query.getHavingExpr());
        for (Expression group : query.getGroupByExprs()) expression(group);
        for (OrderByExpression order : query.getOrderByExprs()) expression(order.getExpression());
    }
    /** Purity alone also permits cheap-first ordering of correlated predicates. */
    public static boolean isPure(SelectClause query) {
        SubqueryInvariance analysis = new SubqueryInvariance();
        analysis.collect(query);
        return analysis.pure;
    }
    public static boolean isInvariant(SelectClause query) {
        SubqueryInvariance analysis = new SubqueryInvariance();
        analysis.collect(query);
        for (SelectClause block : analysis.queries)
            for (SelectClause owner : block.getCorrelationOwners())
                if (!analysis.queries.contains(owner)) return false;
        return analysis.pure;
    }
    private SubqueryInvariance() { }
}
