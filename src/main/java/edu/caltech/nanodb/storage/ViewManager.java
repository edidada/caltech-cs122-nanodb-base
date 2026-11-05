package edu.caltech.nanodb.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import edu.caltech.nanodb.commands.*;
import edu.caltech.nanodb.queryast.*;
import edu.caltech.nanodb.expressions.*;
import edu.caltech.nanodb.relations.*;

/** SQL definitions stored separately from heap files; reparsed on every reference. */
final class ViewManager {
    private final StorageManager storage;
    private static final ThreadLocal<Set<String>> expanding=ThreadLocal.withInitial(HashSet::new);
    ViewManager(StorageManager storage) {this.storage=storage;}
    private Path path(String name) {
        if(!name.matches("[a-zA-Z_][a-zA-Z_0-9]*"))throw new IllegalArgumentException("Invalid view identifier");
        return storage.getBaseDir().toPath().resolve(name+".view");
    }
    boolean exists(String name) {return Files.isRegularFile(path(name));}
    private SelectClause parse(String sql,List<String> columns) {
        Command command=storage.getServer().parseCommand(sql+";");
        if(!(command instanceof SelectCommand))throw new ExecutionException("View definition must be SELECT");
        SelectClause select=((SelectCommand)command).getSelectClause();
        Schema schema=select.computeSchema(storage.getTableManager(),null);
        if(!columns.isEmpty()) {
            if(columns.size()!=schema.numColumns()||new HashSet<>(columns).size()!=columns.size())
                throw new ExecutionException("View column names must be unique and match SELECT width");
            // Wrap the expanded output, including wildcard definitions. Every
            // reference reparses, so no mutable planner AST is shared.
            SelectClause wrapper=new SelectClause();
            wrapper.setFromClause(new FromClause(select,"__view_source"));
            Set<String> names=new HashSet<>();
            for(int i=0;i<schema.numColumns();i++) {
                String source=schema.getColumnInfo(i).getName();
                if(!names.add(source))throw new ExecutionException("Ambiguous view SELECT column: "+source);
                wrapper.addSelectValue(new SelectValue(new ColumnValue(new ColumnName("__view_source",source)),columns.get(i)));
            }
            wrapper.computeSchema(storage.getTableManager(),null);return wrapper;
        }
        return select;
    }
    SelectClause resolve(String name) {
        if(!exists(name))return null;
        Set<String> stack=expanding.get();
        if(!stack.add(name))throw new ExecutionException("Cyclic view reference: "+name);
        try {
            Properties definition=new Properties();
            try(InputStream input=Files.newInputStream(path(name))){definition.load(input);}
            String sql=definition.getProperty("sql");
            if(sql==null)throw new ExecutionException("View definition missing SQL: "+name);
            String columns=definition.getProperty("columns","");
            return parse(sql,columns.isEmpty()?Collections.emptyList():Arrays.asList(columns.split(",")));
        }
        catch(IOException error){throw new ExecutionException("Cannot read view "+name,error);}
        finally{stack.remove(name);if(stack.isEmpty())expanding.remove();}
    }
    void create(String name,String sql,List<String> columns) {
        if(exists(name)||storage.getTableManager().tableExists(name))throw new ExecutionException("Relation already exists: "+name);
        parse(sql,columns); // Reject invalid references before creating a file.
        Properties definition=new Properties();definition.setProperty("sql",sql);definition.setProperty("columns",String.join(",",columns));
        boolean created=false;
        try {
            OutputStream opened=Files.newOutputStream(path(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            created=true;
            try(OutputStream output=opened){definition.store(output,"NanoDB read-only SQL view");}
        }
        catch(IOException error) {
            // Only remove the file if this invocation created it. Never erase
            // a pre-existing definition when CREATE_NEW rejects a collision.
            if(created)try{Files.deleteIfExists(path(name));}catch(IOException cleanup){error.addSuppressed(cleanup);}
            throw new ExecutionException("Cannot create view "+name,error);
        }
    }
    void drop(String name,boolean ifExists) {
        try{if(!Files.deleteIfExists(path(name))&&!ifExists)throw new ExecutionException("View does not exist: "+name);}
        catch(IOException error){throw new ExecutionException("Cannot drop view "+name,error);}
    }
}
