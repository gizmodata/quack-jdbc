package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackServerException;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;

/** Turns driver runtime exceptions into {@link SQLException}s that carry a SQLSTATE. */
public final class QuackSqlExceptions {

    private QuackSqlExceptions() {
    }

    /**
     * The SQLException for a failure: a server error keeps DuckDB's message
     * and gets the SQLSTATE of its exception type (and the matching JDBC
     * subclass); anything else is a connection failure, 08006.
     */
    public static SQLException from(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof QuackServerException se) {
                return of(e.getMessage(), se.sqlState(), e);
            }
        }
        if (e instanceof com.gizmodata.quack.jdbc.QuackUnsupportedTypeException) {
            return new SQLFeatureNotSupportedException(e.getMessage(), "0A000", e);
        }
        return of(e.getMessage(), "08006", e);
    }

    static SQLException of(String message, String state, Throwable cause) {
        String cls = state.length() >= 2 ? state.substring(0, 2) : "";
        return switch (cls) {
            case "42" -> new SQLSyntaxErrorException(message, state, cause);
            case "23" -> new SQLIntegrityConstraintViolationException(message, state, cause);
            case "22" -> new SQLDataException(message, state, cause);
            case "28" -> new SQLInvalidAuthorizationSpecException(message, state, cause);
            case "08" -> new SQLNonTransientConnectionException(message, state, cause);
            case "0A" -> new SQLFeatureNotSupportedException(message, state, cause);
            default -> new SQLException(message, state, cause);
        };
    }
}
