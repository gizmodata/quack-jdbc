package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
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

    private static LogicalType struct(ChildType... fields) {
        return LogicalType.of(LogicalTypeId.STRUCT, new ExtraTypeInfo.StructInfo(List.of(fields), Optional.empty()));
    }

    private static ChildType field(String name, LogicalType type) {
        return new ChildType(name, type);
    }

    private static LogicalType map(LogicalType key, LogicalType value) {
        LogicalType entry = struct(field("key", key), field("value", value));
        return LogicalType.of(LogicalTypeId.MAP, new ExtraTypeInfo.ListInfo(entry, Optional.empty()));
    }

    private static LogicalType enumType(String... values) {
        return LogicalType.of(LogicalTypeId.ENUM, new ExtraTypeInfo.EnumInfo(List.of(values), Optional.empty()));
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

    @Test
    void structTypeNameRendersFieldNamesAndTypes() {
        assertEquals("STRUCT(x INTEGER, y VARCHAR)",
                JdbcTypeMap.typeName(struct(field("x", scalar(LogicalTypeId.INTEGER)),
                        field("y", scalar(LogicalTypeId.VARCHAR)))));
        assertEquals("STRUCT(a DECIMAL(5,2))",
                JdbcTypeMap.typeName(struct(field("a", LogicalType.decimal(5, 2)))));
    }

    @Test
    void structTypeNameQuotesNonSimpleFieldNames() {
        assertEquals("STRUCT(\"weird name\" INTEGER)",
                JdbcTypeMap.typeName(struct(field("weird name", scalar(LogicalTypeId.INTEGER)))));
        assertEquals("STRUCT(\"a\"\"b\" INTEGER)",
                JdbcTypeMap.typeName(struct(field("a\"b", scalar(LogicalTypeId.INTEGER)))));
        assertEquals("STRUCT(Ab INTEGER, _ok INTEGER)",
                JdbcTypeMap.typeName(struct(field("Ab", scalar(LogicalTypeId.INTEGER)),
                        field("_ok", scalar(LogicalTypeId.INTEGER)))));
    }

    @Test
    void mapTypeNameRendersKeyAndValue() {
        assertEquals("MAP(INTEGER, VARCHAR)",
                JdbcTypeMap.typeName(map(scalar(LogicalTypeId.INTEGER), scalar(LogicalTypeId.VARCHAR))));
        assertEquals("MAP(INTEGER, INTEGER[])",
                JdbcTypeMap.typeName(map(scalar(LogicalTypeId.INTEGER), list(scalar(LogicalTypeId.INTEGER)))));
    }

    @Test
    void enumTypeNameIsBareAtTopLevelButExpandedWhenNested() {
        assertEquals("ENUM", JdbcTypeMap.typeName(enumType("x", "y", "z")));
        assertEquals("ENUM('x', 'y', 'z')[]", JdbcTypeMap.typeName(list(enumType("x", "y", "z"))));
        assertEquals("STRUCT(m ENUM('x', 'y', 'z'))",
                JdbcTypeMap.typeName(struct(field("m", enumType("x", "y", "z")))));
    }

    @Test
    void nestedComplexTypeNamesRecurseFully() {
        assertEquals("STRUCT(x INTEGER, y VARCHAR)[]",
                JdbcTypeMap.typeName(list(struct(field("x", scalar(LogicalTypeId.INTEGER)),
                        field("y", scalar(LogicalTypeId.VARCHAR))))));
        assertEquals("MAP(INTEGER, VARCHAR)[]",
                JdbcTypeMap.typeName(list(map(scalar(LogicalTypeId.INTEGER), scalar(LogicalTypeId.VARCHAR)))));
        assertEquals("STRUCT(a STRUCT(b INTEGER))",
                JdbcTypeMap.typeName(struct(field("a", struct(field("b", scalar(LogicalTypeId.INTEGER)))))));
    }

    @Test
    void complexTypeNamesFallBackWhenTypeInfoAbsent() {
        assertEquals("STRUCT", JdbcTypeMap.typeName(scalar(LogicalTypeId.STRUCT)));
        assertEquals("MAP", JdbcTypeMap.typeName(scalar(LogicalTypeId.MAP)));
        assertEquals("ENUM", JdbcTypeMap.typeName(scalar(LogicalTypeId.ENUM)));
    }

    @Test
    void jdbcTypeCodesMatchOracle() {
        assertEquals(Types.STRUCT, JdbcTypeMap.toJdbcType(struct(field("x", scalar(LogicalTypeId.INTEGER)))));
        assertEquals(Types.OTHER, JdbcTypeMap.toJdbcType(map(scalar(LogicalTypeId.INTEGER), scalar(LogicalTypeId.VARCHAR))));
        assertEquals(Types.OTHER, JdbcTypeMap.toJdbcType(enumType("x", "y")));
    }
}
