package edu.caltech.test.nanodb.transactions;

import java.nio.file.*;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.server.*;
import edu.caltech.nanodb.server.properties.ServerProperties;

/** Real heap transactions with logging enabled; not a substitute for crash tests. */
@Test(groups={"hw7"})
public class TestHw7Transactions {
    private NanoDBServer start(Path directory) throws Exception {
        Properties configuration=new Properties();
        configuration.setProperty("nanodb.baseDirectory",directory.toString());
        NanoDBServer database=new NanoDBServer();database.startup(configuration);
        Assert.assertNotNull(database.getStorageManager().getTransactionManager());return database;
    }
    private CommandResult sql(NanoDBServer database,String statement) throws Exception {
        CommandResult result=database.doCommand(statement,true);
        if(result.failed())throw result.getFailure();return result;
    }
    private void values(NanoDBServer database,String... expected) throws Exception {
        List<String> actual=new ArrayList<>();
        for(edu.caltech.nanodb.expressions.TupleLiteral row:sql(database,"SELECT id,v FROM durable_rows ORDER BY id;").getTuples())
            actual.add(row.getColumnValue(0)+":"+row.getColumnValue(1));
        Assert.assertEquals(actual,Arrays.asList(expected));
    }
    public void testCommitRollbackAndRestartWithRealLogging() throws Exception {
        Path directory=Files.createTempDirectory("nanodb-hw7-transactions-");
        NanoDBServer database=start(directory);
        int lastTransactionID;
        try {
            sql(database,"CREATE TABLE durable_rows(id INTEGER,v INTEGER);");
            sql(database,"BEGIN;");sql(database,"INSERT INTO durable_rows VALUES(1,10);");sql(database,"COMMIT;");
            sql(database,"BEGIN;");sql(database,"UPDATE durable_rows SET v=99 WHERE id=1;");
            sql(database,"INSERT INTO durable_rows VALUES(2,20);");
            values(database,"1:99","2:20");sql(database,"ROLLBACK;");values(database,"1:10");
            sql(database,"BEGIN;");sql(database,"DELETE FROM durable_rows WHERE id=1;");
            values(database);sql(database,"ROLLBACK;");values(database,"1:10");
            sql(database,"BEGIN;");sql(database,"INSERT INTO durable_rows VALUES(3,30);");sql(database,"COMMIT;");
            values(database,"1:10","3:30");
            sql(database,"BEGIN;");
            lastTransactionID=SessionState.get().getTransactionState().getTransactionID();
            Assert.assertTrue(lastTransactionID>0);
            sql(database,"ROLLBACK;"); // Read-only transaction still consumes an ID.
        }
        finally {Assert.assertTrue(database.shutdown());}
        database=start(directory);
        try {
            // Check the first post-restart allocation before a SELECT can
            // consume an autocommit ID and hide reuse of the previous ID.
            sql(database,"BEGIN;");
            Assert.assertTrue(SessionState.get().getTransactionState().getTransactionID()>lastTransactionID,
                "Restart reused a transaction ID");
            values(database,"1:10","3:30");
            sql(database,"COMMIT;");
        }
        finally {Assert.assertTrue(database.shutdown());}
    }
}
