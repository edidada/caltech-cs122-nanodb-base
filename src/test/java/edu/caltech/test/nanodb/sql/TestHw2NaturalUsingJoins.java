package edu.caltech.test.nanodb.sql;

import org.testng.annotations.Test;
import edu.caltech.nanodb.server.CommandResult;
import edu.caltech.nanodb.expressions.TupleLiteral;
import java.util.Locale;

/** Historical natural/USING smoke tests with explicit fixture adapters.
 * The upstream source file is retained unchanged for comparison. */
@Test(groups = {"hw2"})
public class TestHw2NaturalUsingJoins extends TestNaturalUsingJoins {
    /** The old smoke suite spells expected identifiers in uppercase, while
     * the pinned 2019 translator folds unquoted identifiers to lowercase.
     * Adapt only spelling; the original qualification/order checks remain. */
    @Override
    @Test(enabled = false)
    public void checkResultSchema(CommandResult result, String... names) {
        String[] normalized = new String[names.length];
        for (int i = 0; i < names.length; i++) normalized[i] = names[i].toLowerCase(Locale.ROOT);
        super.checkResultSchema(result, normalized);
    }
    @Override @Test(groups = {"hw2"})
    public void testNaturalUsingTablesNotEmpty() throws Throwable { super.testNaturalUsingTablesNotEmpty(); }
    @Override @Test(groups = {"hw2"})
    public void testNaturalUsingJoinTwoTables() throws Throwable {
        CommandResult result;

        // NATURAL JOIN with only one common column:  A
        result = server.doCommand(
            "SELECT * FROM test_nuj_t1 t1 NATURAL JOIN test_nuj_t3 t3", true);
        TupleLiteral[] expected1 = {
            new TupleLiteral(2, 20, 200, 2000),
            new TupleLiteral(3, 30, 300, 3000),
            new TupleLiteral(5, 50, 500, 5000),
            new TupleLiteral(6, 60, 600, 6000),
            new TupleLiteral(8, 80, 800, 8000)
        };
        assert checkSizeResults(expected1, result);
        assert checkUnorderedResults(expected1, result);
        checkResultSchema(result, "A", "T1.B", "T3.C", "T3.D");

        // USING with only one common column:  A
        result = server.doCommand(
            "SELECT * FROM test_nuj_t1 t1 JOIN test_nuj_t3 t3 USING (a)", true);
        assert checkSizeResults(expected1, result);
        assert checkUnorderedResults(expected1, result);
        checkResultSchema(result, "A", "T1.B", "T3.C", "T3.D");

        // Legacy fixture corrected: common A,C precede non-shared B,D.
        // The original test source is retained unchanged for comparison.
        // NATURAL JOIN with two common columns:  A and C
        result = server.doCommand(
            "SELECT * FROM test_nuj_t2 t2 NATURAL JOIN test_nuj_t3 t3", true);
        TupleLiteral[] expected2 = {
            new TupleLiteral(3, 300, 40, 3000),
            new TupleLiteral(5, 500, 60, 5000),
            new TupleLiteral(6, 600, 50, 6000),
            new TupleLiteral(8, 800, 80, 8000),
            new TupleLiteral(9, 900, 100, 9000)
        };
        assert checkSizeResults(expected2, result);
        assert checkUnorderedResults(expected2, result);
        checkResultSchema(result, "A", "C", "T2.B", "T3.D");

        // USING with both common columns:  A, C
        result = server.doCommand(
            "SELECT * FROM test_nuj_t2 t2 JOIN test_nuj_t3 t3 USING (a, c)", true);
        assert checkSizeResults(expected2, result);
        assert checkUnorderedResults(expected2, result);
        checkResultSchema(result, "A", "C", "T2.B", "T3.D");

        // USING with only one of the common columns:  C
        result = server.doCommand(
            "SELECT * FROM test_nuj_t2 t2 JOIN test_nuj_t3 t3 USING (c)", true);
        TupleLiteral[] expected3 = {
            new TupleLiteral(300, 3, 40, 3, 3000),
            new TupleLiteral(500, 5, 60, 5, 5000),
            new TupleLiteral(600, 6, 50, 6, 6000),
            new TupleLiteral(800, 8, 80, 8, 8000),
            new TupleLiteral(900, 9, 100, 9, 9000)
        };
        assert checkSizeResults(expected3, result);
        assert checkUnorderedResults(expected3, result);
        checkResultSchema(result, "C", "T2.A", "T2.B", "T3.A", "T3.D");
    }
    @Override @Test(groups = {"hw2"})
    public void testNaturalJoinMultiTables() throws Throwable { super.testNaturalJoinMultiTables(); }
    @Override @Test(groups = {"hw2"})
    public void testUsingJoinMultiTables() throws Throwable { super.testUsingJoinMultiTables(); }
}
