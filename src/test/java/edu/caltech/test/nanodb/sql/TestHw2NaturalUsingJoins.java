package edu.caltech.test.nanodb.sql;

import org.testng.annotations.Test;
import edu.caltech.nanodb.server.CommandResult;
import java.util.Locale;

/** Run the unchanged official natural/USING smoke suite in the HW2 group. */
@Test(groups = {"hw2"})
public class TestHw2NaturalUsingJoins extends TestNaturalUsingJoins {
    /** The old smoke suite spells expected identifiers in uppercase, while
     * the pinned 2019 translator folds unquoted identifiers to lowercase.
     * Adapt only spelling; the original qualification/order checks remain. */
    @Override
    public void checkResultSchema(CommandResult result, String... names) {
        String[] normalized = new String[names.length];
        for (int i = 0; i < names.length; i++) normalized[i] = names[i].toLowerCase(Locale.ROOT);
        super.checkResultSchema(result, normalized);
    }
    @Override @Test(groups = {"hw2"})
    public void testNaturalUsingTablesNotEmpty() throws Throwable { super.testNaturalUsingTablesNotEmpty(); }
    @Override @Test(groups = {"hw2"})
    public void testNaturalUsingJoinTwoTables() throws Throwable { super.testNaturalUsingJoinTwoTables(); }
    @Override @Test(groups = {"hw2"})
    public void testNaturalJoinMultiTables() throws Throwable { super.testNaturalJoinMultiTables(); }
    @Override @Test(groups = {"hw2"})
    public void testUsingJoinMultiTables() throws Throwable { super.testUsingJoinMultiTables(); }
}
