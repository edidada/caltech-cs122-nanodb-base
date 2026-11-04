package edu.caltech.test.nanodb.transactions;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.server.*;
import edu.caltech.nanodb.server.properties.ServerProperties;

/** Separate JVMs halt without shutdown hooks to exercise actual recovery paths. */
@Test(groups={"hw7"})
public class TestHw7CrashRecovery {
    private static NanoDBServer start(Path directory) throws Exception {
        Properties configuration=new Properties();
        configuration.setProperty("nanodb.baseDirectory",directory.toString());
        configuration.setProperty(ServerProperties.PROP_ENABLE_TRANSACTIONS,"true");
        configuration.setProperty("nanodb.flushAfterCmd","false");
        NanoDBServer database=new NanoDBServer();database.startup(configuration);return database;
    }
    private static CommandResult sql(NanoDBServer database,String statement) throws Exception {
        CommandResult result=database.doCommand(statement,true);
        if(result.failed())throw result.getFailure();return result;
    }
    /** Child entry point: Runtime.halt deliberately bypasses shutdown and finally. */
    public static void main(String[] arguments) throws Exception {
        NanoDBServer database=start(Paths.get(arguments[0]));
        sql(database,"CREATE TABLE crash_rows(id INTEGER,v INTEGER);");
        sql(database,"BEGIN;");sql(database,"INSERT INTO crash_rows VALUES(1,10);");
        sql(database,"INSERT INTO crash_rows VALUES(3,30);");sql(database,"COMMIT;");
        if(arguments[1].equals("undo")) {
            sql(database,"BEGIN;");sql(database,"UPDATE crash_rows SET v=99 WHERE id=1;");
            sql(database,"INSERT INTO crash_rows VALUES(2,20);");
            // Force dirty uncommitted data to disk. The eviction observer must
            // first make its undo records durable, but no COMMIT is emitted.
            database.getStorageManager().getBufferManager().writeAll(true);
        }
        Runtime.getRuntime().halt(73);
    }
    private void crashAndRecover(String mode) throws Exception {
        Path directory=Files.createTempDirectory("nanodb-hw7-crash-"+mode+"-");
        Path log=directory.resolve("child-output.txt");
        String executable=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process child=new ProcessBuilder(executable,"-cp",classpath,getClass().getName(),directory.toString(),mode)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            Assert.assertTrue(child.waitFor(90,TimeUnit.SECONDS),"Crash child timed out: "+log);
            Assert.assertEquals(child.exitValue(),73,Files.readString(log));
        }
        finally {if(child.isAlive())child.destroyForcibly().waitFor();}
        // Repeat startup after a normal shutdown as a basic idempotence check.
        for(int attempt=0;attempt<2;attempt++) {
            NanoDBServer database=start(directory);
            try {
                List<String> rows=new ArrayList<>();
                for(edu.caltech.nanodb.expressions.TupleLiteral row:sql(database,"SELECT id,v FROM crash_rows ORDER BY id;").getTuples())
                    rows.add(row.getColumnValue(0)+":"+row.getColumnValue(1));
                Assert.assertEquals(rows,Arrays.asList("1:10","3:30"),mode+" recovery attempt "+attempt);
            }
            finally {Assert.assertTrue(database.shutdown());}
        }
    }
    public void testCommittedWALReplaysWithoutDataFlush() throws Exception {crashAndRecover("redo");}
    public void testUncommittedFlushedDataIsUndone() throws Exception {crashAndRecover("undo");}
}
