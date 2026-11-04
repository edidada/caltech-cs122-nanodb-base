package edu.caltech.nanodb.plannodes;


import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import edu.caltech.nanodb.expressions.Expression;
import edu.caltech.nanodb.expressions.OrderByExpression;
import edu.caltech.nanodb.queryeval.ColumnStats;
import edu.caltech.nanodb.queryeval.PlanCost;
import edu.caltech.nanodb.queryeval.SelectivityEstimator;
import edu.caltech.nanodb.relations.JoinType;
import edu.caltech.nanodb.relations.Tuple;


/**
 * This plan node implements a nested-loop join operation, which can support
 * arbitrary join conditions but is also the slowest join implementation.
 */
public class NestedLoopJoinNode extends ThetaJoinNode {
    /** A logging object for reporting anything interesting that happens. */
    private static Logger logger = LogManager.getLogger(NestedLoopJoinNode.class);


    /** Most recently retrieved tuple of the left relation. */
    private Tuple leftTuple;

    /** Most recently retrieved tuple of the right relation. */
    private Tuple rightTuple;


    /** Set to true when we have exhausted all tuples from our subplans. */
    private boolean done;
    private boolean matchedLeft;
    private int emitted;
    private int marked = -1;


    public NestedLoopJoinNode(PlanNode leftChild, PlanNode rightChild,
                              JoinType joinType, Expression predicate) {

        super(leftChild, rightChild, joinType, predicate);
        if (joinType == JoinType.RIGHT_OUTER) {
            swap();
            this.joinType = JoinType.LEFT_OUTER;
        }
        else if (joinType != JoinType.LEFT_OUTER && joinType != JoinType.INNER &&
                 joinType != JoinType.CROSS) {
            throw new IllegalArgumentException("Unsupported nested-loop join: " + joinType);
        }
    }


    /**
     * Checks if the argument is a plan node tree with the same structure, but not
     * necessarily the same references.
     *
     * @param obj the object to which we are comparing
     */
    @Override
    public boolean equals(Object obj) {

        if (obj instanceof NestedLoopJoinNode) {
            NestedLoopJoinNode other = (NestedLoopJoinNode) obj;

            return joinType == other.joinType && schemaSwapped == other.schemaSwapped &&
                java.util.Objects.equals(predicate, other.predicate) &&
                leftChild.equals(other.leftChild) &&
                rightChild.equals(other.rightChild);
        }

        return false;
    }


    /** Computes the hash-code of the nested-loop plan node. */
    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + (predicate != null ? predicate.hashCode() : 0);
        hash = 31 * hash + leftChild.hashCode();
        hash = 31 * hash + rightChild.hashCode();
        return hash;
    }


    /**
     * Returns a string representing this nested-loop join's vital information.
     *
     * @return a string representing this plan-node.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();

        buf.append("NestedLoop[");

        if (predicate != null)
            buf.append("pred:  ").append(predicate);
        else
            buf.append("no pred");

        if (schemaSwapped)
            buf.append(" (schema swapped)");

        buf.append(']');

        return buf.toString();
    }


    /**
     * Creates a copy of this plan node and its subtrees.
     */
    @Override
    protected PlanNode clone() throws CloneNotSupportedException {
        NestedLoopJoinNode node = (NestedLoopJoinNode) super.clone();
        // Runtime tuple pins belong to the original node, never its clone.
        node.leftTuple = null;
        node.rightTuple = null;
        node.done = false;
        node.matchedLeft = false;
        node.emitted = 0;
        node.marked = -1;

        // Clone the predicate.
        if (predicate != null)
            node.predicate = predicate.duplicate();
        else
            node.predicate = null;

        return node;
    }


    /**
     * Nested-loop joins can conceivably produce sorted results in situations
     * where the outer relation is ordered, but we will keep it simple and just
     * report that the results are not ordered.
     */
    @Override
    public List<OrderByExpression> resultsOrderedBy() {
        return null;
    }


    /** True if the node supports position marking. **/
    public boolean supportsMarking() {
        return true; // Reset is implemented by deterministic replay, not child marks.
    }


    /** True if the node requires that its left child supports marking. */
    public boolean requiresLeftMarking() {
        return false;
    }


    /** True if the node requires that its right child supports marking. */
    public boolean requiresRightMarking() {
        return false;
    }


    @Override
    public void prepare() {
        // Need to prepare the left and right child-nodes before we can do
        // our own work.
        leftChild.prepare();
        rightChild.prepare();

        // Use the parent class' helper-function to prepare the schema.
        prepareSchemaStats();

        PlanCost left = leftChild.getCost(), right = rightChild.getCost();
        // The predicate environment uses physical input order, even for a
        // RIGHT OUTER implemented as a swapped LEFT OUTER.
        edu.caltech.nanodb.relations.Schema physical = new edu.caltech.nanodb.relations.Schema(leftSchema, rightSchema);
        ArrayList<ColumnStats> inputs = new ArrayList<>();
        inputs.addAll(leftStats); inputs.addAll(rightStats);
        float selectivity = predicate == null ? 1 :
            SelectivityEstimator.estimateSelectivity(predicate, physical, inputs);
        float pairs = left.numTuples * right.numTuples;
        float rows = pairs * selectivity;
        if (joinType == JoinType.LEFT_OUTER) rows = Math.max(left.numTuples, rows);
        cost = new PlanCost(rows, left.tupleSize + right.tupleSize,
            left.cpuCost + left.numTuples * right.cpuCost + pairs,
            left.numBlockIOs + (left.numTuples > 0 ? right.numBlockIOs : 0),
            left.numLargeSeeks + (left.numTuples > 0 ? right.numLargeSeeks : 0));
        // Copy columns so costing a parent never mutates table statistics.
        ArrayList<ColumnStats> copied = new ArrayList<>();
        for (ColumnStats column : stats) copied.add(new ColumnStats(column));
        stats = joinType == JoinType.LEFT_OUTER ? copied :
            edu.caltech.nanodb.queryeval.StatisticsUpdater.updateStats(predicate, schema, copied);
    }


    public void initialize() {
        releaseLeft();
        super.initialize();

        done = false;
        emitted = 0;
        marked = -1;
        matchedLeft = false;
        leftTuple = null;
        rightTuple = null;
    }


    /**
     * Returns the next joined tuple that satisfies the join condition.
     *
     * @return the next joined tuple that satisfies the join condition.
     */
    public Tuple getNextTuple() {
        if (done) return null;
        while (true) {
            if (leftTuple == null) {
                leftTuple = leftChild.getNextTuple();
                if (leftTuple == null) {
                    done = true;
                    return null;
                }
                matchedLeft = false;
                rightChild.initialize();
            }
            rightTuple = rightChild.getNextTuple();
            if (rightTuple != null) {
                try {
                    if (canJoinTuples()) {
                        matchedLeft = true;
                        emitted++;
                        return joinTuples(leftTuple, rightTuple);
                    }
                }
                finally {
                    rightTuple.unpin();
                    rightTuple = null;
                }
            }
            else {
                if (joinType == JoinType.LEFT_OUTER && !matchedLeft) {
                    Tuple result = joinTuples(leftTuple,
                        edu.caltech.nanodb.expressions.TupleLiteral.ofSize(rightSchema.numColumns()));
                    releaseLeft();
                    emitted++;
                    return result;
                }
                releaseLeft();
            }
        }
    }

    private void releaseLeft() {
        if (leftTuple != null) {
            leftTuple.unpin();
            leftTuple = null;
        }
    }


    private boolean canJoinTuples() {
        // If the predicate was not set, we can always join them!
        if (predicate == null)
            return true;

        environment.clear();
        environment.addTuple(leftSchema, leftTuple);
        environment.addTuple(rightSchema, rightTuple);

        return predicate.evaluatePredicate(environment);
    }


    public void markCurrentPosition() {
        if (emitted == 0) throw new IllegalStateException("No current join tuple");
        marked = emitted - 1;
    }

    /** Replaying is slower than a materialized result but also works when an
     * input is a hash aggregate or other non-markable plan. */
    public void resetToLastMark() {
        if (marked < 0) throw new IllegalStateException("No join position marked");
        int position = marked;
        initialize();
        for (int i = 0; i < position; i++) {
            Tuple skipped = getNextTuple();
            if (skipped == null) throw new IllegalStateException("Join changed during replay");
            skipped.unpin();
        }
        marked = position;
    }

    public void cleanUp() {
        releaseLeft();
        try { leftChild.cleanUp(); }
        finally { rightChild.cleanUp(); }
    }
}
