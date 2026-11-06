package edu.caltech.nanodb.expressions;

import java.util.*;
import edu.caltech.nanodb.relations.Tuple;

/** Bounded, thread-confined results for one read-only SELECT execution only. */
public final class SubqueryExecutionCache {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    static final class Context {
        final Map<SubqueryOperator,List<TupleLiteral>> rows = new IdentityHashMap<>();
        final Set<SubqueryOperator> disabled = Collections.newSetFromMap(new IdentityHashMap<>());
        long bytes;
    }
    public static final class Scope implements AutoCloseable {
        private final Context previous;
        private boolean closed;
        private Scope(boolean enabled) {
            previous = CURRENT.get();
            if (enabled) CURRENT.set(new Context()); else CURRENT.remove();
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
    public static Scope open(boolean readOnlySelect) { return new Scope(readOnlySelect); }
    static Context current() { return CURRENT.get(); }
    static long valueBytes(Object value) {
        if (value == null) return 8;
        if (value instanceof String) return 40L + 2L * ((String) value).length();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long ||
            value instanceof Float || value instanceof Double || value instanceof Boolean ||
            value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger ||
            value instanceof java.time.LocalDate || value instanceof java.time.LocalTime ||
            value instanceof java.time.LocalDateTime || value instanceof java.time.Duration || value instanceof java.time.Period)
            return 64L + value.toString().length() * 2L;
        return -1; // Mutable or unknown values are not copied into the cache.
    }
    private SubqueryExecutionCache() { }
}
