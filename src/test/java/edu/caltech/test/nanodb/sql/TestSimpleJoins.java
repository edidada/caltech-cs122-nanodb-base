package edu.caltech.test.nanodb.sql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.expressions.TupleLiteral;

/** Required HW2 join matrix: empty sides, NULL keys, duplicate keys and bag
 * multiplicity, exercised through real SQL parsing/planning/execution. */
@Test(groups = {"hw2"})
public class TestSimpleJoins extends SqlTestCase {
    private List<String> rows(String sql) throws Exception {
        List<String> result = new ArrayList<>();
        for (TupleLiteral row : tryDoCommand(sql, true).getTuples()) {
            List<Object> columns = new ArrayList<>();
            for (int i = 0; i < row.getColumnCount(); i++) columns.add(row.getColumnValue(i));
            result.add(columns.toString());
        }
        Collections.sort(result);
        return result;
    }

    private void insert(String name, Integer[] values) throws Exception {
        tryDoCommand("CREATE TABLE " + name + " (k INTEGER, id INTEGER);");
        for (int i = 0; i < values.length; i++)
            tryDoCommand("INSERT INTO " + name + " VALUES (" + values[i] + "," + i + ");");
    }

    private List<String> expected(Integer[] left, Integer[] right, String kind) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < left.length; i++) {
            final Integer key = left[i];
            List<Integer> matches = new ArrayList<>();
            for (int j = 0; j < right.length; j++)
                if (key != null && key.equals(right[j])) matches.add(j);
            if (matches.isEmpty() && kind.equals("LEFT"))
                result.add(Arrays.asList(left[i], i, null, null).toString());
            for (int j : matches)
                result.add(Arrays.asList(left[i], i, right[j], j).toString());
        }
        if (kind.equals("RIGHT")) {
            for (int j = 0; j < right.length; j++) {
                Integer key = right[j];
                if (key == null || Arrays.stream(left).noneMatch(key::equals))
                    result.add(Arrays.asList(null, null, right[j], j).toString());
            }
        }
        Collections.sort(result);
        return result;
    }

    public void testInnerLeftRightEmptyNullAndManyToMany() throws Exception {
        Integer[][] cases = {{}, {null}, {1}, {1, 1, 2}, {null, 1, 2, 2, 3}};
        int number = 0;
        for (Integer[] left : cases) for (Integer[] right : cases) {
            String l = "jl" + number, r = "jr" + number++;
            insert(l, left); insert(r, right);
            for (String kind : new String[]{"INNER", "LEFT", "RIGHT"}) {
                String query = "SELECT * FROM " + l + " l " + kind + " JOIN " + r +
                    " r ON l.k=r.k";
                Assert.assertEquals(rows(query), expected(left, right, kind), query);
            }
            String comma = "SELECT * FROM " + l + " l, " + r + " r WHERE l.k=r.k";
            Assert.assertEquals(rows(comma), expected(left, right, "INNER"), comma);
            String cross = "SELECT * FROM " + l + " l CROSS JOIN " + r + " r";
            Assert.assertEquals(rows(cross).size(), left.length * right.length, cross);
        }
    }

    public void testDerivedTablesAliasesThreeWayAndWhereAfterOuterJoin() throws Exception {
        insert("ja", new Integer[]{1, 1, 2, null});
        insert("jb", new Integer[]{1, 3});
        insert("jc", new Integer[]{1, 1});
        Assert.assertEquals(rows("SELECT x.k, x.id, b.id FROM " +
            "(SELECT k, id FROM ja WHERE k=1) x JOIN jb b ON x.k=b.k"),
            Arrays.asList("[1, 0, 0]", "[1, 1, 0]"));
        Assert.assertEquals(rows("SELECT a.k FROM ja a JOIN jb b ON a.k=b.k " +
            "JOIN jc c ON b.k=c.k"), Arrays.asList("[1]", "[1]", "[1]", "[1]"));
        Assert.assertEquals(rows("SELECT a.k FROM ja a LEFT JOIN jb b ON a.k=b.k " +
            "WHERE b.k IS NULL"), Arrays.asList("[2]", "[null]"));
    }
}
