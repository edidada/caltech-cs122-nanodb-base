package edu.caltech.test.nanodb.sql;

import org.testng.annotations.Test;

/** Run the unchanged official natural/USING smoke suite in the HW2 group. */
@Test(groups = {"hw2"})
public class TestHw2NaturalUsingJoins extends TestNaturalUsingJoins {
    @Override @Test(groups = {"hw2"})
    public void testNaturalUsingTablesNotEmpty() throws Throwable { super.testNaturalUsingTablesNotEmpty(); }
    @Override @Test(groups = {"hw2"})
    public void testNaturalUsingJoinTwoTables() throws Throwable { super.testNaturalUsingJoinTwoTables(); }
    @Override @Test(groups = {"hw2"})
    public void testNaturalJoinMultiTables() throws Throwable { super.testNaturalJoinMultiTables(); }
    @Override @Test(groups = {"hw2"})
    public void testUsingJoinMultiTables() throws Throwable { super.testUsingJoinMultiTables(); }
}
