package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips PreparedStatement parameters (client-side SqlLiteral
 * interpolation) through a live server: set a value, SELECT it back, and
 * confirm DuckDB parses the rendered literal to the same value. Also compares
 * the result against duckdb-jdbc (which uses real bind parameters) under the
 * oracle profile.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class SqlLiteralRoundTripIntegrationTest {

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
    void primitivesRoundTrip() throws Exception {
        try (Connection c = connect()) {
            assertEquals(true, roundTripBoolean(c, Boolean.TRUE));
            assertEquals(42, ((Number) roundTripObject(c, 42)).intValue());
            assertEquals(9_000_000_000L, ((Number) roundTripObject(c, 9_000_000_000L)).longValue());
            assertEquals(2.5, ((Number) roundTripObject(c, 2.5d)).doubleValue(), 1e-9);
            assertEquals(new BigDecimal("12.34"), roundTripBigDecimal(c, new BigDecimal("12.34")));
        }
    }

    @Test
    void stringsRoundTripIncludingQuotesAndInjection() throws Exception {
        try (Connection c = connect()) {
            assertEquals("hello", roundTripString(c, "hello"));
            assertEquals("O'Brien", roundTripString(c, "O'Brien"));
            assertEquals("'); DROP TABLE users; --", roundTripString(c, "'); DROP TABLE users; --"));
            assertEquals("", roundTripString(c, ""));
        }
    }

    @Test
    void binaryRoundTrips() throws Exception {
        byte[] payload = {(byte) 0xCA, (byte) 0xFE, 0x00, 0x7F};
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setBytes(1, payload);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertArrayEquals(payload, rs.getBytes(1));
            }
        }
    }

    @Test
    void temporalAndUuidRoundTrip() throws Exception {
        try (Connection c = connect()) {
            assertEquals(LocalDate.of(2026, 5, 13),
                    roundTripAs(c, LocalDate.of(2026, 5, 13), LocalDate.class));
            assertEquals(LocalDateTime.of(2026, 5, 13, 14, 30, 15),
                    roundTripAs(c, LocalDateTime.of(2026, 5, 13, 14, 30, 15), LocalDateTime.class));
            UUID u = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
            assertEquals(u, roundTripAs(c, u, UUID.class));
        }
    }

    @Test
    void nullRoundTrips() throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, null);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                rs.getObject(1);
                assertTrue(rs.wasNull());
            }
        }
    }

    @Test
    @Tag("oracle")
    void resultsMatchOracleForBoundParameters() throws Exception {
        Assumptions.assumeTrue(DuckDbOracle.available(),
                "duckdb_jdbc driver not on the test classpath (run with -Poracle)");
        Object[] params = {
                42, 9_000_000_000L, 2.5d, new BigDecimal("12.34"),
                "O'Brien", "plain",
        };
        try (Connection quack = connect();
             Connection oracle = DuckDbOracle.newConnection()) {
            for (Object p : params) {
                assertEquals(asString(oracle, p), asString(quack, p),
                        "parameter round-trip mismatch for: " + p);
            }
        }
    }

    private boolean roundTripBoolean(Connection c, Boolean value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private Object roundTripObject(Connection c, Object value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }

    private BigDecimal roundTripBigDecimal(Connection c, BigDecimal value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setBigDecimal(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private String roundTripString(Connection c, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private <T> T roundTripAs(Connection c, Object value, Class<T> as) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, as);
            }
        }
    }

    private static String asString(Connection c, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT (?)::VARCHAR AS a")) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
