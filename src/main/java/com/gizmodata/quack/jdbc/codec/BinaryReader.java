package com.gizmodata.quack.jdbc.codec;

import com.gizmodata.quack.jdbc.QuackProtocolException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reads DuckDB BinarySerializer-compatible primitives written by
 * {@link BinaryWriter}.
 */
public final class BinaryReader {

    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);

    private final byte[] bytes;
    private int offset;

    public BinaryReader(byte[] bytes) {
        this.bytes = bytes;
        this.offset = 0;
    }

    public int position() {
        return offset;
    }

    public int remaining() {
        return bytes.length - offset;
    }

    public boolean eof() {
        return offset >= bytes.length;
    }

    public void assertEof() {
        if (!eof()) {
            throw new QuackProtocolException("Unexpected trailing bytes at offset " + offset);
        }
    }

    public <T> T readObject(Supplier<T> body) {
        T result = body.get();
        readEndObject();
        return result;
    }

    public void readEndObject() {
        int id = readFieldId();
        if (id != QuackConstants.FIELD_END) {
            throw new QuackProtocolException(
                    "Expected end-of-object at offset " + (offset - 2) + ", got field " + id);
        }
    }

    public int readFieldId() {
        ensure(2);
        int lo = bytes[offset++] & 0xFF;
        int hi = bytes[offset++] & 0xFF;
        return lo | (hi << 8);
    }

    public int peekFieldId() {
        ensure(2);
        int lo = bytes[offset] & 0xFF;
        int hi = bytes[offset + 1] & 0xFF;
        return lo | (hi << 8);
    }

    public <T> T readRequiredField(int fieldId, Supplier<T> reader) {
        int actual = readFieldId();
        if (actual != fieldId) {
            throw new QuackProtocolException(
                    "Expected field " + fieldId + " at offset " + (offset - 2) + ", got " + actual);
        }
        return reader.get();
    }

    public <T> T readOptionalField(int fieldId, Supplier<T> reader, T defaultValue) {
        if (eof() || peekFieldId() != fieldId) {
            return defaultValue;
        }
        readFieldId();
        return reader.get();
    }

    public int readByte() {
        ensure(1);
        return bytes[offset++] & 0xFF;
    }

    public byte[] readBytes(int length) {
        if (length < 0) {
            throw new QuackProtocolException("Invalid byte length " + length);
        }
        ensure(length);
        byte[] out = new byte[length];
        System.arraycopy(bytes, offset, out, 0, length);
        offset += length;
        return out;
    }

    public boolean readBool() {
        int b = readByte();
        if (b != 0 && b != 1) {
            throw new QuackProtocolException("Invalid boolean byte " + b);
        }
        return b == 1;
    }

    /** Read an unsigned LEB128 integer. Returned as a {@code long} reinterpreted as unsigned 64-bit. */
    public long readUlebLong() {
        long result = 0L;
        int shift = 0;
        for (int i = 0; i < 10; i++) {
            int b = readByte();
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new QuackProtocolException("Unsigned LEB128 value is too long");
    }

    /** Read an unsigned LEB128 integer as {@code int}. Throws if it exceeds {@code Integer.MAX_VALUE}. */
    public int readUlebInt() {
        long value = readUlebLong();
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new QuackProtocolException("Unsigned LEB128 value " + Long.toUnsignedString(value)
                    + " exceeds Integer.MAX_VALUE");
        }
        return (int) value;
    }

    /** Read an unsigned LEB128 integer as {@code BigInteger} (always non-negative). */
    public BigInteger readUlebBigInteger() {
        long value = readUlebLong();
        BigInteger out = BigInteger.valueOf(value);
        if (value < 0L) {
            out = out.add(TWO_POW_64);
        }
        return out;
    }

    /** Read a signed LEB128 integer. */
    public long readSlebLong() {
        long result = 0L;
        int shift = 0;
        int b = 0;
        for (int i = 0; i < 10; i++) {
            b = readByte();
            result |= ((long) (b & 0x7F)) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                if (shift < 64 && (b & 0x40) != 0) {
                    result |= -1L << shift;
                }
                return result;
            }
        }
        throw new QuackProtocolException("Signed LEB128 value is too long");
    }

    public String readString() {
        return new String(readStringBytes(), StandardCharsets.UTF_8);
    }

    public byte[] readStringBytes() {
        int length = readUlebInt();
        return readBytes(length);
    }

    public byte[] readBlob() {
        int length = readUlebInt();
        return readBytes(length);
    }

    public <T> List<T> readList(ListElementReader<T> reader) {
        int length = readUlebInt();
        // Counts come from the peer. A truncated list should fail on the read,
        // without allocating the entire claimed size first.
        List<T> out = new ArrayList<>(Math.min(length, 1024));
        for (int i = 0; i < length; i++) {
            out.add(reader.read(i));
        }
        return out;
    }

    public <T> T readNullable(Supplier<T> reader) {
        return readBool() ? reader.get() : null;
    }

    public HugeIntParts readHugeInt() {
        long upper = readSlebLong();
        long lower = readUlebLong();
        return new HugeIntParts(upper, lower);
    }

    public byte readFixedInt8() {
        ensure(1);
        return bytes[offset++];
    }

    public int readFixedUint8() {
        ensure(1);
        return bytes[offset++] & 0xFF;
    }

    public short readFixedInt16() {
        ensure(2);
        int lo = bytes[offset++] & 0xFF;
        int hi = bytes[offset++] & 0xFF;
        return (short) (lo | (hi << 8));
    }

    public int readFixedUint16() {
        ensure(2);
        int lo = bytes[offset++] & 0xFF;
        int hi = bytes[offset++] & 0xFF;
        return lo | (hi << 8);
    }

    public int readFixedInt32() {
        ensure(4);
        int v = (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
        offset += 4;
        return v;
    }

    public long readFixedUint32() {
        return readFixedInt32() & 0xFFFFFFFFL;
    }

    public float readFixedFloat32() {
        return Float.intBitsToFloat(readFixedInt32());
    }

    public double readFixedFloat64() {
        return Double.longBitsToDouble(readFixedInt64());
    }

    public long readFixedInt64() {
        ensure(8);
        long v = (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24)
                | ((bytes[offset + 4] & 0xFFL) << 32)
                | ((bytes[offset + 5] & 0xFFL) << 40)
                | ((bytes[offset + 6] & 0xFFL) << 48)
                | ((bytes[offset + 7] & 0xFFL) << 56);
        offset += 8;
        return v;
    }

    public long readFixedUint64() {
        return readFixedInt64();
    }

    private void ensure(int length) {
        if (offset + length > bytes.length) {
            throw new QuackProtocolException("Unexpected end of input at offset " + offset
                    + "; needed " + length + " byte(s), have " + remaining());
        }
    }

    @FunctionalInterface
    public interface ListElementReader<T> {
        T read(int index);
    }
}
