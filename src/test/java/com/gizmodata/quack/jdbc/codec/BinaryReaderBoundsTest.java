package com.gizmodata.quack.jdbc.codec;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReaderBoundsTest {

    @Test
    void stringLengthPastTheEndOfInputIsRejectedAtAnyOffset() {
        // offset + length used to overflow an int once the reader had moved
        // off zero, so the length passed the bounds check and sized a byte[].
        BinaryWriter writer = new BinaryWriter();
        writer.writeByte(7);
        writer.writeUleb(Integer.MAX_VALUE);
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        assertEquals(7, reader.readByte());

        QuackProtocolException e = assertThrows(QuackProtocolException.class, reader::readString);
        assertTrue(e.getMessage().contains("needed " + Integer.MAX_VALUE + " byte(s), have 0"), e.getMessage());
    }

    @Test
    void stringEndingExactlyAtTheEndOfInputDecodes() {
        BinaryWriter writer = new BinaryWriter();
        writer.writeByte(7);
        writer.writeString("quack");
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        assertEquals(7, reader.readByte());
        assertEquals("quack", reader.readString());
        reader.assertEof();
    }
}
