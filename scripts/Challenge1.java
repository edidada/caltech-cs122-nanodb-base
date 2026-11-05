import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import edu.caltech.nanodb.server.*;
import edu.caltech.nanodb.relations.*;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.indexes.*;

/** Full official SF0.01 import, statistics, restart and independent-oracle outputs. */
public final class Challenge1 {
    private static final String[] TABLES={"region","nation","part","supplier","partsupp","customer","orders","lineitem"};
    private static final PrintStream SINK=new PrintStream(OutputStream.nullOutputStream());
    private static String json(String value) {
        return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"";
    }
    private static String canonical(Object value) {
        if(value instanceof Number)return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return value.toString();
    }
    private static CommandResult command(NanoDBServer db,String sql) throws Exception {
        CommandResult r=db.doCommand(sql,sql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT"));
        if(r.failed())throw r.getFailure();return r;
    }
    private static NanoDBServer start(Path directory) throws Exception {
        Properties p=new Properties();p.setProperty("nanodb.baseDirectory",directory.toString());
        p.setProperty("nanodb.pagecache.writebackReserve","262144");
        p.setProperty("nanodb.pagecache.size","10485760");p.setProperty("nanodb.pagesize","8192");
        p.setProperty("nanodb.flushAfterCmd","false");p.setProperty("nanodb.enableTransactions","true");
        p.setProperty("nanodb.enableIndexes","true");p.setProperty("nanodb.createIndexesOnKeys","true");
        p.setProperty("nanodb.enforceKeyConstraints","true");
        NanoDBServer db=new NanoDBServer();db.startup(p);
        return db;
    }
    private static String indexReport(NanoDBServer db,TableInfo table,IndexColumnRefs definition,long expected) {
        TupleFile index=db.getStorageManager().getIndexManager().openIndex(table,definition.getIndexName()).getTupleFile();
        List<String> errors=index.verify();if(!errors.isEmpty())throw new AssertionError(errors);
        Tuple row=table.getTupleFile().getFirstTuple();long checked=0;
        while(row!=null) {
            Tuple next;
            try {
                edu.caltech.nanodb.expressions.TupleLiteral key=IndexUtils.makeTableSearchKey(definition,row,true);
                PageTuple found=IndexUtils.findTupleInIndex(key,index);
                if(found==null)throw new AssertionError("Missing index key "+key);
                try {
                    for(int i=0;i<key.getColumnCount();i++)
                        if(!Objects.equals(found.getColumnValue(i),key.getColumnValue(i)))throw new AssertionError("Wrong index entry");
                }
                finally {found.unpin();}
                checked++;next=table.getTupleFile().getNextTuple(row);
            }
            finally {row.unpin();}
            row=next;
        }
        long entries=0;row=index.getFirstTuple();
        while(row!=null) {
            Tuple next;try {entries++;next=index.getNextTuple(row);}finally {row.unpin();}row=next;
        }
        if(entries!=expected||checked!=expected)throw new AssertionError("Index row count "+entries+" expected "+expected);
        return "{\"name\":"+json(definition.getIndexName())+",\"rows\":"+entries+",\"errors\":[]}";
    }
    private static String tableReport(NanoDBServer db,String name) throws Exception {
        TableInfo table=db.getStorageManager().getTableManager().openTable(name);
        TupleFile file=table.getTupleFile();List<String> rows=new ArrayList<>();Tuple row=file.getFirstTuple();
        while(row!=null) {
            Tuple next;try {
                StringJoiner values=new StringJoiner(",","[","]");
                for(int i=0;i<row.getColumnCount();i++)values.add(row.isNullValue(i)?"null":json(canonical(row.getColumnValue(i))));
                rows.add(values.toString());next=file.getNextTuple(row);
            }
            finally {row.unpin();}row=next;
        }
        Collections.sort(rows);MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String line:rows)hash.update((line+"\n").getBytes(StandardCharsets.UTF_8));
        long count=((Number)command(db,"SELECT count(*) FROM "+name+";").getTuples().get(0).getColumnValue(0)).longValue();
        StringJoiner indexes=new StringJoiner(",","[","]");
        for(IndexColumnRefs definition:table.getSchema().getIndexes())indexes.add(indexReport(db,table,definition,rows.size()));
        String result="{\"name\":"+json(name)+",\"rows\":"+rows.size()+",\"sqlCount\":"+count+",\"statsRows\":"+table.getStats().numTuples+
            ",\"rowSha256\":"+json(HexFormat.of().formatHex(hash.digest()))+",\"indexes\":"+indexes+"}";
        db.getStorageManager().getBufferManager().unpinAllSessionPages();return result;
    }
    public static void main(String[] args) throws Exception {
        Path input=Path.of(args[0]),output=Path.of(args[1]),directory=output.resolve("datafiles");
        Files.createDirectories(directory);
        PrintStream progress=System.out;System.setOut(SINK);SessionState.get().setOutputStream(SINK);
        long t=System.nanoTime();NanoDBServer db=start(directory);
        for(CommandResult r:db.doCommands(Files.readString(input.resolve("make-tpch.sql")),false))if(r.failed())throw r.getFailure();
        int loadTransactions=0;
        for(String table:TABLES) {
            long rows=0;command(db,"BEGIN;");
            try {
                try(BufferedReader reader=Files.newBufferedReader(input.resolve(table+".sql"))) {
                    String sql;while((sql=reader.readLine())!=null)if(!sql.isBlank()){command(db,sql);rows++;}
                }
                command(db,"COMMIT;");loadTransactions++;
            }
            catch(Exception failure) {
                try {command(db,"ROLLBACK;");}catch(Exception rollback){failure.addSuppressed(rollback);}
                throw failure;
            }
            progress.println("Loaded "+table+" rows="+rows+" (one committed table transaction)");
        }
        double load=(System.nanoTime()-t)/1e9;t=System.nanoTime();
        command(db,"ANALYZE "+String.join(",",TABLES)+";");double analyze=(System.nanoTime()-t)/1e9;t=System.nanoTime();
        BufferManager firstPool=db.getStorageManager().getBufferManager();
        if(!db.shutdown())throw new AssertionError("Shutdown failed");double shutdown=(System.nanoTime()-t)/1e9;t=System.nanoTime();
        db=start(directory);double restart=(System.nanoTime()-t)/1e9;
        StringJoiner tables=new StringJoiner(",","[","]");
        for(String table:TABLES){tables.add(tableReport(db,table));progress.println("Verified "+table);}
        TreeMap<String,Long> groups=new TreeMap<>();
        for(edu.caltech.nanodb.expressions.TupleLiteral row:command(db,"SELECT c_nationkey,count(*) FROM customer GROUP BY c_nationkey;").getTuples())
            groups.put(row.getColumnValue(0).toString(),((Number)row.getColumnValue(1)).longValue());
        StringJoiner grouping=new StringJoiner(",","{","}");groups.forEach((key,value)->grouping.add(json(key)+":"+value));
        boolean transactions=db.getPropertyRegistry().getBooleanProperty("nanodb.enableTransactions");
        boolean indexes=db.getPropertyRegistry().getBooleanProperty("nanodb.enableIndexes");
        boolean constraints=db.getPropertyRegistry().getBooleanProperty("nanodb.enforceKeyConstraints");
        boolean flush=db.getPropertyRegistry().getBooleanProperty("nanodb.flushAfterCmd");
        int cache=db.getStorageManager().getBufferManager().getMaxCacheSize();
        int reserve=db.getStorageManager().getBufferManager().getInternalWritebackReserve();
        BufferManager secondPool=db.getStorageManager().getBufferManager();
        if(!db.shutdown())throw new AssertionError("Validation shutdown failed");
        long peak=Math.max(firstPool.getPeakBytesCached(),secondPool.getPeakBytesCached());
        if(peak>10485760)throw new AssertionError("Pool exceeded strict 10 MiB: "+peak);
        long bytes;try(java.util.stream.Stream<Path> files=Files.walk(directory)){bytes=files.filter(Files::isRegularFile).mapToLong(p->p.toFile().length()).sum();}
        String report=String.format(Locale.ROOT,"{\"scale\":0.01,\"bufferPoolBytes\":%d,\"features\":{\"transactions\":%s,\"indexes\":%s,\"keyConstraints\":%s,\"flushAfterCmd\":%s},\"seconds\":{\"load\":%.6f,\"analyze\":%.6f,\"shutdown\":%.6f,\"restart\":%.6f,\"timedTotal\":%.6f},\"datafilesBytes\":%d,\"tables\":%s,\"customerGroups\":%s}",cache,transactions,indexes,constraints,flush,load,analyze,shutdown,restart,load+analyze+shutdown+restart,bytes,tables,grouping);
        report=report.substring(0,report.length()-1)+",\"peakPoolBytes\":"+peak+",\"internalWritebackReserveBytes\":"+reserve+",\"loadTransactions\":"+loadTransactions+"}";
        Files.writeString(output.resolve("results.json"),report+"\n");progress.println(report);
    }
}
