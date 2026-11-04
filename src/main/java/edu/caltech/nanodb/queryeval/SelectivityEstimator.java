package edu.caltech.nanodb.queryeval;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.relations.SQLDataType;
import edu.caltech.nanodb.relations.Schema;

/** HW3 uniform-domain estimates. Compound terms are assumed independent.
 * These are costing approximations, not exact SQL NULL probability models. */
public final class SelectivityEstimator {
    public static final float DEFAULT_SELECTIVITY = .25f;
    private static final EnumSet<SQLDataType> ORDERED = EnumSet.of(
        SQLDataType.TINYINT, SQLDataType.SMALLINT, SQLDataType.INTEGER,
        SQLDataType.BIGINT, SQLDataType.FLOAT, SQLDataType.DOUBLE,
        SQLDataType.NUMERIC, SQLDataType.DATE, SQLDataType.TIME,
        SQLDataType.DATETIME, SQLDataType.TIMESTAMP);
    private SelectivityEstimator() { }
    public static boolean typeSupportsCompareEstimates(SQLDataType type) { return ORDERED.contains(type); }

    public static float estimateSelectivity(Expression expression, Schema schema, ArrayList<ColumnStats> stats) {
        if (expression == null) return 1;
        if (expression instanceof BooleanOperator)
            return estimateBoolOperSelectivity((BooleanOperator)expression, schema, stats);
        if (expression instanceof CompareOperator)
            return estimateCompareSelectivity((CompareOperator)expression, schema, stats);
        if (expression instanceof LiteralValue) {
            Object value = expression.evaluate();
            if (value == null) return 0;
            if (value instanceof Boolean) return (Boolean)value ? 1 : 0;
        }
        return DEFAULT_SELECTIVITY;
    }
    public static float estimateBoolOperSelectivity(BooleanOperator expression, Schema schema, ArrayList<ColumnStats> stats) {
        float value=1;
        switch (expression.getType()) {
        case AND_EXPR:
            for (int i=0;i<expression.getNumTerms();i++) value*=estimateSelectivity(expression.getTerm(i),schema,stats);
            return value;
        case OR_EXPR:
            for (int i=0;i<expression.getNumTerms();i++) value*=1-estimateSelectivity(expression.getTerm(i),schema,stats);
            return 1-value;
        case NOT_EXPR:
            return 1-estimateSelectivity(expression.getTerm(0),schema,stats);
        default: return DEFAULT_SELECTIVITY;
        }
    }
    public static float estimateCompareSelectivity(CompareOperator original, Schema schema, ArrayList<ColumnStats> stats) {
        CompareOperator comparison=(CompareOperator)original.duplicate();
        comparison.normalize();
        Expression left=comparison.getLeftExpression(), right=comparison.getRightExpression();
        if (left instanceof ColumnValue && right instanceof LiteralValue) {
            int index=schema.getColumnIndex(((ColumnValue)left).getColumnName());
            if (index<0) return DEFAULT_SELECTIVITY;
            return columnValue(comparison.getType(),right.evaluate(),schema.getColumnInfo(index).getType().getBaseType(),stats.get(index));
        }
        if (left instanceof ColumnValue && right instanceof ColumnValue) {
            CompareOperator.Type type=comparison.getType();
            if (type!=CompareOperator.Type.EQUALS && type!=CompareOperator.Type.NOT_EQUALS) return DEFAULT_SELECTIVITY;
            int a=schema.getColumnIndex(((ColumnValue)left).getColumnName());
            int b=schema.getColumnIndex(((ColumnValue)right).getColumnName());
            if (a<0 || b<0) return DEFAULT_SELECTIVITY;
            int av=stats.get(a).getNumUniqueValues(), bv=stats.get(b).getNumUniqueValues();
            if (av<0 || bv<0) return DEFAULT_SELECTIVITY;
            if (av==0 || bv==0) return 0;
            float equal=a==b ? 1 : 1f/Math.max(av,bv);
            return type==CompareOperator.Type.EQUALS ? equal : 1-equal;
        }
        return DEFAULT_SELECTIVITY;
    }

    public static float columnValue(CompareOperator.Type type, Object value, SQLDataType sqlType, ColumnStats stats) {
        if (value==null || stats.getNumUniqueValues()==0) return 0;
        if (type==CompareOperator.Type.EQUALS || type==CompareOperator.Type.NOT_EQUALS) {
            int distinct=stats.getNumUniqueValues();
            if (distinct<0) return DEFAULT_SELECTIVITY;
            float equal=1f/distinct;
            if (stats.hasMinMaxValues() &&
                (compare(value,stats.getMinValue())<0 || compare(value,stats.getMaxValue())>0)) equal=0;
            return type==CompareOperator.Type.EQUALS ? equal : 1-equal;
        }
        if (!typeSupportsCompareEstimates(sqlType) || !stats.hasMinMaxValues()) return DEFAULT_SELECTIVITY;
        Object min=stats.getMinValue(), max=stats.getMaxValue();
        if (!stats.hasDifferentMinMaxValues()) {
            int cmp=compare(min,value);
            switch (type) {
            case LESS_THAN:return cmp<0 ? 1 : 0;
            case LESS_OR_EQUAL:return cmp<=0 ? 1 : 0;
            case GREATER_THAN:return cmp>0 ? 1 : 0;
            case GREATER_OR_EQUAL:return cmp>=0 ? 1 : 0;
            default:return DEFAULT_SELECTIVITY;
            }
        }
        switch (type) {
        case GREATER_OR_EQUAL:return computeRatio(value,max,min,max);
        case LESS_THAN:return 1-computeRatio(value,max,min,max);
        case LESS_OR_EQUAL:return computeRatio(min,value,min,max);
        case GREATER_THAN:return 1-computeRatio(min,value,min,max);
        default:return DEFAULT_SELECTIVITY;
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public static int compare(Object a,Object b) {
        edu.caltech.nanodb.util.Pair pair=TypeConverter.coerceComparison(a,b);
        return ((Comparable)pair.value1).compareTo(pair.value2);
    }
    private static BigDecimal scalar(Object value) {
        if (value instanceof LocalDate) return BigDecimal.valueOf(((LocalDate)value).toEpochDay());
        if (value instanceof LocalTime) return BigDecimal.valueOf(((LocalTime)value).toNanoOfDay());
        if (value instanceof LocalDateTime) {
            LocalDateTime time=(LocalDateTime)value;
            return BigDecimal.valueOf(time.toEpochSecond(ZoneOffset.UTC)).add(BigDecimal.valueOf(time.getNano(),9));
        }
        return new BigDecimal(value.toString());
    }
    public static float computeRatio(Object low1,Object high1,Object low2,Object high2) {
        BigDecimal numerator=scalar(high1).subtract(scalar(low1));
        BigDecimal denominator=scalar(high2).subtract(scalar(low2));
        if (denominator.signum()<=0) return 0;
        float ratio=numerator.divide(denominator,MathContext.DECIMAL128).floatValue();
        return Math.max(0,Math.min(1,ratio));
    }
}
