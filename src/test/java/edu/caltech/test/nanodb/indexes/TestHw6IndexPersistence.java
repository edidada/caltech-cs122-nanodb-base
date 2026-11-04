package edu.caltech.test.nanodb.indexes;

import java.nio.file.*;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.server.*;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.indexes.*;

/** Verify index contents against actual table rows before and after restart. */
@Test(groups={"hw6"})
public class TestHw6IndexPersistence {
    private NanoDBServer start(Path directory) throws Exception {
        Properties properties=new Properties();
        properties.setProperty("nanodb.baseDirectory",directory.toString());
        properties.setProperty("nanodb.flushAfterCmd","true");
        NanoDBServer database=new NanoDBServer();database.startup(properties);return database;
    }
    private void sql(NanoDBServer database,String statement) throws Exception {
        CommandResult result=database.doCommand(statement,false);
        if(result.failed())throw result.getFailure();
    }
    private void verify(NanoDBServer database,int expected) {
        TableInfo table=database.getStorageManager().getTableManager().openTable("indexed_persist");
        Assert.assertFalse(table.getSchema().getIndexes().isEmpty(),"Automatic primary-key index required");
        for(IndexColumnRefs definition:table.getSchema().getIndexes()) {
            TupleFile index=database.getStorageManager().getIndexManager().openIndex(table,definition.getIndexName()).getTupleFile();
            Assert.assertTrue(index.verify().isEmpty());
            int count=0;
            Tuple row=table.getTupleFile().getFirstTuple();
            while(row!=null) {
                Tuple next;
                try {
                    edu.caltech.nanodb.expressions.TupleLiteral key=IndexUtils.makeTableSearchKey(definition,row,true);
                    PageTuple entry=IndexUtils.findTupleInIndex(key,index);
                    Assert.assertNotNull(entry,"Missing exact indexed row "+key);
                    try {
                        Assert.assertEquals(entry.getColumnCount(),key.getColumnCount());
                        for(int column=0;column<key.getColumnCount();column++)
                            Assert.assertEquals(entry.getColumnValue(column),key.getColumnValue(column));
                    }
                    finally {entry.unpin();}
                    count++;next=table.getTupleFile().getNextTuple(row);
                }
                finally {row.unpin();}
                row=next;
            }
            Assert.assertEquals(count,expected);
            count=0;row=index.getFirstTuple();
            while(row!=null) {
                Tuple next;
                try {count++;next=index.getNextTuple(row);}
                finally {row.unpin();}
                row=next;
            }
            Assert.assertEquals(count,expected,"No stale or duplicate index entries");
        }
    }
    public void testAutomaticAndSecondaryIndexesSurviveDeleteAndRestart() throws Exception {
        Path directory=Files.createTempDirectory("nanodb-hw6-index-");
        NanoDBServer database=start(directory);
        try {
            sql(database,"CREATE TABLE indexed_persist (id INTEGER PRIMARY KEY,k INTEGER);");
            sql(database,"CREATE INDEX by_k ON indexed_persist(k);");
            for(int id=0;id<400;id++)sql(database,"INSERT INTO indexed_persist VALUES ("+id+","+(id%5)+");");
            verify(database,400);
            sql(database,"DELETE FROM indexed_persist WHERE id<125;");
            verify(database,275);
        }
        finally {Assert.assertTrue(database.shutdown());}
        database=start(directory);
        try {verify(database,275);}
        finally {Assert.assertTrue(database.shutdown());}
    }
}
