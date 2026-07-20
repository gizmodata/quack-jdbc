package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcTypeMapTest {

    private static LogicalType list(LogicalType child) {
        return LogicalType.of(LogicalTypeId.LIST, new ExtraTypeInfo.ListInfo(child, Optional.empty()));
    }

    private static LogicalType array(LogicalType child, int size) {
        return LogicalType.of(LogicalTypeId.ARRAY, new ExtraTypeInfo.ArrayInfo(child, size, Optional.empty()));
    }

    private static LogicalType scalar(LogicalTypeId id) {
        return LogicalType.of(id);
    }

    @Test
    void listTypeNameCarriesElementType() {
        assertEquals("INTEGER[]", JdbcTypeMap.typeName(list(scalar(LogicalTypeId.INTEGER))));
        assertEquals("VARCHAR[]", JdbcTypeMap.typeName(list(scalar(LogicalTypeId.VARCHAR))));
        assertEquals("BIGINT[]", JdbcTypeMap.typeName(list(scalar(LogicalTypeId.BIGINT))));
    }

    @Test
    void decimalElementTypeIsPreserved() {
        LogicalType decimal = LogicalType.decimal(5, 2);
        assertEquals("DECIMAL(5,2)[]", JdbcTypeMap.typeName(list(decimal)));
    }

    @Test
    void nestedListTypeNameRecurses() {
        assertEquals("INTEGER[][]", JdbcTypeMap.typeName(list(list(scalar(LogicalTypeId.INTEGER)))));
    }

    @Test
    void fixedSizeArrayTypeNameCarriesElementAndSize() {
        assertEquals("INTEGER[3]", JdbcTypeMap.typeName(array(scalar(LogicalTypeId.INTEGER), 3)));
        assertEquals("DOUBLE[128]", JdbcTypeMap.typeName(array(scalar(LogicalTypeId.DOUBLE), 128)));
    }

    @Test
    void listWithoutTypeInfoFallsBackToBareName() {
        assertEquals("LIST", JdbcTypeMap.typeName(scalar(LogicalTypeId.LIST)));
        assertEquals("ARRAY", JdbcTypeMap.typeName(scalar(LogicalTypeId.ARRAY)));
    }

    @Test
    void listAndArrayStillMapToJdbcArray() {
        assertEquals(Types.ARRAY, JdbcTypeMap.toJdbcType(list(scalar(LogicalTypeId.INTEGER))));
        assertEquals(Types.ARRAY, JdbcTypeMap.toJdbcType(array(scalar(LogicalTypeId.INTEGER), 3)));
    }
}
