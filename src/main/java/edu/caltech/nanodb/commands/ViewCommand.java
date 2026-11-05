package edu.caltech.nanodb.commands;

import java.util.*;
import edu.caltech.nanodb.server.NanoDBServer;

/** Persistent read-only SQL view DDL; not transactional materialization. */
public class ViewCommand extends Command {
    private final String name,sql;
    private final List<String> columns;
    private final boolean ifExists;
    public ViewCommand(String name,String sql,List<String> columns,boolean ifExists) {
        super(Type.DDL);this.name=name;this.sql=sql;this.columns=new ArrayList<>(columns);this.ifExists=ifExists;
    }
    @Override public void execute(NanoDBServer server) {
        if(sql==null)server.getStorageManager().getTableManager().dropView(name,ifExists);
        else server.getStorageManager().getTableManager().createView(name,sql,columns);
    }
}
