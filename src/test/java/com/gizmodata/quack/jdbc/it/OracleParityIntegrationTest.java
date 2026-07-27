package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
@Tag("oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class OracleParityIntegrationTest {

    private QuackServerFixture server;
    private Connection quack;
    private Connection oracle;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeTrue(DuckDbOracle.available(),
                "duckdb_jdbc driver not on the test classpath (run with -Poracle)");
        server = QuackServerFixture.tryStart();
        Assumptions.assumeTrue(server != null, "duckdb binary not available for the Quack server fixture");
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
        };
        for (String sql : queries) {
            assertColumnTypeParity(sql);
        }
    }

    private void assertColumnTypeParity(String sql) throws SQLException {
        DuckDbOracle.ColumnType q = DuckDbOracle.columnType(quack, sql);
        DuckDbOracle.ColumnType o = DuckDbOracle.columnType(oracle, sql);
        assertEquals(o.typeName(), q.typeName(), "getColumnTypeName mismatch for: " + sql);
        assertEquals(o.type(), q.type(), "getColumnType mismatch for: " + sql);
    }
}
