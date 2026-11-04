package edu.caltech.test.nanodb.storage.btreefile;

import java.nio.file.*;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.test.nanodb.sql.SqlTestCase;
import edu.caltech.nanodb.storage.DBPage;
import edu.caltech.nanodb.storage.btreefile.*;

/** Measure the actual relocation policy instead of assuming split-only occupancy. */
@Test(groups={"hw6"})
public class TestHw6Occupancy extends SqlTestCase {
    private String build(String table,boolean oddEven) throws Exception {
        int rows=6000;
        tryDoCommand("CREATE TABLE "+table+" (a INTEGER,b CHAR(128)) PROPERTIES (storage='btree');");
        String padding="x".repeat(128);
        for(int pass=0;pass<(oddEven?2:1);pass++) {
            int first=oddEven?pass+1:1,step=oddEven?2:1;
            for(int key=first;key<=rows;key+=step)
                tryDoCommand("INSERT INTO "+table+" VALUES ("+key+",'"+padding+"');");
        }
        BTreeTupleFile file=(BTreeTupleFile)server.getStorageManager().getTableManager().openTable(table).getTupleFile();
        Assert.assertTrue(file.verify().isEmpty(),"BTree VERIFY failed for "+table);
        DBPage header=server.getStorageManager().loadDBPage(file.getDBFile(),0);
        int page;
        try {page=HeaderPage.getFirstLeafPageNo(header);}
        finally {header.unpin();}
        int leaves=0,seenRows=0;long used=0,total=0;double minimum=1,maximum=0;
        Set<Integer> visited=new HashSet<>();
        while(page!=0) {
            Assert.assertTrue(visited.add(page),"Leaf chain must not cycle");
            DBPage data=server.getStorageManager().loadDBPage(file.getDBFile(),page);
            try {
                LeafPage leaf=new LeafPage(data,file.getSchema());
                for(int index=0;index<leaf.getNumTuples();index++) {
                    Assert.assertEquals(((Number)leaf.getTuple(index).getColumnValue(0)).intValue(),++seenRows);
                }
                leaves++;used+=leaf.getSpaceUsedByTuples();total+=leaf.getTotalSpace();
                double fill=leaf.getSpaceUsedByTuples()/(double)leaf.getTotalSpace();
                minimum=Math.min(minimum,fill);maximum=Math.max(maximum,fill);
                page=leaf.getNextPageNo();
            }
            finally {data.unpin();}
        }
        Assert.assertEquals(seenRows,rows);
        return String.format(Locale.ROOT,
            "{\"pattern\":\"%s\",\"rows\":%d,\"leaves\":%d,\"usedBytes\":%d,\"capacityBytes\":%d,\"meanFill\":%.8f,\"minFill\":%.8f,\"maxFill\":%.8f}",
            oddEven?"odd-then-even":"ascending",rows,leaves,used,total,used/(double)total,minimum,maximum);
    }
    public void testRebuildOccupancyAndCompleteLeafOrder() throws Exception {
        String ascending=build("occupancy_ascending",false),oddEven=build("occupancy_oddeven",true);
        Path output=Paths.get("target/surefire-reports/hw6-occupancy.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output,"["+ascending+","+oddEven+"]");
    }
    /** An equal prefix spans multiple leaves; the next greater value is later. */
    public void testPrefixLookupCrossesLeafBoundaries() throws Exception {
        tryDoCommand("CREATE TABLE prefix_lookup (a INTEGER,b INTEGER,c CHAR(256)) PROPERTIES (storage='btree');");
        String padding="p".repeat(256);
        for(int key=0;key<180;key++)
            tryDoCommand("INSERT INTO prefix_lookup VALUES (1,"+key+",'"+padding+"');");
        tryDoCommand("INSERT INTO prefix_lookup VALUES (2,0,'"+padding+"');");
        BTreeTupleFile file=(BTreeTupleFile)server.getStorageManager().getTableManager().openTable("prefix_lookup").getTupleFile();
        edu.caltech.nanodb.relations.Tuple first=file.findFirstTupleEquals(new edu.caltech.nanodb.expressions.TupleLiteral(1));
        Assert.assertNotNull(first);
        try {Assert.assertEquals(first.getColumnValue(1),0);}
        finally {first.unpin();}
        edu.caltech.nanodb.relations.Tuple greater=file.findFirstTupleGreaterThan(new edu.caltech.nanodb.expressions.TupleLiteral(1));
        Assert.assertNotNull(greater);
        try {Assert.assertEquals(greater.getColumnValue(0),2);}
        finally {greater.unpin();}
        Assert.assertNull(file.findFirstTupleGreaterThan(new edu.caltech.nanodb.expressions.TupleLiteral(2)));
        Assert.assertTrue(file.verify().isEmpty());
    }

}
