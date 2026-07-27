package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.type.LogicalType;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/**
 * Minimal {@link Array} wrapper around a Java list of decoded values
 * (the form a LIST / ARRAY Quack column decodes to). MAP columns decode
 * to a {@link java.util.Map}, not a list, so they are surfaced via
 * {@code getObject} rather than wrapped here.
 */
public final class QuackArray implements Array {

    private final List<?> values;
    private final LogicalType elementType;
    private boolean freed;

    public QuackArray(List<?> values, LogicalType elementType) {
        this.values = values;
        this.elementType = elementType;
    }

    @Override
    public String getBaseTypeName() {
        return elementType != null ? JdbcTypeMap.typeName(elementType) : "OTHER";
    }

    @Override
    public int getBaseType() {
        return elementType != null ? JdbcTypeMap.toJdbcType(elementType) : Types.OTHER;
    }

    @Override
    public Object getArray() throws SQLException {
        checkFreed();
        return values.toArray();
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        return getArray();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        checkFreed();
        int start = (int) Math.max(1, index) - 1;
        int end = Math.min(values.size(), start + count);
        return values.subList(start, end).toArray();
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        return getArray(index, count);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Array.getResultSet is not supported by quack-jdbc");
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        return getResultSet();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        return getResultSet();
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        return getResultSet();
    }

    @Override
    public void free() {
        freed = true;
    }

    @Override
    public String toString() {
        return values.toString();
    }

    private void checkFreed() throws SQLException {
        if (freed) throw new SQLException("Array.free() has already been called");
    }
}
