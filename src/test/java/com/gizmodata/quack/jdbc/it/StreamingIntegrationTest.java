package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.sql.QuackSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the streaming-cursor + typed-vector rewrite.
 *
 * <p>Pins two behaviors that are easy to silently regress:
 *
 * <ol>
 *   <li>{@code QuackSession.cursor()} does not materialize the entire
 *       result up front — additional FETCH_REQUESTs are issued lazily
 *       as the consumer drains chunks.</li>
 *   <li>Fixed-width primitive logical types decode into typed
 *       {@code DecodedVector} primitive-array records (no boxing).</li>
 * </ol>
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StreamingIntegrationTest {

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

    /**
     * Small server batches, so a modest result spans many FETCH batches:
     * DuckDB 2.0 cuts batches by bytes (quack_target_batch_bytes, 32 MiB by
     * default) and inlines up to quack_prepare_inline_rows rows in PREPARE.
     */
    private static void useSmallBatches(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET GLOBAL quack_target_batch_bytes = 65536");
            s.execute("SET GLOBAL quack_prepare_inline_rows = 2048");
        }
    }

    @Test
    void cursorFetchesLazilyForLargeResult() throws Exception {
        try (QuackConnection c = connect()) {
            useSmallBatches(c);
            // md5() keeps the batches incompressible (a bare range() is sent
            // as tiny SEQUENCE vectors and fits in one batch).
            try (QuackSession.Cursor cursor = c.session().cursor(
                    "SELECT i, md5(i::VARCHAR) AS h FROM range(0, 200000) t(i)")) {
                int initialRows = cursor.materializedRowCount();
                assertTrue(initialRows > 0, "PREPARE_RESPONSE should include at least one initial chunk");
                assertTrue(initialRows < 200_000,
                        "cursor should not have materialized the full result up front; saw "
                                + initialRows + " rows already");
                assertTrue(cursor.hasMoreOnServer(), "the rest of the result should still be on the server");

                // Drain everything: many dense FETCH batches, then the
                // terminal response whose total must match what arrived.
                long expected = 0;
                DataChunk chunk;
                while ((chunk = cursor.nextChunk()) != null) {
                    for (int r = 0; r < chunk.rowCount(); r++) {
                        assertEquals(expected++, chunk.columns().get(0).getLong(r));
                    }
                }
                assertEquals(200_000, expected);
                assertEquals(200_000, cursor.materializedRowCount());
                assertFalse(cursor.hasMoreOnServer());
            }
        }
    }

    @Test
    void secondQueryWhileFirstStreamsKeepsBothResults() throws Exception {
        // The server streams one result per connection; a new query would
        // supersede an unfinished one. The driver buffers the first result
        // before starting the second, so both ResultSets stay readable.
        try (Connection c = connect()) {
            useSmallBatches(c);
            try (Statement s1 = c.createStatement(); Statement s2 = c.createStatement();
                 ResultSet first = s1.executeQuery("SELECT i, md5(i::VARCHAR) FROM range(0, 50000) t(i)")) {
                assertTrue(first.next());
                assertEquals(0, first.getLong(1));
                try (ResultSet second = s2.executeQuery("SELECT count(*) FROM range(0, 1234)")) {
                    assertTrue(second.next());
                    assertEquals(1234, second.getLong(1));
                }
                long expected = 1;
                while (first.next()) {
                    assertEquals(expected++, first.getLong(1));
                }
                assertEquals(50_000, expected);
            }
        }
    }

    @Test
    void resultSetStreamsThroughEntireRange() throws Exception {
        // End-to-end smoke: walk all 100k rows through the JDBC ResultSet
        // surface and verify exact count + monotonic increasing values
        // (proves no chunk was dropped or duplicated across the FETCH loop).
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT i FROM range(0, 100000) t(i)")) {
            long expected = 0L;
            int count = 0;
            while (rs.next()) {
                assertEquals(expected, rs.getLong(1), "row mismatch at " + count);
                expected++;
                count++;
            }
            assertEquals(100_000, count);
        }
    }

    @Test
    void primitiveLogicalTypesProduceTypedVectors() throws Exception {
        try (QuackConnection c = connect();
             QuackSession.Cursor cursor = c.session().cursor(
                     "SELECT 1::BOOLEAN AS b, "
                             + "2::TINYINT AS i8, "
                             + "3::SMALLINT AS i16, "
                             + "4::INTEGER AS i32, "
                             + "5::BIGINT AS i64, "
                             + "1.5::FLOAT AS f, "
                             + "2.5::DOUBLE AS d, "
                             + "'hello'::VARCHAR AS v")) {
            DataChunk chunk = cursor.nextChunk();
            assertNotNull(chunk, "expected at least one chunk");
            assertEquals(8, chunk.columns().size());

            assertColumnVecType(chunk, 0, DecodedVector.BoolVec.class);
            assertColumnVecType(chunk, 1, DecodedVector.ByteVec.class);
            assertColumnVecType(chunk, 2, DecodedVector.ShortVec.class);
            assertColumnVecType(chunk, 3, DecodedVector.IntVec.class);
            assertColumnVecType(chunk, 4, DecodedVector.LongVec.class);
            assertColumnVecType(chunk, 5, DecodedVector.FloatVec.class);
            assertColumnVecType(chunk, 6, DecodedVector.DoubleVec.class);
            // VARCHAR materializes to String objects; ObjectVec is the right home.
            assertColumnVecType(chunk, 7, DecodedVector.ObjectVec.class);
        }
    }

    @Test
    void datesAndDecimalsStayInObjectVec() throws Exception {
        try (QuackConnection c = connect();
             QuackSession.Cursor cursor = c.session().cursor(
                     "SELECT DATE '2026-05-13' AS d, "
                             + "TIMESTAMP '2026-05-13 14:00:00' AS ts, "
                             + "123.45::DECIMAL(10,2) AS dec, "
                             + "'00000000-0000-0000-0000-000000000001'::UUID AS u, "
                             + "INTERVAL '5 days' AS iv")) {
            DataChunk chunk = cursor.nextChunk();
            assertNotNull(chunk);
            for (int i = 0; i < chunk.columns().size(); i++) {
                assertTrue(chunk.columns().get(i) instanceof DecodedVector.ObjectVec,
                        "expected ObjectVec for column " + i
                                + ", got " + chunk.columns().get(i).getClass().getSimpleName());
            }
        }
    }

    @Test
    void sequenceEncodingProducesTypedVector() throws Exception {
        // range() returns BIGINT and DuckDB encodes it as a SEQUENCE vector.
        // We must materialize that as a LongVec (primitive long[]), not
        // ObjectVec — otherwise the memory benefit vanishes for SELECT i FROM range().
        try (QuackConnection c = connect();
             QuackSession.Cursor cursor = c.session().cursor("SELECT i FROM range(0, 1000) t(i)")) {
            DataChunk chunk = cursor.nextChunk();
            assertNotNull(chunk);
            assertTrue(chunk.columns().get(0) instanceof DecodedVector.LongVec,
                    "expected SEQUENCE → LongVec, got "
                            + chunk.columns().get(0).getClass().getSimpleName());
            // Spot-check a couple of values
            DecodedVector.LongVec vec = (DecodedVector.LongVec) chunk.columns().get(0);
            assertEquals(0L, vec.values()[0]);
            if (vec.size() > 5) assertEquals(5L, vec.values()[5]);
        }
    }

    @Test
    void constantEncodingProducesTypedVector() throws Exception {
        // SELECT <literal> FROM range(n) often emits a CONSTANT-encoded vector.
        // Whatever the server chooses, we should land on an IntVec for a
        // literal INTEGER repeated across rows.
        try (QuackConnection c = connect();
             QuackSession.Cursor cursor = c.session().cursor("SELECT 42::INTEGER AS x FROM range(0, 1000)")) {
            DataChunk chunk = cursor.nextChunk();
            assertNotNull(chunk);
            // Either FLAT or CONSTANT, both must land in IntVec.
            assertTrue(chunk.columns().get(0) instanceof DecodedVector.IntVec,
                    "expected typed IntVec, got "
                            + chunk.columns().get(0).getClass().getSimpleName());
            DecodedVector.IntVec vec = (DecodedVector.IntVec) chunk.columns().get(0);
            assertEquals(42, vec.values()[0]);
            assertEquals(42, vec.values()[vec.size() - 1]);
        }
    }

    private static void assertColumnVecType(DataChunk chunk, int column,
                                            Class<? extends DecodedVector> expected) {
        DecodedVector actual = chunk.columns().get(column);
        assertTrue(expected.isInstance(actual),
                "column " + column + " expected " + expected.getSimpleName()
                        + ", got " + actual.getClass().getSimpleName());
    }
}
