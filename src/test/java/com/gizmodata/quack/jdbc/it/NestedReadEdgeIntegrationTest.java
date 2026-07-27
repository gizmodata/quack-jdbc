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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class NestedReadEdgeIntegrationTest {

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
    void emptyListDecodesToEmptyArray() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT []::INTEGER[] AS a")) {
            assertTrue(rs.next());
            Array array = rs.getArray("a");
            assertNotNull(array);
            assertEquals(0, ((Object[]) array.getArray()).length);
        }
    }

    @Test
    void listWithNullElementPreservesNull() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT [1, NULL, 3]::INTEGER[] AS a")) {
            assertTrue(rs.next());
            Object[] elements = (Object[]) rs.getArray("a").getArray();
            assertEquals(3, elements.length);
            assertEquals(1, ((Number) elements[0]).intValue());
            assertNull(elements[1]);
            assertEquals(3, ((Number) elements[2]).intValue());
        }
    }

    @Test
    void nestedListWithEmptyInnerDecodes() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT [[], [1, 2]]::INTEGER[][] AS a")) {
            assertTrue(rs.next());
            Object[] outer = (Object[]) rs.getArray("a").getArray();
            assertEquals(2, outer.length);
            assertEquals(0, ((java.util.List<?>) outer[0]).size());
            assertEquals(2, ((java.util.List<?>) outer[1]).size());
        }
    }

    @Test
    void multiRowChunkWithNullAndNonNullLists() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT * FROM (VALUES (1, [10, 20]), (2, NULL), (3, [])) t(id, arr) ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals(2, ((Object[]) rs.getArray("arr").getArray()).length);
            assertTrue(rs.next());
            assertNull(rs.getArray("arr"));
            assertTrue(rs.wasNull());
            assertTrue(rs.next());
            assertEquals(0, ((Object[]) rs.getArray("arr").getArray()).length);
        }
    }
}
