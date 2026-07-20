package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.sql.Types;

public final class JdbcTypeMap {

    private JdbcTypeMap() {
    }

    public static int toJdbcType(LogicalType type) {
        return switch (type.id()) {
            case BOOLEAN -> Types.BOOLEAN;
            case TINYINT, UTINYINT -> Types.TINYINT;
            case SMALLINT, USMALLINT -> Types.SMALLINT;
            case INTEGER, UINTEGER -> Types.INTEGER;
            case BIGINT, UBIGINT, HUGEINT, UHUGEINT -> Types.BIGINT;
            case FLOAT -> Types.REAL;
            case DOUBLE -> Types.DOUBLE;
            case DECIMAL -> Types.DECIMAL;
            case CHAR -> Types.CHAR;
            case VARCHAR, STRING_LITERAL, BIGNUM -> Types.VARCHAR;
            case BLOB, BIT, GEOMETRY -> Types.BINARY;
            case DATE -> Types.DATE;
            case TIME, TIME_NS, TIME_TZ -> Types.TIME;
            case TIMESTAMP, TIMESTAMP_SEC, TIMESTAMP_MS, TIMESTAMP_NS -> Types.TIMESTAMP;
            case TIMESTAMP_TZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            case INTERVAL -> Types.OTHER;
            case UUID -> Types.OTHER;
            case ENUM -> Types.VARCHAR;
            case STRUCT -> Types.STRUCT;
            case LIST, MAP, ARRAY -> Types.ARRAY;
            case SQLNULL -> Types.NULL;
            default -> Types.OTHER;
        };
    }

    public static String typeName(LogicalType type) {
        return switch (type.id()) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case UTINYINT -> "UTINYINT";
            case USMALLINT -> "USMALLINT";
            case UINTEGER -> "UINTEGER";
            case UBIGINT -> "UBIGINT";
            case HUGEINT -> "HUGEINT";
            case UHUGEINT -> "UHUGEINT";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case DECIMAL -> type.typeInfo().filter(i -> i instanceof ExtraTypeInfo.Decimal)
                    .map(i -> (ExtraTypeInfo.Decimal) i)
                    .map(d -> "DECIMAL(" + d.width() + "," + d.scale() + ")")
                    .orElse("DECIMAL");
            case CHAR -> "CHAR";
            case VARCHAR -> "VARCHAR";
            case BLOB -> "BLOB";
            case BIT -> "BIT";
            case GEOMETRY -> "GEOMETRY";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIME_NS -> "TIME_NS";
            case TIME_TZ -> "TIME WITH TIME ZONE";
            case TIMESTAMP -> "TIMESTAMP";
            case TIMESTAMP_SEC -> "TIMESTAMP_S";
            case TIMESTAMP_MS -> "TIMESTAMP_MS";
            case TIMESTAMP_NS -> "TIMESTAMP_NS";
            case TIMESTAMP_TZ -> "TIMESTAMPTZ";
            case INTERVAL -> "INTERVAL";
            case UUID -> "UUID";
            case ENUM -> "ENUM";
            case STRUCT -> "STRUCT";
            case LIST -> type.typeInfo().filter(i -> i instanceof ExtraTypeInfo.ListInfo)
                    .map(i -> (ExtraTypeInfo.ListInfo) i)
                    .map(l -> typeName(l.childType()) + "[]")
                    .orElse("LIST");
            case MAP -> "MAP";
            case ARRAY -> type.typeInfo().filter(i -> i instanceof ExtraTypeInfo.ArrayInfo)
                    .map(i -> (ExtraTypeInfo.ArrayInfo) i)
                    .map(a -> typeName(a.childType()) + "[" + a.size() + "]")
                    .orElse("ARRAY");
            case SQLNULL -> "NULL";
            default -> type.id().name();
        };
    }

    public static int displaySize(LogicalType type) {
        return switch (type.id()) {
            case BOOLEAN -> 5;
            case TINYINT -> 4;
            case SMALLINT -> 6;
            case INTEGER, UINTEGER -> 11;
            case BIGINT, UBIGINT -> 20;
            case HUGEINT, UHUGEINT -> 40;
            case FLOAT -> 15;
            case DOUBLE -> 24;
            case DECIMAL -> type.typeInfo().filter(i -> i instanceof ExtraTypeInfo.Decimal)
                    .map(i -> (ExtraTypeInfo.Decimal) i)
                    .map(d -> d.width() + 2)
                    .orElse(40);
            case CHAR, VARCHAR, BLOB, BIT, GEOMETRY -> Integer.MAX_VALUE;
            case DATE -> 10;
            case TIME, TIME_NS, TIME_TZ -> 18;
            case TIMESTAMP, TIMESTAMP_SEC, TIMESTAMP_MS, TIMESTAMP_NS, TIMESTAMP_TZ -> 29;
            case UUID -> 36;
            default -> Integer.MAX_VALUE;
        };
    }

    public static int precision(LogicalType type) {
        return switch (type.id()) {
            case BOOLEAN -> 1;
            case TINYINT -> 3;
            case SMALLINT -> 5;
            case INTEGER, UINTEGER -> 10;
            case BIGINT, UBIGINT -> 19;
            case HUGEINT, UHUGEINT -> 39;
            case FLOAT -> 7;
            case DOUBLE -> 15;
            case DECIMAL -> type.typeInfo().filter(i -> i instanceof ExtraTypeInfo.Decimal)
                    .map(i -> (ExtraTypeInfo.Decimal) i)
                    .map(ExtraTypeInfo.Decimal::width)
                    .orElse(38);
            default -> 0;
        };
    }

    public static int scale(LogicalType type) {
        if (type.id() == LogicalTypeId.DECIMAL) {
            return type.typeInfo().filter(i -> i instanceof ExtraTypeInfo.Decimal)
                    .map(i -> (ExtraTypeInfo.Decimal) i)
                    .map(ExtraTypeInfo.Decimal::scale)
                    .orElse(0);
        }
        return 0;
    }
}
