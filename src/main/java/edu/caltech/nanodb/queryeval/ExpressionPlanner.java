package edu.caltech.nanodb.queryeval;

import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.relations.ColumnInfo;
import edu.caltech.nanodb.relations.Schema;

/** Plans immediately nested subqueries while traversing a private expression.
 * Nested query blocks are planned recursively by the same planner. Correlated
 * references retain type metadata from their lexical query, while runtime
 * values are resolved through the subquery's dynamic outer environment. */
public final class ExpressionPlanner implements ExpressionProcessor {
    /** Existing planner reused for recursive query blocks. */
    private final Planner planner;
    /** Lexical SELECT whose expression is being processed. */
    private final SelectClause owner;

    /** Construct a query-local traversal without instantiating another planner. */
    public ExpressionPlanner(Planner planner,SelectClause owner) {
        if(planner==null || owner==null)throw new IllegalArgumentException("Planner and SELECT required");
        this.planner=planner;this.owner=owner;
    }

    /** Generate an independent plan for this operator's query block. */
    @Override public void enter(Expression expression) {
        if(expression instanceof SubqueryOperator) {
            SubqueryOperator operator=(SubqueryOperator)expression;
            operator.setSubqueryPlan(planner.makePlan(operator.getSubquery(),null));
        }
    }

    /** Preserve outer-column type metadata that is absent from the local input. */
    @Override public Expression leave(Expression expression) {
        if(expression instanceof ColumnValue) {
            ColumnValue column=(ColumnValue)expression;
            if(column.getColumnName().isColumnWildcard())return expression;
            if(owner.getFromSchema().getColumnIndex(column.getColumnName())<0) {
                for(SelectClause parent=owner.getParentSelect();parent!=null;parent=parent.getParentSelect()) {
                    int index=parent.getFromSchema().getColumnIndex(column.getColumnName());
                    if(index>=0)return new CorrelatedColumn(column.getColumnName(),parent.getFromSchema().getColumnInfo(index));
                }
            }
        }
        return expression;
    }

    /** Reject one clause independently; no shared found flag leaks between clauses. */
    public static void rejectSubqueries(Expression expression,String clause) {
        if(expression==null)return;
        expression.traverse(new ExpressionProcessor() {
            /** Fail at the immediate subquery; do not traverse its query block. */
            public void enter(Expression current) {
                if(current instanceof SubqueryOperator)
                    throw new InvalidSQLException("Subqueries are forbidden in "+clause);
            }
            /** Validation never rewrites the caller's expression. */
            public Expression leave(Expression current) {return current;}
        });
    }

    /** Column lookup remains dynamic; only its lexical type is bound here. */
    private static final class CorrelatedColumn extends ColumnValue {
        /** Metadata obtained from the nearest enclosing query's FROM schema. */
        private final ColumnInfo info;
        /** Retain a private column name and the already resolved lexical type. */
        CorrelatedColumn(ColumnName name,ColumnInfo info) {
            super(new ColumnName(name.getTableName(),name.getColumnName()));this.info=info;
        }
        /** Resolve output typing without inventing a local statistics position. */
        @Override public ColumnInfo getColumnInfo(Schema schema) {return info;}
    }
}
