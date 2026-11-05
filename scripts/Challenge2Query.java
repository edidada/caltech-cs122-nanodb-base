import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import edu.caltech.nanodb.commands.*;
import edu.caltech.nanodb.server.*;
import edu.caltech.nanodb.sqlparse.ParseUtil;
import edu.caltech.nanodb.plannodes.PlanNode;

/** One isolated query process. Checkpoints survive external timeout/termination. */
public final class Challenge2Query {
    private static String quote(String value) {
        return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"";
    }
    private static void checkpoint(Path output,String stage) throws Exception {
        Files.writeString(output.resolve("stage.txt"),stage,StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]),sql=Path.of(args[1]),output=Path.of(args[2]);
        Files.createDirectories(output);checkpoint(output,"startup");
        PrintStream sink=new PrintStream(OutputStream.nullOutputStream());
        System.setOut(sink);SessionState.get().setOutputStream(sink);
        Properties p=new Properties();p.setProperty("nanodb.baseDirectory",directory.toString());
        p.setProperty("nanodb.pagecache.size","10485760");
        p.setProperty("nanodb.pagecache.writebackReserve","262144");
        p.setProperty("nanodb.enableTransactions","true");p.setProperty("nanodb.enableIndexes","true");
        p.setProperty("nanodb.createIndexesOnKeys","true");p.setProperty("nanodb.enforceKeyConstraints","true");
        p.setProperty("nanodb.flushAfterCmd","false");
        NanoDBServer db=new NanoDBServer();String stage="startup";
        try {
            db.startup(p);stage="parse";checkpoint(output,stage);
            List<Command> commands=ParseUtil.parseCommands(Files.readString(sql),db.getFunctionDirectory());
            int number=0;List<String> rows=new ArrayList<>();
            for(Command command:commands) {
                number++;
                if(command instanceof SelectCommand) {
                    stage="plan";checkpoint(output,stage);
                    SelectCommand select=(SelectCommand)command;
                    select.getSelectClause().computeSchema(db.getStorageManager().getTableManager(),null);
                    PlanNode plan=db.getQueryPlanner().makePlan(select.getSelectClause(),null);
                    Files.writeString(output.resolve("plan-"+number+".txt"),PlanNode.printNodeTreeToString(plan,true));
                }
                stage="execute";checkpoint(output,stage);
                CommandResult result=db.doCommand(command,true);
                if(result.failed())throw result.getFailure();
                if(command instanceof SelectCommand) {
                    rows.clear();
                    for(edu.caltech.nanodb.expressions.TupleLiteral tuple:result.getTuples()) {
                        StringJoiner values=new StringJoiner(",","[","]");
                        for(int i=0;i<tuple.getColumnCount();i++) {
                            Object value=tuple.getColumnValue(i);
                            if(value==null)values.add("null");
                            else {
                                String canonical=value instanceof Number?
                                    new BigDecimal(value.toString()).stripTrailingZeros().toPlainString():value.toString();
                                values.add(quote(canonical));
                            }
                        }
                        rows.add(values.toString());
                    }
                }
            }
            Files.write(output.resolve("rows.jsonl"),rows,StandardCharsets.UTF_8);
            stage="shutdown";checkpoint(output,stage);
            if(!db.shutdown())throw new IllegalStateException("Shutdown failed");
            checkpoint(output,"success");
        }
        catch(Throwable failure) {
            try(PrintWriter writer=new PrintWriter(output.resolve("failure.txt").toFile(),"UTF-8")) {
                writer.println("phase="+stage);failure.printStackTrace(writer);
            }
            checkpoint(output,"failed:"+stage);
            // The dataset is an expendable copy. Avoid a failed shutdown
            // obscuring the actual parse/planning/execution failure.
            System.exit(2);
        }
    }
}
