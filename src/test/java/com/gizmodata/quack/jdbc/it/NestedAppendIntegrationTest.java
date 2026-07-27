package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class NestedAppendIntegrationTest {

    private QuackServerFixture server;

    @BeforeAll
    void startServer() throws Exception {
        server = QuackServerFixture.tryStart();
        assertNotNull(server);
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.close();
    }

    private QuackConnection connect() throws SQLException {
        return (QuackConnection) DriverManager.getConnection(server.jdbcUrl());
    }

    private static LogicalType scalar(LogicalTypeId id) {
        return LogicalType.of(id);
    }

    private static LogicalType list(LogicalType child) {
        return LogicalType.of(LogicalTypeId.LIST, new ExtraTypeInfo.ListInfo(child, Optional.empty()));
    }

    private static LogicalType array(LogicalType child, int size) {
        return LogicalType.of(LogicalTypeId.ARRAY, new ExtraTypeInfo.ArrayInfo(child, size, Optional.empty()));
    }

    private static LogicalType struct(ChildType... fields) {
        return LogicalType.of(LogicalTypeId.STRUCT, new ExtraTypeInfo.StructInfo(List.of(fields), Optional.empty()));
    }

    private static LogicalType map(LogicalType key, LogicalType value) {
        LogicalType entry = struct(new ChildType("key", key), new ChildType("value", value));
        return LogicalType.of(LogicalTypeId.MAP, new ExtraTypeInfo.ListInfo(entry, Optional.empty()));
    }

    private static Map<String, Object> structValue(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void appendListColumn() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_nested_list");
            s.execute("CREATE TABLE jdbc_it_nested_list (id INTEGER, arr INTEGER[])");
            LogicalType intType = scalar(LogicalTypeId.INTEGER);
            LogicalType listType = list(intType);
            DataChunk chunk = new DataChunk(2,
                    List.of(intType, listType),
                    List.of(
                            new DecodedVector.IntVec(intType, new int[]{1, 2}, null),
                            new DecodedVector.ObjectVec(listType, new Object[]{
                                    List.of(10, 20, 30), List.of(40)})));
            c.session().appendChunk("main", "jdbc_it_nested_list", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT arr[1] AS a1, arr[2] AS a2, len(arr) AS n FROM jdbc_it_nested_list ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt("a1"));
                assertEquals(20, rs.getInt("a2"));
                assertEquals(3, rs.getInt("n"));
                assertTrue(rs.next());
                assertEquals(40, rs.getInt("a1"));
                assertEquals(1, rs.getInt("n"));
            }
            s.execute("DROP TABLE jdbc_it_nested_list");
        }
    }

    @Test
    void appendFixedSizeArrayColumn() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_nested_arr");
            s.execute("CREATE TABLE jdbc_it_nested_arr (arr INTEGER[3])");
            LogicalType intType = scalar(LogicalTypeId.INTEGER);
            LogicalType arrType = array(intType, 3);
            DataChunk chunk = new DataChunk(2,
                    List.of(arrType),
                    List.of(new DecodedVector.ObjectVec(arrType, new Object[]{
                            List.of(1, 2, 3), List.of(4, 5, 6)})));
            c.session().appendChunk("main", "jdbc_it_nested_arr", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT arr[1] AS a1, arr[3] AS a3 FROM jdbc_it_nested_arr ORDER BY arr[1]")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("a1"));
                assertEquals(3, rs.getInt("a3"));
                assertTrue(rs.next());
                assertEquals(4, rs.getInt("a1"));
                assertEquals(6, rs.getInt("a3"));
            }
            s.execute("DROP TABLE jdbc_it_nested_arr");
        }
    }

    @Test
    void appendStructColumn() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_nested_struct");
            s.execute("CREATE TABLE jdbc_it_nested_struct (s STRUCT(x INTEGER, y VARCHAR))");
            LogicalType structType = struct(
                    new ChildType("x", scalar(LogicalTypeId.INTEGER)),
                    new ChildType("y", scalar(LogicalTypeId.VARCHAR)));
            DataChunk chunk = new DataChunk(2,
                    List.of(structType),
                    List.of(new DecodedVector.ObjectVec(structType, new Object[]{
                            structValue("x", 1, "y", "alpha"),
                            structValue("x", 2, "y", "beta")})));
            c.session().appendChunk("main", "jdbc_it_nested_struct", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT s.x AS x, s.y AS y FROM jdbc_it_nested_struct ORDER BY s.x")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("x"));
                assertEquals("alpha", rs.getString("y"));
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("x"));
                assertEquals("beta", rs.getString("y"));
            }
            s.execute("DROP TABLE jdbc_it_nested_struct");
        }
    }

    @Test
    void appendMapColumn() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_nested_map");
            s.execute("CREATE TABLE jdbc_it_nested_map (m MAP(INTEGER, VARCHAR))");
            LogicalType mapType = map(scalar(LogicalTypeId.INTEGER), scalar(LogicalTypeId.VARCHAR));
            Map<Object, Object> row0 = new LinkedHashMap<>();
            row0.put(1, "a");
            row0.put(2, "b");
            DataChunk chunk = new DataChunk(1,
                    List.of(mapType),
                    List.of(new DecodedVector.ObjectVec(mapType, new Object[]{row0})));
            c.session().appendChunk("main", "jdbc_it_nested_map", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT m[1] AS v1, m[2] AS v2, cardinality(m) AS n FROM jdbc_it_nested_map")) {
                assertTrue(rs.next());
                assertEquals("a", rs.getString("v1"));
                assertEquals("b", rs.getString("v2"));
                assertEquals(2, rs.getInt("n"));
            }
            s.execute("DROP TABLE jdbc_it_nested_map");
        }
    }

    @Test
    void appendNestedListOfListColumn() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_nested_ll");
            s.execute("CREATE TABLE jdbc_it_nested_ll (arr INTEGER[][])");
            LogicalType innerList = list(scalar(LogicalTypeId.INTEGER));
            LogicalType outerList = list(innerList);
            DataChunk chunk = new DataChunk(1,
                    List.of(outerList),
                    List.of(new DecodedVector.ObjectVec(outerList, new Object[]{
                            List.of(List.of(1, 2), List.of(3, 4, 5))})));
            c.session().appendChunk("main", "jdbc_it_nested_ll", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT arr[1][2] AS a, arr[2][3] AS b, len(arr) AS n FROM jdbc_it_nested_ll")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("a"));
                assertEquals(5, rs.getInt("b"));
                assertEquals(2, rs.getInt("n"));
            }
            s.execute("DROP TABLE jdbc_it_nested_ll");
        }
    }

    @Test
    void appendListOfStructColumn() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_nested_los");
            s.execute("CREATE TABLE jdbc_it_nested_los (arr STRUCT(x INTEGER)[])");
            LogicalType structType = struct(new ChildType("x", scalar(LogicalTypeId.INTEGER)));
            LogicalType listType = list(structType);
            DataChunk chunk = new DataChunk(1,
                    List.of(listType),
                    List.of(new DecodedVector.ObjectVec(listType, new Object[]{
                            List.of(structValue("x", 7), structValue("x", 8))})));
            c.session().appendChunk("main", "jdbc_it_nested_los", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT arr[1].x AS x1, arr[2].x AS x2, len(arr) AS n FROM jdbc_it_nested_los")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt("x1"));
                assertEquals(8, rs.getInt("x2"));
                assertEquals(2, rs.getInt("n"));
            }
            s.execute("DROP TABLE jdbc_it_nested_los");
        }
    }
}
