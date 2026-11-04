package edu.caltech.nanodb.queryeval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.plannodes.*;
import edu.caltech.nanodb.queryast.FromClause;
import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.queryast.SelectValue;
import edu.caltech.nanodb.relations.JoinType;

/** HW2 correctness-first planner; intentionally no cost-based join reordering.
 * The AST is read-only: rewritten SELECT/HAVING expressions are private copies,
 * permitting the same parsed query to be planned and evaluated again. */
public class SimplePlanner extends SimplestPlanner {
    private void rejectAggregates(Expression expression, String clause) {
        if (expression != null)
            expression.duplicate().traverse(new AggregateProcessor(false, clause));
    }

    private PlanNode from(FromClause from) {
        PlanNode plan;
        if (from.isBaseTable()) {
            plan = makeSimpleSelect(from.getTableName(), null, null);
        }
        else if (from.isDerivedTable()) {
            plan = makePlan(from.getSelectClause(), null);
        }
        else if (from.isJoinExpr()) {
            JoinType type = from.getJoinType();
            if (type != JoinType.INNER && type != JoinType.CROSS &&
                type != JoinType.LEFT_OUTER && type != JoinType.RIGHT_OUTER)
                throw new InvalidSQLException("HW2 does not support " + type);
            Expression predicate = from.getComputedJoinExpr();
            rejectAggregates(predicate, "ON");
            plan = new NestedLoopJoinNode(from(from.getLeftChild()),
                from(from.getRightChild()), type,
                predicate == null ? null : predicate.duplicate());
            if (from.getComputedSelectValues() != null)
                plan = new ProjectNode(plan, from.getComputedSelectValues());
        }
        else {
            throw new InvalidSQLException("Unsupported FROM clause: " + from);
        }
        if (from.isRenamed()) plan = new RenameNode(plan, from.getResultName());
        return plan;
    }

    private static final class GroupReferences implements ExpressionProcessor {
        private final List<Expression> groups;
        private final Deque<Integer> matches = new ArrayDeque<>();
        GroupReferences(List<Expression> groups) { this.groups = groups; }
        public void enter(Expression expression) {
            matches.push(groups.indexOf(expression));
        }
        public Expression leave(Expression expression) {
            int match = matches.pop();
            if (match < 0 || groups.get(match) instanceof ColumnValue)
                return expression;
            return new ColumnValue(new ColumnName(groups.get(match).toString()));
        }
    }

    @Override
    public PlanNode makePlan(SelectClause select, List<SelectClause> enclosing) {
        if (enclosing != null && !enclosing.isEmpty())
            throw new InvalidSQLException("Correlated evaluation is outside HW2");
        rejectAggregates(select.getWhereExpr(), "WHERE");
        List<Expression> groups = new ArrayList<>();
        for (Expression group : select.getGroupByExprs()) {
            rejectAggregates(group, "GROUP BY");
            groups.add(group.duplicate());
        }
        AggregateProcessor processor = new AggregateProcessor(true, "SELECT/HAVING");
        List<SelectValue> values = new ArrayList<>();
        for (SelectValue value : select.getSelectValues()) {
            if (value.isScalarSubquery())
                throw new InvalidSQLException("Scalar subqueries are outside HW2");
            if (value.isWildcard()) {
                values.add(new SelectValue(value.getExpression().duplicate()));
                continue;
            }
            Expression original = value.getExpression();
            Expression rewritten = original.duplicate().traverse(processor);
            rewritten = rewritten.traverse(new GroupReferences(groups));
            String alias = value.getAlias();
            if (alias == null && !(original instanceof ColumnValue))
                alias = original.getColumnInfo(select.getFromSchema()).getName();
            values.add(new SelectValue(rewritten, alias));
        }
        Expression having = select.getHavingExpr() == null ? null :
            select.getHavingExpr().duplicate().traverse(processor)
                .traverse(new GroupReferences(groups));
        // A no-FROM query starts with one empty row, not zero rows. This also
        // makes WHERE and COUNT(*) work on constant-only SELECT statements.
        PlanNode plan = select.getFromClause() == null ?
            new ProjectNode(Collections.emptyList()) : from(select.getFromClause());
        if (select.getWhereExpr() != null)
            plan = PlanUtils.addPredicateToPlan(plan, select.getWhereExpr().duplicate());
        if (!groups.isEmpty() || !processor.getAggregates().isEmpty() || having != null)
            plan = new HashedGroupAggregateNode(plan, groups, processor.getAggregates());
        if (having != null) plan = new SimpleFilterNode(plan, having);
        if (!select.isTrivialProject()) plan = new ProjectNode(plan, values);
        if (select.isDistinct())
            throw new InvalidSQLException("SELECT DISTINCT is outside the HW2 planner");
        if (!select.getOrderByExprs().isEmpty()) plan = new SortNode(plan, select.getOrderByExprs());
        if (select.getLimit() > 0 || select.getOffset() > 0)
            throw new InvalidSQLException("LIMIT/OFFSET not implemented in HW2 yet");
        plan.prepare();
        return plan;
    }
}
