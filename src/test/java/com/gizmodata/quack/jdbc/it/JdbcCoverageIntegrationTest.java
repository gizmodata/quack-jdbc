package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.sql.QuackArray;
import com.gizmodata.quack.jdbc.sql.QuackBlob;
import com.gizmodata.quack.jdbc.sql.QuackParameterMetaData;
import com.gizmodata.quack.jdbc.sql.QuackStruct;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the eight JDBC coverage gaps closed in v0.1.0:
 * Connection.setTypeMap (empty), createArrayOf, createStruct,
 * setCatalog/Schema (USE), isValid (SELECT 1), Statement batch,
 * PreparedStatement.getMetaData / getParameterMetaData,
 * ResultSet.getArray / getBlob.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class JdbcCoverageIntegrationTest {

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

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(server.jdbcUrl());
    }

    // ---- Connection ----

    @Test
    void setTypeMapAcceptsEmptyMap() throws Exception {
        try (Connection c = connect()) {
            assertDoesNotThrow(() -> c.setTypeMap(new HashMap<>()));
            assertDoesNotThrow(() -> c.setTypeMap(null));
            assertThrows(SQLException.class, () -> {
                HashMap<String, Class<?>> nonEmpty = new HashMap<>();
                nonEmpty.put("FOO", String.class);
                c.setTypeMap(nonEmpty);
            });
        }
    }

    @Test
    void createArrayOfReturnsUsableArray() throws Exception {
        try (Connection c = connect()) {
            Array array = c.createArrayOf("INTEGER", new Object[]{1, 2, 3});
            assertNotNull(array);
            assertTrue(array instanceof QuackArray);
            Object[] elements = (Object[]) array.getArray();
            assertArrayEquals(new Object[]{1, 2, 3}, elements);
        }
    }

    @Test
    void createStructReturnsUsableStruct() throws Exception {
        try (Connection c = connect()) {
            Struct s = c.createStruct("MY_STRUCT", new Object[]{"a", 42});
            assertNotNull(s);
            assertTrue(s instanceof QuackStruct);
            assertEquals("MY_STRUCT", s.getSQLTypeName());
            assertArrayEquals(new Object[]{"a", 42}, s.getAttributes());
        }
    }

    @Test
    void isValidActuallyProbes() throws Exception {
        try (Connection c = connect()) {
            assertTrue(c.isValid(2), "expected isValid to return true on a fresh connection");
        }
    }

    @Test
    void isValidReturnsFalseOnClosedConnection() throws Exception {
        Connection c = connect();
        c.close();
        assertFalse(c.isValid(2));
    }

    @Test
    void setSchemaSwitchesActiveSchema() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS jdbc_it_schema_a");
            s.execute("CREATE TABLE IF NOT EXISTS jdbc_it_schema_a.t (v INTEGER)");
            s.execute("INSERT INTO jdbc_it_schema_a.t VALUES (7)");
            c.setSchema("jdbc_it_schema_a");
            try (ResultSet rs = s.executeQuery("SELECT v FROM t")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            }
            s.execute("DROP TABLE jdbc_it_schema_a.t");
            s.execute("DROP SCHEMA jdbc_it_schema_a");
        }
    }

    // ---- Statement / PreparedStatement ----

    @Test
    void statementBatchExecutesEachItem() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_batch");
            s.execute("CREATE TABLE jdbc_it_batch (i INTEGER)");
            s.addBatch("INSERT INTO jdbc_it_batch VALUES (1)");
            s.addBatch("INSERT INTO jdbc_it_batch VALUES (2)");
            s.addBatch("INSERT INTO jdbc_it_batch VALUES (3)");
            int[] counts = s.executeBatch();
            assertEquals(3, counts.length);
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM jdbc_it_batch")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
            s.execute("DROP TABLE jdbc_it_batch");
        }
    }

    @Test
    void preparedStatementBatchSnapshotsParameters() throws Exception {
        try (Connection c = connect()) {
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS jdbc_it_pbatch");
                s.execute("CREATE TABLE jdbc_it_pbatch (k VARCHAR, v INTEGER)");
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO jdbc_it_pbatch VALUES (?, ?)")) {
                for (int i = 0; i < 5; i++) {
                    p.setString(1, "row" + i);
                    p.setInt(2, i);
                    p.addBatch();
                }
                int[] counts = p.executeBatch();
                assertEquals(5, counts.length);
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*), SUM(v) FROM jdbc_it_pbatch")) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
                assertEquals(0 + 1 + 2 + 3 + 4, rs.getInt(2));
                s.execute("DROP TABLE jdbc_it_pbatch");
            }
        }
    }

    @Test
    void preparedStatementGetMetaDataReturnsColumnTypes() throws Exception {
        try (Connection c = connect();
             PreparedStatement p = c.prepareStatement(
                     "SELECT ?::INTEGER AS x, ?::VARCHAR AS y, ?::DOUBLE AS z")) {
            ResultSetMetaData md = p.getMetaData();
            assertNotNull(md, "expected metadata for a SELECT prepared statement");
            assertEquals(3, md.getColumnCount());
            assertEquals("x", md.getColumnName(1));
            assertEquals("y", md.getColumnName(2));
            assertEquals("z", md.getColumnName(3));
            assertEquals(java.sql.Types.INTEGER, md.getColumnType(1));
            assertEquals(java.sql.Types.VARCHAR, md.getColumnType(2));
            assertEquals(java.sql.Types.DOUBLE, md.getColumnType(3));
        }
    }

    @Test
    void preparedStatementGetMetaDataReturnsNullForNonSelect() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_md_target");
            s.execute("CREATE TABLE jdbc_it_md_target (v INTEGER)");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO jdbc_it_md_target VALUES (?)")) {
                p.setInt(1, 1);
                assertNull(p.getMetaData());
            }
            s.execute("DROP TABLE jdbc_it_md_target");
        }
    }

    @Test
    void preparedStatementGetParameterMetaDataCountsMarkers() throws Exception {
        try (Connection c = connect();
             PreparedStatement p = c.prepareStatement(
                     "SELECT ?, ?, ? WHERE 'literal?marker' = ? AND \"col?name\" = ?")) {
            ParameterMetaData pmd = p.getParameterMetaData();
            assertNotNull(pmd);
            // 5 outside-of-quotes ?  + 1 inside string literal (skipped)
            //                       + 1 inside double-quoted identifier (skipped) = 5
            assertEquals(5, pmd.getParameterCount());
            assertEquals(ParameterMetaData.parameterModeIn, pmd.getParameterMode(1));
            assertTrue(pmd instanceof QuackParameterMetaData);
        }
    }

    @Test
    void preparedStatementCancelDoesNotThrow() throws Exception {
        try (Connection c = connect();
             PreparedStatement p = c.prepareStatement("SELECT 1")) {
            assertDoesNotThrow(p::cancel);
        }
    }

    // ---- ResultSet ----

    @Test
    void resultSetMetaDataReportsArrayElementType() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT [10, 20, 30]::INTEGER[] AS a")) {
                ResultSetMetaData md = rs.getMetaData();
                assertEquals(Types.ARRAY, md.getColumnType(1));
                assertEquals("INTEGER[]", md.getColumnTypeName(1));
            }
            try (ResultSet rs = s.executeQuery("SELECT list(x) AS a FROM (VALUES (1),(2),(3)) t(x)")) {
                assertEquals("INTEGER[]", rs.getMetaData().getColumnTypeName(1));
            }
            try (ResultSet rs = s.executeQuery("SELECT [1.5, 2.5]::DOUBLE[2] AS a")) {
                assertEquals("DOUBLE[2]", rs.getMetaData().getColumnTypeName(1));
            }
        }
    }

    @Test
    void resultSetGetArrayReturnsJdbcArray() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT [10, 20, 30]::INTEGER[] AS a")) {
            assertTrue(rs.next());
            Array a = rs.getArray("a");
            assertNotNull(a);
            Object[] elements = (Object[]) a.getArray();
            assertEquals(3, elements.length);
            assertEquals(10, ((Number) elements[0]).intValue());
            assertEquals(20, ((Number) elements[1]).intValue());
            assertEquals(30, ((Number) elements[2]).intValue());
            assertTrue(a.getBaseTypeName().contains("INT"),
                    "expected INTEGER-ish baseTypeName, got " + a.getBaseTypeName());
        }
    }

    @Test
    void resultSetGetArrayReturnsNullForNullValue() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT NULL::INTEGER[] AS a")) {
            assertTrue(rs.next());
            assertNull(rs.getArray("a"));
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void resultSetGetBlobReturnsJdbcBlob() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT '\\xDE\\xAD\\xBE\\xEF'::BLOB AS b")) {
            assertTrue(rs.next());
            Blob b = rs.getBlob("b");
            assertNotNull(b);
            assertTrue(b instanceof QuackBlob);
            assertEquals(4L, b.length());
            assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                    b.getBytes(1, 4));
        }
    }
}
