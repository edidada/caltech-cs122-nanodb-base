package edu.caltech.nanodb.functions;

import java.util.List;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.relations.*;

/** SQL one-based character substring, counted in Unicode code points. */
public class Substring extends SimpleFunction {
    private void arity(List<Expression> args) {
        if(args.size()<2||args.size()>3)throw new ExpressionException("SUBSTRING requires two or three arguments");
    }
    @Override public ColumnType getReturnType(List<Expression> args,Schema schema) {
        arity(args);return new ColumnType(SQLDataType.VARCHAR);
    }
    private long integer(Object value) {
        try {return new java.math.BigDecimal(value.toString()).longValueExact();}
        catch(ArithmeticException|NumberFormatException error){throw new ExpressionException("SUBSTRING position/length must be integers");}
    }
    @Override public Object evaluate(Environment env,List<Expression> args) {
        arity(args);Object text=args.get(0).evaluate(env),position=args.get(1).evaluate(env);
        Object length=args.size()==3?args.get(2).evaluate(env):null;
        if(text==null||position==null||(args.size()==3&&length==null))return null;
        String value=TypeConverter.getStringValue(text);long start=integer(position);
        long size=args.size()==3?integer(length):Long.MAX_VALUE;
        if(size<0)throw new ExpressionException("SUBSTRING length must be nonnegative");
        int count=value.codePointCount(0,value.length());
        // Clamp before arithmetic to avoid overflowing extreme start+length.
        long first=Math.max(1,start);
        long end=args.size()==2?count+1L:
            java.math.BigInteger.valueOf(start).add(java.math.BigInteger.valueOf(size))
                .max(java.math.BigInteger.ONE).min(java.math.BigInteger.valueOf(count+1L)).longValue();
        if(first>count||end<=first)return "";
        return value.substring(value.offsetByCodePoints(0,(int)first-1),value.offsetByCodePoints(0,(int)end-1));
    }
}
