package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.message.VectorCodec;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransportFactory;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live Quack session (protocol version 3, DuckDB 2.0): the server-issued
 * connection id, a client query-id counter, the transport, and the
 * heartbeat that keeps the connection's lease alive while it is idle.
 */
public final class QuackSession implements AutoCloseable {

    /** Rows per DataChunk, DuckDB's STANDARD_VECTOR_SIZE. */
    static final int CHUNK_ROWS = 2048;
    /** Target size of one SEND_DATA request: several chunks, well inside any proxy's body limit. */
    static final int SEND_DATA_BYTES = 4 << 20;
    private static final Duration DISCONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(10);
    private static final HugeIntParts NO_QUERY = new HugeIntParts(0, 0);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final QuackUri uri;
    private final QuackTransport transport;
    private final AtomicLong queryIdSeq = new AtomicLong(1);
    private volatile String connectionId;
    private volatile boolean closed;

    private volatile String serverVersion;
    private volatile String serverPlatform;
    private volatile Duration lease;

    private ScheduledExecutorService heartbeat;
    /** Set once the server says this connection is gone (lease expired, unknown id, invalidated database). */
    private final AtomicReference<RuntimeException> dead = new AtomicReference<>();
    /** The query running now, for {@link #cancelActive()}; {@link #NO_QUERY} when none. */
    private final AtomicReference<HugeIntParts> activeQuery = new AtomicReference<>(NO_QUERY);
    /** Serializes statement traffic (PREPARE, FETCH, SEND_DATA); cancel and heartbeat bypass it. */
    private final Object ioLock = new Object();
    /** The cursor whose result is still streaming from the server, if any. */
    private Cursor streaming;

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
        session.startHeartbeat();
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

    /** The server's DuckDB version from the handshake (e.g. {@code v2.0.0}); empty if it sent none. */
    public Optional<String> serverVersion() {
        return Optional.ofNullable(serverVersion).filter(s -> !s.isEmpty());
    }

    /** The server's platform from the handshake; empty if it sent none. */
    public Optional<String> serverPlatform() {
        return Optional.ofNullable(serverPlatform).filter(s -> !s.isEmpty());
    }

    /** The connection lease the server granted. */
    public Duration lease() {
        return lease;
    }

    public boolean isClosed() {
        return closed;
    }

    private MessageHeader header(MessageType type) {
        return MessageHeader.of(type).withConnectionId(connectionId).withClientQueryId(nextQueryId());
    }

    private void handshake() {
        long leaseSeconds = Math.max(1, uri.heartbeatTimeout().getSeconds());
        QuackMessage.ConnectionRequest request = new QuackMessage.ConnectionRequest(
                MessageHeader.of(MessageType.CONNECTION_REQUEST).withClientQueryId(nextQueryId()),
                uri.token(),
                Optional.of("quack-jdbc/" + QuackDriver.VERSION),
                Optional.of("java"),
                QuackConstants.QUACK_VERSION,
                QuackConstants.QUACK_VERSION,
                uri.clientId(),
                leaseSeconds);
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.ConnectionResponse connResp)) {
            throw new QuackProtocolException(
                    "Expected CONNECTION_RESPONSE, got " + response.getClass().getSimpleName());
        }
        if (connResp.quackVersion() != 0 && connResp.quackVersion() != QuackConstants.QUACK_VERSION) {
            throw new QuackProtocolException("The server speaks Quack protocol version " + connResp.quackVersion()
                    + "; this driver speaks version " + QuackConstants.QUACK_VERSION
                    + " (DuckDB 2.0). quack-jdbc 0.2.0-alpha.6 is the last release for DuckDB 1.5.");
        }
        this.connectionId = connResp.header().connectionId()
                .filter(id -> !id.isEmpty())
                .orElseThrow(() -> new QuackProtocolException("Server did not return a connection_id"));
        this.serverVersion = connResp.serverDuckdbVersion().orElse(null);
        this.serverPlatform = connResp.serverPlatform().orElse(null);
        long granted = connResp.heartbeatTimeoutSeconds();
        this.lease = Duration.ofSeconds(granted > 0 ? granted : leaseSeconds);
    }

    /**
     * Renew the lease about every third of it, with jitter, as DuckDB's own
     * client does. Any request renews it too, so this matters only while the
     * connection is idle.
     */
    private void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quack-heartbeat-" + connectionId);
            t.setDaemon(true);
            return t;
        });
        scheduleHeartbeat();
    }

    private void scheduleHeartbeat() {
        long base = Math.max(200, lease.toMillis() / 3);
        long delay = base - base / 10 + ThreadLocalRandom.current().nextLong(base / 5 + 1);
        try {
            heartbeat.schedule(this::beat, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    private void beat() {
        if (closed) return;
        try {
            transport.send(new QuackMessage.HeartbeatRequest(header(MessageType.HEARTBEAT_REQUEST)));
        } catch (QuackServerException e) {
            // The server answered and refused: the connection is gone.
            // Transport failures are transient: the server expires the lease
            // only after the whole timeout passes without valid traffic.
            dead.compareAndSet(null, new QuackException(
                    "The Quack server closed this connection: " + e.getMessage(), e));
            return;
        } catch (RuntimeException ignored) {
            // transient; try again next period
        }
        scheduleHeartbeat();
    }

    /**
     * Post a request, failing fast once the server said this connection is
     * gone. When the caller gives up on a call (request timeout, interrupt),
     * dropping the HTTP request does not stop the statement on the server,
     * so it is cancelled there too, off the caller's time budget.
     */
    private QuackMessage send(QuackMessage request, HugeIntParts query) {
        if (closed) throw new QuackException("Connection is closed");
        RuntimeException gone = dead.get();
        if (gone != null) throw new QuackException(gone.getMessage(), gone);
        try {
            return transport.send(request);
        } catch (QuackServerException e) {
            if (e.mustInvalidate()) {
                dead.compareAndSet(null, new QuackException(
                        "The Quack server's database is invalidated: " + e.getMessage(), e));
            }
            throw e;
        } catch (QuackException e) {
            if (query != null && !NO_QUERY.equals(query) && isAbandoned(e)) {
                cancelInBackground(query);
            }
            throw e;
        }
    }

    private static boolean isAbandoned(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException || t instanceof InterruptedException) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    private void cancelInBackground(HugeIntParts query) {
        Thread t = new Thread(() -> {
            try {
                CompletableFuture.runAsync(() -> sendCancel(query))
                        .get(CANCEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }, "quack-cancel");
        t.setDaemon(true);
        t.start();
    }

    private void sendCancel(HugeIntParts query) {
        transport.send(new QuackMessage.CancelRequest(header(MessageType.CANCEL_REQUEST), query));
    }

    /** A random non-zero 128-bit query id: the server treats {0,0} as internal traffic. */
    static HugeIntParts newQueryUuid() {
        while (true) {
            HugeIntParts u = new HugeIntParts(RANDOM.nextLong(), RANDOM.nextLong());
            if (!NO_QUERY.equals(u)) return u;
        }
    }

    private void clearActive(HugeIntParts u) {
        activeQuery.compareAndSet(u, NO_QUERY);
    }

    /**
     * Ask the server to interrupt the statement running on this connection,
     * if any (JDBC {@code Statement.cancel()}). The interrupted call then
     * fails with SQLSTATE 57014. Safe to call from another thread.
     */
    public void cancelActive() {
        HugeIntParts u = activeQuery.get();
        if (NO_QUERY.equals(u) || closed) return;
        sendCancel(u);
    }

    /**
     * Open a streaming cursor over a query. Only the chunks the server sent
     * with {@code PREPARE_RESPONSE} are buffered up front; later batches are
     * fetched one at a time as the caller calls {@link Cursor#nextChunk()}.
     *
     * <p>The server streams one result per connection: a new query
     * supersedes an unfinished one. So starting a query while another
     * cursor is still streaming first reads the rest of that result into
     * memory, which keeps both usable (JDBC allows several open ResultSets
     * per connection) at the cost of buffering the older one.
     */
    public Cursor cursor(String sql) {
        return prepare(sql, OptionalLong.empty());
    }

    private Cursor prepare(String sql, OptionalLong inlineRows) {
        synchronized (ioLock) {
            Cursor previous = streaming;
            streaming = null;
            if (previous != null) previous.bufferRest();
            Cursor cursor = prepareLocked(sql, inlineRows);
            if (cursor.needsMore) streaming = cursor;
            return cursor;
        }
    }

    private Cursor prepareLocked(String sql, OptionalLong inlineRows) {
        HugeIntParts uuid = newQueryUuid();
        activeQuery.set(uuid);
        QuackMessage response;
        try {
            response = send(new QuackMessage.PrepareRequest(
                    header(MessageType.PREPARE_REQUEST), sql, uuid, inlineRows), uuid);
        } catch (RuntimeException e) {
            clearActive(uuid);
            throw e;
        }
        if (!(response instanceof QuackMessage.PrepareResponse prep)) {
            clearActive(uuid);
            throw new QuackProtocolException(
                    "Expected PREPARE_RESPONSE, got " + response.getClass().getSimpleName());
        }
        Cursor cursor = new Cursor(this, prep, uuid);
        if (!prep.needsMoreFetch()) clearActive(uuid);
        return cursor;
    }

    /**
     * Append a {@link DataChunk} into {@code schema.tableName}. See
     * {@link #appendChunks(String, String, List)}.
     */
    public long appendChunk(String schema, String tableName, DataChunk chunk) {
        if (chunk == null) {
            throw new QuackProtocolException("appendChunk: chunk is required");
        }
        return appendChunks(schema, tableName, List.of(chunk));
    }

    /**
     * Bulk-load chunks into an existing table, the way DuckDB's own Quack
     * client inserts into an attached table:
     * <pre>
     * INSERT INTO schema.table SELECT * FROM scan_data_from_quack_client('&lt;stream&gt;', NULL::STRUCT(col0 T, ...), ordered := true)
     * </pre>
     * with the rows streamed by {@code SEND_DATA} in 2048-row chunks, several
     * per request. The statement is planned before any data moves, and the
     * terminal {@code SEND_DATA} carries the batch count so a lost batch
     * fails the statement. Columns bind by position, all in one statement:
     * either every row is inserted or none.
     *
     * @param schema the table's schema, or null for an unqualified name
     *               (which also finds temporary tables)
     * @return the number of rows inserted
     */
    public long appendChunks(String schema, String tableName, List<DataChunk> chunks) {
        if (tableName == null || tableName.isEmpty()) {
            throw new QuackProtocolException("appendChunk: tableName is required");
        }
        if (chunks == null || chunks.isEmpty() || chunks.stream().anyMatch(Objects::isNull)) {
            throw new QuackProtocolException("appendChunk: at least one chunk is required");
        }
        List<LogicalType> types = chunks.get(0).types();
        for (DataChunk c : chunks) {
            if (!c.types().equals(types)) {
                throw new QuackProtocolException("appendChunks: every chunk must have the same column types");
            }
        }
        StringBuilder proto = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            LogicalType t = types.get(i);
            if (t.id() == LogicalTypeId.VARIANT) {
                throw new QuackUnsupportedTypeException(
                        "appendChunk cannot send VARIANT columns; send JSON or VARCHAR and cast in SQL");
            }
            if (i > 0) proto.append(", ");
            proto.append("col").append(i).append(' ').append(JdbcTypeMap.sqlTypeName(t));
        }
        String target = (schema == null || schema.isEmpty() ? "" : quoteIdent(schema) + ".") + quoteIdent(tableName);
        String streamId = newStreamId();
        String insert = "INSERT INTO " + target + " SELECT * FROM scan_data_from_quack_client('" + streamId
                + "', NULL::STRUCT(" + proto + "), ordered := true)";

        synchronized (ioLock) {
            try {
                return appendLocked(insert, streamId, chunks);
            } catch (QuackServerException e) {
                // A Binder error means the table exists (a missing table is a
                // Catalog error) but cannot take these columns. The server can
                // raise it from PREPARE, a SEND_DATA or the final FETCH.
                if ("Binder".equalsIgnoreCase(e.getExceptionType())) {
                    throw new QuackServerException("appendChunk: the chunk's columns do not match the existing table "
                            + target + ": " + e.getMessage(), "42S01", e);
                }
                throw e;
            }
        }
    }

    private long appendLocked(String insert, String streamId, List<DataChunk> chunks) {
        Cursor previous = streaming;
        streaming = null;
        if (previous != null) previous.bufferRest();
        Cursor cursor = prepareLocked(insert, OptionalLong.of(0));
        HugeIntParts uuid = cursor.queryUuid;
        activeQuery.set(uuid);
        try {
            long batches = 0;
            List<DataChunk> pending = new ArrayList<>();
            long pendingBytes = 0;
            for (DataChunk chunk : chunks) {
                for (DataChunk piece : split(chunk)) {
                    pending.add(piece);
                    pendingBytes += encodedSize(piece);
                    if (pendingBytes >= SEND_DATA_BYTES) {
                        sendData(streamId, OptionalLong.of(++batches), OptionalLong.empty(), pending, uuid);
                        pending = new ArrayList<>();
                        pendingBytes = 0;
                    }
                }
            }
            if (!pending.isEmpty()) {
                sendData(streamId, OptionalLong.of(++batches), OptionalLong.empty(), pending, uuid);
            }
            sendData(streamId, OptionalLong.empty(), OptionalLong.of(batches), List.of(), uuid);
        } catch (RuntimeException e) {
            // Release the server-side statement promptly: it is waiting for
            // batches that will never come.
            try {
                CompletableFuture.runAsync(() -> sendCancel(uuid))
                        .get(DISCONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // best effort
            }
            clearActive(uuid);
            throw e;
        }
        try (cursor) {
            long total = 0;
            for (DataChunk c : cursor.drainAll()) {
                for (int r = 0; r < c.rowCount(); r++) {
                    Object v = c.columns().isEmpty() ? null : c.columns().get(0).getObject(r);
                    if (v instanceof Number n) total += n.longValue();
                }
            }
            return total;
        } finally {
            clearActive(uuid);
        }
    }

    private void sendData(String streamId, OptionalLong batchIndex, OptionalLong total,
                          List<DataChunk> chunks, HugeIntParts query) {
        QuackMessage resp = send(new QuackMessage.SendDataRequest(
                header(MessageType.SEND_DATA_REQUEST), streamId, chunks, total, batchIndex), query);
        if (!(resp instanceof QuackMessage.SendDataResponse)) {
            throw new QuackProtocolException(
                    "Expected SEND_DATA_RESPONSE, got " + resp.getClass().getSimpleName());
        }
    }

    private static long encodedSize(DataChunk chunk) {
        BinaryWriter w = new BinaryWriter();
        VectorCodec.encodeDataChunk(w, chunk);
        return w.size();
    }

    /** Split a chunk into pieces of at most {@link #CHUNK_ROWS} rows. */
    static List<DataChunk> split(DataChunk chunk) {
        if (chunk.rowCount() <= CHUNK_ROWS) return List.of(chunk);
        List<DataChunk> out = new ArrayList<>();
        for (int start = 0; start < chunk.rowCount(); start += CHUNK_ROWS) {
            int end = Math.min(chunk.rowCount(), start + CHUNK_ROWS);
            List<DecodedVector> cols = new ArrayList<>(chunk.columns().size());
            for (int c = 0; c < chunk.columns().size(); c++) {
                DecodedVector v = chunk.columns().get(c);
                Object[] values = new Object[end - start];
                for (int r = start; r < end; r++) values[r - start] = v.getObject(r);
                cols.add(new DecodedVector.ObjectVec(chunk.types().get(c), values));
            }
            out.add(new DataChunk(end - start, chunk.types(), cols));
        }
        return out;
    }

    private static String newStreamId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (heartbeat != null) heartbeat.shutdownNow();
        if (connectionId == null) return;
        // Best effort, and bounded: a gone server must not hang close().
        try {
            CompletableFuture.runAsync(() -> transport.send(
                            new QuackMessage.DisconnectMessage(header(MessageType.DISCONNECT_MESSAGE))),
                    r -> {
                        Thread t = new Thread(r, "quack-disconnect");
                        t.setDaemon(true);
                        t.start();
                    })
                    .get(DISCONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Best-effort disconnect.
        }
    }

    private long nextQueryId() {
        return queryIdSeq.getAndIncrement();
    }

    /**
     * Streaming cursor over the batches of one query. Holds at most one
     * server batch in memory at a time.
     */
    public static final class Cursor implements AutoCloseable {

        private final QuackSession session;
        private final List<String> columnNames;
        private final List<LogicalType> columnTypes;
        private final HugeIntParts queryUuid;
        private final Deque<DataChunk> buffered;
        private boolean needsMore;
        /** The dense FETCH batch index to ask for next (from 1, after PREPARE's inline batches). */
        private long nextBatch = 1;
        private boolean closed;
        private int materializedRowCount;

        Cursor(QuackSession session, QuackMessage.PrepareResponse prep, HugeIntParts sent) {
            this.session = session;
            this.columnNames = prep.resultNames();
            this.columnTypes = prep.resultTypes();
            this.queryUuid = NO_QUERY.equals(prep.queryUuid()) ? sent : prep.queryUuid();
            this.buffered = new ArrayDeque<>(prep.results());
            this.needsMore = prep.needsMoreFetch();
            for (DataChunk c : prep.results()) materializedRowCount += c.rowCount();
        }

        public List<String> columnNames() {
            return columnNames;
        }

        public List<LogicalType> columnTypes() {
            return columnTypes;
        }

        /** True while batches remain on the server. */
        public boolean hasMoreOnServer() {
            return needsMore;
        }

        /** Peek at the first buffered chunk without advancing the cursor. */
        public DataChunk peekFirstChunk() {
            return buffered.peek();
        }

        /**
         * Return the next chunk, fetching from the server if the local
         * buffer is empty and more batches are available. Returns
         * {@code null} when the result set is exhausted.
         */
        public DataChunk nextChunk() {
            if (closed) return null;
            synchronized (session.ioLock) {
                while (buffered.isEmpty() && needsMore) {
                    fetchMore();
                }
                return buffered.poll();
            }
        }

        /** Drain every remaining chunk and return them in order. */
        public List<DataChunk> drainAll() {
            synchronized (session.ioLock) {
                List<DataChunk> all = new ArrayList<>(buffered);
                buffered.clear();
                while (needsMore) {
                    fetchMore();
                    all.addAll(buffered);
                    buffered.clear();
                }
                return all;
            }
        }

        /** Read the rest of the result into memory (a new query is about to supersede it). */
        private void bufferRest() {
            if (closed) return;
            while (needsMore) {
                fetchMore();
            }
        }

        /**
         * Ask for batch {@code nextBatch}. A batch response carries its index
         * and chunks; the terminal response carries no index and, when the
         * server knows it, the total batch count, which must match what
         * arrived: a short stream is an error, never a truncated result.
         */
        private void fetchMore() {
            QuackMessage resp;
            try {
                resp = session.send(new QuackMessage.FetchRequest(
                        session.header(MessageType.FETCH_REQUEST), queryUuid, nextBatch, nextBatch - 1), queryUuid);
            } catch (RuntimeException e) {
                needsMore = false;
                session.clearActive(queryUuid);
                throw e;
            }
            if (!(resp instanceof QuackMessage.FetchResponse fr)) {
                needsMore = false;
                session.clearActive(queryUuid);
                throw new QuackProtocolException("Expected FETCH_RESPONSE, got " + resp.getClass().getSimpleName());
            }
            if (fr.batchIndex().isPresent()) {
                if (fr.batchIndex().getAsLong() != nextBatch) {
                    needsMore = false;
                    session.clearActive(queryUuid);
                    throw new QuackProtocolException("Asked for batch " + nextBatch + ", the server sent batch "
                            + fr.batchIndex().getAsLong());
                }
                buffered.addAll(fr.results());
                for (DataChunk c : fr.results()) materializedRowCount += c.rowCount();
                nextBatch++;
                return;
            }
            needsMore = false;
            session.clearActive(queryUuid);
            if (session.streaming == this) session.streaming = null;
            buffered.addAll(fr.results());
            if (fr.totalBatches().isPresent() && fr.totalBatches().getAsLong() != nextBatch - 1) {
                throw new QuackProtocolException("Result truncated: the server announced "
                        + fr.totalBatches().getAsLong() + " batches, received " + (nextBatch - 1));
            }
        }

        /**
         * Count of rows already pulled across the wire (not the total
         * result size). Useful for diagnostics and tests.
         */
        public int materializedRowCount() {
            return materializedRowCount;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            buffered.clear();
            synchronized (session.ioLock) {
                if (session.streaming == this) session.streaming = null;
                if (needsMore) {
                    // The server releases an abandoned result when the next query
                    // supersedes it, or on disconnect.
                    needsMore = false;
                    session.clearActive(queryUuid);
                }
            }
        }
    }
}
