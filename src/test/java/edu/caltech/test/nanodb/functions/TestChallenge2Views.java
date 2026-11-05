package edu.caltech.test.nanodb.functions;

import java.nio.file.*;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.server.*;

@Test(groups={"challenge2"})
public class TestChallenge2Views {
    private NanoDBServer start(Path directory) throws Exception {
        Properties properties=new Properties();properties.setProperty("nanodb.baseDirectory",directory.toString());
        properties.setProperty("nanodb.enableTransactions","false");
        NanoDBServer server=new NanoDBServer();server.startup(properties);return server;
    }
    private CommandResult sql(NanoDBServer server,String sql) throws Exception {
        CommandResult result=server.doCommand(sql,true);if(result.failed())throw result.getFailure();return result;
    }
    public void testDefinitionRestartExpansionAndDrop() throws Exception {
        Path directory=Files.createTempDirectory("challenge2-views-");NanoDBServer server=start(directory);
        try {
            sql(server,"CREATE TABLE sales(supplier INTEGER,amount INTEGER)");
            sql(server,"INSERT INTO sales VALUES(1,10)");sql(server,"INSERT INTO sales VALUES(1,20)");
            sql(server,"INSERT INTO sales VALUES(2,5)");
            sql(server,"CREATE VIEW revenue(supplier_no,total_revenue) AS SELECT supplier,SUM(amount) FROM sales GROUP BY supplier");
            Assert.assertEquals(((Number)sql(server,"SELECT MAX(total_revenue) FROM revenue").getTuples().get(0).getColumnValue(0)).intValue(),30);
            sql(server,"CREATE VIEW nested AS SELECT * FROM revenue");
            sql(server,"INSERT INTO sales VALUES(2,50)");
            Assert.assertEquals(((Number)sql(server,"SELECT MAX(total_revenue) FROM nested").getTuples().get(0).getColumnValue(0)).intValue(),55);
        }finally{Assert.assertTrue(server.shutdown());}
        server=start(directory);
        try {
            Assert.assertEquals(((Number)sql(server,"SELECT supplier_no FROM revenue WHERE total_revenue=(SELECT MAX(total_revenue) FROM revenue)").getTuples().get(0).getColumnValue(0)).intValue(),2);
            Assert.assertEquals(sql(server,"SELECT * FROM nested ORDER BY supplier_no").getTuples().size(),2);
            sql(server,"DROP VIEW nested");sql(server,"DROP VIEW revenue");sql(server,"DROP VIEW IF EXISTS revenue");
            Assert.assertTrue(server.doCommand("SELECT * FROM revenue",true).failed());
            Assert.assertTrue(server.doCommand("DROP VIEW revenue",true).failed());
            Assert.assertFalse(Files.exists(directory.resolve("revenue.view")));
        }finally{Assert.assertTrue(server.shutdown());}
    }
    public void testInvalidDefinitionsAndTableNameCollision() throws Exception {
        NanoDBServer server=start(Files.createTempDirectory("challenge2-invalid-views-"));
        try {
            sql(server,"CREATE TABLE original(k INTEGER)");
            Assert.assertTrue(server.doCommand("CREATE VIEW original AS SELECT 1 AS k",true).failed());
            Assert.assertTrue(server.doCommand("CREATE VIEW bad(a,b) AS SELECT 1 AS k",true).failed());
            Assert.assertTrue(server.doCommand("CREATE VIEW bad(a,a) AS SELECT 1 AS k,2 AS v",true).failed());
            Assert.assertTrue(server.doCommand("CREATE VIEW bad AS SELECT * FROM missing",true).failed());
            sql(server,"CREATE VIEW valid AS SELECT * FROM original");
            Assert.assertTrue(server.doCommand("CREATE VIEW valid AS SELECT 2 AS k",true).failed());
            Assert.assertTrue(server.doCommand("CREATE TABLE valid(k INTEGER)",true).failed());
        }finally{Assert.assertTrue(server.shutdown());}
    }
}
