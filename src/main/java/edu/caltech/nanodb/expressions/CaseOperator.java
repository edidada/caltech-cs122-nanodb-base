package edu.caltech.nanodb.expressions;

import java.util.*;
import edu.caltech.nanodb.relations.*;

/** SQL simple/searched CASE. Only the selected result branch is evaluated. */
public class CaseOperator extends Expression {
    private Expression base,fallback;
    private ArrayList<Expression> conditions,results;
    private ColumnType resultType;
    public CaseOperator(Expression base,List<Expression> conditions,List<Expression> results,Expression fallback) {
        if(conditions.isEmpty()||conditions.size()!=results.size())throw new IllegalArgumentException("CASE requires paired branches");
        this.base=base;this.conditions=new ArrayList<>(conditions);this.results=new ArrayList<>(results);
        this.fallback=Objects.requireNonNull(fallback);
    }
    private boolean numeric(SQLDataType type){return type.getTypeID()>0&&type.getTypeID()<=7;}
    private ColumnType merge(ColumnType left,ColumnType right) {
        SQLDataType a=left.getBaseType(),b=right.getBaseType();
        if(a==SQLDataType.NULL)return right;
        if(b==SQLDataType.NULL)return left;
        if(left.hasLength()&&right.hasLength())return ColumnType.VARCHAR(Math.max(left.getLength(),right.getLength()));
        if(a==b)return left;
        if(numeric(a)&&numeric(b)) {
            if(a==SQLDataType.DOUBLE||b==SQLDataType.DOUBLE||a==SQLDataType.FLOAT||b==SQLDataType.FLOAT)return new ColumnType(SQLDataType.DOUBLE);
            if(a==SQLDataType.NUMERIC||b==SQLDataType.NUMERIC)return new ColumnType(SQLDataType.NUMERIC);
            return new ColumnType(SQLDataType.BIGINT);
        }
        throw new ExpressionException("Incompatible CASE result types: "+left+" and "+right);
    }
    @Override public ColumnInfo getColumnInfo(Schema schema) {
        ColumnType type=fallback.getColumnInfo(schema).getType();
        for(Expression result:results)type=merge(type,result.getColumnInfo(schema).getType());
        resultType=type;return new ColumnInfo(type);
    }
    @Override public Object evaluate(Environment env) {
        Object operand=base==null?null:base.evaluate(env);Expression chosen=fallback;
        for(int i=0;i<conditions.size();i++) {
            Object value=conditions.get(i).evaluate(env);
            boolean matches=base==null?value!=null&&TypeConverter.getBooleanValue(value):
                operand!=null&&value!=null&&CompareOperator.areObjectsEqual(operand,value);
            if(matches){chosen=results.get(i);break;}
        }
        Object value=chosen.evaluate(env);
        if(value==null||resultType==null)return value;
        // CASE is an expression, not assignment into a storage column. The
        // framework's default VARCHAR(1)/NUMERIC(scale=0) metadata must not
        // truncate selected strings or round decimal expression results.
        switch(resultType.getBaseType()) {
            case NUMERIC:return TypeConverter.getBigDecimalValue(value);
            case DOUBLE:return TypeConverter.getDoubleValue(value);
            case FLOAT:return TypeConverter.getFloatValue(value);
            case BIGINT:return TypeConverter.getLongValue(value);
            default:return value;
        }
    }
    @Override public Expression simplify(){return this;}
    @Override public Expression traverse(ExpressionProcessor processor) {
        processor.enter(this);
        if(base!=null)base=base.traverse(processor);
        for(int i=0;i<conditions.size();i++) {
            conditions.set(i,conditions.get(i).traverse(processor));
            results.set(i,results.get(i).traverse(processor));
        }
        fallback=fallback.traverse(processor);
        // Symbol visitors and planner rewrites preserve expression semantics;
        // retain the declared result type until getColumnInfo recomputes it.
        return processor.leave(this);
    }
    @Override protected Object clone() throws CloneNotSupportedException {
        CaseOperator copy=(CaseOperator)super.clone();copy.base=base==null?null:base.duplicate();
        copy.conditions=new ArrayList<>();copy.results=new ArrayList<>();
        for(Expression expression:conditions)copy.conditions.add(expression.duplicate());
        for(Expression expression:results)copy.results.add(expression.duplicate());
        copy.fallback=fallback.duplicate();return copy;
    }
    @Override public boolean equals(Object obj) {
        if(!(obj instanceof CaseOperator))return false;CaseOperator other=(CaseOperator)obj;
        return Objects.equals(base,other.base)&&conditions.equals(other.conditions)&&results.equals(other.results)&&fallback.equals(other.fallback);
    }
    @Override public int hashCode(){return Objects.hash(base,conditions,results,fallback);}
    @Override public String toString(){return "CASE[base="+base+", when="+conditions+", then="+results+", else="+fallback+"]";}
}
