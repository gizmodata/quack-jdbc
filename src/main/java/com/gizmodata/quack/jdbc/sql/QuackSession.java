package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransportFactory;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Live Quack session: connection id, query-id sequence, and a Quack transport. */
public final class QuackSession implements AutoCloseable {

    private final QuackUri uri;
    private final QuackTransport transport;
    private final AtomicLong queryIdSeq = new AtomicLong(1);
    private volatile String connectionId;
    private volatile boolean closed;

    public QuackSession(QuackUri uri, QuackTransport transport) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public QuackSession(QuackUri uri, QuackHttpTransport transport) {
        this(uri, (QuackTransport) transport);
    }

    public static QuackSession connect(QuackUri uri) {
        return connect(uri, QuackTransportFactory.http());
    }

    public static QuackSession connect(QuackUri uri, QuackTransport transport) {
        QuackSession session = new QuackSession(uri, transport);
        session.handshake();
        return session;
    }

    public static QuackSession connect(QuackUri uri, QuackTransportFactory transportFactory) {
        Objects.requireNonNull(transportFactory, "transportFactory");
        return connect(uri, Objects.requireNonNull(
                transportFactory.create(uri), "transportFactory returned null"));
    }

    public QuackUri uri() {
        return uri;
    }

    public String connectionId() {
        return connectionId;
    }

    public boolean isClosed() {
        return closed;
    }

    private void handshake() {
        MessageHeader header = MessageHeader.of(MessageType.CONNECTION_REQUEST)
                .withClientQueryId(nextQueryId());
        QuackMessage.ConnectionRequest request = new QuackMessage.ConnectionRequest(
                header,
                uri.token(),
                Optional.of("quack-jdbc/0.1.0"),
                Optional.of(System.getProperty("os.name", "unknown")),
                Optional.of(QuackConstants.QUACK_VERSION),
                Optional.of(QuackConstants.QUACK_VERSION));
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.ConnectionResponse connResp)) {
            throw new QuackProtocolException(
                    "Expected CONNECTION_RESPONSE, got " + response.getClass().getSimpleName());
        }
        this.connectionId = connResp.header().connectionId().orElseThrow(
                () -> new QuackProtocolException("Server did not return a connection_id"));
    }

    /**
     * Open a streaming cursor over a prepared query. Only the initial
     * batch of chunks is fetched up front (whatever the server included
     * in {@code PREPARE_RESPONSE}); subsequent chunks are fetched on
     * demand as the caller calls {@link Cursor#nextChunk()}.
     */
    public Cursor cursor(String sql) {
        if (closed) {
            throw new QuackProtocolException("Session is closed");
        }
        QuackMessage.PrepareRequest request = new QuackMessage.PrepareRequest(
                MessageHeader.of(MessageType.PREPARE_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                sql);
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.PrepareResponse prep)) {
            throw new QuackProtocolException(
                    "Expected PREPARE_RESPONSE, got " + response.getClass().getSimpleName());
        }
        return new Cursor(this, prep);
    }

    /**
     * Append a {@link DataChunk} into {@code schema.tableName} via a Quack
     * {@code APPEND_REQUEST}. The chunk's column count and types must
     * match the destination table; rows are appended atomically as a
     * single server-side batch.
     *
     * <p>This is the bulk-load fast-path — it sends column-oriented
     * binary data directly, bypassing per-row INSERT parsing. For typical
     * workloads it's an order of magnitude faster than
     * {@code PreparedStatement.executeBatch()}.
     */
    public void appendChunk(String schema, String tableName, DataChunk chunk) {
        if (closed) {
            throw new QuackProtocolException("Session is closed");
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new QuackProtocolException("appendChunk: tableName is required");
        }
        if (chunk == null) {
            throw new QuackProtocolException("appendChunk: chunk is required");
        }
        QuackMessage.AppendRequest request = new QuackMessage.AppendRequest(
                MessageHeader.of(MessageType.APPEND_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                Optional.ofNullable(schema),
                tableName,
                chunk);
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.SuccessResponse)) {
            throw new QuackProtocolException(
                    "Expected SUCCESS_RESPONSE for APPEND, got "
                            + response.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void close() {
        if (closed || connectionId == null) {
            closed = true;
            return;
        }
        try {
            QuackMessage.DisconnectMessage disconnect = new QuackMessage.DisconnectMessage(
                    MessageHeader.of(MessageType.DISCONNECT_MESSAGE)
                            .withConnectionId(connectionId)
                            .withClientQueryId(nextQueryId()));
            transport.send(disconnect);
        } catch (RuntimeException ignored) {
            // Best-effort disconnect.
        } finally {
            closed = true;
        }
    }

    private long nextQueryId() {
        return queryIdSeq.getAndIncrement();
    }

    private List<DataChunk> fetchMoreChunks(HugeIntParts resultUuid, FetchState state) {
        QuackMessage.FetchRequest fetch = new QuackMessage.FetchRequest(
                MessageHeader.of(MessageType.FETCH_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                resultUuid);
        QuackMessage fr = transport.send(fetch);
        if (!(fr instanceof QuackMessage.FetchResponse fetchResp)) {
            throw new QuackProtocolException(
                    "Expected FETCH_RESPONSE, got " + fr.getClass().getSimpleName());
        }
        state.hasMore = fetchResp.batchIndex().isPresent();
        return fetchResp.results();
    }

    /**
     * Streaming cursor over the chunks of a Quack-prepared query.
     *
     * <p>Holds at most one server batch in memory at a time
     * (whatever {@code quack_fetch_batch_chunks} is set to on the
     * server, default 12). {@link #nextChunk()} pulls from the
     * buffered batch and issues a fresh {@code FETCH_REQUEST} when
     * the buffer is empty and the server signalled more chunks were
     * available.
     */
    public static final class Cursor implements AutoCloseable {

        private final QuackSession session;
        private final List<String> columnNames;
        private final List<LogicalType> columnTypes;
        private final HugeIntParts resultUuid;
        private final Deque<DataChunk> buffered;
        private final FetchState fetchState;
        private boolean closed;
        private int materializedRowCount;

        Cursor(QuackSession session, QuackMessage.PrepareResponse prep) {
            this.session = session;
            this.columnNames = prep.resultNames();
            this.columnTypes = prep.resultTypes();
            this.resultUuid = prep.resultUuid();
            this.buffered = new ArrayDeque<>(prep.results());
            this.fetchState = new FetchState(prep.needsMoreFetch());
            for (DataChunk c : prep.results()) materializedRowCount += c.rowCount();
        }

        public List<String> columnNames() {
            return columnNames;
        }

        public List<LogicalType> columnTypes() {
            return columnTypes;
        }

        /** Peek at the first buffered chunk without advancing the cursor. */
        public DataChunk peekFirstChunk() {
            return buffered.peek();
        }

        /**
         * Return the next chunk, fetching from the server if the local
         * buffer is empty and more chunks are available. Returns
         * {@code null} when the result set is exhausted.
         */
        public DataChunk nextChunk() {
            if (closed) return null;
            if (buffered.isEmpty() && fetchState.hasMore) {
                List<DataChunk> next = session.fetchMoreChunks(resultUuid, fetchState);
                buffered.addAll(next);
                for (DataChunk c : next) materializedRowCount += c.rowCount();
            }
            return buffered.poll();
        }

        /** Drain every remaining chunk and return them in order. */
        public List<DataChunk> drainAll() {
            List<DataChunk> all = new ArrayList<>(buffered);
            buffered.clear();
            while (fetchState.hasMore) {
                List<DataChunk> next = session.fetchMoreChunks(resultUuid, fetchState);
                all.addAll(next);
                for (DataChunk c : next) materializedRowCount += c.rowCount();
            }
            return all;
        }

        /**
         * Approximate count of rows already pulled across the wire (not the
         * total result-set size — only what the cursor has seen). Useful for
         * diagnostics and tests.
         */
        public int materializedRowCount() {
            return materializedRowCount;
        }

        @Override
        public void close() {
            closed = true;
            buffered.clear();
            // Quack has no explicit "release result" message yet; the server
            // releases state on DISCONNECT or after all chunks have been
            // fetched. Drain in the background if needed to free server-side
            // state, but for now leave any un-fetched chunks to be reaped on
            // disconnect — closing a JDBC ResultSet shouldn't block.
        }
    }

    private static final class FetchState {
        boolean hasMore;

        FetchState(boolean hasMore) {
            this.hasMore = hasMore;
        }
    }
}
