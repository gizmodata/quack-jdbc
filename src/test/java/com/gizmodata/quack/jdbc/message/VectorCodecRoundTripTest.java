package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link VectorCodec} encode/decode. Builds a
 * DataChunk in code, encodes it, decodes the bytes back, and verifies
 * the values match — pure unit tests, no Quack server required.
 */
class VectorCodecRoundTripTest {

    @Test
    void integerVarcharChunkRoundTrips() {
        LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
        LogicalType varcharType = LogicalType.of(LogicalTypeId.VARCHAR);
        DataChunk chunk = new DataChunk(3,
                List.of(intType, varcharType),
                List.of(
                        new DecodedVector.IntVec(intType, new int[]{1, 2, 3}, null),
                        new DecodedVector.ObjectVec(varcharType, new Object[]{"alpha", "beta", "gamma"})));

        DataChunk decoded = roundTrip(chunk);
        assertEquals(3, decoded.rowCount());
        assertEquals(2, decoded.columns().size());
        for (int row = 0; row < 3; row++) {
            assertEquals(row + 1, ((Number) decoded.columns().get(0).getObject(row)).intValue());
        }
        assertEquals("alpha", decoded.columns().get(1).getObject(0));
        assertEquals("beta", decoded.columns().get(1).getObject(1));
        assertEquals("gamma", decoded.columns().get(1).getObject(2));
    }

    @Test
    void nullsRoundTripViaValidityBitmap() {
        LogicalType bigintType = LogicalType.of(LogicalTypeId.BIGINT);
        long[] validity = Validity.allValid(4);
        Validity.setNull(validity, 1);
        Validity.setNull(validity, 3);
        DataChunk chunk = new DataChunk(4, List.of(bigintType), List.of(
                new DecodedVector.LongVec(bigintType, new long[]{10L, 0L, 30L, 0L}, validity)));

        DataChunk decoded = roundTrip(chunk);
        assertEquals(10L, decoded.columns().get(0).getLong(0));
        assertTrue(decoded.columns().get(0).isNull(1));
        assertEquals(30L, decoded.columns().get(0).getLong(2));
        assertTrue(decoded.columns().get(0).isNull(3));
    }

    @Test
    void scalarMixRoundTrips() {
        LogicalType boolType = LogicalType.of(LogicalTypeId.BOOLEAN);
        LogicalType doubleType = LogicalType.of(LogicalTypeId.DOUBLE);
        LogicalType dateType = LogicalType.of(LogicalTypeId.DATE);
        LogicalType tsType = LogicalType.of(LogicalTypeId.TIMESTAMP);
        LogicalType decType = LogicalType.decimal(10, 2);
        LogicalType uuidType = LogicalType.of(LogicalTypeId.UUID);

        UUID u = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        LocalDate d = LocalDate.of(2026, 5, 13);
        LocalDateTime ts = LocalDateTime.of(2026, 5, 13, 14, 30, 0);

        DataChunk chunk = new DataChunk(1,
                List.of(boolType, doubleType, dateType, tsType, decType, uuidType),
                List.of(
                        new DecodedVector.BoolVec(boolType, new boolean[]{true}, null),
                        new DecodedVector.DoubleVec(doubleType, new double[]{3.14}, null),
                        new DecodedVector.ObjectVec(dateType, new Object[]{d}),
                        new DecodedVector.ObjectVec(tsType, new Object[]{ts}),
                        new DecodedVector.ObjectVec(decType, new Object[]{new BigDecimal("12.34")}),
                        new DecodedVector.ObjectVec(uuidType, new Object[]{u})));

        DataChunk decoded = roundTrip(chunk);
        assertEquals(true, decoded.columns().get(0).getObject(0));
        assertEquals(3.14, decoded.columns().get(1).getDouble(0), 1e-9);
        assertEquals(d, decoded.columns().get(2).getObject(0));
        assertEquals(ts, decoded.columns().get(3).getObject(0));
        assertEquals(new BigDecimal("12.34"), decoded.columns().get(4).getObject(0));
        assertEquals(u, decoded.columns().get(5).getObject(0));
    }

    @Test
    void emptyChunkRoundTrips() {
        LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
        DataChunk chunk = new DataChunk(0, List.of(intType), List.of(
                new DecodedVector.IntVec(intType, new int[0], null)));
        DataChunk decoded = roundTrip(chunk);
        assertEquals(0, decoded.rowCount());
        assertEquals(0, decoded.columns().get(0).size());
    }

    @Test
    void allNullColumnRoundTrips() {
        LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
        long[] validity = new long[Validity.wordCount(3)]; // all zeros = all null
        DataChunk chunk = new DataChunk(3, List.of(intType), List.of(
                new DecodedVector.IntVec(intType, new int[3], validity)));
        DataChunk decoded = roundTrip(chunk);
        for (int i = 0; i < 3; i++) {
            assertNull(decoded.columns().get(0).getObject(i));
        }
    }

    private static DataChunk roundTrip(DataChunk chunk) {
        BinaryWriter writer = new BinaryWriter();
        VectorCodec.encodeDataChunkWrapper(writer, chunk);
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        DataChunk decoded = VectorCodec.decodeDataChunkWrapper(reader);
        assertNotNull(decoded);
        return decoded;
    }
}
