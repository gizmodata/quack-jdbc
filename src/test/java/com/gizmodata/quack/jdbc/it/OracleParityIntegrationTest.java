package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
@Tag("oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OracleParityIntegrationTest {

    private QuackServerFixture server;
    private Connection quack;
    private Connection oracle;

    @BeforeAll
    void setUp() throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(DuckDbOracle.available(),
                "duckdb_jdbc driver not on the test classpath (run with -Poracle)");
        server = QuackServerFixture.tryStart();
        quack = DriverManager.getConnection(server.jdbcUrl());
        oracle = DuckDbOracle.newConnection();
        for (Connection c : new Connection[]{quack, oracle}) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TYPE mood AS ENUM ('x', 'y', 'z')");
            }
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        if (quack != null) quack.close();
        if (oracle != null) oracle.close();
        if (server != null) server.close();
    }

    @Test
    @DisplayName("Column types match duckdb-jdbc exactly across scalars, arrays, and STRUCT/MAP/ENUM")
    void columnTypesMatchOracle() throws Exception {
        String[] queries = {
                "SELECT 42 AS a",
                "SELECT 1.23::DECIMAL(5,2) AS a",
                "SELECT '12345678-1234-1234-1234-123456789012'::UUID AS a",
                "SELECT [10, 20, 30]::INTEGER[] AS a",
                "SELECT [[1, 2], [3]]::INTEGER[][] AS a",
                "SELECT [1.23, 4.56]::DECIMAL(5,2)[] AS a",
                "SELECT [TIMESTAMP '2020-01-01 00:00:00'] AS a",
                "SELECT ['a', 'b']::VARCHAR[] AS a",
                "SELECT ['12345678-1234-1234-1234-123456789012'::UUID] AS a",
                "SELECT [1.5, 2.5]::DOUBLE[2] AS a",
                "SELECT [[1, 2], [3, 4]]::INTEGER[2][] AS a",
                "SELECT {'x': 1, 'y': 'a'} AS a",
                "SELECT [{'x': 1, 'y': 'a'}] AS a",
                "SELECT [{'x': 1}] AS a",
                "SELECT {'a': 1.23::DECIMAL(5,2), 'b': [1, 2], 'c': {'d': 1}} AS a",
                "SELECT MAP {1: 'a', 2: 'b'} AS a",
                "SELECT [MAP {1: 'a'}] AS a",
                "SELECT MAP {'k': [1, 2]} AS a",
                "SELECT 'x'::mood AS a",
                "SELECT ['x']::mood[] AS a",
                "SELECT {'m': 'x'::mood} AS a",
                // DuckDB 2.0 types
                "SELECT '2024-01-02 03:04:05.123456789+00'::TIMESTAMPTZ_NS AS a",
                "SELECT '2024-01-02 03:04:05.123456789'::TIMESTAMP_NS AS a",
                "SELECT TIMESTAMPTZ '2024-01-02 03:04:05+00' AS a",
                "SELECT '12:34:56.123456789'::TIME_NS AS a",
                "SELECT 'POINT(1 2)'::GEOMETRY AS a",
                "SELECT 42::BIGNUM AS a",
                "SELECT 'x'::BLOB AS a",
                "SELECT 123::HUGEINT AS a",
                "SELECT INTERVAL 1 DAY AS a",
                "SELECT 1::VARIANT AS a",
                "SELECT (1, 'a') AS a",
                "SELECT TIMETZ '01:02:03+05' AS a",
                "SELECT TIME '01:02:03' AS a",
                "SELECT '0101'::BIT AS a",
                "SELECT 1::UBIGINT AS a",
                "SELECT 1::UINTEGER AS a",
                "SELECT 1::UTINYINT AS a",
                "SELECT 1::UHUGEINT AS a",
                "SELECT 1.5::FLOAT AS a",
                "SELECT DATE '2024-01-01' AS a",
                "SELECT TIMESTAMP '2024-01-01' AS a",
                "SELECT '2024-01-01'::TIMESTAMP_S AS a",
                "SELECT '2024-01-01'::TIMESTAMP_MS AS a",
                "SELECT true AS a",
        };
        java.util.List<String> mismatches = new java.util.ArrayList<>();
        for (String sql : queries) {
            DuckDbOracle.ColumnType q = DuckDbOracle.columnType(quack, sql);
            DuckDbOracle.ColumnType o = DuckDbOracle.columnType(oracle, sql);
            if (!o.typeName().equals(q.typeName()) || o.type() != q.type()) {
                mismatches.add(sql + ": duckdb-jdbc " + o.typeName() + "/" + o.type()
                        + ", quack-jdbc " + q.typeName() + "/" + q.type());
            }
        }
        assertEquals(java.util.List.of(), mismatches);
    }

    /**
     * DBeaver and DataGrip build their navigator from these calls; the
     * result sets must match DuckDB's own JDBC driver row for row.
     */
    @Test
    @DisplayName("DatabaseMetaData matches duckdb-jdbc for tables, columns, keys and schemas")
    void databaseMetaDataMatchesOracle() throws Exception {
        String[] ddl = {
                "CREATE SCHEMA IF NOT EXISTS parity",
                "CREATE TABLE parity.parent (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL, created TIMESTAMPTZ_NS)",
                "CREATE TABLE parity.child (id BIGINT PRIMARY KEY, parent_id INTEGER REFERENCES parity.parent(id), "
                        + "amount DECIMAL(12,3) DEFAULT 0, tags VARCHAR[], attrs STRUCT(a INTEGER, b VARCHAR), g GEOMETRY)",
                "CREATE UNIQUE INDEX child_amount ON parity.child(amount)",
                "CREATE VIEW parity.v AS SELECT id, name FROM parity.parent",
        };
        for (Connection c : new Connection[]{quack, oracle}) {
            try (Statement s = c.createStatement()) {
                for (String d : ddl) s.execute(d);
            }
        }
        java.sql.DatabaseMetaData q = quack.getMetaData();
        java.sql.DatabaseMetaData o = oracle.getMetaData();
        assertSameRows("getSchemas", o.getSchemas(null, "parity"), q.getSchemas(null, "parity"));
        assertSameRows("getTables", o.getTables(null, "parity", "%", null), q.getTables(null, "parity", "%", null));
        assertSameRows("getColumns", o.getColumns(null, "parity", "%", "%"), q.getColumns(null, "parity", "%", "%"));
        assertSameRows("getPrimaryKeys", o.getPrimaryKeys(null, "parity", "child"), q.getPrimaryKeys(null, "parity", "child"));
        assertSameRows("getImportedKeys", o.getImportedKeys(null, "parity", "child"), q.getImportedKeys(null, "parity", "child"));
        assertSameRows("getTableTypes", o.getTableTypes(), q.getTableTypes());
    }

    /** Compare by column label (catalog names differ: each side has its own in-memory database). */
    private static void assertSameRows(String what, java.sql.ResultSet expected, java.sql.ResultSet actual)
            throws SQLException {
        try (expected; actual) {
            java.util.List<String> eCols = labels(expected);
            java.util.List<String> aCols = labels(actual);
            assertEquals(eCols, aCols, what + " column labels");
            java.util.List<java.util.List<Object>> eRows = rows(expected, eCols);
            java.util.List<java.util.List<Object>> aRows = rows(actual, aCols);
            org.junit.jupiter.api.Assertions.assertFalse(eRows.isEmpty(), what + " returned no rows to compare");
            assertEquals(eRows.size(), aRows.size(), what + " row count: " + aRows + " vs " + eRows);
            for (int i = 0; i < eRows.size(); i++) {
                assertEquals(eRows.get(i), aRows.get(i), what + " row " + i + " (" + eCols + ")");
            }
        }
    }

    private static java.util.List<String> labels(java.sql.ResultSet rs) throws SQLException {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnLabel(i));
        return out;
    }

    private static java.util.List<java.util.List<Object>> rows(java.sql.ResultSet rs, java.util.List<String> cols)
            throws SQLException {
        java.util.List<java.util.List<Object>> out = new java.util.ArrayList<>();
        while (rs.next()) {
            java.util.List<Object> row = new java.util.ArrayList<>();
            for (int i = 1; i <= cols.size(); i++) {
                String col = cols.get(i - 1);
                Object v = rs.getObject(i);
                // the catalog is each side's own database name
                if (col.endsWith("_CAT") || col.equals("TABLE_CATALOG")) v = v == null ? null : "<catalog>";
                row.add(v == null ? null : v.toString());
            }
            out.add(row);
        }
        return out;
    }

    private void assertColumnTypeParity(String sql) throws SQLException {
        DuckDbOracle.ColumnType q = DuckDbOracle.columnType(quack, sql);
        DuckDbOracle.ColumnType o = DuckDbOracle.columnType(oracle, sql);
        assertEquals(o.typeName(), q.typeName(), "getColumnTypeName mismatch for: " + sql);
        assertEquals(o.type(), q.type(), "getColumnType mismatch for: " + sql);
    }
}
