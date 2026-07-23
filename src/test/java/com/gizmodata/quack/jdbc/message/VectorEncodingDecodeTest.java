package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decode tests for the compressed vector encodings the Quack server can emit
 * (CONSTANT, DICTIONARY, SEQUENCE) plus the FSST guard. The FLAT encoder never
 * produces these, so the wire bytes are hand-crafted to exercise the decode
 * branches directly.
 */
class VectorEncodingDecodeTest {

    private static final LogicalType INT = LogicalType.of(LogicalTypeId.INTEGER);
    private static final LogicalType BIGINT = LogicalType.of(LogicalTypeId.BIGINT);

    private static byte[] le32(int... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) buf.putInt(v);
        return buf.array();
    }

    private static DecodedVector decode(LogicalType type, int count, byte[] object) {
        return VectorCodec.decodeVector(new BinaryReader(object), type, count);
    }

    @Test
    void constantVectorBroadcastsSingleValue() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.CONSTANT.wireId()));
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(102, () -> obj.writeBlob(le32(42)));
        });
        DecodedVector v = decode(INT, 5, w.toByteArray());
        assertEquals(5, v.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(42, ((Number) v.getObject(i)).intValue());
        }
    }

    @Test
    void sequenceVectorProducesArithmeticSeries() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.SEQUENCE.wireId()));
            obj.writeField(91, () -> obj.writeSleb(100));
            obj.writeField(92, () -> obj.writeSleb(5));
        });
        DecodedVector v = decode(BIGINT, 4, w.toByteArray());
        assertEquals(4, v.size());
        assertEquals(100L, v.getLong(0));
        assertEquals(105L, v.getLong(1));
        assertEquals(110L, v.getLong(2));
        assertEquals(115L, v.getLong(3));
    }

    @Test
    void dictionaryVectorProjectsSelection() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.DICTIONARY.wireId()));
            obj.writeField(91, () -> obj.writeBlob(le32(2, 0, 1, 2)));
            obj.writeField(92, () -> obj.writeUleb(3));
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(102, () -> obj.writeBlob(le32(10, 20, 30)));
        });
        DecodedVector v = decode(INT, 4, w.toByteArray());
        assertEquals(4, v.size());
        assertEquals(30, ((Number) v.getObject(0)).intValue());
        assertEquals(10, ((Number) v.getObject(1)).intValue());
        assertEquals(20, ((Number) v.getObject(2)).intValue());
        assertEquals(30, ((Number) v.getObject(3)).intValue());
    }

    @Test
    void structEncodeRejectsNonMapRow() {
        // A non-null STRUCT row that is not a Map must fail loudly rather than
        // silently encoding every field as NULL in the bulk-load path.
        LogicalType structType = LogicalType.of(LogicalTypeId.STRUCT,
                new ExtraTypeInfo.StructInfo(List.of(new ChildType("x", INT)), Optional.empty()));
        DecodedVector vector = new DecodedVector.ObjectVec(structType, new Object[]{"not-a-map"});
        QuackProtocolException ex = assertThrows(QuackProtocolException.class,
                () -> VectorCodec.encodeVector(new BinaryWriter(), structType, vector));
        assertTrue(ex.getMessage().contains("map value for STRUCT"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    void fsstVectorIsRejectedWithClearError() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> obj.writeField(90, () -> obj.writeUleb(VectorType.FSST.wireId())));
        QuackUnsupportedTypeException ex = assertThrows(QuackUnsupportedTypeException.class,
                () -> decode(INT, 3, w.toByteArray()));
        assertEquals(true, ex.getMessage().toUpperCase().contains("FSST"));
    }
}
