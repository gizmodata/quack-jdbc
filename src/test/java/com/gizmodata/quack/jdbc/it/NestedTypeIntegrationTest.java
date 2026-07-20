package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class NestedTypeIntegrationTest {

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
    void structColumnDecodesToMap() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT {'x': 1, 'y': 'a'} AS a")) {
            assertTrue(rs.next());
            assertEquals("STRUCT(x INTEGER, y VARCHAR)", rs.getMetaData().getColumnTypeName(1));
            Object value = rs.getObject("a");
            Map<?, ?> struct = assertInstanceOf(Map.class, value);
            assertEquals(1, ((Number) struct.get("x")).intValue());
            assertEquals("a", struct.get("y"));
        }
    }

    @Test
    void mapColumnDecodesToListOfKeyValueEntries() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAP {1: 'a', 2: 'b'} AS a")) {
            assertTrue(rs.next());
            assertEquals("MAP(INTEGER, VARCHAR)", rs.getMetaData().getColumnTypeName(1));
            Object value = rs.getObject("a");
            List<?> entries = assertInstanceOf(List.class, value);
            assertEquals(2, entries.size());
            Map<?, ?> first = assertInstanceOf(Map.class, entries.get(0));
            assertEquals(1, ((Number) first.get("key")).intValue());
            assertEquals("a", first.get("value"));
        }
    }

    @Test
    void listOfStructDecodesThroughGetArray() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT [{'x': 1}, {'x': 2}] AS a")) {
            assertTrue(rs.next());
            assertEquals("STRUCT(x INTEGER)[]", rs.getMetaData().getColumnTypeName(1));
            Array array = rs.getArray("a");
            assertNotNull(array);
            Object[] elements = (Object[]) array.getArray();
            assertEquals(2, elements.length);
            Map<?, ?> first = assertInstanceOf(Map.class, elements[0]);
            assertEquals(1, ((Number) first.get("x")).intValue());
        }
    }

    @Test
    void structOfListAndStructDecodesRecursively() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT {'nums': [1, 2, 3], 'inner': {'k': 'v'}} AS a")) {
            assertTrue(rs.next());
            assertEquals("STRUCT(nums INTEGER[], inner STRUCT(k VARCHAR))",
                    rs.getMetaData().getColumnTypeName(1));
            Map<?, ?> struct = assertInstanceOf(Map.class, rs.getObject("a"));
            List<?> nums = assertInstanceOf(List.class, struct.get("nums"));
            assertEquals(3, nums.size());
            Map<?, ?> inner = assertInstanceOf(Map.class, struct.get("inner"));
            assertEquals("v", inner.get("k"));
        }
    }
}
