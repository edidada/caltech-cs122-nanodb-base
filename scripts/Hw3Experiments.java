import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import edu.caltech.nanodb.commands.SelectCommand;
import edu.caltech.nanodb.plannodes.PlanNode;
import edu.caltech.nanodb.queryast.SelectClause;
import edu.caltech.nanodb.queryeval.*;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.server.*;

/** Full official stores-28K experiment; execute on Actions only. */
public final class Hw3Experiments {
    private static final PrintStream SINK=new PrintStream(OutputStream.nullOutputStream());
    private static String json(String value) {
        return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"";
    }
    private static void command(NanoDBServer server,String sql) throws Exception {
        CommandResult result=server.doCommand(sql,false);
        if(result.failed()) throw result.getFailure();
    }
    private static String explain(NanoDBServer server,String sql) throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        SessionState.get().setOutputStream(new PrintStream(buffer,true,StandardCharsets.UTF_8));
        try { command(server,"EXPLAIN "+sql); }
        finally { SessionState.get().setOutputStream(SINK); }
        return buffer.toString(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        PrintStream output=System.out;
        System.setOut(SINK);
        SessionState.get().setOutputStream(SINK);
        Properties settings=new Properties();
        settings.setProperty("nanodb.baseDirectory",Files.createTempDirectory("hw3-stores-").toString());
        settings.setProperty("nanodb.enforceKeyConstraints","false");
        settings.setProperty("nanodb.flushAfterCmd","true");
        settings.setProperty("nanodb.pagesize","8192");
        NanoDBServer server=new NanoDBServer();server.startup(settings);
        try {
            for(String file:new String[]{"make-stores.sql","stores-28K.sql"}) {
                String script=Files.readString(Path.of("schemas/stores",file));
                for(CommandResult result:server.doCommands(script,false))
                    if(result.failed()) throw result.getFailure();
            }
            command(server,"ANALYZE states, cities, stores, employees;");
            StringBuilder report=new StringBuilder("{\"dataset\":\"stores-28K.sql\",\"pageSize\":8192,\"tables\":[");
            boolean first=true;
            for(String table:new String[]{"states","cities","stores","employees"}) {
                TableInfo info=server.getStorageManager().getTableManager().openTable(table);
                // Check actual metadata reopening, not only an in-memory stats object.
                server.getStorageManager().getTableManager().closeTable(info);
                info=server.getStorageManager().getTableManager().openTable(table);
                TableStats stats=info.getStats();
                if(!first)report.append(',');first=false;
                report.append(String.format(Locale.ROOT,"{\"name\":%s,\"rows\":%d,\"dataPages\":%d,\"averageTupleBytes\":%.8f,\"columns\":[",json(table),stats.numTuples,stats.numDataPages,stats.avgTupleSize));
                for(int i=0;i<info.getSchema().numColumns();i++) {
                    if(i>0)report.append(',');ColumnStats column=stats.getColumnStats(i);
                    report.append(String.format(Locale.ROOT,"{\"name\":%s,\"distinct\":%d,\"nulls\":%d,\"min\":%s,\"max\":%s}",
                        json(info.getSchema().getColumnInfo(i).getName()),column.getNumUniqueValues(),column.getNumNullValues(),
                        column.getMinValue()==null?"null":json(column.getMinValue().toString()),
                        column.getMaxValue()==null?"null":json(column.getMaxValue().toString())));
                }
                report.append("]}");
            }
            report.append("],\"queries\":[");
            String[][] queries={
                {"cities_all","SELECT * FROM cities"},
                {"cities_1m","SELECT * FROM cities WHERE population>1000000"},
                {"cities_5m","SELECT * FROM cities WHERE population>5000000"},
                {"cities_8m","SELECT * FROM cities WHERE population>8000000"},
                {"join_original","SELECT store_id FROM stores,cities WHERE stores.city_id=cities.city_id AND cities.population>1000000"},
                {"join_rewritten","SELECT store_id FROM stores JOIN (SELECT city_id FROM cities WHERE population>1000000) big_cities ON stores.city_id=big_cities.city_id"},
                {"oregon_original","SELECT store_id,property_costs FROM stores,cities,states WHERE stores.city_id=cities.city_id AND cities.state_id=states.state_id AND state_name='Oregon' AND property_costs>500000"},
                {"oregon_rewritten","SELECT s.store_id,s.property_costs FROM (SELECT store_id,city_id,property_costs FROM stores WHERE property_costs>500000) s JOIN (SELECT c.city_id FROM cities c JOIN (SELECT state_id FROM states WHERE state_name='Oregon') o ON c.state_id=o.state_id) eligible ON s.city_id=eligible.city_id"},
                {"range_direct","SELECT * FROM cities WHERE city_id>20 AND city_id<200"},
                {"range_low_first","SELECT * FROM (SELECT * FROM cities WHERE city_id>20) t WHERE city_id<200"},
                {"range_high_first","SELECT * FROM (SELECT * FROM cities WHERE city_id<200) t WHERE city_id>20"}
            };
            first=true;
            for(String[] query:queries) {
                String explanation=explain(server,query[1]);
                SelectClause select=((SelectCommand)server.parseCommand(query[1])).getSelectClause();
                select.computeSchema(server.getStorageManager().getTableManager(),null);
                PlanNode plan=server.getQueryPlanner().makePlan(select,null);
                PlanCost cost=plan.getCost();
                List<String> rows=new ArrayList<>();
                long start=System.nanoTime();
                QueryEvaluator.executePlan(plan,new TupleProcessor() {
                    public void setSchema(Schema schema) { }
                    public void finish() { }
                    public void process(Tuple tuple) {
                        List<Object> values=new ArrayList<>();
                        for(int i=0;i<tuple.getColumnCount();i++)values.add(tuple.getColumnValue(i));
                        rows.add(values.toString());
                    }
                });
                double seconds=(System.nanoTime()-start)/1e9;
                Collections.sort(rows);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                for(String row:rows)digest.update((row+"\n").getBytes(StandardCharsets.UTF_8));
                if(!first)report.append(',');first=false;
                report.append(String.format(Locale.ROOT,"{\"name\":%s,\"sql\":%s,\"estimatedRows\":%.8f,\"cpuCost\":%.8f,\"blockIOs\":%d,\"actualRows\":%d,\"seconds\":%.8f,\"resultSha256\":%s,\"explain\":%s}",
                    json(query[0]),json(query[1]),cost.numTuples,cost.cpuCost,cost.numBlockIOs,rows.size(),seconds,
                    json(HexFormat.of().formatHex(digest.digest())),json(explanation)));
                Path.of("target/hw3-experiments").toFile().mkdirs();
                Files.writeString(Path.of("target/hw3-experiments/progress.json"),report.toString()+"]}");
                output.println(query[0]+": rows="+rows.size()+" estimated="+cost.numTuples+" seconds="+seconds);
            }
            report.append("]}");
            Files.writeString(Path.of("target/hw3-experiments/results.json"),report.toString());
        }
        finally { if(!server.shutdown())throw new AssertionError("Shutdown failed"); }
    }
}
