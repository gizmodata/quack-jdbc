package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual-commit transaction handling (issue #4). With autoCommit off the
 * connection must lazily open a server-side transaction before the first
 * statement, otherwise a later {@code commit()} fails on the server with
 * "cannot commit - no transaction is active".
 */
class QuackConnectionTransactionTest {

    @Test
    void manualCommitOpensTransactionLazilyAndCommits() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            assertTrue(transport.statements.isEmpty(),
                    "setAutoCommit alone must not touch the server");

            try (Statement s = connection.createStatement()) {
                s.execute("UPDATE t1 SET v = 'x'");
            }
            connection.commit();
        }

        assertEquals(List.of("BEGIN TRANSACTION", "UPDATE t1 SET v = 'x'", "COMMIT"),
                transport.statements);
    }

    @Test
    void commitWithoutPendingStatementsIsNoOp() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            connection.commit();
            connection.rollback();
        }

        assertEquals(List.of(), transport.statements);
    }

    @Test
    void rollbackEndsTransactionAndNextStatementBeginsAgain() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO t1 VALUES (1)");
                connection.rollback();
                s.execute("INSERT INTO t1 VALUES (2)");
                connection.commit();
            }
        }

        assertEquals(List.of(
                "BEGIN TRANSACTION", "INSERT INTO t1 VALUES (1)", "ROLLBACK",
                "BEGIN TRANSACTION", "INSERT INTO t1 VALUES (2)", "COMMIT"),
                transport.statements);
    }

    @Test
    void enablingAutoCommitCommitsActiveTransaction() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO t1 VALUES (1)");
            }
            connection.setAutoCommit(true);

            try (Statement s = connection.createStatement()) {
                s.execute("SELECT 1");
            }
        }

        assertEquals(List.of(
                "BEGIN TRANSACTION", "INSERT INTO t1 VALUES (1)", "COMMIT",
                "SELECT 1"),
                transport.statements);
    }

    @Test
    void autoCommitModeSendsNoTransactionCommands() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            try (Statement s = connection.createStatement()) {
                s.execute("SELECT 1");
            }
            connection.commit();
            connection.rollback();
        }

        assertEquals(List.of("SELECT 1"), transport.statements);
    }

    private static QuackConnection connect(ScriptedTransport transport) {
        return new QuackConnection(QuackUri.parse("jdbc:quack://example.test:9494"),
                uri -> transport);
    }

    /** Replies success to everything and records the SQL of each PREPARE_REQUEST. */
    private static final class ScriptedTransport implements QuackTransport {

        final List<String> statements = new ArrayList<>();

        @Override
        public QuackMessage send(QuackMessage request) {
            long clientQueryId = request.header().clientQueryId().orElse(0L);
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                                .withConnectionId("tx-test")
                                .withClientQueryId(clientQueryId),
                        Optional.empty(), Optional.empty(), 3L, 60L);
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                statements.add(prepare.sql());
                return new QuackMessage.PrepareResponse(
                        MessageHeader.of(MessageType.PREPARE_RESPONSE)
                                .withConnectionId("tx-test")
                                .withClientQueryId(clientQueryId),
                        List.of(), List.of(), false, List.of(),
                        new HugeIntParts(0, 0));
            }
            if (request instanceof QuackMessage.DisconnectMessage) {
                return new QuackMessage.SuccessResponse(
                        MessageHeader.of(MessageType.SUCCESS_RESPONSE)
                                .withConnectionId("tx-test")
                                .withClientQueryId(clientQueryId));
            }
            throw new AssertionError("unexpected request: " + request.getClass().getSimpleName());
        }
    }
}
