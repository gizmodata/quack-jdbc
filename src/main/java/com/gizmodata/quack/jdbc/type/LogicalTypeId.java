package com.gizmodata.quack.jdbc.type;

public enum LogicalTypeId {
    INVALID(0),
    SQLNULL(1),
    UNKNOWN(2),
    ANY(3),
    UNBOUND(4),
    TEMPLATE(5),
    TYPE(6),
    BOOLEAN(10),
    TINYINT(11),
    SMALLINT(12),
    INTEGER(13),
    BIGINT(14),
    DATE(15),
    TIME(16),
    TIMESTAMP_SEC(17),
    TIMESTAMP_MS(18),
    TIMESTAMP(19),
    TIMESTAMP_NS(20),
    DECIMAL(21),
    FLOAT(22),
    DOUBLE(23),
    CHAR(24),
    VARCHAR(25),
    BLOB(26),
    INTERVAL(27),
    UTINYINT(28),
    USMALLINT(29),
    UINTEGER(30),
    UBIGINT(31),
    TIMESTAMP_TZ(32),
    /** DuckDB 2.0: TIMESTAMPTZ_NS. */
    TIMESTAMP_TZ_NS(33),
    TIME_TZ(34),
    TIME_NS(35),
    BIT(36),
    STRING_LITERAL(37),
    INTEGER_LITERAL(38),
    BIGNUM(39),
    UHUGEINT(49),
    HUGEINT(50),
    POINTER(51),
    VALIDITY(53),
    UUID(54),
    GEOMETRY(60),
    STRUCT(100),
    LIST(101),
    MAP(102),
    TABLE(103),
    ENUM(104),
    AGGREGATE_STATE(105),
    LAMBDA(106),
    UNION(107),
    ARRAY(108),
    VARIANT(109),
    /** DuckDB 2.0: a STRUCT whose children are unnamed. */
    TUPLE(110);

    private final int wireId;

    LogicalTypeId(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static LogicalTypeId fromWireId(int wireId) {
        for (LogicalTypeId id : values()) {
            if (id.wireId == wireId) {
                return id;
            }
        }
        return INVALID;
    }
}
