package com.gizmodata.quack.jdbc.codec;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryReaderListTest {
    @Test
    void truncatedListDoesNotAllocateItsClaimedSize() {
        BinaryWriter writer = new BinaryWriter();
        writer.writeUleb(Integer.MAX_VALUE);
        BinaryReader reader = new BinaryReader(writer.toByteArray());

        assertThrows(QuackProtocolException.class, () -> reader.readList(i -> reader.readByte()));
    }

    @Test
    void validListCanGrowBeyondInitialCapacity() {
        BinaryWriter writer = new BinaryWriter();
        writer.writeUleb(2048);
        for (int i = 0; i < 2048; i++) writer.writeByte(i % 256);
        BinaryReader reader = new BinaryReader(writer.toByteArray());

        List<Integer> values = reader.readList(i -> reader.readByte());

        assertEquals(2048, values.size());
        for (int i = 0; i < values.size(); i++) assertEquals(i % 256, values.get(i));
        reader.assertEof();
    }
}
