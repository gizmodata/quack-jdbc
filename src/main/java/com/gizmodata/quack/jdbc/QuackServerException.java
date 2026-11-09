package com.gizmodata.quack.jdbc;

import java.util.Locale;
import java.util.Map;

/**
 * An {@code ERROR_RESPONSE} from the Quack server. DuckDB 2.0 reports its
 * exception type ("Catalog", "Parser", "Constraint", ...) and any extra
 * info with every error, which {@link #sqlState()} maps to a SQLSTATE so
 * callers can tell a missing table from a syntax error from a constraint
 * violation.
 */
public class QuackServerException extends QuackException {
    private final String serverMessage;
    private final String exceptionType;
    private final Map<String, String> extraInfo;
    private final boolean mustInvalidate;
    private final String sqlStateOverride;

    public QuackServerException(String serverMessage) {
        this(serverMessage, "", Map.of(), false);
    }

    public QuackServerException(String serverMessage, String exceptionType,
                                Map<String, String> extraInfo, boolean mustInvalidate) {
        super(serverMessage);
        this.serverMessage = serverMessage;
        this.exceptionType = exceptionType == null ? "" : exceptionType;
        this.extraInfo = extraInfo == null ? Map.of() : Map.copyOf(extraInfo);
        this.mustInvalidate = mustInvalidate;
        this.sqlStateOverride = null;
    }

    /** The same server error, reported with {@code message} and {@code sqlState} (e.g. a mismatch on bulk load). */
    public QuackServerException(String message, String sqlState, QuackServerException cause) {
        super(message, cause);
        this.serverMessage = cause.serverMessage;
        this.exceptionType = cause.exceptionType;
        this.extraInfo = cause.extraInfo;
        this.mustInvalidate = cause.mustInvalidate;
        this.sqlStateOverride = sqlState;
    }

    public String getServerMessage() {
        return serverMessage;
    }

    /** DuckDB's exception type name, e.g. {@code "Catalog"}; empty when the server sent none. */
    public String getExceptionType() {
        return exceptionType;
    }

    /** DuckDB's extra error info (error_subtype, position, ...). */
    public Map<String, String> getExtraInfo() {
        return extraInfo;
    }

    /** True when the server says its database is invalidated and the connection is unusable. */
    public boolean mustInvalidate() {
        return mustInvalidate;
    }

    /** The SQLSTATE for this error, from DuckDB's exception type and, where that is too broad, the message. */
    public String sqlState() {
        return sqlStateOverride != null ? sqlStateOverride : sqlStateFor(exceptionType, serverMessage);
    }

    static String sqlStateFor(String exceptionType, String message) {
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (msg.contains("authentication failed")) return "28000";
        if (msg.contains("authorization failed")) return "42501";
        if (msg.contains("heartbeat lease expired") || msg.contains("invalid connection id")) return "08003";
        String type = exceptionType == null ? "" : exceptionType.toLowerCase(Locale.ROOT);
        switch (type) {
            case "catalog":
                if (msg.contains("already exists")) return "42S01";
                if (msg.contains("does not exist")) {
                    if (msg.contains("table") || msg.contains("view")) return "42S02";
                    if (msg.contains("column")) return "42S22";
                }
                return "42000";
            case "binder":
                if (msg.contains("column") && (msg.contains("not found") || msg.contains("does not exist"))) {
                    return "42S22";
                }
                if (msg.contains("table") && msg.contains("does not exist")) return "42S02";
                return "42000";
            case "parser":
            case "syntax":
                return "42601";
            case "constraint":
                return "23000";
            case "divide by zero":
                return "22012";
            case "out of range":
                return "22003";
            case "conversion":
            case "invalid input":
            case "mismatch type":
            case "decimal":
            case "unknown type":
            case "invalid type":
            case "object size":
                return "22000";
            case "transactioncontext":
            case "transaction":
                return "25000";
            case "permission":
                return "42501";
            case "interrupt":
                return "57014";
            case "not implemented":
                return "0A000";
            case "io":
            case "http":
            case "network":
            case "connection":
                return "58030";
            case "dependency":
                return "2BP01";
            case "out of memory":
                return "53200";
            case "":
            case "internal":
            case "fatal":
                return "XX000";
            default:
                return "HY000";
        }
    }
}
