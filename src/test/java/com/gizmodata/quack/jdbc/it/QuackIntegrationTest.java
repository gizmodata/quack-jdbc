package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests against a live DuckDB Quack server. Skipped when the
 * {@code duckdb} CLI is not on PATH so unit-only test runs still work.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class QuackIntegrationTest {

    private QuackServerFixture server;

    @BeforeAll
    void startServer() throws Exception {
        server = QuackServerFixture.tryStart();
        assertNotNull(server, "QuackServerFixture failed to start");
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.close();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(server.jdbcUrl());
    }

    @Test
    void connectsAndRunsSelect() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 42 AS x, 'hello' AS y")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt("x"));
            assertEquals("hello", rs.getString("y"));
            assertFalse(rs.next());
        }
    }

    @Test
    void createInsertSelectUpdateDeleteDrop() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_t1");
            s.execute("CREATE TABLE jdbc_it_t1 (id INTEGER PRIMARY KEY, name VARCHAR, score DOUBLE)");
            s.executeUpdate("INSERT INTO jdbc_it_t1 VALUES (1, 'alice', 9.5), (2, 'bob', 8.25), (3, 'carol', 7.0)");

            try (ResultSet rs = s.executeQuery("SELECT id, name, score FROM jdbc_it_t1 ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("alice", rs.getString(2));
                assertEquals(9.5, rs.getDouble(3), 1e-9);
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals("bob", rs.getString(2));
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
                assertFalse(rs.next());
            }

            s.executeUpdate("UPDATE jdbc_it_t1 SET score = 10.0 WHERE id = 1");
            try (ResultSet rs = s.executeQuery("SELECT score FROM jdbc_it_t1 WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(10.0, rs.getDouble(1), 1e-9);
            }

            s.executeUpdate("DELETE FROM jdbc_it_t1 WHERE id = 2");
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM jdbc_it_t1")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }

            s.execute("DROP TABLE jdbc_it_t1");
        }
    }

    @Test
    void preparedStatementBinds() throws Exception {
        try (Connection c = connect()) {
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS jdbc_it_p");
                s.execute("CREATE TABLE jdbc_it_p (k VARCHAR, v INTEGER)");
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO jdbc_it_p VALUES (?, ?)")) {
                p.setString(1, "alpha");
                p.setInt(2, 1);
                p.executeUpdate();
                p.setString(1, "beta'q");
                p.setInt(2, 2);
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("SELECT v FROM jdbc_it_p WHERE k = ?")) {
                p.setString(1, "beta'q");
                try (ResultSet rs = p.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                }
            }
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE jdbc_it_p");
            }
        }
    }

    @Test
    void multiChunkFetch() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT i AS x, i * 2 AS y FROM range(0, 50000) t(i)")) {
            int count = 0;
            long lastX = -1;
            while (rs.next()) {
                long x = rs.getLong("x");
                long y = rs.getLong("y");
                assertEquals(x * 2, y);
                assertEquals(lastX + 1, x);
                lastX = x;
                count++;
            }
            assertEquals(50000, count);
        }
    }

    @Test
    void scalarTypeRoundTrips() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT TRUE AS b, 127::TINYINT AS i8, 32000::SMALLINT AS i16, " +
                     "1000000::INTEGER AS i32, 9999999999::BIGINT AS i64, " +
                     "1.5::FLOAT AS f, 2.5::DOUBLE AS d, " +
                     "123.4567::DECIMAL(10,4) AS dec, " +
                     "DATE '2026-05-13' AS dt, " +
                     "TIMESTAMP '2026-05-13 14:30:00' AS ts, " +
                     "'hello'::VARCHAR AS v")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("b"));
            assertEquals(127, rs.getByte("i8"));
            assertEquals(32000, rs.getShort("i16"));
            assertEquals(1_000_000, rs.getInt("i32"));
            assertEquals(9_999_999_999L, rs.getLong("i64"));
            assertEquals(1.5f, rs.getFloat("f"), 1e-6);
            assertEquals(2.5, rs.getDouble("d"), 1e-9);
            assertEquals(new BigDecimal("123.4567"), rs.getBigDecimal("dec"));
            assertEquals(LocalDate.of(2026, 5, 13), rs.getObject("dt", LocalDate.class));
            assertNotNull(rs.getTimestamp("ts"));
            assertEquals("hello", rs.getString("v"));
        }
    }

    @Test
    void nullsAreReportedViaWasNull() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT NULL::INTEGER AS x, 'present' AS y")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("x"));
            assertTrue(rs.wasNull());
            assertEquals("present", rs.getString("y"));
            assertFalse(rs.wasNull());
        }
    }

    @Test
    void serverErrorPropagatesAsSqlException() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.executeQuery("SELECT * FROM jdbc_it_does_not_exist"));
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    void badTokenFailsToConnect() {
        String badUrl = "jdbc:quack://localhost:" + server.port() + "?token=wrong-token";
        assertThrows(SQLException.class, () -> DriverManager.getConnection(badUrl).close());
    }

    @Test
    void databaseMetaDataLists() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_meta");
            s.execute("CREATE TABLE jdbc_it_meta (id INTEGER PRIMARY KEY, label VARCHAR NOT NULL)");

            DatabaseMetaData md = c.getMetaData();

            // catalogs / schemas
            try (ResultSet rs = md.getCatalogs()) {
                List<String> cats = collect(rs, "TABLE_CAT");
                assertTrue(cats.size() >= 1, "expected at least one catalog, got " + cats);
            }
            try (ResultSet rs = md.getSchemas()) {
                List<String> schemas = collect(rs, "TABLE_SCHEM");
                assertTrue(schemas.contains("main"), "expected 'main' schema, got " + schemas);
            }

            // tables
            try (ResultSet rs = md.getTables(null, null, "jdbc_it_meta", null)) {
                assertTrue(rs.next(), "expected jdbc_it_meta table");
                assertEquals("jdbc_it_meta", rs.getString("TABLE_NAME"));
                assertEquals("TABLE", rs.getString("TABLE_TYPE"));
            }

            // columns
            try (ResultSet rs = md.getColumns(null, null, "jdbc_it_meta", null)) {
                List<String> cols = new ArrayList<>();
                while (rs.next()) cols.add(rs.getString("COLUMN_NAME"));
                assertTrue(cols.contains("id"));
                assertTrue(cols.contains("label"));
            }

            // primary keys
            try (ResultSet rs = md.getPrimaryKeys(null, null, "jdbc_it_meta")) {
                assertTrue(rs.next());
                assertEquals("id", rs.getString("COLUMN_NAME"));
                assertEquals(1, rs.getInt("KEY_SEQ"));
            }

            // table types
            try (ResultSet rs = md.getTableTypes()) {
                List<String> tts = collect(rs, "TABLE_TYPE");
                assertTrue(tts.contains("TABLE"));
                assertTrue(tts.contains("VIEW"));
            }

            // type info smoke
            try (ResultSet rs = md.getTypeInfo()) {
                assertTrue(rs.next(), "expected at least one row from getTypeInfo()");
            }

            s.execute("DROP TABLE jdbc_it_meta");
        }
    }

    @Test
    void resultSetMetaDataReportsTypes() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1::INTEGER AS x, 'a'::VARCHAR AS y, 1.5::DOUBLE AS z")) {
            var md = rs.getMetaData();
            assertEquals(3, md.getColumnCount());
            assertEquals("x", md.getColumnName(1));
            assertEquals(Types.INTEGER, md.getColumnType(1));
            assertEquals(Types.VARCHAR, md.getColumnType(2));
            assertEquals(Types.DOUBLE, md.getColumnType(3));
        }
    }

    @Test
    void concurrentConnections() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    try (Connection c = connect();
                         Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT " + n + " AS v")) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals(i, futures.get(i).get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void blobAndBytesRoundTrip() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT '\\xCA\\xFE\\xBA\\xBE'::BLOB AS b")) {
            assertTrue(rs.next());
            assertArrayEquals(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, rs.getBytes("b"));
        }
    }

    @Test
    void disconnectIsClean() throws Exception {
        Connection c = connect();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
        }
        c.close();
        assertTrue(c.isClosed());
    }

    @Test
    void selectFromInformationSchema() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name")) {
            List<String> schemas = collect(rs, "schema_name");
            assertTrue(schemas.contains("main"), "expected main schema, got " + schemas);
        }
    }

    private static List<String> collect(ResultSet rs, String col) throws SQLException {
        List<String> out = new ArrayList<>();
        while (rs.next()) out.add(rs.getString(col));
        return out;
    }
}
