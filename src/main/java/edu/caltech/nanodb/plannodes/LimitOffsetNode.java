package edu.caltech.nanodb.plannodes;

import java.util.List;
import java.util.Objects;
import edu.caltech.nanodb.expressions.OrderByExpression;
import edu.caltech.nanodb.relations.Tuple;

/** HW2 extra credit. The course AST defines limit=0 as unlimited. The node
 * owns skipped tuples, while returned tuples transfer to its consumer. */
public final class LimitOffsetNode extends PlanNode {
    private final int limit;
    private final int offset;
    private int skipped;
    private int returned;
    private boolean done;
    public LimitOffsetNode(PlanNode child, int limit, int offset) {
        super(child);
        if (limit < 0 || offset < 0) throw new IllegalArgumentException("Negative LIMIT/OFFSET");
        this.limit=limit; this.offset=offset;
    }
    public void prepare() {
        leftChild.prepare();
        schema=leftChild.getSchema(); stats=leftChild.getStats();
        cost=new edu.caltech.nanodb.queryeval.PlanCost(leftChild.getCost());
        cost.numTuples=Math.max(0,cost.numTuples-offset);
        if (limit>0) cost.numTuples=Math.min(limit,cost.numTuples);
    }
    public List<OrderByExpression> resultsOrderedBy() { return leftChild.resultsOrderedBy(); }
    public boolean supportsMarking() { return false; }
    public void initialize() {
        super.initialize();
        leftChild.initialize(); skipped=0; returned=0; done=false;
    }
    public Tuple getNextTuple() {
        if (done) return null;
        if (limit > 0 && returned >= limit) { done=true; return null; }
        while (skipped < offset) {
            Tuple tuple=leftChild.getNextTuple();
            if (tuple==null) { done=true; return null; }
            tuple.unpin(); skipped++;
        }
        Tuple tuple=leftChild.getNextTuple();
        if (tuple==null) done=true;
        else returned++;
        return tuple;
    }
    public void cleanUp() { leftChild.cleanUp(); }
    public String toString() { return "LimitOffset[limit="+limit+", offset="+offset+"]"; }
    public boolean equals(Object object) {
        if (!(object instanceof LimitOffsetNode)) return false;
        LimitOffsetNode other=(LimitOffsetNode)object;
        return limit==other.limit && offset==other.offset && leftChild.equals(other.leftChild);
    }
    public int hashCode() { return Objects.hash(leftChild,limit,offset); }
}
