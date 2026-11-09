package com.gizmodata.quack.jdbc.codec;

public final class QuackConstants {
    /** Quack protocol version 3: the one DuckDB 2.0 serves. Version 1 (DuckDB 1.5) is not supported. */
    public static final long QUACK_VERSION = 3L;
    public static final int DEFAULT_QUACK_PORT = 9494;
    public static final String QUACK_ENDPOINT = "/quack";
    public static final String DUCKDB_MIME_TYPE = "application/vnd.duckdb";
    public static final int FIELD_END = 0xFFFF;
    public static final long OPTIONAL_INDEX_INVALID = -1L;

    private QuackConstants() {
    }
}
