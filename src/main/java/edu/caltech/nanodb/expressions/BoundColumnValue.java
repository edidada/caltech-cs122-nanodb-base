package edu.caltech.nanodb.expressions;

import java.util.Objects;
import edu.caltech.nanodb.relations.ColumnInfo;
import edu.caltech.nanodb.relations.Schema;

/** Planner-only positional binding after schema resolution. A NATURAL/USING
 * child may expose an unqualified merged key; looking up that key by name in
 * the parent's raw concatenation would wrongly make it ambiguous again. */
public final class BoundColumnValue extends Expression {
    private final int tupleIndex;
    private final int columnIndex;
    private final ColumnInfo column;
    private final int schemaIndex;

    public BoundColumnValue(int tupleIndex, int columnIndex, ColumnInfo column) {
        this(tupleIndex, columnIndex, column, tupleIndex == 0 ? columnIndex : -1);
    }
    public BoundColumnValue(int tupleIndex, int columnIndex, ColumnInfo column, int schemaIndex) {
        this.tupleIndex = tupleIndex;
        this.columnIndex = columnIndex;
        this.column = column;
        this.schemaIndex = schemaIndex;
    }
    public int getSchemaIndex() { return schemaIndex; }
    public ColumnInfo getColumnInfo(Schema schema) { return column; }
    public Object evaluate(Environment environment) {
        return environment.getCurrentTuples().get(tupleIndex).getColumnValue(columnIndex);
    }
    public Expression traverse(ExpressionProcessor processor) {
        processor.enter(this);
        return processor.leave(this);
    }
    public Expression simplify() { return this; }
    public String toString() { return "Bound[" + tupleIndex + ":" + columnIndex + "]"; }
    public boolean equals(Object object) {
        if (!(object instanceof BoundColumnValue)) return false;
        BoundColumnValue other = (BoundColumnValue) object;
        return tupleIndex == other.tupleIndex && columnIndex == other.columnIndex &&
            column.equals(other.column);
    }
    public int hashCode() { return Objects.hash(tupleIndex, columnIndex, column); }
}
