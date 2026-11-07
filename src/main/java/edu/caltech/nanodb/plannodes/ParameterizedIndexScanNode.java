package edu.caltech.nanodb.plannodes;

import java.util.*;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.indexes.*;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.storage.*;

/** A correlated equality prefix is rebound for every subplan initialization.
 * The complete predicate remains a residual filter; index order is not exposed. */
public final class ParameterizedIndexScanNode extends SelectNode {
    private final IndexInfo index;
    private final Schema outputSchema;
    private final int[] keyColumns;
    private List<Expression> keys;
    private Tuple cursor;
    private TupleLiteral lookup;
    private boolean started, empty;
    private final int pointerColumn;

    public ParameterizedIndexScanNode(IndexInfo index, Schema outputSchema,
                                     int[] keyColumns, List<Expression> keys, Expression predicate) {
        super(predicate);
        if (!(index.getTupleFile() instanceof SequentialTupleFile) || keys.isEmpty())
            throw new IllegalArgumentException("A nonempty ordered-index prefix is required");
        this.index=index; this.outputSchema=outputSchema;
        this.keyColumns=keyColumns.clone(); this.keys=new ArrayList<>();
        for (Expression key:keys) this.keys.add(key.duplicate());
        pointerColumn=index.getTupleFile().getSchema().getColumnIndex(IndexManager.COLNAME_TUPLEPTR);
        if (pointerColumn<0) throw new IllegalArgumentException("Index has no tuple pointer");
    }
    @Override public void prepare() {
        schema=outputSchema;
        TableStats table=index.getTableInfo().getTupleFile().getStats();
        stats=StatisticsUpdater.updateStats(predicate,schema,table.getAllColumnStats());
        float matches=Math.max(1,table.numTuples);
        for(int column:keyColumns) {
            int distinct=table.getAllColumnStats().get(column).getNumUniqueValues();
            if(distinct>0) matches=Math.max(1,matches/distinct);
        }
        cost=new PlanCost(matches,table.avgTupleSize,4+matches,1+(long)Math.ceil(matches/32),1);
        ExpressionCostCalculator expressions=new ExpressionCostCalculator();
        expressions.add(predicate); expressions.addTo(cost,matches);
    }
    @Override public void initialize() {
        cleanUp(); super.initialize(); environment.clear(); started=false; empty=false;
        Object[] values=new Object[keys.size()];
        for(int i=0;i<values.length;i++) {
            values[i]=keys.get(i).evaluate(environment);
            if(values[i]==null) empty=true;
        }
        lookup=new TupleLiteral(values);
    }
    @Override protected void advanceCurrentTuple() {
        currentTuple=null;
        if(empty)return;
        SequentialTupleFile ordered=(SequentialTupleFile)index.getTupleFile();
        if(!started) {
            started=true; cursor=ordered.findFirstTupleEquals(lookup);
        } else if(cursor!=null) {
            Tuple previous=cursor;cursor=null;
            try {cursor=ordered.getNextTuple(previous);}
            finally {previous.unpin();}
        }
        if(cursor==null)return;
        if(TupleComparator.comparePartialTuples(cursor,lookup,TupleComparator.CompareMode.IGNORE_LENGTH)!=0) {
            cursor.unpin();cursor=null;return;
        }
        FilePointer pointer=(FilePointer)cursor.getColumnValue(pointerColumn);
        currentTuple=index.getTableInfo().getTupleFile().getTuple(pointer);
    }
    @Override public void cleanUp() {
        if(cursor!=null) {cursor.unpin();cursor=null;}
    }
    @Override public List<OrderByExpression> resultsOrderedBy() {return Collections.emptyList();}
    @Override public boolean supportsMarking() {return false;}
    @Override public boolean requiresLeftMarking() {return false;}
    @Override public boolean requiresRightMarking() {return false;}
    @Override protected PlanNode clone() throws CloneNotSupportedException {
        ParameterizedIndexScanNode copy=(ParameterizedIndexScanNode)super.clone();
        copy.cursor=null;copy.lookup=null;copy.started=false;copy.empty=false;copy.keys=new ArrayList<>();
        for(Expression key:keys)copy.keys.add(key.duplicate());
        return copy;
    }
    @Override public String toString() {return "IndexProbe[table="+index.getTableName()+", index="+index.getIndexName()+", keys="+keys+", pred="+predicate+"]";}
    @Override public boolean equals(Object other) {
        if(!(other instanceof ParameterizedIndexScanNode))return false;
        ParameterizedIndexScanNode node=(ParameterizedIndexScanNode)other;
        return index==node.index && keys.equals(node.keys) && Objects.equals(predicate,node.predicate) && outputSchema.toString().equals(node.outputSchema.toString());
    }
    @Override public int hashCode() {return Objects.hash(System.identityHashCode(index),keys,predicate,outputSchema.toString());}
}
