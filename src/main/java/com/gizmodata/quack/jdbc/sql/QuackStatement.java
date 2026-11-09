package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class QuackStatement extends SkeletalStatement {

    private final QuackConnection connection;
    private final List<String> batch = new ArrayList<>();
    private QuackResultSet currentResultSet;
    private int updateCount = -1;
    private boolean closed;

    public QuackStatement(QuackConnection connection) {
        this.connection = connection;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        checkOpen();
        batch.add(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        int[] counts = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            try {
                counts[i] = executeUpdate(batch.get(i));
            } catch (SQLException e) {
                counts[i] = java.sql.Statement.EXECUTE_FAILED;
                batch.clear();
                throw new java.sql.BatchUpdateException(e.getMessage(), counts, e);
            }
        }
        batch.clear();
        return counts;
    }

    /**
     * Interrupt the statement running on this connection: sends a Quack
     * {@code CANCEL} for it, and the interrupted call fails with SQLSTATE
     * 57014. A no-op when nothing is running.
     */
    @Override
    public void cancel() throws SQLException {
        if (closed) throw new SQLException("Statement is closed");
        try {
            connection.session().cancelActive();
        } catch (RuntimeException e) {
            throw QuackSqlExceptions.from(e);
        }
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        execute(sql);
        if (currentResultSet == null) {
            throw new SQLException("Query did not produce a ResultSet: " + sql);
        }
        return currentResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        execute(sql);
        return updateCount < 0 ? 0 : updateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkOpen();
        connection.beginTransactionIfNeeded();
        try {
            if (currentResultSet != null) {
                currentResultSet.close();
                currentResultSet = null;
            }
            QuackSession.Cursor cursor = connection.session().cursor(sql);
            if (looksLikeRowsAffected(cursor)) {
                List<DataChunk> chunks = cursor.drainAll();
                cursor.close();
                updateCount = extractRowsAffected(chunks);
                currentResultSet = null;
                return false;
            }
            updateCount = -1;
            currentResultSet = new QuackResultSet(this, cursor);
            return true;
        } catch (RuntimeException e) {
            throw QuackSqlExceptions.from(e);
        }
    }

    private boolean looksLikeRowsAffected(QuackSession.Cursor cursor) {
        if (cursor.columnNames().size() != 1) return false;
        String name = cursor.columnNames().get(0);
        if (!"Count".equalsIgnoreCase(name) && !"rows_affected".equalsIgnoreCase(name)) {
            return false;
        }
        if (cursor.columnTypes().isEmpty()) return false;
        LogicalTypeId id = cursor.columnTypes().get(0).id();
        return (id == LogicalTypeId.BIGINT || id == LogicalTypeId.INTEGER
                || id == LogicalTypeId.UBIGINT || id == LogicalTypeId.UINTEGER)
                && (first(cursor) == null || first(cursor).rowCount() <= 1);
    }

    private static DataChunk first(QuackSession.Cursor cursor) {
        return cursor.peekFirstChunk();
    }

    private int extractRowsAffected(List<DataChunk> chunks) {
        long total = 0;
        for (DataChunk chunk : chunks) {
            if (chunk.columns().isEmpty()) continue;
            for (int r = 0; r < chunk.rowCount(); r++) {
                if (chunk.columns().get(0).getObject(r) instanceof Number n) total += n.longValue();
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    @Override
    public ResultSet getResultSet() {
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() {
        return updateCount;
    }

    /**
     * Advance past the current result. Quack executes exactly one statement
     * per {@link #execute(String)}, so there is never a second result: this
     * closes the current {@link ResultSet} (if any) and clears the update
     * count. Per the JDBC contract, callers detect end-of-results when
     * {@code getMoreResults() == false && getUpdateCount() == -1}; without
     * resetting the count here, tools like DataGrip / DBeaver that drain
     * results in a loop after an INSERT/UPDATE/DELETE would spin forever
     * because {@link #getUpdateCount()} would keep reporting the affected-row
     * count instead of {@code -1}.
     */
    @Override
    public boolean getMoreResults() {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
        updateCount = -1;
        return false;
    }

    @Override
    public boolean getMoreResults(int current) {
        // KEEP_CURRENT_RESULT / CLOSE_ALL_RESULTS only matter with multiple
        // open result sets, which Quack never produces. Behave like the
        // no-arg form so the drain loop terminates.
        return getMoreResults();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (currentResultSet != null) {
            currentResultSet.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    protected void checkOpen() throws SQLException {
        if (closed) throw new SQLException("Statement is closed");
    }
}
