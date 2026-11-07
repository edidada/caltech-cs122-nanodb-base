package edu.caltech.nanodb.queryeval;

import java.util.*;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.indexes.*;
import edu.caltech.nanodb.plannodes.*;
import edu.caltech.nanodb.queryast.FromClause;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.server.properties.ServerProperties;

/** Only single-table query blocks with an external equality key are eligible.
 * Outer joins, OR conditions, non-equality keys and absent indexes keep the scan. */
final class ParameterizedIndexPlanner {
    private static Expression binding(Expression local,Expression bound,Schema schema,int column) {
        if(!(local instanceof ColumnValue) || schema.getColumnIndex(((ColumnValue)local).getColumnName())!=column)return null;
        if(bound instanceof LiteralValue)return bound;
        if(bound instanceof ColumnValue && schema.getColumnIndex(((ColumnValue)bound).getColumnName())<0)return bound;
        return null;
    }
    static PlanNode choose(StorageManager storage,FromClause from,Collection<Expression> terms,Expression predicate) {
        if(!from.isBaseTable() || !storage.getServer().getPropertyRegistry().getBooleanProperty(ServerProperties.PROP_ENABLE_INDEXES))return null;
        TableInfo table=storage.getTableManager().openTable(from.getTableName());
        Schema schema=from.getSchema();
        List<IndexColumnRefs> definitions=new ArrayList<>(table.getSchema().getIndexes());
        definitions.sort(Comparator.comparing(IndexColumnRefs::getIndexName));
        IndexInfo best=null;List<Expression> bestKeys=Collections.emptyList();int[] bestColumns=null;
        for(IndexColumnRefs definition:definitions) {
            List<Expression> keys=new ArrayList<>();List<Integer> columns=new ArrayList<>();boolean external=false;
            for(int column:definition.getCols()) {
                Expression key=null;
                for(Expression term:terms) if(term instanceof CompareOperator && ((CompareOperator)term).getType()==CompareOperator.Type.EQUALS) {
                    CompareOperator compare=(CompareOperator)term;
                    key=binding(compare.getLeftExpression(),compare.getRightExpression(),schema,column);
                    if(key==null)key=binding(compare.getRightExpression(),compare.getLeftExpression(),schema,column);
                    if(key!=null)break;
                }
                if(key==null)break;
                keys.add(key);columns.add(column);external|=key instanceof ColumnValue;
            }
            if(!external || keys.size()<=bestKeys.size())continue;
            IndexInfo candidate=storage.getIndexManager().openIndex(table,definition.getIndexName());
            if(!(candidate.getTupleFile() instanceof SequentialTupleFile))continue;
            best=candidate;bestKeys=keys;bestColumns=columns.stream().mapToInt(Integer::intValue).toArray();
        }
        if(best==null)return null;
        PlanNode result=new ParameterizedIndexScanNode(best,schema,bestColumns,bestKeys,predicate);
        result.prepare();return result;
    }
    private ParameterizedIndexPlanner() { }
}
