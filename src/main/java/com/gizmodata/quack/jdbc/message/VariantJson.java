package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Renders one DuckDB VARIANT value as JSON text, the way DuckDB's own
 * {@code variant::JSON} cast does ({@code src/function/cast/variant/to_json.cpp}).
 *
 * <p>Quack sends a VARIANT as DuckDB's internal storage struct:
 * <pre>
 * STRUCT(keys VARCHAR[],
 *        children STRUCT(keys_index UINTEGER, values_index UINTEGER)[],
 *        values STRUCT(type_id UTINYINT, byte_offset UINTEGER)[],
 *        data BLOB)
 * </pre>
 * {@code values[0]} is the root. Each value's bytes start at its
 * {@code byte_offset} in {@code data}; objects and arrays hold a varint
 * child count and the index of their first entry in {@code children}.
 */
public final class VariantJson {

    // DuckDB's VariantLogicalType ids (src/include/duckdb/common/types/variant.hpp).
    private static final int NULL = 0, TRUE = 1, FALSE = 2, INT8 = 3, INT16 = 4, INT32 = 5, INT64 = 6,
            INT128 = 7, UINT8 = 8, UINT16 = 9, UINT32 = 10, UINT64 = 11, UINT128 = 12, FLOAT = 13,
            DOUBLE = 14, DECIMAL = 15, VARCHAR = 16, BLOB = 17, UUID = 18, DATE = 19, TIME_MICROS = 20,
            TIME_NANOS = 21, TIMESTAMP_SEC = 22, TIMESTAMP_MILIS = 23, TIMESTAMP_MICROS = 24,
            TIMESTAMP_NANOS = 25, TIME_MICROS_TZ = 26, TIMESTAMP_MICROS_TZ = 27, INTERVAL = 28,
            OBJECT = 29, ARRAY = 30, BIGNUM = 31, BITSTRING = 32, GEOMETRY = 33, TIMESTAMP_NANOS_TZ = 34;

    private static final int MAX_DEPTH = 1000;

    private final List<?> keys;
    private final List<?> children;
    private final List<?> values;
    private final byte[] data;
    private final StringBuilder out = new StringBuilder();

    private VariantJson(Map<String, Object> row) {
        this.keys = listField(row, "keys");
        this.children = listField(row, "children");
        this.values = listField(row, "values");
        Object d = row.get("data");
        this.data = d == null ? new byte[0] : (byte[]) d;
        if (values.isEmpty()) {
            throw new QuackProtocolException("VARIANT row has no values");
        }
    }

    /** JSON text of a decoded VARIANT storage row; the JSON literal {@code null} for a VARIANT null. */
    public static String toJson(Map<String, Object> row) {
        VariantJson v = new VariantJson(row);
        v.write(0, 0);
        return v.out.toString();
    }

    private static List<?> listField(Map<String, Object> row, String name) {
        Object v = row.get(name);
        if (v == null) return List.of();
        if (!(v instanceof List<?> l)) {
            throw new QuackProtocolException("VARIANT field " + name + " is " + v.getClass().getName());
        }
        return l;
    }

    private static long num(Object struct, String field) {
        if (!(struct instanceof Map<?, ?> m) || !(m.get(field) instanceof Number n)) {
            throw new QuackProtocolException("VARIANT entry has no " + field);
        }
        return n.longValue();
    }

    private void write(long valueIndex, int depth) {
        if (depth > MAX_DEPTH) throw new QuackProtocolException("VARIANT nesting is too deep");
        if (valueIndex < 0 || valueIndex >= values.size()) {
            throw new QuackProtocolException("VARIANT values_index " + valueIndex + " is out of range");
        }
        Object entry = values.get((int) valueIndex);
        int type = (int) num(entry, "type_id");
        int off = (int) num(entry, "byte_offset");
        switch (type) {
            case NULL -> out.append("null");
            case TRUE -> out.append("true");
            case FALSE -> out.append("false");
            case INT8 -> out.append(le(off, 1).get());
            case INT16 -> out.append(le(off, 2).getShort());
            case INT32 -> out.append(le(off, 4).getInt());
            case INT64 -> out.append(le(off, 8).getLong());
            case UINT8 -> out.append(Byte.toUnsignedInt(le(off, 1).get()));
            case UINT16 -> out.append(Short.toUnsignedInt(le(off, 2).getShort()));
            case UINT32 -> out.append(Integer.toUnsignedLong(le(off, 4).getInt()));
            case UINT64 -> out.append(Long.toUnsignedString(le(off, 8).getLong()));
            case INT128 -> out.append(hugeint(off).toSignedBigInteger());
            case UINT128 -> out.append(hugeint(off).toUnsignedBigInteger());
            case FLOAT -> out.append(formatDouble(le(off, 4).getFloat()));
            case DOUBLE -> out.append(formatDouble(le(off, 8).getDouble()));
            case DECIMAL -> out.append(decimal(off).toPlainString());
            case VARCHAR -> quote(new String(lengthPrefixed(off), StandardCharsets.UTF_8));
            case BLOB -> quote(blobToString(lengthPrefixed(off)));
            case BITSTRING -> quote(bitToString(lengthPrefixed(off)));
            case UUID -> {
                HugeIntParts h = hugeint(off);
                quote(new java.util.UUID(h.upper() ^ (1L << 63), h.lower()).toString());
            }
            case DATE -> quote(formatDate(le(off, 4).getInt()));
            case TIME_MICROS -> quote(formatTimeOfDay(le(off, 8).getLong(), 6));
            case TIME_NANOS -> quote(formatTimeOfDay(le(off, 8).getLong(), 9));
            case TIME_MICROS_TZ -> quote(formatTimeTz(le(off, 8).getLong()));
            case TIMESTAMP_SEC -> quote(formatTimestamp(le(off, 8).getLong(), 1L, 0));
            case TIMESTAMP_MILIS -> quote(formatTimestamp(le(off, 8).getLong(), 1_000L, 3));
            case TIMESTAMP_MICROS -> quote(formatTimestamp(le(off, 8).getLong(), 1_000_000L, 6));
            case TIMESTAMP_NANOS -> quote(formatTimestamp(le(off, 8).getLong(), 1_000_000_000L, 9));
            case TIMESTAMP_MICROS_TZ -> quote(withUtc(formatTimestamp(le(off, 8).getLong(), 1_000_000L, 6)));
            case TIMESTAMP_NANOS_TZ -> quote(withUtc(formatTimestamp(le(off, 8).getLong(), 1_000_000_000L, 9)));
            case INTERVAL -> {
                ByteBuffer b = le(off, 16);
                quote(formatInterval(b.getInt(), b.getInt(), b.getLong()));
            }
            case OBJECT, ARRAY -> writeNested(type == OBJECT, off, depth);
            case BIGNUM -> throw unsupported("BIGNUM");
            case GEOMETRY -> throw unsupported("GEOMETRY");
            default -> throw unsupported("type " + type);
        }
    }

    private static QuackUnsupportedTypeException unsupported(String what) {
        return new QuackUnsupportedTypeException("A VARIANT holding a " + what
                + " value cannot be read as JSON by this driver; cast the column to JSON or VARCHAR in the query");
    }

    /**
     * Objects print with their keys sorted bytewise, as DuckDB 2.0's
     * {@code variant::JSON} does (VariantIterationOrder::LEXICOGRAPHIC);
     * arrays keep their order.
     */
    private void writeNested(boolean object, int off, int depth) {
        int[] pos = {off};
        long count = uvarint(pos);
        long first = count > 0 ? uvarint(pos) : 0;
        if (first + count > children.size()) {
            throw new QuackProtocolException("VARIANT children are out of range");
        }
        if (!object) {
            out.append('[');
            for (long i = 0; i < count; i++) {
                if (i > 0) out.append(',');
                write(num(children.get((int) (first + i)), "values_index"), depth + 1);
            }
            out.append(']');
            return;
        }
        List<Map.Entry<byte[], Long>> entries = new java.util.ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            Object child = children.get((int) (first + i));
            Object ki = child instanceof Map<?, ?> m ? m.get("keys_index") : null;
            if (!(ki instanceof Number n) || n.longValue() < 0 || n.longValue() >= keys.size()) {
                throw new QuackProtocolException("VARIANT object child has no key");
            }
            byte[] key = String.valueOf(keys.get(n.intValue())).getBytes(StandardCharsets.UTF_8);
            entries.add(Map.entry(key, num(child, "values_index")));
        }
        entries.sort((a, b) -> java.util.Arrays.compareUnsigned(a.getKey(), b.getKey()));
        out.append('{');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            quote(new String(entries.get(i).getKey(), StandardCharsets.UTF_8));
            out.append(':');
            write(entries.get(i).getValue(), depth + 1);
        }
        out.append('}');
    }

    private ByteBuffer le(int off, int width) {
        if (off < 0 || off + width > data.length) {
            throw new QuackProtocolException("VARIANT value at byte " + off + " overruns the data");
        }
        return ByteBuffer.wrap(data, off, width).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private HugeIntParts hugeint(int off) {
        ByteBuffer b = le(off, 16);
        long lower = b.getLong();
        long upper = b.getLong();
        return new HugeIntParts(upper, lower);
    }

    private long uvarint(int[] pos) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= data.length) throw new QuackProtocolException("VARIANT varint overruns the data");
            int b = data[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new QuackProtocolException("VARIANT varint is too long");
    }

    private byte[] lengthPrefixed(int off) {
        int[] pos = {off};
        long n = uvarint(pos);
        if (n > data.length - pos[0]) throw new QuackProtocolException("VARIANT string overruns the data");
        return java.util.Arrays.copyOfRange(data, pos[0], pos[0] + (int) n);
    }

    private BigDecimal decimal(int off) {
        int[] pos = {off};
        long width = uvarint(pos);
        int scale = (int) uvarint(pos);
        BigInteger unscaled;
        if (width <= 4) unscaled = BigInteger.valueOf(le(pos[0], 2).getShort());
        else if (width <= 9) unscaled = BigInteger.valueOf(le(pos[0], 4).getInt());
        else if (width <= 18) unscaled = BigInteger.valueOf(le(pos[0], 8).getLong());
        else unscaled = hugeint(pos[0]).toSignedBigInteger();
        return new BigDecimal(unscaled, scale);
    }

    // ---- JSON text, as yyjson writes it -------------------------------------------------

    private void quote(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04X", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /**
     * A double as yyjson writes it with {@code YYJSON_WRITE_ALLOW_INF_AND_NAN}:
     * shortest round-trip digits, plain notation while the decimal point
     * falls within (-6, 21] digits of the first digit, else {@code 1.5e-7}.
     */
    static String formatDouble(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        String sign = (Double.doubleToRawLongBits(d) < 0) ? "-" : "";
        if (d == 0) return sign + "0.0";
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int dotPos = digits.length() - bd.scale();
        StringBuilder sb = new StringBuilder(sign);
        if (-6 < dotPos && dotPos <= 21) {
            if (dotPos <= 0) {
                sb.append("0.").append("0".repeat(-dotPos)).append(digits);
            } else if (digits.length() <= dotPos) {
                sb.append(digits).append("0".repeat(dotPos - digits.length())).append(".0");
            } else {
                sb.append(digits, 0, dotPos).append('.').append(digits, dotPos, digits.length());
            }
        } else {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
            sb.append('e').append(dotPos - 1);
        }
        return sb.toString();
    }

    // ---- DuckDB's Value::ToString formats ------------------------------------------------

    private static String blobToString(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length);
        for (byte b : raw) {
            int c = b & 0xFF;
            if (c >= 32 && c <= 126 && c != '\\' && c != '\'' && c != '"') sb.append((char) c);
            else sb.append(String.format("\\x%02X", c));
        }
        return sb.toString();
    }

    private static String bitToString(byte[] raw) {
        if (raw.length == 0) return "";
        int padding = raw[0] & 0xFF;
        int bits = (raw.length - 1) * 8 - padding;
        StringBuilder sb = new StringBuilder(Math.max(bits, 0));
        for (int i = 0; i < bits; i++) {
            int p = i + padding;
            sb.append(((raw[1 + p / 8] >> (7 - p % 8)) & 1) == 1 ? '1' : '0');
        }
        return sb.toString();
    }

    static String formatDate(int epochDay) {
        if (epochDay == Integer.MAX_VALUE) return "infinity";
        if (epochDay == -Integer.MAX_VALUE) return "-infinity";
        LocalDate d = LocalDate.ofEpochDay(epochDay);
        int year = d.getYear();
        boolean bc = year <= 0;
        String y = Integer.toString(bc ? 1 - year : year);
        if (y.length() < 4) y = "0".repeat(4 - y.length()) + y;
        return y + "-" + two(d.getMonthValue()) + "-" + two(d.getDayOfMonth()) + (bc ? " (BC)" : "");
    }

    /** HH:MM:SS with the fraction trimmed of trailing zeros (DuckDB allows 24:00:00). */
    static String formatTimeOfDay(long units, int fractionDigits) {
        long perSecond = (long) Math.pow(10, fractionDigits);
        long seconds = units / perSecond;
        long frac = units % perSecond;
        String s = two(seconds / 3600) + ":" + two(seconds / 60 % 60) + ":" + two(seconds % 60);
        return frac == 0 ? s : s + "." + trimZeros(frac, fractionDigits);
    }

    private static String trimZeros(long frac, int digits) {
        String f = Long.toString(frac);
        f = "0".repeat(digits - f.length()) + f;
        int end = f.length();
        while (end > 1 && f.charAt(end - 1) == '0') end--;
        return f.substring(0, end);
    }

    static String formatTimestamp(long value, long perSecond, int fractionDigits) {
        if (value == Long.MAX_VALUE) return "infinity";
        if (value == -Long.MAX_VALUE) return "-infinity";
        long perDay = 86_400L * perSecond;
        int day = (int) Math.floorDiv(value, perDay);
        long time = Math.floorMod(value, perDay);
        String date = formatDate(day);
        String tod = fractionDigits == 0 ? formatTimeOfDay(time * 10, 1) : formatTimeOfDay(time, fractionDigits);
        return date + " " + tod;
    }

    private static String withUtc(String ts) {
        return ts.endsWith("infinity") ? ts : ts + "+00";
    }

    /** dtime_tz_t: micros in the upper 40 bits, (MAX_OFFSET - offset seconds) in the lower 24. */
    static String formatTimeTz(long bits) {
        long micros = bits >>> 24;
        int offset = 16 * 60 * 60 - 1 - (int) (bits & 0xFFFFFF);
        StringBuilder sb = new StringBuilder(formatTimeOfDay(micros, 6));
        sb.append(offset < 0 ? '-' : '+');
        int a = Math.abs(offset);
        sb.append(two(a / 3600));
        if (a % 3600 != 0) {
            sb.append(':').append(two(a / 60 % 60));
            if (a % 60 != 0) sb.append(':').append(two(a % 60));
        }
        return sb.toString();
    }

    /** Interval text as DuckDB's IntervalToStringCast::Format writes it. */
    static String formatInterval(int months, int days, long micros) {
        StringBuilder sb = new StringBuilder();
        if (months != 0) {
            intervalPart(sb, months / 12, " year");
            intervalPart(sb, months % 12, " month");
        }
        intervalPart(sb, days, " day");
        if (micros != 0) {
            if (sb.length() != 0) sb.append(' ');
            if (micros < 0) sb.append('-');
            BigInteger abs = BigInteger.valueOf(micros).abs();
            long m = abs.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            long hour = m / 3_600_000_000L;
            long min = m / 60_000_000L % 60;
            long sec = m / 1_000_000L % 60;
            long frac = m % 1_000_000L;
            if (hour < 10) sb.append('0');
            sb.append(hour).append(':').append(two(min)).append(':').append(two(sec));
            if (frac != 0) sb.append('.').append(trimZeros(frac, 6));
        } else if (sb.length() == 0) {
            return "00:00:00";
        }
        return sb.toString();
    }

    private static void intervalPart(StringBuilder sb, int value, String name) {
        if (value == 0) return;
        if (sb.length() != 0) sb.append(' ');
        sb.append(value).append(name);
        if (value != 1 && value != -1) sb.append('s');
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }
}
