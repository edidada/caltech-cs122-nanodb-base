import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.TreeMap;
import edu.caltech.nanodb.relations.Tuple;
import edu.caltech.nanodb.server.CommandResult;
import edu.caltech.nanodb.server.NanoDBServer;
import edu.caltech.nanodb.server.performance.PerformanceCounters;
import edu.caltech.nanodb.storage.TupleFile;

/** Run only on Actions. Identical driver and settings for baseline and map. */
public final class Hw1Benchmark {
    private static void command(NanoDBServer server, String sql) throws Exception {
        CommandResult result = server.doCommand(sql, false);
        if (result.failed()) throw result.getFailure();
    }

    public static void main(String[] args) throws Exception {
        PrintStream output = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        NanoDBServer server = new NanoDBServer();
        Properties props = new Properties();
        Path dir = Files.createTempDirectory("hw1-benchmark-");
        props.setProperty("nanodb.baseDirectory", dir.toString());
        props.setProperty("nanodb.flushAfterCmd", "true");
        props.setProperty("nanodb.enforceKeyConstraints", "false");
        props.setProperty("nanodb.pagecache.size", "1048576");
        props.setProperty("nanodb.pagesize", "8192");
        server.startup(props);
        try {
            command(server, "CREATE TABLE insert_perf (id INTEGER, str VARCHAR(200), num FLOAT);");
            server.getStorageManager().flushAllData();
            PerformanceCounters.clearAll();
            long start = System.nanoTime();
            int statements = 0;
            for (String line : Files.readAllLines(Path.of(args[0]))) {
                String sql = line.trim();
                if (sql.isEmpty() || sql.equalsIgnoreCase("EXIT;")) continue;
                command(server, sql);
                statements++;
            }
            server.getStorageManager().flushAllData();
            double seconds = (System.nanoTime() - start) / 1e9;
            long reads = PerformanceCounters.get(PerformanceCounters.STORAGE_PAGES_READ);
            long writes = PerformanceCounters.get(PerformanceCounters.STORAGE_PAGES_WRITTEN);
            long changes = PerformanceCounters.get(PerformanceCounters.STORAGE_FILE_CHANGES);
            long distance = PerformanceCounters.get(PerformanceCounters.STORAGE_FILE_DISTANCE_TRAVELED);
            TupleFile file = server.getStorageManager().getTableManager()
                .openTable("insert_perf").getTupleFile();
            long bytes = file.getDBFile().getFileSize();
            TreeMap<Integer, String> rows = new TreeMap<>();
            for (Tuple tuple = file.getFirstTuple(); tuple != null;) {
                int id = (Integer) tuple.getColumnValue(0);
                String row = id + "|" + tuple.getColumnValue(1) + "|" +
                    Float.floatToIntBits((Float) tuple.getColumnValue(2)) + "\n";
                if (rows.put(id, row) != null) throw new AssertionError("Duplicate row " + id);
                tuple.unpin();
                tuple = file.getNextTuple(tuple);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String row : rows.values()) digest.update(row.getBytes(StandardCharsets.UTF_8));
            output.printf(java.util.Locale.ROOT,
                "{\"workload\":\"%s\",\"statements\":%d,\"rows\":%d,\"bytes\":%d," +
                "\"pagesRead\":%d,\"pagesWritten\":%d,\"fileChanges\":%d," +
                "\"fileDistanceTraveled\":%d,\"seconds\":%.6f,\"rowsSha256\":\"%s\"}%n",
                Path.of(args[0]).getFileName(), statements, rows.size(), bytes,
                reads, writes, changes, distance, seconds,
                HexFormat.of().formatHex(digest.digest()));
        }
        finally {
            if (!server.shutdown()) throw new AssertionError("Shutdown failed");
        }
    }
}
