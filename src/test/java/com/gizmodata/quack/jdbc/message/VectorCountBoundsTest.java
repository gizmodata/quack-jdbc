package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row and element counts come from the peer. A count the message cannot back
 * with bytes must fail as a {@link QuackProtocolException}, never size an
 * allocation: each rejected case here raised {@code OutOfMemoryError} (or an
 * index error) from a message of a few dozen bytes before the check existed.
 * The accepting twins pin what a real server sends and must keep decoding.
 */
class VectorCountBoundsTest {

    private static final int HUGE = Integer.MAX_VALUE;

    private static final LogicalType INT = LogicalType.of(LogicalTypeId.INTEGER);
    private static final LogicalType BIGINT = LogicalType.of(LogicalTypeId.BIGINT);
    private static final LogicalType BOOL = LogicalType.of(LogicalTypeId.BOOLEAN);
    private static final LogicalType INT_LIST = LogicalType.of(LogicalTypeId.LIST,
            new ExtraTypeInfo.ListInfo(INT, Optional.empty()));

    private static byte[] le32(int... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) buf.putInt(v);
        return buf.array();
    }

    private static DecodedVector decode(LogicalType type, int count, byte[] wire) {
        return VectorCodec.decodeVector(new BinaryReader(wire), type, count);
    }

    private static void assertRejected(String expectedMessagePart, LogicalType type, int count, byte[] wire) {
        QuackProtocolException e = assertThrows(QuackProtocolException.class,
                () -> decode(type, count, wire));
        assertTrue(e.getMessage().contains(expectedMessagePart),
                "expected \"" + expectedMessagePart + "\" in: " + e.getMessage());
    }

    private static void writeSequenceBody(BinaryWriter w) {
        w.writeField(90, () -> w.writeUleb(VectorType.SEQUENCE.wireId()));
        w.writeField(91, () -> w.writeSleb(0));
        w.writeField(92, () -> w.writeSleb(1));
    }

    private static void writeFlatInts(BinaryWriter w, int... values) {
        w.writeField(100, () -> w.writeBool(false));
        w.writeField(102, () -> w.writeBlob(le32(values)));
    }

    /** A FLAT LIST vector body: list size, one (offset, length) entry per row, then the child vector. */
    private static byte[] listVector(int listSize, int[][] entries, Runnable childBody, BinaryWriter w) {
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(104, () -> obj.writeUleb(listSize));
            obj.writeField(105, () -> obj.writeList(Arrays.asList(entries), (int[] e, int i) ->
                    obj.writeObject(o -> {
                        o.writeField(100, () -> o.writeUleb(e[0]));
                        o.writeField(101, () -> o.writeUleb(e[1]));
                    })));
            obj.writeField(106, () -> obj.writeObject(child -> childBody.run()));
        });
        return w.toByteArray();
    }

    // ---- rejected: a count with no bytes behind it ----

    @Test
    void sequenceVectorWithHugeRowCountIsRejected() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> writeSequenceBody(obj));
        assertRejected("Vector claims " + HUGE + " rows", INT, HUGE, w.toByteArray());
    }

    @Test
    void constantVectorWithHugeRowCountIsRejected() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.CONSTANT.wireId()));
            writeFlatInts(obj, 42);
        });
        assertRejected("Vector claims " + HUGE + " rows", INT, HUGE, w.toByteArray());
    }

    @Test
    void structWithoutChildrenAndHugeRowCountIsRejected() {
        LogicalType emptyStruct = LogicalType.of(LogicalTypeId.STRUCT,
                new ExtraTypeInfo.StructInfo(List.<ChildType>of(), Optional.empty()));
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(103, () -> obj.writeUleb(0));
        });
        assertRejected("Vector claims " + HUGE + " rows", emptyStruct, HUGE, w.toByteArray());
    }

    @Test
    void dictionaryWithHugeDictionaryCountIsRejected() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.DICTIONARY.wireId()));
            obj.writeField(91, () -> obj.writeBlob(le32(0)));
            obj.writeField(92, () -> obj.writeUleb(HUGE));
            writeSequenceBody(obj);
        });
        assertRejected("Vector claims " + HUGE + " rows", INT, 1, w.toByteArray());
    }

    @Test
    void listWithHugeChildCountIsRejected() {
        BinaryWriter w = new BinaryWriter();
        byte[] wire = listVector(HUGE, new int[0][], () -> writeSequenceBody(w), w);
        assertRejected("Vector claims " + HUGE + " rows", INT_LIST, 0, wire);
    }

    @Test
    void listEntryLongerThanItsChildVectorIsRejected() {
        BinaryWriter w = new BinaryWriter();
        byte[] wire = listVector(1, new int[][]{{0, HUGE}}, () -> writeFlatInts(w, 7), w);
        assertRejected("LIST row 0 spans child elements 0 to " + HUGE, INT_LIST, 1, wire);
    }

    @Test
    void listEntryPastTheEndOfItsChildVectorIsRejected() {
        BinaryWriter w = new BinaryWriter();
        byte[] wire = listVector(1, new int[][]{{5, 1}}, () -> writeFlatInts(w, 7), w);
        assertRejected("LIST row 0 spans child elements 5 to 6, but the child vector has 1", INT_LIST, 1, wire);
    }

    @Test
    void arrayWhoseChildCountOverflowsIsRejected() {
        // 65536 rows of 65536 elements is 2^32 child elements: zero as an int.
        LogicalType bigArray = LogicalType.of(LogicalTypeId.ARRAY,
                new ExtraTypeInfo.ArrayInfo(INT, 65536, Optional.empty()));
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(103, () -> obj.writeUleb(65536));
            obj.writeField(104, () -> obj.writeObject(child -> writeFlatInts(child)));
        });
        assertRejected("more child elements than a vector can hold", bigArray, 65536, w.toByteArray());
    }

    @Test
    void chunkWithHugeRowCountIsRejected() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeUleb(HUGE));
            obj.writeField(101, () -> obj.writeUleb(0));
            obj.writeField(102, () -> obj.writeUleb(0));
        });
        QuackProtocolException e = assertThrows(QuackProtocolException.class,
                () -> VectorCodec.decodeDataChunk(new BinaryReader(w.toByteArray())));
        assertTrue(e.getMessage().contains("DataChunk claims " + HUGE + " rows"), e.getMessage());
    }

    // ---- accepted: what a real server sends ----

    @Test
    void flatVectorAboveTheCapDecodesWhenItsRowsAreOnTheWire() {
        // DuckDB sends a long list's child as one FLAT vector of every element.
        int rows = VectorCodec.MAX_UNBACKED_VECTOR_ROWS + 1;
        byte[] data = new byte[rows];
        data[rows - 1] = 1;
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(102, () -> obj.writeBlob(data));
        });
        DecodedVector v = decode(BOOL, rows, w.toByteArray());
        assertEquals(rows, v.size());
        assertEquals(Boolean.FALSE, v.getObject(0));
        assertEquals(Boolean.TRUE, v.getObject(rows - 1));
    }

    @Test
    void sequenceVectorAtTheCapDecodes() {
        int rows = VectorCodec.MAX_UNBACKED_VECTOR_ROWS;
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> writeSequenceBody(obj));
        DecodedVector v = decode(BIGINT, rows, w.toByteArray());
        assertEquals(rows, v.size());
        assertEquals(rows - 1L, v.getLong(rows - 1));
    }

    @Test
    void sequenceVectorJustAboveTheCapIsRejected() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> writeSequenceBody(obj));
        int rows = VectorCodec.MAX_UNBACKED_VECTOR_ROWS + 1;
        assertRejected("Vector claims " + rows + " rows", BIGINT, rows, w.toByteArray());
    }

    @Test
    void listRowsMayShareChildElements() {
        // A flattened constant LIST gives every row the same (offset, length).
        BinaryWriter w = new BinaryWriter();
        byte[] wire = listVector(2, new int[][]{{0, 2}, {0, 2}}, () -> writeFlatInts(w, 10, 20), w);
        DecodedVector v = decode(INT_LIST, 2, wire);
        assertEquals(List.of(10, 20), v.getObject(0));
        assertEquals(List.of(10, 20), v.getObject(1));
    }
}
