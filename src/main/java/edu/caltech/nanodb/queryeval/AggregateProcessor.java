package edu.caltech.nanodb.queryeval;

import java.util.LinkedHashMap;
import java.util.Map;
import edu.caltech.nanodb.expressions.ColumnName;
import edu.caltech.nanodb.expressions.ColumnValue;
import edu.caltech.nanodb.expressions.Expression;
import edu.caltech.nanodb.expressions.ExpressionProcessor;
import edu.caltech.nanodb.expressions.FunctionCall;
import edu.caltech.nanodb.functions.AggregateFunction;

/** Query-local aggregate extraction. Children are visited while the enclosing
 * aggregate is still active, so nested aggregate calls cannot slip through. */
public final class AggregateProcessor implements ExpressionProcessor {
    private final boolean permitted;
    private final String clause;
    private boolean inside;
    private final Map<String, FunctionCall> aggregates = new LinkedHashMap<>();

    public AggregateProcessor(boolean permitted, String clause) {
        this.permitted = permitted;
        this.clause = clause;
    }

    private boolean aggregate(Expression expression) {
        return expression instanceof FunctionCall &&
            ((FunctionCall) expression).getFunction() instanceof AggregateFunction;
    }

    public void enter(Expression expression) {
        if (aggregate(expression)) {
            if (!permitted)
                throw new InvalidSQLException("Aggregate functions are forbidden in " + clause);
            if (inside)
                throw new InvalidSQLException("Nested aggregate functions in " + clause);
            inside = true;
        }
    }

    public Expression leave(Expression expression) {
        if (!aggregate(expression)) return expression;
        inside = false;
        for (Map.Entry<String, FunctionCall> entry : aggregates.entrySet()) {
            if (entry.getValue().equals(expression))
                return new ColumnValue(new ColumnName(entry.getKey()));
        }
        String name = "#HW2_AGG_" + aggregates.size();
        aggregates.put(name, (FunctionCall) expression);
        return new ColumnValue(new ColumnName(name));
    }

    public Map<String, FunctionCall> getAggregates() { return aggregates; }
}
