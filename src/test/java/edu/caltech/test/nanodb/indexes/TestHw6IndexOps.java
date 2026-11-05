package edu.caltech.test.nanodb.indexes;

import org.testng.annotations.Test;

/** Explicit method annotations include the four enabled upstream tests.
 * Three historical unique-index methods are comments in the upstream file. */
public class TestHw6IndexOps extends TestIndexOps {
    @Override @Test(groups={"hw6"})
    public void testCreateNormalIndex() {super.testCreateNormalIndex();}
    @Override @Test(groups={"hw6"})
    public void testCreateSameColIndex() {super.testCreateSameColIndex();}
    @Override @Test(groups={"hw6"})
    public void testCreateSameIndex() {super.testCreateSameIndex();}
    @Override @Test(groups={"hw6"})
    public void testDropNormalIndex() {super.testDropNormalIndex();}
}
