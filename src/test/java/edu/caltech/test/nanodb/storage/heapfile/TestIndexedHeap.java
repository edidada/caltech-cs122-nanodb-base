package edu.caltech.test.nanodb.storage.heapfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import edu.caltech.nanodb.expressions.TupleLiteral;
import edu.caltech.nanodb.relations.Tuple;
import edu.caltech.nanodb.server.CommandResult;
import edu.caltech.nanodb.server.NanoDBServer;
import edu.caltech.nanodb.storage.FilePointer;
import edu.caltech.nanodb.storage.PageTuple;
import edu.caltech.nanodb.storage.TupleFile;

/** Direct storage calls deliberately bypass the server's end-command unpin
 * safety net, so leaked storage pins cannot be hidden by SQL cleanup. */
@Test(groups = {"hw1"})
public class TestIndexedHeap {
    private NanoDBServer start(Path dir, int cache) throws Exception {
        Properties props = new Properties();
        props.setProperty("nanodb.baseDirectory", dir.toString());
        props.setProperty("nanodb.pagecache.size", Integer.toString(cache));
        props.setProperty("nanodb.flushAfterCmd", "true");
        NanoDBServer server = new NanoDBServer();
        server.startup(props);
        return server;
    }

    private void sql(NanoDBServer server, String command) throws Exception {
        CommandResult result = server.doCommand(command, false);
        if (result.failed())
            throw result.getFailure();
    }

    private void release(Tuple tuple) {
        PageTuple pageTuple = (PageTuple) tuple;
        Assert.assertEquals(pageTuple.getDBPage().getPinCount(), 1,
            "Exactly the returned tuple owns the data-page pin");
        tuple.unpin();
        Assert.assertEquals(pageTuple.getDBPage().getPinCount(), 0);
    }

    public void testAllPageSizesAndReopen() throws Exception {
        for (int size = 512; size <= 65536; size *= 2)
            scenario(size, 30, 1 << 20);
    }

    public void testSecondMapLeafAndMinimumCache() throws Exception {
        // A 450-character row fits once in a 512-byte page. 620 rows cross
        // two 256-data-page directory boundaries, under the minimum 64KiB cache.
        scenario(512, 620, 65536);
    }

    private void scenario(int size, int rows, int cache) throws Exception {
        Path dir = Files.createTempDirectory("hw1-map-");
        FilePointer[] pointers = new FilePointer[rows];
        NanoDBServer server = start(dir, cache);
        try {
            sql(server, "CREATE TABLE h (id INTEGER, text VARCHAR(450)) " +
                "PROPERTIES (pagesize=" + size + ");");
            TupleFile file = server.getStorageManager().getTableManager()
                .openTable("h").getTupleFile();
            String value = "x".repeat(450);
            for (int i = 0; i < rows; i++) {
                Tuple tuple = file.addTuple(new TupleLiteral(i, value));
                pointers[i] = tuple.getExternalReference();
                release(tuple);
            }
            // Delete alternating rows; retain the other stable external IDs.
            for (int i = 0; i < rows; i += 2) {
                Tuple tuple = file.getTuple(pointers[i]);
                file.deleteTuple(tuple);
                release(tuple);
            }
            server.getStorageManager().flushAllData();
        }
        finally { Assert.assertTrue(server.shutdown()); }
        server = start(dir, cache);
        try {
            TupleFile file = server.getStorageManager().getTableManager()
                .openTable("h").getTupleFile();
            long bytes = file.getDBFile().getFileSize();
            for (int i = 1; i < rows; i += 2) {
                Tuple tuple = file.getTuple(pointers[i]);
                Assert.assertEquals(tuple.getColumnValue(0), i);
                Assert.assertEquals(tuple.getColumnValue(1), "x".repeat(450));
                release(tuple);
            }
            // Reopened map must reuse deleted capacity rather than append.
            for (int i = 0; i < rows; i += 2)
                release(file.addTuple(new TupleLiteral(i, "y".repeat(450))));
            server.getStorageManager().flushAllData();
            Assert.assertEquals(file.getDBFile().getFileSize(), bytes);
            boolean[] seen = new boolean[rows];
            int count = 0;
            for (Tuple tuple = file.getFirstTuple(); tuple != null;) {
                int id = (Integer) tuple.getColumnValue(0);
                Assert.assertFalse(seen[id]);
                seen[id] = true;
                Assert.assertEquals(tuple.getColumnValue(1),
                    (id % 2 == 0 ? "y" : "x").repeat(450));
                release(tuple);
                tuple = file.getNextTuple(tuple);
                count++;
            }
            Assert.assertEquals(count, rows);
        }
        finally { Assert.assertTrue(server.shutdown()); }
    }

    public void testRandomNullBitmapAndResize() throws Exception {
        Path dir = Files.createTempDirectory("hw1-update-");
        NanoDBServer server = start(dir, 65536);
        try {
            StringBuilder schema = new StringBuilder("CREATE TABLE h (");
            for (int i = 0; i < 20; i++) {
                if (i > 0) schema.append(",");
                schema.append("c").append(i).append(" VARCHAR(50)");
            }
            sql(server, schema.append(") PROPERTIES (pagesize=4096);").toString());
            TupleFile file = server.getStorageManager().getTableManager()
                .openTable("h").getTupleFile();
            Object[] expected = new Object[20];
            Tuple tuple = file.addTuple(new TupleLiteral(expected));
            FilePointer pointer = tuple.getExternalReference();
            release(tuple);
            Random random = new Random(122);
            for (int n = 0; n < 1500; n++) {
                int column = random.nextInt(expected.length);
                Object value = random.nextInt(4) == 0 ? null :
                    "a".repeat(random.nextInt(51));
                Map<String, Object> update = new HashMap<>();
                update.put("c" + column, value);
                tuple = file.getTuple(pointer);
                file.updateTuple(tuple, update);
                expected[column] = value;
                for (int i = 0; i < expected.length; i++)
                    Assert.assertEquals(tuple.getColumnValue(i), expected[i]);
                release(tuple);
                if (n % 19 == 0) server.getStorageManager().flushAllData();
            }
        }
        finally { Assert.assertTrue(server.shutdown()); }
    }
}
