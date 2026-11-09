package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.type.LogicalType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Quack protocol 3 messages (DuckDB 2.0). Field ids and defaults mirror
 * duckdb-quack's {@code serialize_quack_message.cpp}. {@link FetchResponse}
 * and {@link SendDataRequest} carry their chunks in a raw blob after the
 * message body.
 */
public sealed interface QuackMessage {

    MessageHeader header();

    record ConnectionRequest(MessageHeader header,
                             Optional<String> authString,
                             Optional<String> clientDuckdbVersion,
                             Optional<String> clientPlatform,
                             long minSupportedQuackVersion,
                             long maxSupportedQuackVersion,
                             Optional<String> clientId,
                             long heartbeatTimeoutSeconds) implements QuackMessage {}

    record ConnectionResponse(MessageHeader header,
                              Optional<String> serverDuckdbVersion,
                              Optional<String> serverPlatform,
                              long quackVersion,
                              long heartbeatTimeoutSeconds) implements QuackMessage {}

    /** {@code inlineRows}: rows PREPARE may answer with; empty = server default, 0 = answer before any row. */
    record PrepareRequest(MessageHeader header, String sql, HugeIntParts queryUuid,
                          OptionalLong inlineRows) implements QuackMessage {}

    record PrepareResponse(MessageHeader header,
                           List<LogicalType> resultTypes,
                           List<String> resultNames,
                           boolean needsMoreFetch,
                           List<DataChunk> results,
                           HugeIntParts queryUuid) implements QuackMessage {}

    /** One dense batch (from 1, after PREPARE's inline batches); {@code ackIndex}: every batch up to it arrived. */
    record FetchRequest(MessageHeader header, HugeIntParts queryUuid, long batchIndex,
                        long ackIndex) implements QuackMessage {}

    /** A batch ({@code batchIndex} set), or the terminal response ({@code totalBatches} when known). */
    record FetchResponse(MessageHeader header,
                         List<DataChunk> results,
                         OptionalLong totalBatches,
                         OptionalLong batchIndex) implements QuackMessage {}

    /** One batch of client rows for a scan_data_from_quack_client stream, or the terminal message (no chunks, total set). */
    record SendDataRequest(MessageHeader header, String streamId, List<DataChunk> chunks,
                           OptionalLong totalBatches, OptionalLong batchIndex) implements QuackMessage {}

    record SendDataResponse(MessageHeader header, OptionalLong acceptBudget) implements QuackMessage {}

    record SuccessResponse(MessageHeader header) implements QuackMessage {}

    record DisconnectMessage(MessageHeader header) implements QuackMessage {}

    record HeartbeatRequest(MessageHeader header) implements QuackMessage {}

    /** {@code queryUuid} {0,0} cancels whatever runs on the connection. */
    record CancelRequest(MessageHeader header, HugeIntParts queryUuid) implements QuackMessage {}

    record Acknowledgement(MessageHeader header, HugeIntParts queryUuid) implements QuackMessage {}

    /** {@code exceptionType} is DuckDB's (e.g. "Catalog"); {@code mustInvalidate}: the server's database is gone. */
    record ErrorResponse(MessageHeader header, String message, String exceptionType,
                         Map<String, String> extraInfo, boolean mustInvalidate) implements QuackMessage {}
}
