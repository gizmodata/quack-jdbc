package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import com.gizmodata.quack.jdbc.type.PhysicalTypeUtil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuackResultSet extends SkeletalResultSet {

    private final Statement statement;
    private final QuackSession.Cursor cursor;
    private final List<String> columnNames;
    private final List<LogicalType> columnTypes;
    private final Map<String, Integer> columnIndexByName;
    private DataChunk currentChunk;
    private int rowInChunk = -1;
    private int absoluteRow = 0;
    private boolean wasNull;
    private boolean closed;
    private boolean exhausted;

    public QuackResultSet(Statement statement, QuackSession.Cursor cursor) {
        this.statement = statement;
        this.cursor = cursor;
        this.columnNames = cursor.columnNames();
        this.columnTypes = cursor.columnTypes();
        this.columnIndexByName = new HashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            columnIndexByName.putIfAbsent(columnNames.get(i).toUpperCase(), i);
        }
    }

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (exhausted) return false;
        if (currentChunk == null) {
            currentChunk = cursor.nextChunk();
            rowInChunk = 0;
        } else {
            rowInChunk++;
        }
        while (currentChunk != null && rowInChunk >= currentChunk.rowCount()) {
            currentChunk = cursor.nextChunk();
            rowInChunk = 0;
        }
        if (currentChunk == null) {
            exhausted = true;
            return false;
        }
        absoluteRow++;
        return true;
    }

    @Override
    public void close() {
        closed = true;
        cursor.close();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int getRow() {
        return absoluteRow;
    }

    @Override
    public boolean wasNull() {
        return wasNull;
    }

    @Override
    public Statement getStatement() {
        return statement;
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return new QuackResultSetMetaData(columnNames, columnTypes);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        Integer idx = columnIndexByName.get(columnLabel.toUpperCase());
        if (idx == null) {
            throw new SQLException("Unknown column: " + columnLabel);
        }
        return idx + 1;
    }

    private Object rawValue(int columnIndex) throws SQLException {
        checkOpen();
        if (columnIndex < 1 || columnIndex > columnNames.size()) {
            throw new SQLException("Column index out of range: " + columnIndex);
        }
        if (currentChunk == null) {
            throw new SQLException("Not on a row");
        }
        DecodedVector col = currentChunk.columns().get(columnIndex - 1);
        Object value = col.getObject(rowInChunk);
        wasNull = (value == null);
        return value;
    }

    private Object rawValue(String columnLabel) throws SQLException {
        return rawValue(findColumn(columnLabel));
    }

    @Override public String getString(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (v instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return v.toString();
    }
    @Override public String getString(String columnLabel) throws SQLException { return getString(findColumn(columnLabel)); }

    @Override public boolean getBoolean(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0L;
        if (v instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s) || "t".equalsIgnoreCase(s);
        return false;
    }
    @Override public boolean getBoolean(String columnLabel) throws SQLException { return getBoolean(findColumn(columnLabel)); }

    @Override public byte getByte(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return 0;
        return ((Number) toNumber(v)).byteValue();
    }
    @Override public byte getByte(String columnLabel) throws SQLException { return getByte(findColumn(columnLabel)); }

    @Override public short getShort(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return 0;
        return ((Number) toNumber(v)).shortValue();
    }
    @Override public short getShort(String columnLabel) throws SQLException { return getShort(findColumn(columnLabel)); }

    @Override public int getInt(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return 0;
        return ((Number) toNumber(v)).intValue();
    }
    @Override public int getInt(String columnLabel) throws SQLException { return getInt(findColumn(columnLabel)); }

    @Override public long getLong(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return 0;
        return ((Number) toNumber(v)).longValue();
    }
    @Override public long getLong(String columnLabel) throws SQLException { return getLong(findColumn(columnLabel)); }

    @Override public float getFloat(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return 0f;
        return ((Number) toNumber(v)).floatValue();
    }
    @Override public float getFloat(String columnLabel) throws SQLException { return getFloat(findColumn(columnLabel)); }

    @Override public double getDouble(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return 0d;
        return ((Number) toNumber(v)).doubleValue();
    }
    @Override public double getDouble(String columnLabel) throws SQLException { return getDouble(findColumn(columnLabel)); }

    @Override public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof BigInteger bi) return new BigDecimal(bi);
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(v.toString());
    }
    @Override public BigDecimal getBigDecimal(String columnLabel) throws SQLException { return getBigDecimal(findColumn(columnLabel)); }

    @Override public byte[] getBytes(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (v instanceof byte[] b) return b;
        return v.toString().getBytes(StandardCharsets.UTF_8);
    }
    @Override public byte[] getBytes(String columnLabel) throws SQLException { return getBytes(findColumn(columnLabel)); }

    @Override public Date getDate(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (v instanceof LocalDate ld) return Date.valueOf(ld);
        if (v instanceof LocalDateTime ldt) return Date.valueOf(ldt.toLocalDate());
        if (v instanceof OffsetDateTime odt) return Date.valueOf(odt.toLocalDate());
        return Date.valueOf(v.toString());
    }
    @Override public Date getDate(String columnLabel) throws SQLException { return getDate(findColumn(columnLabel)); }

    @Override public Time getTime(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (v instanceof LocalTime lt) return Time.valueOf(lt);
        if (v instanceof LocalDateTime ldt) return Time.valueOf(ldt.toLocalTime());
        return Time.valueOf(v.toString());
    }
    @Override public Time getTime(String columnLabel) throws SQLException { return getTime(findColumn(columnLabel)); }

    @Override public Timestamp getTimestamp(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return Timestamp.valueOf(ldt);
        if (v instanceof OffsetDateTime odt) return Timestamp.from(odt.toInstant());
        if (v instanceof LocalDate ld) return Timestamp.valueOf(ld.atStartOfDay());
        return Timestamp.valueOf(v.toString());
    }
    @Override public Timestamp getTimestamp(String columnLabel) throws SQLException { return getTimestamp(findColumn(columnLabel)); }

    @Override public Object getObject(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        LogicalType type = columnTypes.get(columnIndex - 1);
        LogicalTypeId id = type == null ? null : type.id();
        if ((id == LogicalTypeId.LIST || id == LogicalTypeId.ARRAY) && v instanceof List<?> list) {
            return new QuackArray(list, extractElementType(type));
        }
        if (id == LogicalTypeId.STRUCT && v instanceof Map<?, ?> struct) {
            return toStruct(type, struct);
        }
        return v;
    }
    @Override public Object getObject(String columnLabel) throws SQLException { return getObject(findColumn(columnLabel)); }

    private static QuackStruct toStruct(LogicalType type, Map<?, ?> values) {
        Object[] attributes;
        if (type.typeInfo().orElse(null) instanceof ExtraTypeInfo.StructInfo info) {
            List<ChildType> children = info.childTypes();
            attributes = new Object[children.size()];
            for (int i = 0; i < children.size(); i++) {
                attributes[i] = values.get(children.get(i).name());
            }
        } else {
            attributes = values.values().toArray();
        }
        return new QuackStruct(JdbcTypeMap.typeName(type), attributes);
    }

    @Override public Array getArray(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (!(v instanceof java.util.List<?> list)) {
            throw new SQLException("Column " + columnIndex + " is not an array-typed value");
        }
        LogicalType type = columnTypes.get(columnIndex - 1);
        LogicalType elementType = extractElementType(type);
        return new QuackArray(list, elementType);
    }
    @Override public Array getArray(String columnLabel) throws SQLException { return getArray(findColumn(columnLabel)); }

    @Override public Blob getBlob(int columnIndex) throws SQLException {
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (v instanceof byte[] bytes) return new QuackBlob(bytes);
        return new QuackBlob(v.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Override public Blob getBlob(String columnLabel) throws SQLException { return getBlob(findColumn(columnLabel)); }

    private static LogicalType extractElementType(LogicalType type) {
        if (type == null || type.typeInfo().isEmpty()) return null;
        ExtraTypeInfo info = type.typeInfo().get();
        if (info instanceof ExtraTypeInfo.ListInfo l) return l.childType();
        if (info instanceof ExtraTypeInfo.ArrayInfo a) return a.childType();
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        if (type == Array.class) return (T) getArray(columnIndex);
        if (type == java.sql.Struct.class) {
            Object wrapped = getObject(columnIndex);
            if (wrapped instanceof java.sql.Struct s) return (T) s;
        }
        Object v = rawValue(columnIndex);
        if (v == null) return null;
        if (type.isInstance(v)) return type.cast(v);
        if (type == String.class) return (T) v.toString();
        if (type == Boolean.class) return (T) Boolean.valueOf(getBooleanFromValue(v));
        if (type == Integer.class) return (T) Integer.valueOf(((Number) toNumber(v)).intValue());
        if (type == Long.class) return (T) Long.valueOf(((Number) toNumber(v)).longValue());
        if (type == Double.class) return (T) Double.valueOf(((Number) toNumber(v)).doubleValue());
        if (type == Float.class) return (T) Float.valueOf(((Number) toNumber(v)).floatValue());
        if (type == Short.class) return (T) Short.valueOf(((Number) toNumber(v)).shortValue());
        if (type == Byte.class) return (T) Byte.valueOf(((Number) toNumber(v)).byteValue());
        if (type == BigDecimal.class) return (T) getBigDecimal(columnIndex);
        if (type == BigInteger.class) {
            if (v instanceof BigInteger bi) return (T) bi;
            if (v instanceof BigDecimal bd) return (T) bd.toBigInteger();
            return (T) BigInteger.valueOf(((Number) toNumber(v)).longValue());
        }
        if (type == LocalDate.class && v instanceof LocalDate ld) return (T) ld;
        if (type == LocalTime.class && v instanceof LocalTime lt) return (T) lt;
        if (type == LocalDateTime.class && v instanceof LocalDateTime ldt) return (T) ldt;
        if (type == OffsetDateTime.class && v instanceof OffsetDateTime odt) return (T) odt;
        if (type == UUID.class && v instanceof UUID u) return (T) u;
        if (type == byte[].class && v instanceof byte[] b) return (T) b;
        throw new SQLException("Cannot convert " + v.getClass().getSimpleName() + " to " + type.getName());
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] bytes = getBytes(columnIndex);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }
    @Override public InputStream getBinaryStream(String columnLabel) throws SQLException { return getBinaryStream(findColumn(columnLabel)); }

    @Override public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }
    @Override public InputStream getAsciiStream(String columnLabel) throws SQLException { return getAsciiStream(findColumn(columnLabel)); }

    @Override public Reader getCharacterStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new StringReader(s);
    }
    @Override public Reader getCharacterStream(String columnLabel) throws SQLException { return getCharacterStream(findColumn(columnLabel)); }

    private Number toNumber(Object v) throws SQLException {
        if (v instanceof Number n) return n;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            try { return new BigDecimal(s); } catch (NumberFormatException e) {
                throw new SQLException("Cannot convert " + s + " to a number", e);
            }
        }
        throw new SQLException("Cannot convert " + v.getClass().getSimpleName() + " to a number");
    }

    private boolean getBooleanFromValue(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0L;
        if (v instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
        return false;
    }

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed");
    }
}
