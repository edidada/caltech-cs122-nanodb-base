package edu.caltech.test.nanodb.sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.*;

@Test(groups={"hw3"})
public class TestHw3Estimates {
    private Schema schema() {
        return new Schema(Arrays.asList(new ColumnInfo("a",new ColumnType(SQLDataType.INTEGER)),
            new ColumnInfo("b",new ColumnType(SQLDataType.VARCHAR)),
            new ColumnInfo("c",new ColumnType(SQLDataType.INTEGER))));
    }
    private ArrayList<ColumnStats> stats() {
        return new ArrayList<>(Arrays.asList(new ColumnStats(10,2,0,100),
            new ColumnStats(4,0,null,null),new ColumnStats(20,0,0,1000)));
    }
    private CompareOperator comparison(CompareOperator.Type type,String column,Object value) {
        return new CompareOperator(type,new ColumnValue(new ColumnName(column)),new LiteralValue(value));
    }
    private void close(float actual,float expected) { Assert.assertEquals(actual,expected,.00001f); }
    public void testSixComparisonsAndNormalizedCopy() {
        CompareOperator.Type[] types={CompareOperator.Type.EQUALS,CompareOperator.Type.NOT_EQUALS,
            CompareOperator.Type.GREATER_OR_EQUAL,CompareOperator.Type.LESS_THAN,
            CompareOperator.Type.LESS_OR_EQUAL,CompareOperator.Type.GREATER_THAN};
        float[] expected={.1f,.9f,.75f,.25f,.25f,.75f};
        for(int i=0;i<types.length;i++) close(SelectivityEstimator.estimateSelectivity(
            comparison(types[i],"a",25),schema(),stats()),expected[i]);
        CompareOperator reverse=new CompareOperator(CompareOperator.Type.LESS_THAN,
            new LiteralValue(25),new ColumnValue(new ColumnName("a")));
        String text=reverse.toString();
        close(SelectivityEstimator.estimateSelectivity(reverse,schema(),stats()),.75f);
        Assert.assertEquals(reverse.toString(),text);
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.EQUALS,"a",200),schema(),stats()),0);
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.NOT_EQUALS,"a",200),schema(),stats()),1);
    }
    public void testBooleanIndependenceAndColumnEquality() {
        Expression a=comparison(CompareOperator.Type.EQUALS,"a",5);
        Expression c=comparison(CompareOperator.Type.EQUALS,"c",5);
        for(BooleanOperator.Type type:new BooleanOperator.Type[]{BooleanOperator.Type.AND_EXPR,BooleanOperator.Type.OR_EXPR}) {
            BooleanOperator b=new BooleanOperator(type);b.addTerm(a);b.addTerm(c);
            close(SelectivityEstimator.estimateSelectivity(b,schema(),stats()),
                type==BooleanOperator.Type.AND_EXPR?.005f:.145f);
        }
        BooleanOperator not=new BooleanOperator(BooleanOperator.Type.NOT_EXPR);not.addTerm(a);
        close(SelectivityEstimator.estimateSelectivity(not,schema(),stats()),.9f);
        close(SelectivityEstimator.estimateSelectivity(new CompareOperator(CompareOperator.Type.EQUALS,
            new ColumnValue(new ColumnName("a")),new ColumnValue(new ColumnName("c"))),schema(),stats()),.05f);
    }
    public void testUnknownNullEmptyConstantAndStringDomains() {
        ArrayList<ColumnStats> s=stats();s.set(0,new ColumnStats());
        for(CompareOperator.Type type:CompareOperator.Type.values())
            close(SelectivityEstimator.estimateSelectivity(comparison(type,"a",5),schema(),s),.25f);
        s.set(0,new ColumnStats(0,30,null,null));
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.NOT_EQUALS,"a",5),schema(),s),0);
        s.set(0,new ColumnStats(1,0,5,5));
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.GREATER_THAN,"a",5),schema(),s),0);
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.LESS_OR_EQUAL,"a",5),schema(),s),1);
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.EQUALS,"b","hi"),schema(),s),.25f);
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.GREATER_THAN,"b","hi"),schema(),s),.25f);
        close(SelectivityEstimator.estimateSelectivity(comparison(CompareOperator.Type.EQUALS,"a",null),schema(),s),0);
    }
    public void testRatioAvoidsIntegerOverflowAndSupportsNumericDates() {
        close(SelectivityEstimator.computeRatio(Long.MIN_VALUE,0L,Long.MIN_VALUE,Long.MAX_VALUE),.5f);
        close(SelectivityEstimator.computeRatio(new BigDecimal("0"),new BigDecimal("0.25"),0,1),.25f);
        close(SelectivityEstimator.computeRatio(LocalDate.of(2026,1,1),LocalDate.of(2026,1,6),
            LocalDate.of(2026,1,1),LocalDate.of(2026,1,11)),.5f);
        close(SelectivityEstimator.computeRatio(LocalTime.of(0,0),LocalTime.of(6,0),
            LocalTime.of(0,0),LocalTime.of(12,0)),.5f);
        close(SelectivityEstimator.computeRatio(0,20,0,10),1);
        close(SelectivityEstimator.computeRatio(10,0,0,10),0);
    }
    public void testUpdatesCopyInputAndNarrowConjuncts() {
        ArrayList<ColumnStats> original=stats();
        BooleanOperator and=new BooleanOperator(BooleanOperator.Type.AND_EXPR);
        and.addTerm(comparison(CompareOperator.Type.GREATER_OR_EQUAL,"a",20));
        and.addTerm(comparison(CompareOperator.Type.LESS_OR_EQUAL,"a",80));
        ArrayList<ColumnStats> output=StatisticsUpdater.updateStats(and,schema(),original);
        Assert.assertEquals(output.get(0).getMinValue(),20);
        Assert.assertEquals(output.get(0).getMaxValue(),80);
        Assert.assertEquals(output.get(0).getNumUniqueValues(),6);
        Assert.assertEquals(output.get(0).getNumNullValues(),0);
        Assert.assertEquals(original.get(0).getMinValue(),0);
        Assert.assertEquals(original.get(0).getMaxValue(),100);
        Assert.assertEquals(original.get(0).getNumNullValues(),2);
        ColumnStats equal=StatisticsUpdater.updateStats(comparison(CompareOperator.Type.EQUALS,"a",5),schema(),original).get(0);
        Assert.assertEquals(equal.getNumUniqueValues(),1);Assert.assertEquals(equal.getMinValue(),5);
        ColumnStats impossible=StatisticsUpdater.updateStats(comparison(CompareOperator.Type.GREATER_THAN,"a",100),schema(),original).get(0);
        Assert.assertEquals(impossible.getNumUniqueValues(),0);Assert.assertNull(impossible.getMinValue());
    }
}
