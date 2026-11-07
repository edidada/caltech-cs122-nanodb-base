package edu.caltech.nanodb.plannodes;

import java.util.*;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.*;

/** Inner join with a single-table indexed right input, rebound for each left
 * row. The right scan checks the complete local and join predicate. */
public final class IndexNestedLoopJoinNode extends PlanNode {
    private Tuple leftTuple;
    private boolean done;
    private final float estimatedRows;

    public IndexNestedLoopJoinNode(PlanNode left, ParameterizedIndexScanNode right,
                                   float estimatedRows) {
        super(left,right);
        this.estimatedRows=estimatedRows;
        right.addParentEnvironmentToPlanTree(environment);
    }
    @Override public void prepare() {
        leftChild.prepare();rightChild.prepare();
        schema=new Schema(leftChild.getSchema(),rightChild.getSchema());
        stats=new ArrayList<>();
        for(ColumnStats column:leftChild.getStats())stats.add(new ColumnStats(column));
        for(ColumnStats column:rightChild.getStats())stats.add(new ColumnStats(column));
        PlanCost left=leftChild.getCost(),right=rightChild.getCost();
        cost=new PlanCost(estimatedRows,left.tupleSize+right.tupleSize,
            left.cpuCost+left.numTuples*(right.cpuCost+right.numTuples),
            left.numBlockIOs+(long)Math.ceil(left.numTuples*right.numBlockIOs),
            left.numLargeSeeks+(long)Math.ceil(left.numTuples*right.numLargeSeeks));
    }
    @Override public void initialize() {
        cleanUp();super.initialize();done=false;
        leftChild.initialize(); // Right keys cannot be bound until a left row exists.
    }
    @Override public Tuple getNextTuple() {
        if(done)return null;
        while(true) {
            if(leftTuple==null) {
                leftTuple=leftChild.getNextTuple();
                if(leftTuple==null) {done=true;return null;}
                environment.clear();environment.addTuple(leftChild.getSchema(),leftTuple);
                rightChild.initialize();
            }
            Tuple right=rightChild.getNextTuple();
            if(right!=null) {
                try {
                    TupleLiteral result=TupleLiteral.fromTuple(leftTuple);
                    result.appendTuple(right);return result;
                }
                finally {right.unpin();}
            }
            rightChild.cleanUp();releaseLeft();
        }
    }
    private void releaseLeft() {
        if(leftTuple!=null) {leftTuple.unpin();leftTuple=null;}
        environment.clear();
    }
    @Override public void cleanUp() {
        try {rightChild.cleanUp();}
        finally {
            try {releaseLeft();}
            finally {leftChild.cleanUp();}
        }
    }
    @Override public List<OrderByExpression> resultsOrderedBy() {return Collections.emptyList();}
    @Override public boolean supportsMarking() {return false;}
    @Override public boolean requiresLeftMarking() {return false;}
    @Override public boolean requiresRightMarking() {return false;}
    @Override protected PlanNode clone() throws CloneNotSupportedException {
        IndexNestedLoopJoinNode copy=(IndexNestedLoopJoinNode)super.clone();
        copy.leftTuple=null;copy.done=false;
        copy.rightChild.addParentEnvironmentToPlanTree(copy.environment);
        return copy;
    }
    @Override public String toString() {return "IndexNestedLoop[inner, rebound right prefix]";}
    @Override public boolean equals(Object other) {
        if(!(other instanceof IndexNestedLoopJoinNode))return false;
        IndexNestedLoopJoinNode node=(IndexNestedLoopJoinNode)other;
        return leftChild.equals(node.leftChild)&&rightChild.equals(node.rightChild);
    }
    @Override public int hashCode() {return Objects.hash(leftChild,rightChild);}
}
