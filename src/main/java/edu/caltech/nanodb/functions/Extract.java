package edu.caltech.nanodb.functions;

import java.util.List;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.*;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.relations.*;

/** Extract temporal fields, with explicit errors for incompatible field/type pairs. */
public class Extract extends SimpleFunction {
    @Override public boolean isDeterministic() { return true; }

    private void arity(List<Expression> args) {
        if(args.size()!=2)throw new ExpressionException("EXTRACT requires field and temporal value");
    }
    @Override public ColumnType getReturnType(List<Expression> args,Schema schema) {
        arity(args);return new ColumnType(SQLDataType.NUMERIC);
    }
    @Override public Object evaluate(Environment env,List<Expression> args) {
        arity(args);Object field=args.get(0).evaluate(env),value=args.get(1).evaluate(env);
        if(field==null||value==null)return null;
        if(!(value instanceof TemporalAccessor))throw new ExpressionException("EXTRACT requires DATE, TIME or TIMESTAMP");
        TemporalAccessor temporal=(TemporalAccessor)value;
        String name=field.toString().toLowerCase(java.util.Locale.ROOT);
        try {
            switch(name) {
                case "year":return BigDecimal.valueOf(temporal.get(ChronoField.YEAR));
                case "month":return BigDecimal.valueOf(temporal.get(ChronoField.MONTH_OF_YEAR));
                case "day":return BigDecimal.valueOf(temporal.get(ChronoField.DAY_OF_MONTH));
                case "hour":return BigDecimal.valueOf(temporal.get(ChronoField.HOUR_OF_DAY));
                case "minute":return BigDecimal.valueOf(temporal.get(ChronoField.MINUTE_OF_HOUR));
                case "second":return BigDecimal.valueOf(temporal.get(ChronoField.SECOND_OF_MINUTE))
                    .add(BigDecimal.valueOf(temporal.get(ChronoField.NANO_OF_SECOND),9));
                default:throw new ExpressionException("Unsupported EXTRACT field: "+name);
            }
        }
        catch(DateTimeException error){throw new ExpressionException("EXTRACT field incompatible with temporal type: "+name,error);}
    }
}
