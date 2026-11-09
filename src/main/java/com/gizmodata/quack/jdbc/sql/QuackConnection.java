package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.transport.QuackTransportFactory;
import com.gizmodata.quack.jdbc.transport.QuackUri;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public final class QuackConnection extends SkeletalConnection {

    private final QuackUri uri;
    private final QuackSession session;
    private boolean autoCommit = true;
    private boolean transactionActive;
    private String catalog;
    private String schema;
    private volatile boolean closed;

    public QuackConnection(QuackUri uri) {
        this(uri, QuackTransportFactory.http());
    }

    public QuackConnection(QuackUri uri, QuackTransportFactory transportFactory) {
        this.uri = uri;
        this.session = QuackSession.connect(uri, transportFactory);
        this.catalog = uri.database().orElse(null);
    }

    public QuackSession session() {
        return session;
    }

    QuackUri uri() {
        return uri;
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new QuackStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        return new QuackPreparedStatement(this, sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (this.autoCommit == autoCommit) {
            return;
        }
        // JDBC spec: changing auto-commit mode mid-transaction commits it.
        if (transactionActive) {
            runTransactionCommand("COMMIT");
            transactionActive = false;
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        if (transactionActive) {
            runTransactionCommand("COMMIT");
            transactionActive = false;
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        if (transactionActive) {
            runTransactionCommand("ROLLBACK");
            transactionActive = false;
        }
    }

    /**
     * Lazily open a server-side transaction before the first statement
     * executed in manual-commit mode. The server stays in auto-commit
     * until a statement actually runs, so {@code commit()} with nothing
     * pending is a harmless no-op instead of a server error
     * ("cannot commit - no transaction is active").
     */
    void beginTransactionIfNeeded() throws SQLException {
        if (autoCommit || transactionActive) {
            return;
        }
        checkOpen();
        runTransactionCommand("BEGIN TRANSACTION");
        transactionActive = true;
    }

    private void runTransactionCommand(String sql) throws SQLException {
        try {
            session.cursor(sql).close();
        } catch (RuntimeException e) {
            throw QuackSqlExceptions.from(e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return new QuackDatabaseMetaData(this);
    }

    @Override
    public String getCatalog() {
        return catalog;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        if (catalog != null && !catalog.isEmpty() && !catalog.equals(this.catalog)) {
            try (Statement s = createStatement()) {
                s.execute("USE " + quoteIdent(catalog)
                        + (schema != null && !schema.isEmpty() ? "." + quoteIdent(schema) : ""));
            }
        }
        this.catalog = catalog;
    }

    @Override
    public String getSchema() {
        return schema;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        if (schema != null && !schema.isEmpty() && !schema.equals(this.schema)) {
            try (Statement s = createStatement()) {
                String catPrefix = catalog != null && !catalog.isEmpty()
                        ? quoteIdent(catalog) + "."
                        : "";
                s.execute("USE " + catPrefix + quoteIdent(schema));
            }
        }
        this.schema = schema;
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }
}
