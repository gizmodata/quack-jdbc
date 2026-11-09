package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackServerException;
import org.junit.jupiter.api.Test;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class QuackSqlExceptionsTest {

    private static SQLException map(String type, String message) {
        return QuackSqlExceptions.from(new QuackServerException(message, type, Map.of(), false));
    }

    @Test
    void duckDbExceptionTypesMapToSqlStates() {
        assertEquals("42S02", map("Catalog", "Catalog Error: Table with name nope does not exist!").getSQLState());
        assertEquals("42S01", map("Catalog", "Catalog Error: Table with name t already exists!").getSQLState());
        assertEquals("42S22", map("Binder", "Binder Error: Referenced column \"x\" not found in FROM clause!").getSQLState());
        assertEquals("42601", map("Parser", "Parser Error: syntax error at or near \"SELEC\"").getSQLState());
        assertEquals("23000", map("Constraint", "Constraint Error: Duplicate key").getSQLState());
        assertEquals("22012", map("Divide by Zero", "Divide by zero").getSQLState());
        assertEquals("22003", map("Out of Range", "Out of Range Error").getSQLState());
        assertEquals("22000", map("Conversion", "Conversion Error: Could not convert").getSQLState());
        assertEquals("25000", map("TransactionContext", "cannot commit - no transaction is active").getSQLState());
        assertEquals("57014", map("Interrupt", "INTERRUPT Error: Interrupted!").getSQLState());
        assertEquals("0A000", map("Not implemented", "Not implemented Error").getSQLState());
        assertEquals("28000", map("", "authentication failed").getSQLState());
        assertEquals("08003", map("", "heartbeat lease expired").getSQLState());
        assertEquals("XX000", map("Internal", "INTERNAL Error").getSQLState());
        assertEquals("HY000", map("Something New", "x").getSQLState());
    }

    @Test
    void sqlStatesPickTheJdbcSubclass() {
        assertInstanceOf(SQLSyntaxErrorException.class, map("Catalog", "Table with name x does not exist!"));
        assertInstanceOf(SQLIntegrityConstraintViolationException.class, map("Constraint", "dup"));
        assertInstanceOf(SQLDataException.class, map("Conversion", "bad"));
        assertInstanceOf(SQLInvalidAuthorizationSpecException.class, map("", "authentication failed"));
        SQLException transport = QuackSqlExceptions.from(new QuackException("connection refused"));
        assertInstanceOf(SQLNonTransientConnectionException.class, transport);
        assertEquals("08006", transport.getSQLState());
    }

    @Test
    void wrappedServerErrorsKeepTheirSqlState() {
        QuackServerException cause = new QuackServerException("Catalog Error: Table with name t does not exist!",
                "Catalog", Map.of(), false);
        assertEquals("42S02", QuackSqlExceptions.from(new QuackException("wrapped", cause)).getSQLState());
    }
}
