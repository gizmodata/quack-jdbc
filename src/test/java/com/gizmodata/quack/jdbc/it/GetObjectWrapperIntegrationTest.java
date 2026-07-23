package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class GetObjectWrapperIntegrationTest {

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

    @Test
    void getObjectOnArrayColumnReturnsJdbcArray() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT [10, 20, 30]::INTEGER[] AS a")) {
            assertTrue(rs.next());
            Array array = assertInstanceOf(Array.class, rs.getObject("a"));
            Object[] elements = (Object[]) array.getArray();
            assertEquals(3, elements.length);
            assertEquals(10, ((Number) elements[0]).intValue());
        }
    }

    @Test
    void getObjectOnStructColumnReturnsJdbcStruct() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT {'x': 1, 'y': 'a'} AS a")) {
            assertTrue(rs.next());
            Struct struct = assertInstanceOf(Struct.class, rs.getObject("a"));
            Object[] attributes = struct.getAttributes();
            assertEquals(2, attributes.length);
            assertEquals(1, ((Number) attributes[0]).intValue());
            assertEquals("a", attributes[1]);
        }
    }

    @Test
    void getObjectOnMapColumnReturnsJavaMap() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAP {1: 'a', 2: 'b'} AS a")) {
            assertTrue(rs.next());
            Map<?, ?> map = assertInstanceOf(Map.class, rs.getObject("a"));
            assertEquals(2, map.size());
            assertEquals("a", map.get(1));
            assertEquals("b", map.get(2));
        }
    }

    @Test
    void getObjectWithExplicitTypeReturnsWrappers() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT [1, 2]::INTEGER[] AS a")) {
                assertTrue(rs.next());
                assertNotNull(rs.getObject("a", Array.class));
            }
            try (ResultSet rs = s.executeQuery("SELECT {'x': 1} AS a")) {
                assertTrue(rs.next());
                assertNotNull(rs.getObject("a", Struct.class));
            }
            try (ResultSet rs = s.executeQuery("SELECT MAP {1: 'a'} AS a")) {
                assertTrue(rs.next());
                assertInstanceOf(Map.class, rs.getObject("a", Map.class));
            }
        }
    }

    @Test
    @Tag("oracle")
    void getObjectWrapperCategoriesMatchOracle() throws Exception {
        Assumptions.assumeTrue(DuckDbOracle.available(),
                "duckdb_jdbc driver not on the test classpath (run with -Poracle)");
        String[] queries = {
                "SELECT [10, 20, 30]::INTEGER[] AS a",
                "SELECT [{'x': 1}] AS a",
                "SELECT {'x': 1, 'y': 'a'} AS a",
                "SELECT MAP {1: 'a', 2: 'b'} AS a",
        };
        try (Connection quack = connect();
             Connection oracle = DuckDbOracle.newConnection()) {
            for (String sql : queries) {
                assertEquals(category(oracle, sql), category(quack, sql),
                        "getObject wrapper category mismatch for: " + sql);
            }
        }
    }

    private static String category(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            Object o = rs.getObject(1);
            if (o instanceof Array) return "ARRAY";
            if (o instanceof Struct) return "STRUCT";
            if (o instanceof Map) return "MAP";
            return o == null ? "NULL" : o.getClass().getName();
        }
    }
}
