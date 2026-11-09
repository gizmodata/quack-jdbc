package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.sql.QuackDriver;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quack protocol 3 (DuckDB 2.0) behaviour against a real server: multi-batch
 * ingest, the heartbeat lease, cancel, DuckDB 2.0 types and SQLSTATEs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Protocol3IntegrationTest {

    private QuackServerFixture server;

    @BeforeAll
    void start() throws Exception {
        server = QuackServerFixture.tryStart();
        assertNotNull(server);
    }

    @AfterAll
    void stop() {
        if (server != null) server.close();
    }

    private QuackConnection connect() throws SQLException {
        return (QuackConnection) DriverManager.getConnection(server.jdbcUrl());
    }

    // ---- ingest (SEND_DATA) ----

    @Test
    void appendSpansManySendDataBatches() throws Exception {
        // ~300k rows with 64-character strings: well over the 4 MiB the
        // driver puts in one SEND_DATA request, so several batches plus the
        // terminal message with the batch count.
        int rows = 300_000;
        LogicalType bigint = LogicalType.of(LogicalTypeId.BIGINT);
        LogicalType varchar = LogicalType.of(LogicalTypeId.VARCHAR);
        List<DataChunk> chunks = new ArrayList<>();
        for (int start = 0; start < rows; start += 50_000) {
            long[] ids = new long[50_000];
            Object[] text = new Object[50_000];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = start + i;
                text[i] = (i % 97 == 0) ? null : String.format("%064d", start + i);
            }
            chunks.add(new DataChunk(ids.length, List.of(bigint, varchar), List.of(
                    new DecodedVector.LongVec(bigint, ids, null), new DecodedVector.ObjectVec(varchar, text))));
        }
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE OR REPLACE TABLE p3_ingest (id BIGINT, t VARCHAR)");
            long inserted = c.session().appendChunks("main", "p3_ingest", chunks);
            assertEquals(rows, inserted);
            try (ResultSet rs = s.executeQuery("SELECT count(*), count(t), sum(id), min(t), max(t) FROM p3_ingest")) {
                assertTrue(rs.next());
                assertEquals(rows, rs.getLong(1));
                long nulls = 0;
                for (int start = 0; start < rows; start += 50_000) nulls += (50_000 + 96) / 97;
                assertEquals(rows - nulls, rs.getLong(2));
                assertEquals((long) rows * (rows - 1) / 2, rs.getLong(3));
                assertEquals(String.format("%064d", 1), rs.getString(4));
                assertEquals(String.format("%064d", rows - 1), rs.getString(5));
            }
        }
    }

    @Test
    void appendIntoMismatchedTableReportsAlreadyExists() throws Exception {
        LogicalType varchar = LogicalType.of(LogicalTypeId.VARCHAR);
        DataChunk chunk = new DataChunk(1, List.of(varchar, varchar), List.of(
                new DecodedVector.ObjectVec(varchar, new Object[]{"a"}),
                new DecodedVector.ObjectVec(varchar, new Object[]{"b"})));
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE OR REPLACE TABLE p3_mismatch (i INTEGER)");
            var e = assertThrows(com.gizmodata.quack.jdbc.QuackServerException.class,
                    () -> c.session().appendChunk("main", "p3_mismatch", chunk));
            assertEquals("42S01", e.sqlState(), e.getMessage());
            assertTrue(e.getMessage().contains("do not match"), e.getMessage());
            // the connection is still usable afterwards
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM p3_mismatch")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong(1));
            }
        }
    }

    @Test
    void appendIntoMissingTableReportsNotFound() throws Exception {
        LogicalType integer = LogicalType.of(LogicalTypeId.INTEGER);
        DataChunk chunk = new DataChunk(1, List.of(integer),
                List.of(new DecodedVector.IntVec(integer, new int[]{1}, null)));
        try (QuackConnection c = connect()) {
            var e = assertThrows(com.gizmodata.quack.jdbc.QuackServerException.class,
                    () -> c.session().appendChunk("main", "p3_no_such_table", chunk));
            assertEquals("42S02", e.sqlState(), e.getMessage());
        }
    }

    // ---- heartbeat lease ----

    @Test
    void heartbeatKeepsAnIdleConnectionAlive() throws Exception {
        Properties props = new Properties();
        props.setProperty("heartbeatTimeout", "2");
        try (Connection c = DriverManager.getConnection(server.jdbcUrl(), props); Statement s = c.createStatement()) {
            assertEquals(2, ((QuackConnection) c).session().lease().getSeconds());
            Thread.sleep(5_000); // over twice the lease, with the heartbeat running
            try (ResultSet rs = s.executeQuery("SELECT 42")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
        }
    }

    @Test
    void withoutHeartbeatsTheLeaseExpires() throws Exception {
        // Negative twin of the test above: the same idle period with every
        // heartbeat dropped must cost the connection.
        AtomicInteger dropped = new AtomicInteger();
        QuackDriver driver = new QuackDriver();
        Properties props = new Properties();
        props.setProperty("heartbeatTimeout", "2");
        try (Connection c = driver.connect(server.jdbcUrl(), props, uri -> {
            QuackTransport http = QuackHttpTransport.from(uri);
            return request -> {
                if (request instanceof QuackMessage.HeartbeatRequest) {
                    dropped.incrementAndGet();
                    throw new com.gizmodata.quack.jdbc.QuackException("heartbeat dropped by the test");
                }
                return http.send(request);
            };
        }); Statement s = c.createStatement()) {
            Thread.sleep(5_000);
            assertTrue(dropped.get() > 0, "the driver should have tried to send heartbeats");
            SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("SELECT 42"));
            assertEquals("08003", e.getSQLState(), e.getMessage());
            assertTrue(e.getMessage().toLowerCase().contains("lease") || e.getMessage().toLowerCase().contains("connection id"),
                    e.getMessage());
        }
    }

    // ---- cancel ----

    @Test
    void cancelInterruptsARunningStatement() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            CompletableFuture<Object> run = CompletableFuture.supplyAsync(() -> {
                try (ResultSet rs = s.executeQuery(
                        "SELECT sum(a.i * b.i) FROM range(0, 1000000) a(i), range(0, 1000000) b(i)")) {
                    return rs.next() ? rs.getObject(1) : null;
                } catch (SQLException e) {
                    return e;
                }
            });
            long start = System.nanoTime();
            // Cancel until the statement stops: an early CANCEL can reach the
            // server before it registered the query.
            while (!run.isDone() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(60)) {
                Thread.sleep(200);
                s.cancel();
            }
            Object result = run.get(10, TimeUnit.SECONDS);
            SQLException e = assertInstanceOf(SQLException.class, result, "the statement should have been interrupted");
            assertEquals("57014", e.getSQLState(), e.getMessage());
            assertTrue(e.getMessage().toLowerCase().contains("interrupt"), e.getMessage());
            // the connection is still usable afterwards
            try (ResultSet rs = s.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    void cancelWithNothingRunningIsANoOp() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.cancel();
            try (ResultSet rs = s.executeQuery("SELECT 7")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            }
        }
    }

    // ---- DuckDB 2.0 types ----

    @Test
    void variantReadsAsTheSameJsonDuckDbPrints() throws Exception {
        String[] values = {
                "42::VARIANT", "(-128)::TINYINT::VARIANT", "255::UTINYINT::VARIANT", "65535::USMALLINT::VARIANT",
                "4294967295::UINTEGER::VARIANT", "'18446744073709551615'::UBIGINT::VARIANT",
                "'170141183460469231731687303715884105727'::HUGEINT::VARIANT",
                "'340282366920938463463374607431768211455'::UHUGEINT::VARIANT",
                "1.5::DOUBLE::VARIANT", "1e21::DOUBLE::VARIANT", "1e-7::DOUBLE::VARIANT", "0.1::FLOAT::VARIANT",
                "'nan'::DOUBLE::VARIANT", "'-inf'::DOUBLE::VARIANT",
                "1.23::DECIMAL(3,2)::VARIANT", "(-0.12)::DECIMAL(2,2)::VARIANT", "123456.789::DECIMAL(15,3)::VARIANT",
                "'12345678901234567890.123456'::DECIMAL(38,6)::VARIANT",
                "'plain'::VARIANT", "(E'quote\" back\\\\ nl\\n tab\\t ctl' || chr(1) || ' é ✓')::VARIANT",
                "{'zeta': 1, 'Alpha': 2, 'beta': 3, 'é': 4, 'b': 5}::VARIANT",
                "'\\x00\\xFFab'::BLOB::VARIANT", "'0101101'::BIT::VARIANT",
                "'4b6f3c2e-1d2a-4f1e-9c3b-1234567890ab'::UUID::VARIANT",
                "DATE '2024-01-02'::VARIANT", "'0044-03-15 (BC)'::DATE::VARIANT", "'infinity'::DATE::VARIANT",
                "TIME '12:34:56.789'::VARIANT", "'12:34:56.123456789'::TIME_NS::VARIANT",
                "TIMETZ '01:02:03-08:00:30'::VARIANT",
                "TIMESTAMP '2024-01-02 03:04:05.5'::VARIANT", "'2024-01-02 03:04:05'::TIMESTAMP_S::VARIANT",
                "'2024-01-02 03:04:05.12'::TIMESTAMP_MS::VARIANT",
                "'2024-01-02 03:04:05.123456789'::TIMESTAMP_NS::VARIANT",
                "TIMESTAMPTZ '2024-01-02 03:04:05.5+00'::VARIANT",
                "'2024-01-02 03:04:05.123456789+00'::TIMESTAMPTZ_NS::VARIANT",
                "TIMESTAMP '1960-06-01 23:59:59.999999'::VARIANT",
                "INTERVAL '1 year 2 months 3 days 04:05:06.7'::VARIANT", "INTERVAL '-3 hours -2 minutes'::VARIANT",
                "true::VARIANT", "false::VARIANT",
                "{'b': 1, 'a': [1, NULL, 3], 'c': {'d': 'x'}}::VARIANT", "[]::INTEGER[]::VARIANT",
                "[[1, 2], [3]]::VARIANT", "{'k': NULL::INTEGER}::VARIANT", "NULL::INTEGER::VARIANT",
        };
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String v : values) {
                try (ResultSet rs = s.executeQuery("SELECT x, x::JSON::VARCHAR, typeof(x) FROM (SELECT " + v + " AS x)")) {
                    assertTrue(rs.next(), v);
                    assertEquals(rs.getString(2), rs.getString(1), "VARIANT " + v);
                    if (rs.getString(2) != null) {
                        assertInstanceOf(String.class, rs.getObject(1), v);
                    }
                    assertEquals(java.sql.Types.OTHER, rs.getMetaData().getColumnType(1)); // as duckdb-jdbc 2.0
                    assertEquals("VARIANT", rs.getMetaData().getColumnTypeName(1));
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT NULL::VARIANT")) {
                assertTrue(rs.next());
                assertNull(rs.getObject(1));
            }
            // VARIANTs nested in a LIST and a STRUCT decode too.
            try (ResultSet rs = s.executeQuery("SELECT [1::VARIANT, 'a'::VARIANT], {'v': [2]::VARIANT}")) {
                assertTrue(rs.next());
                assertEquals(List.of("1", "\"a\""), rs.getObject(1) instanceof java.sql.Array a
                        ? List.of((Object[]) a.getArray()) : rs.getObject(1));
                Object struct = rs.getObject(2);
                Map<?, ?> m = struct instanceof java.sql.Struct st
                        ? Map.of("v", st.getAttributes()[0]) : (Map<?, ?>) struct;
                assertEquals("[2]", m.get("v"));
            }
        }
    }

    @Test
    void variantColumnFromATable() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE OR REPLACE TABLE p3_variant AS SELECT i, CASE WHEN i % 3 = 0 THEN i::VARIANT "
                    + "WHEN i % 3 = 1 THEN ('s' || i)::VARIANT ELSE {'n': i, 'l': [i, i + 1]}::VARIANT END AS v "
                    + "FROM range(0, 5000) t(i)");
            try (ResultSet rs = s.executeQuery("SELECT v, v::JSON::VARCHAR FROM p3_variant ORDER BY i")) {
                int n = 0;
                while (rs.next()) {
                    assertEquals(rs.getString(2), rs.getString(1), "row " + n);
                    n++;
                }
                assertEquals(5000, n);
            }
        }
    }

    @Test
    void geometryIsWkbBytesBothWays() throws Exception {
        byte[] point = HexFormat.of().parseHex("0101000000000000000000F03F0000000000000040");
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT 'POINT(1 2)'::GEOMETRY")) {
                assertTrue(rs.next());
                assertArrayEquals(point, rs.getBytes(1));
                assertArrayEquals(point, (byte[]) rs.getObject(1));
                assertEquals("GEOMETRY", rs.getMetaData().getColumnTypeName(1));
            }
            s.execute("CREATE OR REPLACE TABLE p3_geom (g GEOMETRY)");
            LogicalType geom = LogicalType.of(LogicalTypeId.GEOMETRY);
            c.session().appendChunk("main", "p3_geom", new DataChunk(2, List.of(geom),
                    List.of(new DecodedVector.ObjectVec(geom, new Object[]{point, null}))));
            try (ResultSet rs = s.executeQuery("SELECT g::VARCHAR FROM p3_geom ORDER BY g NULLS LAST")) {
                assertTrue(rs.next());
                assertEquals("POINT (1 2)", rs.getString(1));
                assertTrue(rs.next());
                assertNull(rs.getString(1));
            }
        }
    }

    @Test
    void uuidAndNanosecondTimestamps() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT '4b6f3c2e-1d2a-4f1e-9c3b-1234567890ab'::UUID, "
                     + "'2024-01-02 03:04:05.123456789'::TIMESTAMP_NS, "
                     + "'1969-12-31 23:59:59.999999999'::TIMESTAMP_NS, "
                     + "'2024-01-02 03:04:05.123456789+00'::TIMESTAMPTZ_NS, "
                     + "TIMESTAMP '2024-01-02 03:04:05', "
                     + "TIMETZ '01:02:03-08:00', "
                     + "(1, 'a')")) {
            assertTrue(rs.next());
            assertEquals(UUID.fromString("4b6f3c2e-1d2a-4f1e-9c3b-1234567890ab"), rs.getObject(1));
            assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_789), rs.getObject(2));
            assertEquals(LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_999), rs.getObject(3));
            assertEquals(OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_789, ZoneOffset.UTC), rs.getObject(4));
            assertEquals("TIMESTAMPTZ_NS", rs.getMetaData().getColumnTypeName(4));
            assertEquals(java.sql.Types.OTHER, rs.getMetaData().getColumnType(4)); // as duckdb-jdbc 2.0
            // a naive TIMESTAMP is not shifted by the JVM's time zone
            assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5), rs.getObject(5));
            assertEquals(OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofHours(-8)), rs.getObject(6));
            assertEquals("TUPLE(INTEGER, VARCHAR)", rs.getMetaData().getColumnTypeName(7));
        }
    }

    // ---- SQLSTATEs ----

    @Test
    void serverErrorsCarrySqlStates() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertState(s, "SELECT * FROM p3_no_such_table", "42S02");
            assertState(s, "SELEC 1", "42601");
            assertState(s, "SELECT no_such_column FROM range(1)", "42S22");
            s.execute("CREATE OR REPLACE TABLE p3_pk (i INTEGER PRIMARY KEY)");
            s.execute("INSERT INTO p3_pk VALUES (1)");
            assertState(s, "INSERT INTO p3_pk VALUES (1)", "23000");
            assertState(s, "SELECT 'x'::INTEGER", "22000");
            assertState(s, "CREATE TABLE p3_pk (j INTEGER)", "42S01");
            assertState(s, "COMMIT", "25000");
        }
    }

    @Test
    void badTokenIsAnAuthorizationError() {
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(
                "jdbc:quack://127.0.0.1:" + server.port() + "?token=wrong-token"));
        assertEquals("28000", e.getSQLState(), e.getMessage());
    }

    @Test
    void metadataReportsTheServerVersionWithoutAQuery() throws Exception {
        try (Connection c = connect()) {
            var md = c.getMetaData();
            assertTrue(md.getDatabaseProductVersion().startsWith("v2."), md.getDatabaseProductVersion());
            assertEquals(2, md.getDatabaseMajorVersion());
            assertFalse(md.getDriverVersion().isEmpty());
        }
    }

    private static void assertState(Statement s, String sql, String state) {
        SQLException e = assertThrows(SQLException.class, () -> s.execute(sql), sql);
        assertEquals(state, e.getSQLState(), sql + " -> " + e.getMessage());
    }
}
