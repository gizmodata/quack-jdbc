package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeCodec;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import com.gizmodata.quack.jdbc.type.PhysicalType;
import com.gizmodata.quack.jdbc.type.PhysicalTypeUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decode (and stub-encode) DuckDB DataChunks from the Quack wire format.
 *
 * <p>FLAT vectors of fixed-width primitive logical types decode directly
 * into typed {@link DecodedVector} primitive-array records
 * ({@link DecodedVector.IntVec}, {@link DecodedVector.LongVec}, etc.) to
 * avoid the per-row boxing cost of a generic {@code Object[]}. Logical
 * types whose materialized Java form is not a primitive
 * (DATE → {@code LocalDate}, TIMESTAMP → {@code LocalDateTime},
 * DECIMAL → {@code BigDecimal}, VARCHAR/BLOB, nested types, etc.) fall
 * through to {@link DecodedVector.ObjectVec}.
 *
 * <p>CONSTANT, DICTIONARY, and SEQUENCE vector encodings currently
 * materialize into {@code ObjectVec}; they're uncommon enough in real
 * workloads that the additional code path didn't pay for itself yet.
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static DataChunk decodeDataChunkWrapper(BinaryReader reader) {
        return reader.readObject(() ->
                reader.readRequiredField(300, () -> decodeDataChunk(reader)));
    }

    public static DataChunk decodeDataChunk(BinaryReader reader) {
        return reader.readObject(() -> {
            int rowCount = reader.readRequiredField(100, reader::readUlebInt);
            List<LogicalType> types = reader.readRequiredField(101,
                    () -> reader.readList(i -> LogicalTypeCodec.decode(reader)));
            List<DecodedVector> columns = reader.readRequiredField(102,
                    () -> reader.readList(i -> {
                        if (i >= types.size()) {
                            throw new QuackProtocolException(
                                    "Column vector " + i + " has no matching logical type");
                        }
                        return decodeVector(reader, types.get(i), rowCount);
                    }));
            if (columns.size() != types.size()) {
                throw new QuackProtocolException(
                        "DataChunk declared " + types.size() + " types but serialized "
                                + columns.size() + " columns");
            }
            return new DataChunk(rowCount, types, columns);
        });
    }

    public static DecodedVector decodeVector(BinaryReader reader, LogicalType type, int count) {
        return reader.readObject(() -> decodeVectorBody(reader, type, count));
    }

    private static DecodedVector decodeVectorBody(BinaryReader reader, LogicalType type, int count) {
        int vectorTypeId = reader.readOptionalField(90, reader::readUlebInt, VectorType.FLAT.wireId());
        VectorType vectorType = VectorType.fromWireId(vectorTypeId);
        return switch (vectorType) {
            case FLAT -> decodeFlatVector(reader, type, count);
            case FSST -> throw new QuackUnsupportedTypeException(
                    "FSST-compressed vectors are not yet supported");
            case CONSTANT -> broadcastConstant(reader, type, count);
            case DICTIONARY -> decodeDictionary(reader, type, count);
            case SEQUENCE -> decodeSequence(reader, type, count);
        };
    }

    private static DecodedVector broadcastConstant(BinaryReader reader, LogicalType type, int count) {
        DecodedVector single = decodeVectorBody(reader, type, count > 0 ? 1 : 0);
        Object value = single.size() == 0 ? null : single.getObject(0);
        return broadcastValueTyped(type, value, count);
    }

    private static DecodedVector decodeDictionary(BinaryReader reader, LogicalType type, int count) {
        int[] selection = reader.readRequiredField(91, () -> readSelectionVector(reader, count));
        int dictionaryCount = reader.readRequiredField(92, reader::readUlebInt);
        DecodedVector dictionary = decodeVectorBody(reader, type, dictionaryCount);
        for (int idx : selection) {
            if (idx < 0 || idx >= dictionary.size()) {
                throw new QuackProtocolException("Dictionary selection " + idx + " is out of range");
            }
        }
        return projectTyped(type, dictionary, selection);
    }

    private static DecodedVector decodeSequence(BinaryReader reader, LogicalType type, int count) {
        long start = reader.readRequiredField(91, reader::readSlebLong);
        long increment = reader.readRequiredField(92, reader::readSlebLong);
        return sequenceTyped(type, count, start, increment);
    }

    // ---- typed broadcast / project / sequence ----

    /** Produce a vector of {@code count} rows, every row carrying {@code value}. */
    private static DecodedVector broadcastValueTyped(LogicalType type, Object value, int count) {
        PhysicalType physical = PhysicalTypeUtil.getPhysicalType(type);
        if (!physical.isConstantSize() || needsObjectMaterialization(type, physical)) {
            Object[] arr = new Object[count];
            if (value != null) java.util.Arrays.fill(arr, value);
            return new DecodedVector.ObjectVec(type, arr);
        }
        if (value == null) {
            long[] allNull = new long[Validity.wordCount(count)]; // all zeros = all null
            return zeroFilledPrimitive(type, physical, count, allNull);
        }
        return switch (physical) {
            case BOOL -> {
                boolean[] arr = new boolean[count];
                java.util.Arrays.fill(arr, (Boolean) value);
                yield new DecodedVector.BoolVec(type, arr, null);
            }
            case INT8 -> {
                byte[] arr = new byte[count];
                java.util.Arrays.fill(arr, ((Number) value).byteValue());
                yield new DecodedVector.ByteVec(type, arr, null);
            }
            case UINT8, INT16 -> {
                short[] arr = new short[count];
                java.util.Arrays.fill(arr, ((Number) value).shortValue());
                yield new DecodedVector.ShortVec(type, arr, null);
            }
            case UINT16, INT32 -> {
                int[] arr = new int[count];
                java.util.Arrays.fill(arr, ((Number) value).intValue());
                yield new DecodedVector.IntVec(type, arr, null);
            }
            case UINT32, INT64, UINT64 -> {
                long[] arr = new long[count];
                java.util.Arrays.fill(arr, ((Number) value).longValue());
                yield new DecodedVector.LongVec(type, arr, null);
            }
            case FLOAT -> {
                float[] arr = new float[count];
                java.util.Arrays.fill(arr, ((Number) value).floatValue());
                yield new DecodedVector.FloatVec(type, arr, null);
            }
            case DOUBLE -> {
                double[] arr = new double[count];
                java.util.Arrays.fill(arr, ((Number) value).doubleValue());
                yield new DecodedVector.DoubleVec(type, arr, null);
            }
            default -> {
                // Fall back to object materialization for unusual primitives
                Object[] arr = new Object[count];
                java.util.Arrays.fill(arr, value);
                yield new DecodedVector.ObjectVec(type, arr);
            }
        };
    }

    /** Project a dictionary onto a selection vector, preserving the dictionary's storage type. */
    private static DecodedVector projectTyped(LogicalType type, DecodedVector dictionary, int[] selection) {
        int count = selection.length;
        if (dictionary instanceof DecodedVector.BoolVec d) {
            boolean[] arr = new boolean[count];
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                int idx = selection[i];
                if (d.isNull(idx)) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                } else arr[i] = d.values()[idx];
            }
            return new DecodedVector.BoolVec(type, arr, validity);
        }
        if (dictionary instanceof DecodedVector.ByteVec d) {
            byte[] arr = new byte[count];
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                int idx = selection[i];
                if (d.isNull(idx)) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                } else arr[i] = d.values()[idx];
            }
            return new DecodedVector.ByteVec(type, arr, validity);
        }
        if (dictionary instanceof DecodedVector.ShortVec d) {
            short[] arr = new short[count];
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                int idx = selection[i];
                if (d.isNull(idx)) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                } else arr[i] = d.values()[idx];
            }
            return new DecodedVector.ShortVec(type, arr, validity);
        }
        if (dictionary instanceof DecodedVector.IntVec d) {
            int[] arr = new int[count];
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                int idx = selection[i];
                if (d.isNull(idx)) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                } else arr[i] = d.values()[idx];
            }
            return new DecodedVector.IntVec(type, arr, validity);
        }
        if (dictionary instanceof DecodedVector.LongVec d) {
            long[] arr = new long[count];
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                int idx = selection[i];
                if (d.isNull(idx)) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                } else arr[i] = d.values()[idx];
            }
            return new DecodedVector.LongVec(type, arr, validity);
        }
        if (dictionary instanceof DecodedVector.FloatVec d) {
            float[] arr = new float[count];
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                int idx = selection[i];
                if (d.isNull(idx)) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                } else arr[i] = d.values()[idx];
            }
            return new DecodedVector.FloatVec(type, arr, validity);
        }
        if (dictionary instanceof DecodedVector.DoubleVec d) {
            double[] arr = new double[count];
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                int idx = selection[i];
                if (d.isNull(idx)) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                } else arr[i] = d.values()[idx];
            }
            return new DecodedVector.DoubleVec(type, arr, validity);
        }
        // ObjectVec fallback (and any other future variants)
        Object[] arr = new Object[count];
        for (int i = 0; i < count; i++) arr[i] = dictionary.getObject(selection[i]);
        return new DecodedVector.ObjectVec(type, arr);
    }

    /** Materialize an arithmetic-progression sequence into the right vector for the logical type. */
    private static DecodedVector sequenceTyped(LogicalType type, int count, long start, long increment) {
        // INTEGER and BIGINT get the typed primitive path; other types (DATE,
        // TIMESTAMP, BIGINT-via-decimal) materialize into ObjectVec because the
        // Java value type isn't a primitive.
        if (type.id() == LogicalTypeId.INTEGER) {
            int[] arr = new int[count];
            long v = start;
            for (int i = 0; i < count; i++) {
                arr[i] = (int) v;
                v += increment;
            }
            return new DecodedVector.IntVec(type, arr, null);
        }
        if (type.id() == LogicalTypeId.BIGINT) {
            long[] arr = new long[count];
            long v = start;
            for (int i = 0; i < count; i++) {
                arr[i] = v;
                v += increment;
            }
            return new DecodedVector.LongVec(type, arr, null);
        }
        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            values[i] = decodeSequenceValue(type, start + increment * (long) i);
        }
        return new DecodedVector.ObjectVec(type, values);
    }

    private static DecodedVector zeroFilledPrimitive(LogicalType type, PhysicalType physical,
                                                     int count, long[] validity) {
        return switch (physical) {
            case BOOL -> new DecodedVector.BoolVec(type, new boolean[count], validity);
            case INT8 -> new DecodedVector.ByteVec(type, new byte[count], validity);
            case UINT8, INT16 -> new DecodedVector.ShortVec(type, new short[count], validity);
            case UINT16, INT32 -> new DecodedVector.IntVec(type, new int[count], validity);
            case UINT32, INT64, UINT64 -> new DecodedVector.LongVec(type, new long[count], validity);
            case FLOAT -> new DecodedVector.FloatVec(type, new float[count], validity);
            case DOUBLE -> new DecodedVector.DoubleVec(type, new double[count], validity);
            default -> new DecodedVector.ObjectVec(type, new Object[count]);
        };
    }

    private static DecodedVector decodeFlatVector(BinaryReader reader, LogicalType type, int count) {
        if (type.id() == LogicalTypeId.GEOMETRY && !reader.eof() && reader.peekFieldId() == 99) {
            reader.readRequiredField(99, reader::readUlebInt);
        }
        boolean hasValidity = reader.readRequiredField(100, reader::readBool);
        long[] validity = hasValidity
                ? reader.readRequiredField(101, () -> readValidityMask(reader, count))
                : null;
        PhysicalType physicalType = PhysicalTypeUtil.getPhysicalType(type);

        if (physicalType.isConstantSize()) {
            int byteLength = physicalType.byteWidth() * count;
            byte[] bytes = reader.readRequiredField(102, reader::readBlob);
            if (bytes.length != byteLength) {
                throw new QuackProtocolException(
                        "Fixed-size vector data has " + bytes.length + " bytes, expected " + byteLength);
            }
            return decodeFixedFlatVector(type, physicalType, bytes, count, validity);
        }

        return switch (physicalType) {
            case VARCHAR -> {
                List<byte[]> raw = reader.readRequiredField(102,
                        () -> reader.readList(i -> reader.readStringBytes()));
                Object[] values = new Object[raw.size()];
                for (int i = 0; i < raw.size(); i++) {
                    values[i] = Validity.isValid(validity, i) ? decodeStringLikeValue(type, raw.get(i)) : null;
                }
                yield new DecodedVector.ObjectVec(type, values);
            }
            case STRUCT -> {
                List<ChildType> children = PhysicalTypeUtil.getStructChildren(type);
                List<DecodedVector> childVectors = reader.readRequiredField(103,
                        () -> reader.readList(i -> {
                            if (i >= children.size()) {
                                throw new QuackProtocolException(
                                        "STRUCT child vector " + i + " has no matching type metadata");
                            }
                            return decodeVector(reader, children.get(i).type(), count);
                        }));
                Object[] values = new Object[count];
                for (int row = 0; row < count; row++) {
                    if (!Validity.isValid(validity, row)) {
                        values[row] = null;
                        continue;
                    }
                    Map<String, Object> rowMap = new LinkedHashMap<>();
                    for (int c = 0; c < children.size(); c++) {
                        rowMap.put(children.get(c).name(), childVectors.get(c).getObject(row));
                    }
                    values[row] = rowMap;
                }
                yield new DecodedVector.ObjectVec(type, values);
            }
            case LIST -> {
                int listSize = reader.readRequiredField(104, reader::readUlebInt);
                List<ListEntry> entries = reader.readRequiredField(105,
                        () -> readListEntries(reader, count));
                LogicalType childType = PhysicalTypeUtil.getChildType(type);
                DecodedVector childVector = reader.readRequiredField(106,
                        () -> decodeVector(reader, childType, listSize));
                Object[] values = new Object[count];
                for (int row = 0; row < count; row++) {
                    if (!Validity.isValid(validity, row)) {
                        values[row] = null;
                        continue;
                    }
                    ListEntry e = entries.get(row);
                    List<Object> slice = new ArrayList<>(e.length);
                    for (int k = 0; k < e.length; k++) {
                        slice.add(childVector.getObject(e.offset + k));
                    }
                    values[row] = type.id() == LogicalTypeId.MAP ? toMap(slice) : slice;
                }
                yield new DecodedVector.ObjectVec(type, values);
            }
            case ARRAY -> {
                int arraySize = reader.readRequiredField(103, reader::readUlebInt);
                int expected = PhysicalTypeUtil.getArraySize(type);
                if (arraySize != expected) {
                    throw new QuackProtocolException("ARRAY vector serialized size " + arraySize
                            + ", expected " + expected);
                }
                LogicalType childType = PhysicalTypeUtil.getChildType(type);
                DecodedVector childVector = reader.readRequiredField(104,
                        () -> decodeVector(reader, childType, arraySize * count));
                Object[] values = new Object[count];
                for (int row = 0; row < count; row++) {
                    if (!Validity.isValid(validity, row)) {
                        values[row] = null;
                        continue;
                    }
                    int offset = row * arraySize;
                    List<Object> slice = new ArrayList<>(arraySize);
                    for (int k = 0; k < arraySize; k++) {
                        slice.add(childVector.getObject(offset + k));
                    }
                    values[row] = slice;
                }
                yield new DecodedVector.ObjectVec(type, values);
            }
            default -> throw new QuackUnsupportedTypeException(
                    "Variable-width physical type " + physicalType + " is not supported");
        };
    }

    private static Map<Object, Object> toMap(List<Object> entries) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> kv) {
                map.put(kv.get("key"), kv.get("value"));
            }
        }
        return map;
    }

    // ---- typed fixed-flat decoding ----

    private static DecodedVector decodeFixedFlatVector(LogicalType type, PhysicalType physicalType,
                                                       byte[] bytes, int count, long[] validity) {
        BinaryReader reader = new BinaryReader(bytes);

        // Logical types that materialize into non-primitive Java objects always go via ObjectVec.
        if (needsObjectMaterialization(type, physicalType)) {
            Object[] values = new Object[count];
            for (int i = 0; i < count; i++) {
                Object v = decodeFixedValue(reader, type, physicalType);
                values[i] = Validity.isValid(validity, i) ? v : null;
            }
            reader.assertEof();
            return new DecodedVector.ObjectVec(type, values);
        }

        // Primitive path: write directly into the right typed array.
        DecodedVector vec = switch (physicalType) {
            case BOOL -> {
                boolean[] arr = new boolean[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedUint8() != 0;
                yield new DecodedVector.BoolVec(type, arr, validity);
            }
            case INT8 -> {
                byte[] arr = new byte[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedInt8();
                yield new DecodedVector.ByteVec(type, arr, validity);
            }
            case UINT8 -> {
                short[] arr = new short[count];
                for (int i = 0; i < count; i++) arr[i] = (short) reader.readFixedUint8();
                yield new DecodedVector.ShortVec(type, arr, validity);
            }
            case INT16 -> {
                short[] arr = new short[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedInt16();
                yield new DecodedVector.ShortVec(type, arr, validity);
            }
            case UINT16 -> {
                int[] arr = new int[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedUint16();
                yield new DecodedVector.IntVec(type, arr, validity);
            }
            case INT32 -> {
                int[] arr = new int[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedInt32();
                yield new DecodedVector.IntVec(type, arr, validity);
            }
            case UINT32 -> {
                long[] arr = new long[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedUint32();
                yield new DecodedVector.LongVec(type, arr, validity);
            }
            case INT64, UINT64 -> {
                long[] arr = new long[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedInt64();
                yield new DecodedVector.LongVec(type, arr, validity);
            }
            case FLOAT -> {
                float[] arr = new float[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedFloat32();
                yield new DecodedVector.FloatVec(type, arr, validity);
            }
            case DOUBLE -> {
                double[] arr = new double[count];
                for (int i = 0; i < count; i++) arr[i] = reader.readFixedFloat64();
                yield new DecodedVector.DoubleVec(type, arr, validity);
            }
            default -> throw new QuackUnsupportedTypeException(
                    "Primitive vector path not implemented for physical type " + physicalType);
        };
        reader.assertEof();
        return vec;
    }

    /**
     * True when the logical type requires per-row Java object
     * materialization (DECIMAL, DATE, TIME, TIMESTAMP variants, UUID,
     * INTERVAL, HUGEINT family, ENUM) so we can't use a primitive array.
     */
    private static boolean needsObjectMaterialization(LogicalType type, PhysicalType physicalType) {
        return switch (type.id()) {
            case DECIMAL, DATE, TIME, TIME_NS, TIME_TZ,
                 TIMESTAMP, TIMESTAMP_SEC, TIMESTAMP_MS, TIMESTAMP_NS, TIMESTAMP_TZ,
                 UUID, INTERVAL, HUGEINT, UHUGEINT, ENUM -> true;
            default -> physicalType == PhysicalType.INTERVAL
                    || physicalType == PhysicalType.INT128
                    || physicalType == PhysicalType.UINT128;
        };
    }

    private static Object decodeFixedValue(BinaryReader reader, LogicalType type, PhysicalType physicalType) {
        return switch (physicalType) {
            case BOOL -> reader.readFixedUint8() != 0;
            case INT8 -> (int) reader.readFixedInt8();
            case UINT8 -> decodeEnumOrInt(type, reader.readFixedUint8());
            case INT16 -> {
                int value = reader.readFixedInt16();
                yield type.id() == LogicalTypeId.DECIMAL
                        ? decimalFromUnscaled(type, BigInteger.valueOf(value))
                        : (Object) value;
            }
            case UINT16 -> decodeEnumOrInt(type, reader.readFixedUint16());
            case INT32 -> {
                int value = reader.readFixedInt32();
                if (type.id() == LogicalTypeId.DATE) {
                    yield LocalDate.ofEpochDay(value);
                }
                yield type.id() == LogicalTypeId.DECIMAL
                        ? decimalFromUnscaled(type, BigInteger.valueOf(value))
                        : (Object) value;
            }
            case UINT32 -> decodeEnumOrLong(type, reader.readFixedUint32());
            case INT64 -> decodeInt64LogicalValue(type, reader.readFixedInt64());
            case UINT64 -> reader.readFixedUint64();
            case FLOAT -> reader.readFixedFloat32();
            case DOUBLE -> reader.readFixedFloat64();
            case INT128 -> {
                long lower = reader.readFixedUint64();
                long upper = reader.readFixedInt64();
                if (type.id() == LogicalTypeId.UUID) {
                    yield uuidFromHugeIntParts(upper, lower);
                }
                BigInteger value = new HugeIntParts(upper, lower).toSignedBigInteger();
                yield type.id() == LogicalTypeId.DECIMAL ? decimalFromUnscaled(type, value) : (Object) value;
            }
            case UINT128 -> {
                long lower = reader.readFixedUint64();
                long upper = reader.readFixedUint64();
                yield new HugeIntParts(upper, lower).toUnsignedBigInteger();
            }
            case INTERVAL -> new IntervalValue(
                    reader.readFixedInt32(),
                    reader.readFixedInt32(),
                    reader.readFixedInt64());
            default -> throw new QuackUnsupportedTypeException(
                    "Cannot decode fixed physical type " + physicalType);
        };
    }

    private static Object decodeSequenceValue(LogicalType type, long value) {
        return switch (type.id()) {
            case INTEGER -> (int) value;
            case DATE -> LocalDate.ofEpochDay(value);
            case BIGINT -> value;
            default -> decodeInt64LogicalValue(type, value);
        };
    }

    private static Object decodeInt64LogicalValue(LogicalType type, long value) {
        return switch (type.id()) {
            case TIME -> microsToLocalTime(value);
            case TIME_NS -> LocalTime.ofNanoOfDay(value);
            case TIME_TZ -> value;
            case TIMESTAMP_SEC -> LocalDateTime.ofInstant(Instant.ofEpochSecond(value), ZoneOffset.UTC);
            case TIMESTAMP_MS -> LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC);
            case TIMESTAMP -> microsToLocalDateTime(value);
            case TIMESTAMP_NS -> LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(value / 1_000_000_000L, value % 1_000_000_000L), ZoneOffset.UTC);
            case TIMESTAMP_TZ -> OffsetDateTime.ofInstant(microsToInstant(value), ZoneOffset.UTC);
            case DECIMAL -> decimalFromUnscaled(type, BigInteger.valueOf(value));
            default -> value;
        };
    }

    private static LocalTime microsToLocalTime(long micros) {
        long nanos = Math.multiplyExact(micros, 1_000L);
        return LocalTime.ofNanoOfDay(Math.floorMod(nanos, 86_400L * 1_000_000_000L));
    }

    private static LocalDateTime microsToLocalDateTime(long micros) {
        return LocalDateTime.ofInstant(microsToInstant(micros), ZoneOffset.UTC);
    }

    private static Instant microsToInstant(long micros) {
        long seconds = Math.floorDiv(micros, 1_000_000L);
        long microsPart = Math.floorMod(micros, 1_000_000L);
        return Instant.ofEpochSecond(seconds, microsPart * 1_000L);
    }

    private static Object decodeEnumOrInt(LogicalType type, int index) {
        if (type.id() != LogicalTypeId.ENUM) return index;
        List<String> values = PhysicalTypeUtil.getEnumValues(type);
        if (index < 0 || index >= values.size()) {
            throw new QuackProtocolException("ENUM index " + index + " is out of range");
        }
        return values.get(index);
    }

    private static Object decodeEnumOrLong(LogicalType type, long index) {
        if (type.id() != LogicalTypeId.ENUM) return index;
        if (index < 0 || index >= Integer.MAX_VALUE) {
            throw new QuackProtocolException("ENUM index " + index + " is out of range");
        }
        return decodeEnumOrInt(type, (int) index);
    }

    private static Object decodeStringLikeValue(LogicalType type, byte[] raw) {
        return switch (type.id()) {
            case BLOB, GEOMETRY, BIT -> raw;
            default -> new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    private static BigDecimal decimalFromUnscaled(LogicalType type, BigInteger value) {
        ExtraTypeInfo info = type.typeInfo().orElseThrow(
                () -> new QuackProtocolException("DECIMAL value is missing DecimalTypeInfo"));
        if (!(info instanceof ExtraTypeInfo.Decimal d)) {
            throw new QuackProtocolException("DECIMAL value is missing DecimalTypeInfo");
        }
        return new BigDecimal(value, d.scale());
    }

    private static UUID uuidFromHugeIntParts(long upper, long lower) {
        long displayUpper = upper ^ (1L << 63);
        return new UUID(displayUpper, lower);
    }

    private static int[] readSelectionVector(BinaryReader reader, int count) {
        int expectedBytes = count * 4;
        byte[] bytes = reader.readBlob();
        if (bytes.length != expectedBytes) {
            throw new QuackProtocolException("Selection vector has " + bytes.length
                    + " bytes, expected " + expectedBytes);
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int o = i * 4;
            out[i] = (bytes[o] & 0xFF)
                    | ((bytes[o + 1] & 0xFF) << 8)
                    | ((bytes[o + 2] & 0xFF) << 16)
                    | ((bytes[o + 3] & 0xFF) << 24);
        }
        return out;
    }

    private static long[] readValidityMask(BinaryReader reader, int count) {
        int expected = Validity.wireByteCount(count);
        byte[] bytes = reader.readBlob();
        if (bytes.length != expected) {
            throw new QuackProtocolException("Validity mask has " + bytes.length
                    + " bytes, expected " + expected);
        }
        return Validity.fromBytes(bytes, count);
    }

    private static List<ListEntry> readListEntries(BinaryReader reader, int count) {
        List<ListEntry> entries = reader.readList(i -> reader.readObject(() -> new ListEntry(
                reader.readRequiredField(100, reader::readUlebInt),
                reader.readRequiredField(101, reader::readUlebInt))));
        if (entries.size() != count) {
            throw new QuackProtocolException("LIST vector serialized " + entries.size()
                    + " entries for " + count + " rows");
        }
        return entries;
    }

    // ---- encoder ----

    public static void encodeDataChunkWrapper(BinaryWriter writer, DataChunk chunk) {
        writer.writeObject(obj -> obj.writeField(300, () -> encodeDataChunk(obj, chunk)));
    }

    public static void encodeDataChunk(BinaryWriter writer, DataChunk chunk) {
        if (chunk.types().size() != chunk.columns().size()) {
            throw new QuackProtocolException("DataChunk type count must match column count");
        }
        for (int i = 0; i < chunk.columns().size(); i++) {
            if (chunk.columns().get(i).size() != chunk.rowCount()) {
                throw new QuackProtocolException(
                        "Column " + i + " has " + chunk.columns().get(i).size()
                                + " rows, DataChunk declares " + chunk.rowCount());
            }
        }
        writer.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeUleb(chunk.rowCount()));
            obj.writeField(101, () -> obj.writeList(chunk.types(),
                    (t, i) -> LogicalTypeCodec.encode(obj, t)));
            obj.writeField(102, () -> obj.writeList(chunk.columns(),
                    (col, i) -> encodeVector(obj, chunk.types().get(i), col)));
        });
    }

    public static void encodeVector(BinaryWriter writer, LogicalType type, DecodedVector vector) {
        writer.writeObject(obj -> encodeFlatVectorBody(obj, type, vector));
    }

    private static void encodeFlatVectorBody(BinaryWriter writer, LogicalType type, DecodedVector vector) {
        int count = vector.size();
        long[] validity = extractValidity(vector);
        boolean hasNulls = validity != null;
        writer.writeField(100, () -> writer.writeBool(hasNulls));
        if (hasNulls) {
            byte[] bytes = Validity.toBytes(validity, count);
            writer.writeField(101, () -> writer.writeBlob(bytes));
        }
        PhysicalType physical = PhysicalTypeUtil.getPhysicalType(type);
        if (physical.isConstantSize()) {
            byte[] bytes = encodeFixedBytes(type, physical, vector);
            writer.writeField(102, () -> writer.writeBlob(bytes));
            return;
        }
        switch (physical) {
            case VARCHAR -> writer.writeField(102, () -> {
                writer.writeUleb(count);
                for (int i = 0; i < count; i++) {
                    writer.writeStringBytes(encodeStringLikeValueForWrite(type, vector.getObject(i)));
                }
            });
            case STRUCT -> encodeStructChildren(writer, type, vector, count);
            case LIST -> encodeListChild(writer, type, vector, count);
            case ARRAY -> encodeArrayChild(writer, type, vector, count);
            default -> throw new QuackUnsupportedTypeException(
                    "Encoding physical type " + physical + " is not yet supported");
        }
    }

    private static void encodeStructChildren(BinaryWriter writer, LogicalType type,
                                             DecodedVector vector, int count) {
        List<ChildType> children = PhysicalTypeUtil.getStructChildren(type);
        writer.writeField(103, () -> writer.writeList(children, (child, ci) -> {
            Object[] childValues = new Object[count];
            for (int r = 0; r < count; r++) {
                Object row = vector.isNull(r) ? null : vector.getObject(r);
                if (row != null && !(row instanceof Map<?, ?>)) {
                    throw new QuackProtocolException("Expected a map value for STRUCT row " + r
                            + ", got " + row.getClass().getName());
                }
                childValues[r] = (row instanceof Map<?, ?> m) ? m.get(child.name()) : null;
            }
            encodeVector(writer, child.type(), new DecodedVector.ObjectVec(child.type(), childValues));
        }));
    }

    private static void encodeListChild(BinaryWriter writer, LogicalType type,
                                        DecodedVector vector, int count) {
        LogicalType childType = PhysicalTypeUtil.getChildType(type);
        List<Object> flat = new ArrayList<>();
        List<ListEntry> entries = new ArrayList<>(count);
        int offset = 0;
        for (int r = 0; r < count; r++) {
            List<Object> elements = listElements(type, vector.isNull(r) ? null : vector.getObject(r));
            entries.add(new ListEntry(offset, elements.size()));
            flat.addAll(elements);
            offset += elements.size();
        }
        int listSize = offset;
        writer.writeField(104, () -> writer.writeUleb(listSize));
        writer.writeField(105, () -> writer.writeList(entries, (e, i) -> writer.writeObject(o -> {
            o.writeField(100, () -> o.writeUleb(e.offset()));
            o.writeField(101, () -> o.writeUleb(e.length()));
        })));
        writer.writeField(106, () -> encodeVector(writer, childType,
                new DecodedVector.ObjectVec(childType, flat.toArray())));
    }

    private static void encodeArrayChild(BinaryWriter writer, LogicalType type,
                                         DecodedVector vector, int count) {
        int arraySize = PhysicalTypeUtil.getArraySize(type);
        LogicalType childType = PhysicalTypeUtil.getChildType(type);
        Object[] flat = new Object[count * arraySize];
        for (int r = 0; r < count; r++) {
            Object row = vector.isNull(r) ? null : vector.getObject(r);
            if (row == null) {
                continue;
            }
            if (!(row instanceof List<?> list) || list.size() != arraySize) {
                throw new QuackProtocolException("ARRAY row " + r + " must have exactly "
                        + arraySize + " elements");
            }
            for (int k = 0; k < arraySize; k++) {
                flat[r * arraySize + k] = list.get(k);
            }
        }
        writer.writeField(103, () -> writer.writeUleb(arraySize));
        writer.writeField(104, () -> encodeVector(writer, childType,
                new DecodedVector.ObjectVec(childType, flat)));
    }

    private static List<Object> listElements(LogicalType type, Object rowValue) {
        if (rowValue == null) {
            return List.of();
        }
        if (type.id() == LogicalTypeId.MAP && rowValue instanceof Map<?, ?> map) {
            List<Object> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Map<String, Object> kv = new LinkedHashMap<>();
                kv.put("key", e.getKey());
                kv.put("value", e.getValue());
                entries.add(kv);
            }
            return entries;
        }
        if (rowValue instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new QuackProtocolException("Expected a list or map value for " + type.id()
                + ", got " + rowValue.getClass().getName());
    }

    private static long[] extractValidity(DecodedVector vector) {
        if (vector instanceof DecodedVector.BoolVec v) return v.validity();
        if (vector instanceof DecodedVector.ByteVec v) return v.validity();
        if (vector instanceof DecodedVector.ShortVec v) return v.validity();
        if (vector instanceof DecodedVector.IntVec v) return v.validity();
        if (vector instanceof DecodedVector.LongVec v) return v.validity();
        if (vector instanceof DecodedVector.FloatVec v) return v.validity();
        if (vector instanceof DecodedVector.DoubleVec v) return v.validity();
        if (vector instanceof DecodedVector.ObjectVec ov) {
            int count = ov.values().length;
            long[] validity = null;
            for (int i = 0; i < count; i++) {
                if (ov.values()[i] == null) {
                    if (validity == null) validity = Validity.allValid(count);
                    Validity.setNull(validity, i);
                }
            }
            return validity;
        }
        return null;
    }

    private static byte[] encodeFixedBytes(LogicalType type, PhysicalType physical, DecodedVector vector) {
        int rows = vector.size();
        BinaryWriter buf = new BinaryWriter(Math.max(16, physical.byteWidth() * rows));
        for (int i = 0; i < rows; i++) {
            Object v = vector.isNull(i) ? null : vector.getObject(i);
            encodeFixedValueForWrite(buf, type, physical, v);
        }
        return buf.toByteArray();
    }

    private static void encodeFixedValueForWrite(BinaryWriter buf, LogicalType type,
                                                 PhysicalType physical, Object value) {
        switch (physical) {
            case BOOL -> buf.writeFixedUint8(value != null && (Boolean) value ? 1 : 0);
            case INT8 -> buf.writeFixedInt8(value == null ? 0 : ((Number) value).byteValue());
            case UINT8 -> buf.writeFixedUint8(encodeEnumOrInt(type, value, 0));
            case INT16 -> {
                if (type.id() == LogicalTypeId.DECIMAL) {
                    buf.writeFixedInt16(value == null ? 0 : decimalUnscaled(type, value).intValueExact());
                } else {
                    buf.writeFixedInt16(value == null ? 0 : ((Number) value).shortValue());
                }
            }
            case UINT16 -> buf.writeFixedUint16(encodeEnumOrInt(type, value, 0));
            case INT32 -> {
                if (type.id() == LogicalTypeId.DATE) {
                    buf.writeFixedInt32(value == null ? 0 : (int) ((LocalDate) value).toEpochDay());
                } else if (type.id() == LogicalTypeId.DECIMAL) {
                    buf.writeFixedInt32(value == null ? 0 : decimalUnscaled(type, value).intValueExact());
                } else {
                    buf.writeFixedInt32(value == null ? 0 : ((Number) value).intValue());
                }
            }
            case UINT32 -> buf.writeFixedUint32(encodeEnumOrLong(type, value, 0L));
            case INT64 -> buf.writeFixedInt64(encodeInt64LogicalValueForWrite(type, value));
            case UINT64 -> buf.writeFixedUint64(value == null ? 0L : ((Number) value).longValue());
            case FLOAT -> buf.writeFixedFloat32(value == null ? 0f : ((Number) value).floatValue());
            case DOUBLE -> buf.writeFixedFloat64(value == null ? 0d : ((Number) value).doubleValue());
            case INT128 -> {
                HugeIntParts parts;
                if (value == null) {
                    parts = new HugeIntParts(0L, 0L);
                } else if (type.id() == LogicalTypeId.UUID) {
                    parts = uuidToHugeIntParts((UUID) value);
                } else if (type.id() == LogicalTypeId.DECIMAL) {
                    parts = HugeIntParts.ofSigned(decimalUnscaled(type, value));
                } else {
                    parts = HugeIntParts.ofSigned((BigInteger) value);
                }
                buf.writeFixedUint64(parts.lower());
                buf.writeFixedInt64(parts.upper());
            }
            case UINT128 -> {
                BigInteger v = value == null ? BigInteger.ZERO : (BigInteger) value;
                BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
                long lower = v.and(mask).longValue();
                long upper = v.shiftRight(64).and(mask).longValue();
                buf.writeFixedUint64(lower);
                buf.writeFixedUint64(upper);
            }
            case INTERVAL -> {
                IntervalValue iv = value == null ? new IntervalValue(0, 0, 0L) : (IntervalValue) value;
                buf.writeFixedInt32(iv.months());
                buf.writeFixedInt32(iv.days());
                buf.writeFixedInt64(iv.micros());
            }
            default -> throw new QuackUnsupportedTypeException(
                    "Cannot encode fixed physical type " + physical);
        }
    }

    private static long encodeInt64LogicalValueForWrite(LogicalType type, Object value) {
        if (value == null) return 0L;
        return switch (type.id()) {
            case TIME -> ((LocalTime) value).toNanoOfDay() / 1_000L;
            case TIME_NS -> ((LocalTime) value).toNanoOfDay();
            case TIMESTAMP_SEC -> ((LocalDateTime) value).toEpochSecond(ZoneOffset.UTC);
            case TIMESTAMP_MS -> ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toEpochMilli();
            case TIMESTAMP -> instantToMicros(((LocalDateTime) value).toInstant(ZoneOffset.UTC));
            case TIMESTAMP_NS -> {
                Instant inst = ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
                yield Math.multiplyExact(inst.getEpochSecond(), 1_000_000_000L) + inst.getNano();
            }
            case TIMESTAMP_TZ -> instantToMicros(((OffsetDateTime) value).toInstant());
            case TIME_TZ -> ((Number) value).longValue();
            case DECIMAL -> decimalUnscaled(type, value).longValueExact();
            default -> ((Number) value).longValue();
        };
    }

    private static long instantToMicros(Instant inst) {
        return Math.multiplyExact(inst.getEpochSecond(), 1_000_000L) + inst.getNano() / 1_000L;
    }

    private static int encodeEnumOrInt(LogicalType type, Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (type.id() == LogicalTypeId.ENUM) {
            List<String> values = PhysicalTypeUtil.getEnumValues(type);
            int idx = values.indexOf(value.toString());
            if (idx < 0) throw new QuackProtocolException("Unknown ENUM value: " + value);
            return idx;
        }
        return ((Number) value).intValue();
    }

    private static long encodeEnumOrLong(LogicalType type, Object value, long defaultValue) {
        if (value == null) return defaultValue;
        return encodeEnumOrInt(type, value, 0);
    }

    private static BigInteger decimalUnscaled(LogicalType type, Object value) {
        BigDecimal bd;
        if (value instanceof BigDecimal d) bd = d;
        else if (value instanceof Number n) bd = BigDecimal.valueOf(n.doubleValue());
        else if (value instanceof String s) bd = new BigDecimal(s);
        else throw new QuackProtocolException("Cannot encode " + value + " as DECIMAL");
        ExtraTypeInfo info = type.typeInfo().orElseThrow(
                () -> new QuackProtocolException("DECIMAL value is missing DecimalTypeInfo"));
        if (!(info instanceof ExtraTypeInfo.Decimal d)) {
            throw new QuackProtocolException("DECIMAL value is missing DecimalTypeInfo");
        }
        return bd.setScale(d.scale(), java.math.RoundingMode.HALF_UP).unscaledValue();
    }

    private static HugeIntParts uuidToHugeIntParts(UUID uuid) {
        if (uuid == null) return new HugeIntParts(0L, 0L);
        long displayUpper = uuid.getMostSignificantBits();
        long upper = displayUpper ^ (1L << 63);
        return new HugeIntParts(upper, uuid.getLeastSignificantBits());
    }

    private static byte[] encodeStringLikeValueForWrite(LogicalType type, Object value) {
        if (value == null) return new byte[0];
        if (value instanceof byte[] b) return b;
        return value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private record ListEntry(int offset, int length) {
    }
}
