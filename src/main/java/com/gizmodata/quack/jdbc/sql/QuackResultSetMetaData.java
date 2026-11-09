package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.type.LogicalType;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import java.util.List;

public final class QuackResultSetMetaData implements ResultSetMetaData {

    private final List<String> columnNames;
    private final List<LogicalType> columnTypes;

    public QuackResultSetMetaData(List<String> columnNames, List<LogicalType> columnTypes) {
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
    }

    private LogicalType typeAt(int column) throws SQLException {
        if (column < 1 || column > columnTypes.size()) {
            throw new SQLException("Column index out of range: " + column);
        }
        return columnTypes.get(column - 1);
    }

    @Override public int getColumnCount() { return columnNames.size(); }
    @Override public boolean isAutoIncrement(int column) { return false; }
    @Override public boolean isCaseSensitive(int column) { return true; }
    @Override public boolean isSearchable(int column) { return true; }
    @Override public boolean isCurrency(int column) { return false; }
    @Override public int isNullable(int column) { return ResultSetMetaData.columnNullableUnknown; }
    @Override public boolean isSigned(int column) throws SQLException {
        return switch (typeAt(column).id()) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT, FLOAT, DOUBLE, DECIMAL -> true;
            default -> false;
        };
    }
    @Override public int getColumnDisplaySize(int column) throws SQLException { return JdbcTypeMap.displaySize(typeAt(column)); }
    @Override public String getColumnLabel(int column) throws SQLException { return getColumnName(column); }
    @Override public String getColumnName(int column) throws SQLException {
        if (column < 1 || column > columnNames.size()) {
            throw new SQLException("Column index out of range: " + column);
        }
        return columnNames.get(column - 1);
    }
    @Override public String getSchemaName(int column) { return ""; }
    @Override public int getPrecision(int column) throws SQLException { return JdbcTypeMap.precision(typeAt(column)); }
    @Override public int getScale(int column) throws SQLException { return JdbcTypeMap.scale(typeAt(column)); }
    @Override public String getTableName(int column) { return ""; }
    @Override public String getCatalogName(int column) { return ""; }
    @Override public int getColumnType(int column) throws SQLException { return JdbcTypeMap.toJdbcType(typeAt(column)); }
    @Override public String getColumnTypeName(int column) throws SQLException { return JdbcTypeMap.typeName(typeAt(column)); }
    @Override public boolean isReadOnly(int column) { return true; }
    @Override public boolean isWritable(int column) { return false; }
    @Override public boolean isDefinitelyWritable(int column) { return false; }
    @Override public String getColumnClassName(int column) throws SQLException {
        return switch (typeAt(column).id()) {
            case BOOLEAN -> Boolean.class.getName();
            case TINYINT -> Byte.class.getName();
            case SMALLINT -> Short.class.getName();
            case INTEGER, UTINYINT, USMALLINT -> Integer.class.getName();
            case BIGINT, UINTEGER, UBIGINT -> Long.class.getName();
            case HUGEINT, UHUGEINT -> java.math.BigInteger.class.getName();
            case FLOAT -> Float.class.getName();
            case DOUBLE -> Double.class.getName();
            case DECIMAL -> java.math.BigDecimal.class.getName();
            case VARCHAR, CHAR, ENUM, BIGNUM -> String.class.getName();
            case BLOB, BIT, GEOMETRY -> byte[].class.getName();
            case DATE -> java.time.LocalDate.class.getName();
            case TIME, TIME_NS -> java.time.LocalTime.class.getName();
            case TIME_TZ -> java.time.OffsetTime.class.getName();
            case TIMESTAMP, TIMESTAMP_SEC, TIMESTAMP_MS, TIMESTAMP_NS -> java.time.LocalDateTime.class.getName();
            case TIMESTAMP_TZ, TIMESTAMP_TZ_NS -> java.time.OffsetDateTime.class.getName();
            case UUID -> java.util.UUID.class.getName();
            case INTERVAL -> com.gizmodata.quack.jdbc.message.IntervalValue.class.getName();
            case STRUCT, TUPLE -> java.sql.Struct.class.getName();
            case VARIANT -> String.class.getName();
            case LIST, ARRAY -> java.sql.Array.class.getName();
            case MAP -> java.util.Map.class.getName();
            default -> Object.class.getName();
        };
    }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
