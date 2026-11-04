package edu.caltech.nanodb.queryeval;

import java.util.*;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.plannodes.*;
import edu.caltech.nanodb.queryast.*;
import edu.caltech.nanodb.relations.*;

/** HW4 left-deep subset DP under the HW3 CPU model. Outer joins and derived
 * queries are semantic boundaries. Ordinal leaf identity preserves self joins. */
public class CostBasedJoinPlanner extends AbstractPlannerImpl {
    /** Best candidate for a subset, including its consumed conjuncts. */
    private static final class JoinComponent {
        final PlanNode plan; final BitSet leaves; final Set<Expression> used; final String order;
        JoinComponent(PlanNode p,BitSet l,Set<Expression> u,String o) {plan=p;leaves=l;used=u;order=o;}
    }
    /** Split private conjunct copies in stable order. */
    private List<Expression> terms(Expression expression) {
        Set<Expression> terms=new LinkedHashSet<>();
        collectTerms(expression,terms);
        List<Expression> result=new ArrayList<>(terms);result.sort(Comparator.comparing(Object::toString));return result;
    }
    /** Parenthesized nested ANDs are still conjunctions; OR/NOT stay opaque. */
    private void collectTerms(Expression expression,Set<Expression> destination) {
        if(expression==null)return;
        if(expression instanceof BooleanOperator &&
            ((BooleanOperator)expression).getType()==BooleanOperator.Type.AND_EXPR) {
            BooleanOperator and=(BooleanOperator)expression;
            for(int i=0;i<and.getNumTerms();i++)collectTerms(and.getTerm(i),destination);
        }
        else destination.add(prepareExpression(expression));
    }
    /** Find expressions evaluable from available columns. */
    private Set<Expression> applicable(Collection<Expression> terms,Schema... schemas) {
        Set<Expression> result=new LinkedHashSet<>();
        PredicateUtils.findExprsUsingSchemas(terms,false,result,schemas);return result;
    }
    /** Copy predicates before attaching them to a candidate plan. */
    private Expression predicate(Collection<Expression> terms) {
        List<Expression> result=new ArrayList<>();
        terms.stream().sorted(Comparator.comparing(Object::toString)).forEach(e->result.add(e.duplicate()));
        return PredicateUtils.makePredicate(result);
    }
    /** Flatten ordinary inner joins only, collecting ON restrictions. */
    private void collectDetails(FromClause from,List<FromClause> leaves,Set<Expression> terms) {
        if(from.isJoinExpr() && !from.isOuterJoin() && from.getComputedSelectValues()==null && !from.isRenamed()) {
            rejectAggregates(from.getComputedJoinExpr(),"ON");terms.addAll(terms(from.getComputedJoinExpr()));
            collectDetails(from.getLeftChild(),leaves,terms);collectDetails(from.getRightChild(),leaves,terms);
        }
        else leaves.add(from);
    }
    /** Apply a filter and refresh its metadata. */
    private PlanNode filter(PlanNode plan,Collection<Expression> terms) {
        if(!terms.isEmpty()) {plan=PlanUtils.addPredicateToPlan(plan,predicate(terms));plan.prepare();}
        return plan;
    }
    /** Build a leaf; WHERE may cross only the preserved side of an outer join,
     * whereas ON-only restrictions may filter its null-supplying input. */
    private PlanNode makeLeafPlan(FromClause from,Collection<Expression> restrictions) {
        if(!from.isJoinExpr() || !from.isOuterJoin() || from.getComputedSelectValues()!=null || from.isRenamed()) {
            PlanNode plan=from(from);plan.prepare();return filter(plan,restrictions);
        }
        boolean leftOuter=from.getJoinType()==JoinType.LEFT_OUTER;
        if(!leftOuter && from.getJoinType()!=JoinType.RIGHT_OUTER)
            throw new InvalidSQLException("FULL OUTER is not implemented");
        FromClause left=from.getLeftChild(),right=from.getRightChild();
        Set<Expression> lt=new LinkedHashSet<>(),rt=new LinkedHashSet<>();
        Set<Expression> pushed=applicable(restrictions,leftOuter?left.getSchema():right.getSchema());
        (leftOuter?lt:rt).addAll(pushed);
        Expression on=from.getComputedJoinExpr();rejectAggregates(on,"ON");
        Set<Expression> remaining=new LinkedHashSet<>(terms(on));
        Set<Expression> onPushed=applicable(remaining,leftOuter?right.getSchema():left.getSchema());
        (leftOuter?rt:lt).addAll(onPushed);remaining.removeAll(onPushed);
        PlanNode plan=new NestedLoopJoinNode(makeJoinPlan(left,lt),makeJoinPlan(right,rt),from.getJoinType(),predicate(remaining));
        plan.prepare();Set<Expression> above=new LinkedHashSet<>(restrictions);above.removeAll(pushed);
        return filter(plan,above);
    }
    /** Restore output positions directly from leaf identity and column offsets.
     * Opaque USING/NATURAL leaves retain their own public column order. */
    private PlanNode restore(PlanNode plan,Schema expected,List<FromClause> original,
                             List<FromClause> sorted,String order) {
        Map<FromClause,Integer> offsets=new IdentityHashMap<>();int offset=0;
        for(String ordinal:order.split(",")) {
            FromClause leaf=sorted.get(Integer.parseInt(ordinal));
            offsets.put(leaf,offset);offset+=leaf.getSchema().numColumns();
        }
        if(offset!=expected.numColumns() || offset!=plan.getSchema().numColumns())
            throw new InvalidSQLException("Join output width changed during reordering");
        boolean same=true;List<SelectValue> values=new ArrayList<>();int output=0;
        for(FromClause leaf:original)for(int i=0;i<leaf.getSchema().numColumns();i++) {
            int index=offsets.get(leaf)+i;ColumnInfo column=expected.getColumnInfo(output);
            same &= index==output++;values.add(new SelectValue(new BoundColumnValue(0,index,column)));
        }
        if(same)return plan;
        PlanNode result=new ProjectNode(plan,values);result.prepare();return result;
    }
    /** Grow best n-leaf plans by one right leaf, applying unused conjuncts at
     * their earliest legal node. Equal costs use a stable canonical tie break. */
    private PlanNode makeJoinPlan(FromClause from,Collection<Expression> extra) {
        List<FromClause> clauses=new ArrayList<>();Set<Expression> all=new LinkedHashSet<>(extra);
        collectDetails(from,clauses,all);List<FromClause> original=new ArrayList<>(clauses);
        clauses.sort(Comparator.comparing(Object::toString));
        List<JoinComponent> leaves=new ArrayList<>();
        for(int i=0;i<clauses.size();i++) {
            FromClause clause=clauses.get(i);Set<Expression> used=applicable(all,clause.getSchema());
            PlanNode plan=makeLeafPlan(clause,used);BitSet ids=new BitSet();ids.set(i);
            leaves.add(new JoinComponent(plan,ids,used,String.format(Locale.ROOT,"%06d",i)));
        }
        Map<BitSet,JoinComponent> layer=new LinkedHashMap<>();
        for(JoinComponent leaf:leaves)layer.put(leaf.leaves,leaf);
        for(int size=1;size<leaves.size();size++) {
            Map<BitSet,JoinComponent> next=new LinkedHashMap<>();
            for(JoinComponent prefix:layer.values())for(int i=0;i<leaves.size();i++) {
                if(prefix.leaves.get(i))continue;
                JoinComponent leaf=leaves.get(i);Set<Expression> used=new LinkedHashSet<>(prefix.used);used.addAll(leaf.used);
                Set<Expression> unused=new LinkedHashSet<>(all);unused.removeAll(used);
                Set<Expression> joinTerms=applicable(unused,prefix.plan.getSchema(),leaf.plan.getSchema());used.addAll(joinTerms);
                PlanNode join=new NestedLoopJoinNode(prefix.plan,leaf.plan,JoinType.INNER,predicate(joinTerms));join.prepare();
                BitSet ids=(BitSet)prefix.leaves.clone();ids.set(i);String order=prefix.order+","+leaf.order;
                JoinComponent old=next.get(ids);int cmp=old==null?-1:Float.compare(join.getCost().cpuCost,old.plan.getCost().cpuCost);
                if(old==null || cmp<0 || cmp==0 && order.compareTo(old.order)<0)
                    next.put(ids,new JoinComponent(join,ids,used,order));
            }
            layer=next;
        }
        if(layer.size()!=1)throw new InvalidSQLException("No complete join plan");
        JoinComponent best=layer.values().iterator().next();Set<Expression> unused=new LinkedHashSet<>(all);unused.removeAll(best.used);
        return restore(filter(best.plan,unused),from.getSchema(),original,clauses,best.order);
    }
    /** Optimize only FROM/WHERE; all upper SQL clauses remain shared. */
    @Override protected PlanNode buildFromWhere(SelectClause select) {
        if(select.getFromClause()==null)return super.buildFromWhere(select);
        return makeJoinPlan(select.getFromClause(),terms(select.getWhereExpr()));
    }
}
