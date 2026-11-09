package com.gizmodata.quack.jdbc.type;

import com.gizmodata.quack.jdbc.QuackProtocolException;

import java.util.List;

public final class PhysicalTypeUtil {

    private PhysicalTypeUtil() {
    }

    public static PhysicalType getPhysicalType(LogicalType type) {
        return switch (type.id()) {
            case BOOLEAN -> PhysicalType.BOOL;
            case TINYINT -> PhysicalType.INT8;
            case UTINYINT -> PhysicalType.UINT8;
            case SMALLINT -> PhysicalType.INT16;
            case USMALLINT -> PhysicalType.UINT16;
            case SQLNULL, DATE, INTEGER -> PhysicalType.INT32;
            case UINTEGER -> PhysicalType.UINT32;
            case BIGINT, TIME, TIME_NS, TIMESTAMP, TIMESTAMP_SEC, TIMESTAMP_NS,
                 TIMESTAMP_MS, TIME_TZ, TIMESTAMP_TZ, TIMESTAMP_TZ_NS -> PhysicalType.INT64;
            case UBIGINT -> PhysicalType.UINT64;
            case UHUGEINT -> PhysicalType.UINT128;
            case HUGEINT, UUID -> PhysicalType.INT128;
            case FLOAT -> PhysicalType.FLOAT;
            case DOUBLE -> PhysicalType.DOUBLE;
            case DECIMAL -> getDecimalPhysicalType(type);
            case BIGNUM, VARCHAR, CHAR, BLOB, BIT, TYPE, AGGREGATE_STATE, GEOMETRY -> PhysicalType.VARCHAR;
            case INTERVAL -> PhysicalType.INTERVAL;
            case UNION, VARIANT, STRUCT, TUPLE -> PhysicalType.STRUCT;
            case LIST, MAP -> PhysicalType.LIST;
            case ARRAY -> PhysicalType.ARRAY;
            case POINTER -> PhysicalType.UINT64;
            case VALIDITY -> PhysicalType.BIT;
            case ENUM -> getEnumPhysicalType(type);
            default -> PhysicalType.INVALID;
        };
    }

    public static LogicalType getChildType(LogicalType type) {
        return type.typeInfo().map(info -> {
            if (info instanceof ExtraTypeInfo.ListInfo l) return l.childType();
            if (info instanceof ExtraTypeInfo.ArrayInfo a) return a.childType();
            return null;
        }).orElseThrow(() -> new QuackProtocolException(
                "Logical type " + type.id() + " does not have a child type"));
    }

    public static List<ChildType> getStructChildren(LogicalType type) {
        return type.typeInfo().map(info -> {
            if (info instanceof ExtraTypeInfo.StructInfo s) {
                return s.childTypes();
            }
            return null;
        }).orElseGet(() -> {
            if (type.id() == LogicalTypeId.VARIANT || type.id() == LogicalTypeId.UNION) {
                return List.of();
            }
            throw new QuackProtocolException(
                    "Logical type " + type.id() + " does not have struct children");
        });
    }

    public static int getArraySize(LogicalType type) {
        return type.typeInfo().map(info -> {
            if (info instanceof ExtraTypeInfo.ArrayInfo a) {
                return a.size();
            }
            return null;
        }).orElseThrow(() -> new QuackProtocolException(
                "Logical type " + type.id() + " is not an ARRAY"));
    }

    public static List<String> getEnumValues(LogicalType type) {
        return type.typeInfo().map(info -> {
            if (info instanceof ExtraTypeInfo.EnumInfo e) {
                return e.values();
            }
            return null;
        }).orElseThrow(() -> new QuackProtocolException(
                "Logical type " + type.id() + " is not an ENUM"));
    }

    private static PhysicalType getDecimalPhysicalType(LogicalType type) {
        ExtraTypeInfo info = type.typeInfo().orElseThrow(
                () -> new QuackProtocolException("DECIMAL type is missing DecimalTypeInfo"));
        if (!(info instanceof ExtraTypeInfo.Decimal d)) {
            throw new QuackProtocolException("DECIMAL type is missing DecimalTypeInfo");
        }
        if (d.width() <= 4) return PhysicalType.INT16;
        if (d.width() <= 9) return PhysicalType.INT32;
        if (d.width() <= 18) return PhysicalType.INT64;
        if (d.width() <= 38) return PhysicalType.INT128;
        throw new QuackProtocolException("Unsupported DECIMAL width " + d.width());
    }

    private static PhysicalType getEnumPhysicalType(LogicalType type) {
        int n = getEnumValues(type).size();
        if (n <= 0xFF) return PhysicalType.UINT8;
        if (n <= 0xFFFF) return PhysicalType.UINT16;
        return PhysicalType.UINT32;
    }
}
