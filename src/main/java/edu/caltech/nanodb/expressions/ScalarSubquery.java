package edu.caltech.nanodb.expressions;


import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.relations.ColumnInfo;
import edu.caltech.nanodb.relations.Schema;
import edu.caltech.nanodb.relations.SchemaNameException;
import edu.caltech.nanodb.relations.Tuple;


/**
 * This class represents a scalar subquery embedded in another query's
 * predicate.
 */
public class ScalarSubquery extends SubqueryOperator {
    public ScalarSubquery(SelectClause subquery) {
        if (subquery == null)
            throw new IllegalArgumentException("subquery cannot be null");

        this.subquery = subquery;

    }


    public ColumnInfo getColumnInfo(Schema schema) throws SchemaNameException {
        Schema subquerySchema = subquery.getSchema();

        int numCols = subquerySchema.numColumns();
        if (numCols != 1) {
            throw new ExpressionException(
                "Scalar subquery must produce exactly one column (got " +
                numCols + " instead)");
        }

        // Return whatever the subquery produces for its column.
        return subquerySchema.getColumnInfo(0);
    }


    /** Evaluate afresh for every outer row; scalar cardinality follows the
     * framework's existing exactly-one-row contract. */
    public Object evaluate(Environment env) {
        if (subqueryPlan == null)
            throw new IllegalStateException("No execution plan for subquery");
        subqueryPlan.initialize();
        Tuple first = null, second = null;
        try {
            first = subqueryPlan.getNextTuple();
            if (first == null)
                throw new ExpressionException("Scalar subquery must produce exactly one row (got 0)");
            if (first.getColumnCount() != 1)
                throw new ExpressionException("Scalar subquery must produce exactly one column");
            // Copy before advancing; some plan nodes reuse tuple objects.
            Object value = first.getColumnValue(0);
            first.unpin();first = null;
            second = subqueryPlan.getNextTuple();
            if (second != null)
                throw new ExpressionException("Scalar subquery must produce exactly one row (got > 1)");
            return value;
        }
        finally {
            try {
                if (first != null) first.unpin();
                if (second != null) second.unpin();
            }
            finally {subqueryPlan.cleanUp();}
        }
    }


    @Override
    public Expression traverse(ExpressionProcessor p) {
        p.enter(this);
        // We do not traverse the subquery; it is treated as a "black box"
        // by the expression-traversal mechanism.
        return p.leave(this);
    }


    @Override
    public String toString() {
        return "(" + subquery.toString() + ")";
    }


    /**
     * Checks if the argument is an expression with the same structure, but not
     * necessarily the same references.
     *
     * @design This function operates correctly on the assumption that all
     *         supported types override Object.equals().
     *
     * @param obj the object to which we are comparing
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ScalarSubquery) {
            ScalarSubquery other = (ScalarSubquery) obj;
            return subquery.equals(other.subquery);
        }
        return false;
    }


    /**
     * Computes the hash-code of this scalar subquery expression.
     */
    @Override
    public int hashCode() {
        return subquery.hashCode();
    }


    /**
     * Creates a copy of expression.
     *
     * @design The reference of the internal value is simply copied since the
     *         value types are all immutable.
     */
    @Override
    protected Object clone() throws CloneNotSupportedException {
        // We don't clone SelectClause expressions at this point since they are
        // currently not cloneable.
        return super.clone();
    }
}

