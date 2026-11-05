package edu.caltech.test.nanodb.transactions;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.server.*;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.indexes.*;

/** Full-feature single-writer index WAL, including actual process death. */
@Test(groups={"challenge1"})
public class TestChallenge1Indexes {
    private static NanoDBServer start(Path directory) throws Exception {
        Properties p=new Properties();p.setProperty("nanodb.baseDirectory",directory.toString());
        p.setProperty("nanodb.enableTransactions","true");p.setProperty("nanodb.enableIndexes","true");
        p.setProperty("nanodb.createIndexesOnKeys","true");p.setProperty("nanodb.enforceKeyConstraints","true");
        p.setProperty("nanodb.flushAfterCmd","false");
        NanoDBServer db=new NanoDBServer();db.startup(p);return db;
    }
    private static CommandResult sql(NanoDBServer db,String statement) throws Exception {
        CommandResult r=db.doCommand(statement,true);if(r.failed())throw r.getFailure();return r;
    }
    private static void insert(NanoDBServer db,int id) throws Exception {
        sql(db,"INSERT INTO indexed_rows VALUES("+id+","+(id%7)+",'v"+id+"-"+"x".repeat(120)+"');");
    }
    private static void seed(NanoDBServer db) throws Exception {
        sql(db,"CREATE TABLE indexed_rows(id INTEGER PRIMARY KEY,k INTEGER,v VARCHAR(200));");
        sql(db,"CREATE INDEX by_k ON indexed_rows(k);");
        for(int i=0;i<400;i++)insert(db,i);
    }
    private static void verify(NanoDBServer db,int expected) throws Exception {
        TableInfo table=db.getStorageManager().getTableManager().openTable("indexed_rows");
        Set<Integer> ids=new HashSet<>();Tuple row=table.getTupleFile().getFirstTuple();
        while(row!=null) {
            Tuple next;try {
                int id=((Number)row.getColumnValue(0)).intValue();Assert.assertTrue(ids.add(id));
                Assert.assertTrue(id>=0&&id<expected);Assert.assertEquals(row.getColumnValue(1),id%7);
                Assert.assertEquals(row.getColumnValue(2),"v"+id+"-"+"x".repeat(120));
                next=table.getTupleFile().getNextTuple(row);
            }finally{row.unpin();}row=next;
        }
        Assert.assertEquals(ids.size(),expected);
        Assert.assertEquals(table.getSchema().getIndexes().size(),2);
        for(IndexColumnRefs definition:table.getSchema().getIndexes()) {
            TupleFile index=db.getStorageManager().getIndexManager().openIndex(table,definition.getIndexName()).getTupleFile();
            Assert.assertTrue(index.verify().isEmpty(),index.verify().toString());
            row=table.getTupleFile().getFirstTuple();int matched=0;
            while(row!=null) {
                Tuple next;try {
                    edu.caltech.nanodb.expressions.TupleLiteral key=IndexUtils.makeTableSearchKey(definition,row,true);
                    PageTuple found=IndexUtils.findTupleInIndex(key,index);Assert.assertNotNull(found,"Missing "+key);
                    try {for(int i=0;i<key.getColumnCount();i++)Assert.assertEquals(found.getColumnValue(i),key.getColumnValue(i));}
                    finally{found.unpin();}
                    matched++;next=table.getTupleFile().getNextTuple(row);
                }finally{row.unpin();}row=next;
            }
            int entries=0;row=index.getFirstTuple();
            while(row!=null){Tuple next;try{entries++;next=index.getNextTuple(row);}finally{row.unpin();}row=next;}
            Assert.assertEquals(matched,expected);Assert.assertEquals(entries,expected);
        }
        db.getStorageManager().getBufferManager().unpinAllSessionPages();
    }
    public void testRollbackAndRestartRestoreAllIndexEntries() throws Exception {
        Path directory=Files.createTempDirectory("challenge1-index-rollback-");NanoDBServer db=start(directory);
        try {
            seed(db);verify(db,400);sql(db,"BEGIN;");
            sql(db,"DELETE FROM indexed_rows WHERE id<100;");
            sql(db,"UPDATE indexed_rows SET k=k+3 WHERE id>=100;");
            for(int i=400;i<1200;i++)insert(db,i);
            sql(db,"ROLLBACK;");verify(db,400);
        }finally{Assert.assertTrue(db.shutdown());}
        db=start(directory);try{verify(db,400);}finally{Assert.assertTrue(db.shutdown());}
    }
    public void testIndexedPrimaryAndForeignConstraints() throws Exception {
        NanoDBServer db=start(Files.createTempDirectory("challenge1-constraints-"));
        try {
            sql(db,"CREATE TABLE parents(id INTEGER PRIMARY KEY);");
            sql(db,"CREATE TABLE children(id INTEGER PRIMARY KEY,pid INTEGER REFERENCES parents(id));");
            sql(db,"INSERT INTO parents VALUES(1);");sql(db,"INSERT INTO children VALUES(1,1);");
            Assert.assertTrue(db.doCommand("INSERT INTO children VALUES(1,1);",true).failed());
            Assert.assertTrue(db.doCommand("INSERT INTO children VALUES(2,99);",true).failed());
            Assert.assertTrue(db.doCommand("INSERT INTO parents VALUES(NULL);",true).failed());
            Assert.assertEquals(((Number)sql(db,"SELECT count(*) FROM children;").getTuples().get(0).getColumnValue(0)).intValue(),1);
        }finally{Assert.assertTrue(db.shutdown());}
    }
    public static void main(String[] args) throws Exception {
        NanoDBServer db=start(Paths.get(args[0]));seed(db);sql(db,"BEGIN;");
        for(int i=400;i<1600;i++)insert(db,i);
        if(args[1].equals("redo"))sql(db,"COMMIT;");
        else db.getStorageManager().getBufferManager().writeAll(true);
        Runtime.getRuntime().halt(79);
    }
    private void crash(String mode) throws Exception {
        Path directory=Files.createTempDirectory("challenge1-index-crash-");Path log=directory.resolve("child.log");
        String cp=System.getProperty("nanodb.test.classpath",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));
        Process child=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,getClass().getName(),directory.toString(),mode)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{Assert.assertTrue(child.waitFor(180,TimeUnit.SECONDS),"Timed out: "+log);Assert.assertEquals(child.exitValue(),79,Files.readString(log));}
        finally{if(child.isAlive())child.destroyForcibly().waitFor();}
        for(int attempt=0;attempt<2;attempt++) {
            NanoDBServer db=start(directory);try{verify(db,mode.equals("redo")?1600:400);}finally{Assert.assertTrue(db.shutdown());}
        }
    }
    public void testIndexedCrashRedo() throws Exception{crash("redo");}
    public void testIndexedCrashUndo() throws Exception{crash("undo");}
}
