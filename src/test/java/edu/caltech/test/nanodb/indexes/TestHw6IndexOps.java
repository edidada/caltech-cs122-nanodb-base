package edu.caltech.test.nanodb.indexes;

import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.relations.TableInfo;
import edu.caltech.nanodb.indexes.IndexInfo;
import edu.caltech.nanodb.server.CommandResult;

/** Four enabled historical tests; two metadata fixtures adapt SQL name folding.
 * Original TestIndexOps remains unchanged, including its three commented tests. */
public class TestHw6IndexOps extends TestIndexOps {
    @Override @Test(groups={"hw6"})
    public void testCreateNormalIndex() {
        CommandResult result=server.doCommand("CREATE INDEX idx_test1 ON test_index_ops(a)",false);
        Assert.assertFalse(result.failed(),String.valueOf(result.getFailure()));
        TableInfo table=server.getStorageManager().getTableManager().openTable("test_index_ops");
        IndexInfo index=server.getStorageManager().getIndexManager().openIndex(table,"idx_test1");
        Assert.assertEquals(index.getIndexName(),"idx_test1");
        Assert.assertEquals(index.getTableName(),"test_index_ops");
        Assert.assertTrue(table.getSchema().getIndexNames().contains("idx_test1"));
    }
    @Override @Test(groups={"hw6"})
    public void testCreateSameColIndex() {super.testCreateSameColIndex();}
    @Override @Test(groups={"hw6"})
    public void testCreateSameIndex() {super.testCreateSameIndex();}
    @Override @Test(groups={"hw6"})
    public void testDropNormalIndex() {
        TableInfo table=server.getStorageManager().getTableManager().openTable("test_index_ops");
        java.util.Set<String> before=new java.util.HashSet<>(table.getSchema().getIndexNames());
        CommandResult created=server.doCommand("CREATE INDEX idx_test4 ON test_index_ops(c)",false);
        Assert.assertFalse(created.failed(),String.valueOf(created.getFailure()));
        Assert.assertTrue(table.getSchema().getIndexNames().contains("idx_test4"));
        CommandResult dropped=server.doCommand("DROP INDEX idx_test4 ON test_index_ops",false);
        Assert.assertFalse(dropped.failed(),String.valueOf(dropped.getFailure()));
        // Other methods share the table and may already have created indexes.
        Assert.assertEquals(new java.util.HashSet<>(table.getSchema().getIndexNames()),before);
    }
}
