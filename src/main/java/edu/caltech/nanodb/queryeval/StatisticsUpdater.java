package edu.caltech.nanodb.queryeval;

import java.util.ArrayList;
import java.util.List;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.relations.Schema;
import edu.caltech.nanodb.relations.SQLDataType;

/** Conservative single-column domain updates for conjunctive selections.
 * Bounds are inclusive envelopes; strict numeric bounds retain the endpoint
 * as an approximation rather than inventing the next observed value. */
public final class StatisticsUpdater {
    private StatisticsUpdater() { }
    public static ArrayList<ColumnStats> updateStats(Expression predicate, Schema schema, List<ColumnStats> input) {
        ArrayList<ColumnStats> result=new ArrayList<>();
        for (ColumnStats stat:input) result.add(new ColumnStats(stat));
        update(predicate,schema,result);
        return result;
    }
    private static void update(Expression expression,Schema schema,List<ColumnStats> stats) {
        if (expression instanceof BooleanOperator && ((BooleanOperator)expression).getType()==BooleanOperator.Type.AND_EXPR) {
            BooleanOperator and=(BooleanOperator)expression;
            for (int i=0;i<and.getNumTerms();i++) update(and.getTerm(i),schema,stats);
        }
        else if (expression instanceof CompareOperator) updateCompareStats((CompareOperator)expression,schema,stats);
    }
    private static void empty(ColumnStats stat) {
        stat.setNumUniqueValues(0);stat.setNumNullValues(0);
        stat.setMinValue(null);stat.setMaxValue(null);
    }
    private static void updateCompareStats(CompareOperator original,Schema schema,List<ColumnStats> stats) {
        CompareOperator comparison=(CompareOperator)original.duplicate();
        comparison.normalize();
        Expression left=comparison.getLeftExpression(),right=comparison.getRightExpression();
        if (!(left instanceof ColumnValue) || !(right instanceof LiteralValue)) return;
        int index=schema.getColumnIndex(((ColumnValue)left).getColumnName());
        if (index<0) return;
        ColumnStats stat=stats.get(index);
        Object value=TypeConverter.coerceTo(right.evaluate(),schema.getColumnInfo(index).getType());
        SQLDataType type=schema.getColumnInfo(index).getType().getBaseType();
        if (value==null || stat.getNumUniqueValues()==0) {empty(stat);return;}
        float selectivity=SelectivityEstimator.columnValue(comparison.getType(),value,type,stat);
        int distinct=stat.getNumUniqueValues();
        stat.setNumNullValues(0);
        switch (comparison.getType()) {
        case EQUALS:
            if (selectivity==0) {empty(stat);return;}
            stat.setNumUniqueValues(1);
            if (SelectivityEstimator.typeSupportsCompareEstimates(type)) {
                stat.setMinValue(value);stat.setMaxValue(value);
            }
            break;
        case NOT_EQUALS:
            if (distinct>=0 && selectivity<1) stat.setNumUniqueValues(Math.max(0,distinct-1));
            if (stat.getNumUniqueValues()==0) empty(stat);
            break;
        case GREATER_THAN: case GREATER_OR_EQUAL:
        case LESS_THAN: case LESS_OR_EQUAL:
            if (!SelectivityEstimator.typeSupportsCompareEstimates(type) || !stat.hasMinMaxValues()) return;
            if (selectivity==0) {empty(stat);return;}
            if (distinct>=0) stat.setNumUniqueValues(Math.min(distinct,Math.max(1,(int)Math.ceil(distinct*selectivity))));
            if (comparison.getType()==CompareOperator.Type.GREATER_THAN || comparison.getType()==CompareOperator.Type.GREATER_OR_EQUAL) {
                if (SelectivityEstimator.compare(value,stat.getMinValue())>0) stat.setMinValue(value);
            }
            else if (SelectivityEstimator.compare(value,stat.getMaxValue())<0) stat.setMaxValue(value);
            break;
        default: break;
        }
    }
}
