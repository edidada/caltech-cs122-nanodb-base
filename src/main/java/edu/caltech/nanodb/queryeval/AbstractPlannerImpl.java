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
import edu.caltech.nanodb.relations.Schema;
import edu.caltech.nanodb.relations.ColumnInfo;

/** Shared SQL translation for syntax-order and cost-based planners.
 * The AST is read-only: rewritten SELECT/HAVING expressions are private copies,
 * permitting the same parsed query to be planned and evaluated again. */
public abstract class AbstractPlannerImpl extends SimplestPlanner {
    /** Lexical scope restored after each recursive makePlan invocation. */
    private SelectClause planningSelect;

    /** Plan private expression copies; callers retain ownership of the AST. */
    protected final Expression prepareExpression(Expression expression) {
        return expression==null?null:expression.duplicate().traverse(new ExpressionPlanner(this,planningSelect));
    }

    protected final void rejectAggregates(Expression expression, String clause) {
        if ("ON".equals(clause)) ExpressionPlanner.rejectSubqueries(expression,"JOIN ON");
        if (expression != null)
            expression.duplicate().traverse(new AggregateProcessor(false, clause));
    }

    protected PlanNode from(FromClause from) {
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
            PlanNode left = from(from.getLeftChild());
            PlanNode right = from(from.getRightChild());
            if (from.getComputedSelectValues() != null)
                plan = mergedJoin(from, left, right);
            else
                plan = new NestedLoopJoinNode(left, right, type,
                    prepareExpression(predicate));
        }
        else {
            throw new InvalidSQLException("Unsupported FROM clause: " + from);
        }
        if (from.isRenamed()) plan = new RenameNode(plan, from.getResultName());
        return plan;
    }

    protected PlanNode mergedJoin(FromClause from, PlanNode left, PlanNode right) {
        Schema ls = from.getLeftChild().getSchema();
        Schema rs = from.getRightChild().getSchema();
        Schema output = from.getSchema();
        List<String> keys = from.getConditionType() == FromClause.JoinConditionType.JOIN_USING ?
            from.getUsingNames() : new ArrayList<>(ls.getCommonColumnNames(rs));
        BooleanOperator condition = new BooleanOperator(BooleanOperator.Type.AND_EXPR);
        List<SelectValue> projection = new ArrayList<>();
        boolean reverse = from.getJoinType() == JoinType.RIGHT_OUTER;
        int column = 0;
        for (String key : keys) {
            int li = ls.getColumnIndex(key), ri = rs.getColumnIndex(key);
            condition.addTerm(new CompareOperator(CompareOperator.Type.EQUALS,
                new BoundColumnValue(reverse ? 1 : 0, li, ls.getColumnInfo(li), reverse ? rs.numColumns() + li : li),
                new BoundColumnValue(reverse ? 0 : 1, ri, rs.getColumnInfo(ri), reverse ? ri : ls.numColumns() + ri)));
            projection.add(new SelectValue(new BoundColumnValue(0,
                reverse ? ls.numColumns() + ri : li, output.getColumnInfo(column++))));
        }
        for (int i = 0; i < ls.numColumns(); i++) {
            if (!keys.contains(ls.getColumnInfo(i).getName()))
                projection.add(new SelectValue(new BoundColumnValue(0, i,
                    output.getColumnInfo(column++))));
        }
        for (int i = 0; i < rs.numColumns(); i++) {
            if (!keys.contains(rs.getColumnInfo(i).getName()))
                projection.add(new SelectValue(new BoundColumnValue(0, ls.numColumns() + i,
                    output.getColumnInfo(column++))));
        }
        // Raw input schemas may both contain an unqualified merged key.
        // Hide their names internally; all bindings above are positional,
        // and the output projection restores the exact public schema.
        left = privateJoinInput(left, ls, "L");
        right = privateJoinInput(right, rs, "R");
        PlanNode join = new NestedLoopJoinNode(left, right, from.getJoinType(),
            keys.isEmpty() ? null : condition);
        return new ProjectNode(join, projection);
    }

    private PlanNode privateJoinInput(PlanNode input, Schema schema, String side) {
        List<SelectValue> values = new ArrayList<>();
        for (int i = 0; i < schema.numColumns(); i++) {
            ColumnInfo internal = new ColumnInfo("#HW2_" + side + "_" + i,
                schema.getColumnInfo(i).getType());
            values.add(new SelectValue(new BoundColumnValue(0, i, internal)));
        }
        return new ProjectNode(input, values);
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
        SelectClause previous=planningSelect;planningSelect=select;
        try {return makeSelectPlan(select);}
        finally {planningSelect=previous;}
    }

    /** Construct this query block while recursive expression planning restores scope. */
    private PlanNode makeSelectPlan(SelectClause select) {
        rejectAggregates(select.getWhereExpr(), "WHERE");
        List<Expression> groups = new ArrayList<>();
        for (Expression group : select.getGroupByExprs()) {
            ExpressionPlanner.rejectSubqueries(group,"GROUP BY");
            rejectAggregates(group, "GROUP BY");
            groups.add(group.duplicate());
        }
        AggregateProcessor processor = new AggregateProcessor(true, "SELECT/HAVING");
        List<SelectValue> values = new ArrayList<>();
        for (SelectValue value : select.getSelectValues()) {
            if (value.isWildcard()) {
                values.add(new SelectValue(value.getExpression().duplicate()));
                continue;
            }
            Expression original = value.getExpression();
            Expression rewritten = prepareExpression(original).traverse(processor);
            rewritten = rewritten.traverse(new GroupReferences(groups));
            String alias = value.getAlias();
            if (alias == null && !(original instanceof ColumnValue))
                alias = original.getColumnInfo(select.getExpressionSchema()).getName();
            values.add(new SelectValue(rewritten, alias));
        }
        Expression having = select.getHavingExpr() == null ? null :
            prepareExpression(select.getHavingExpr()).traverse(processor)
                .traverse(new GroupReferences(groups));
        // A no-FROM query starts with one empty row, not zero rows. This also
        // makes WHERE and COUNT(*) work on constant-only SELECT statements.
        PlanNode plan = buildFromWhere(select);
        if (!groups.isEmpty() || !processor.getAggregates().isEmpty() || having != null)
            plan = new HashedGroupAggregateNode(plan, groups, processor.getAggregates());
        if (having != null) plan = new SimpleFilterNode(plan, having);
        if (!select.isTrivialProject()) plan = new ProjectNode(plan, values);
        if (select.isDistinct())
            throw new InvalidSQLException("SELECT DISTINCT is outside the HW2 planner");
        for(edu.caltech.nanodb.expressions.OrderByExpression order:select.getOrderByExprs())
            ExpressionPlanner.rejectSubqueries(order.getExpression(),"ORDER BY");
        if (!select.getOrderByExprs().isEmpty()) plan = new SortNode(plan, select.getOrderByExprs());
        if (select.getLimit() > 0 || select.getOffset() > 0)
            plan = new LimitOffsetNode(plan, select.getLimit(), select.getOffset());
        plan.prepare();
        return plan;
    }
    /** Build FROM and WHERE; subclasses may reorder only this relational region.
     * Grouping, projection, ordering, limits and DML access remain shared. */
    protected PlanNode buildFromWhere(SelectClause select) {
        PlanNode plan = select.getFromClause() == null ?
            new ProjectNode(Collections.emptyList()) : from(select.getFromClause());
        if (select.getWhereExpr() != null)
            plan = PlanUtils.addPredicateToPlan(plan, prepareExpression(select.getWhereExpr()));
        return plan;
    }

}
