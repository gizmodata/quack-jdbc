package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Encodes and decodes Quack protocol 3 messages: a header object, a body
 * object, and, for {@code FETCH_RESPONSE} and {@code SEND_DATA_REQUEST},
 * a blob of {@code chunk_count} DataChunk objects written back to back
 * after the body. Field ids and defaults mirror duckdb-quack's
 * {@code serialize_quack_message.cpp}.
 */
public final class MessageCodec {

    private static final HugeIntParts ZERO = new HugeIntParts(0, 0);

    private MessageCodec() {
    }

    public static byte[] encode(QuackMessage message) {
        BinaryWriter writer = new BinaryWriter(256);
        encodeHeader(writer, message.header(), message);
        encodeBody(writer, message);
        encodeBlob(writer, message);
        return writer.toByteArray();
    }

    public static QuackMessage decode(byte[] bytes) {
        BinaryReader reader = new BinaryReader(bytes);
        MessageHeader header = decodeHeader(reader);
        QuackMessage message = decodeBody(reader, header);
        reader.assertEof();
        return message;
    }

    private static void encodeHeader(BinaryWriter writer, MessageHeader header, QuackMessage message) {
        writer.writeObject(obj -> {
            obj.writeField(1, () -> obj.writeUleb(messageType(message).wireId()));
            header.connectionId().filter(id -> !id.isEmpty()).ifPresent(id ->
                    obj.writeField(2, () -> obj.writeString(id)));
            obj.writeField(3, () -> obj.writeUleb(
                    header.clientQueryId().orElse(QuackConstants.OPTIONAL_INDEX_INVALID)));
        });
    }

    private static MessageHeader decodeHeader(BinaryReader reader) {
        return reader.readObject(() -> {
            MessageType type = MessageType.fromWireId(reader.readRequiredField(1, reader::readUlebInt));
            Optional<String> connectionId = Optional.ofNullable(
                    reader.readOptionalField(2, reader::readString, null)).filter(s -> !s.isEmpty());
            long rawQueryId = reader.readOptionalField(3, reader::readUlebLong,
                    QuackConstants.OPTIONAL_INDEX_INVALID);
            Optional<Long> clientQueryId = rawQueryId == QuackConstants.OPTIONAL_INDEX_INVALID
                    ? Optional.empty() : Optional.of(rawQueryId);
            return new MessageHeader(type, connectionId, clientQueryId);
        });
    }

    static MessageType messageType(QuackMessage message) {
        if (message instanceof QuackMessage.ConnectionRequest) return MessageType.CONNECTION_REQUEST;
        if (message instanceof QuackMessage.ConnectionResponse) return MessageType.CONNECTION_RESPONSE;
        if (message instanceof QuackMessage.PrepareRequest) return MessageType.PREPARE_REQUEST;
        if (message instanceof QuackMessage.PrepareResponse) return MessageType.PREPARE_RESPONSE;
        if (message instanceof QuackMessage.FetchRequest) return MessageType.FETCH_REQUEST;
        if (message instanceof QuackMessage.FetchResponse) return MessageType.FETCH_RESPONSE;
        if (message instanceof QuackMessage.SendDataRequest) return MessageType.SEND_DATA_REQUEST;
        if (message instanceof QuackMessage.SendDataResponse) return MessageType.SEND_DATA_RESPONSE;
        if (message instanceof QuackMessage.SuccessResponse) return MessageType.SUCCESS_RESPONSE;
        if (message instanceof QuackMessage.DisconnectMessage) return MessageType.DISCONNECT_MESSAGE;
        if (message instanceof QuackMessage.HeartbeatRequest) return MessageType.HEARTBEAT_REQUEST;
        if (message instanceof QuackMessage.CancelRequest) return MessageType.CANCEL_REQUEST;
        if (message instanceof QuackMessage.Acknowledgement) return MessageType.ACKNOWLEDGEMENT;
        if (message instanceof QuackMessage.ErrorResponse) return MessageType.ERROR_RESPONSE;
        throw new QuackProtocolException("Unknown message: " + message.getClass());
    }

    private static void encodeBody(BinaryWriter writer, QuackMessage message) {
        if (message instanceof QuackMessage.ConnectionRequest m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, m.authString());
                writeOptionalString(obj, 2, m.clientDuckdbVersion());
                writeOptionalString(obj, 3, m.clientPlatform());
                writeIndexNonZero(obj, 4, m.minSupportedQuackVersion());
                writeIndexNonZero(obj, 5, m.maxSupportedQuackVersion());
                writeOptionalString(obj, 6, m.clientId());
                writeIndexNonZero(obj, 7, m.heartbeatTimeoutSeconds());
            });
        } else if (message instanceof QuackMessage.ConnectionResponse m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, m.serverDuckdbVersion());
                writeOptionalString(obj, 2, m.serverPlatform());
                writeIndexNonZero(obj, 3, m.quackVersion());
                writeIndexNonZero(obj, 4, m.heartbeatTimeoutSeconds());
            });
        } else if (message instanceof QuackMessage.PrepareRequest m) {
            writer.writeObject(obj -> {
                if (m.sql() != null && !m.sql().isEmpty()) {
                    obj.writeField(1, () -> obj.writeString(m.sql()));
                }
                obj.writeField(2, () -> obj.writeHugeInt(m.queryUuid()));
                writeOptionalIndex(obj, 3, m.inlineRows());
            });
        } else if (message instanceof QuackMessage.PrepareResponse m) {
            writer.writeObject(obj -> {
                if (!m.resultTypes().isEmpty()) {
                    obj.writeField(1, () -> obj.writeList(m.resultTypes(),
                            (t, i) -> LogicalTypeCodec.encode(obj, t)));
                }
                if (!m.resultNames().isEmpty()) {
                    obj.writeField(2, () -> obj.writeList(m.resultNames(), (n, i) -> obj.writeString(n)));
                }
                if (m.needsMoreFetch()) {
                    obj.writeField(3, () -> obj.writeBool(true));
                }
                if (!m.results().isEmpty()) {
                    obj.writeField(4, () -> obj.writeList(m.results(), (chunk, i) -> obj.writeNullable(chunk,
                            value -> VectorCodec.encodeDataChunkWrapper(obj, value))));
                }
                obj.writeField(5, () -> obj.writeHugeInt(m.queryUuid()));
            });
        } else if (message instanceof QuackMessage.FetchRequest m) {
            writer.writeObject(obj -> {
                obj.writeField(1, () -> obj.writeHugeInt(m.queryUuid()));
                writeIndexNonZero(obj, 2, m.batchIndex());
                writeIndexNonZero(obj, 3, m.ackIndex());
            });
        } else if (message instanceof QuackMessage.FetchResponse m) {
            writer.writeObject(obj -> {
                writeIndexNonZero(obj, 1, m.results().size());
                writeOptionalIndex(obj, 2, m.totalBatches());
                writeOptionalIndex(obj, 3, m.batchIndex());
            });
        } else if (message instanceof QuackMessage.SendDataRequest m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, Optional.ofNullable(m.streamId()));
                writeIndexNonZero(obj, 2, m.chunks().size());
                writeOptionalIndex(obj, 3, m.totalBatches());
                writeOptionalIndex(obj, 4, m.batchIndex());
            });
        } else if (message instanceof QuackMessage.SendDataResponse m) {
            writer.writeObject(obj -> obj.writeField(1, () -> obj.writeUleb(
                    m.acceptBudget().isPresent() ? m.acceptBudget().getAsLong()
                            : QuackConstants.OPTIONAL_INDEX_INVALID)));
        } else if (message instanceof QuackMessage.SuccessResponse
                || message instanceof QuackMessage.DisconnectMessage
                || message instanceof QuackMessage.HeartbeatRequest) {
            writer.writeObject(obj -> { });
        } else if (message instanceof QuackMessage.CancelRequest m) {
            writer.writeObject(obj -> obj.writeField(1, () -> obj.writeHugeInt(m.queryUuid())));
        } else if (message instanceof QuackMessage.Acknowledgement m) {
            writer.writeObject(obj -> {
                if (!ZERO.equals(m.queryUuid())) {
                    obj.writeField(1, () -> obj.writeHugeInt(m.queryUuid()));
                }
            });
        } else if (message instanceof QuackMessage.ErrorResponse m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, Optional.ofNullable(m.message()));
                writeOptionalString(obj, 2, Optional.ofNullable(m.exceptionType()));
                if (m.extraInfo() != null && !m.extraInfo().isEmpty()) {
                    List<Map.Entry<String, String>> entries = new ArrayList<>(new TreeMap<>(m.extraInfo()).entrySet());
                    obj.writeField(3, () -> obj.writeList(entries, (e, i) -> obj.writeObject(pair -> {
                        pair.writeField(0, () -> pair.writeString(e.getKey()));
                        pair.writeField(1, () -> pair.writeString(e.getValue()));
                    })));
                }
                if (m.mustInvalidate()) {
                    obj.writeField(4, () -> obj.writeBool(true));
                }
            });
        } else {
            throw new QuackProtocolException("Cannot encode " + message.getClass().getSimpleName());
        }
    }

    /** The chunk blob after FETCH_RESPONSE / SEND_DATA_REQUEST bodies: one self-delimiting object per chunk. */
    private static void encodeBlob(BinaryWriter writer, QuackMessage message) {
        List<DataChunk> chunks;
        if (message instanceof QuackMessage.FetchResponse m) {
            chunks = m.results();
        } else if (message instanceof QuackMessage.SendDataRequest m) {
            chunks = m.chunks();
        } else {
            return;
        }
        for (DataChunk chunk : chunks) {
            VectorCodec.encodeDataChunk(writer, chunk);
        }
    }

    private static List<DataChunk> readChunkBlob(BinaryReader reader, long count) {
        List<DataChunk> chunks = new ArrayList<>((int) Math.min(count, 1024));
        for (long i = 0; i < count; i++) {
            chunks.add(VectorCodec.decodeDataChunk(reader));
        }
        return chunks;
    }

    private static QuackMessage decodeBody(BinaryReader reader, MessageHeader header) {
        return switch (header.type()) {
            case CONNECTION_REQUEST -> reader.readObject(() -> new QuackMessage.ConnectionRequest(
                    header,
                    optionalString(reader, 1),
                    optionalString(reader, 2),
                    optionalString(reader, 3),
                    reader.readOptionalField(4, reader::readUlebLong, 0L),
                    reader.readOptionalField(5, reader::readUlebLong, 0L),
                    optionalString(reader, 6),
                    reader.readOptionalField(7, reader::readUlebLong, 0L)));
            case CONNECTION_RESPONSE -> reader.readObject(() -> new QuackMessage.ConnectionResponse(
                    header,
                    optionalString(reader, 1),
                    optionalString(reader, 2),
                    reader.readOptionalField(3, reader::readUlebLong, 0L),
                    reader.readOptionalField(4, reader::readUlebLong, 0L)));
            case PREPARE_REQUEST -> reader.readObject(() -> new QuackMessage.PrepareRequest(
                    header,
                    reader.readOptionalField(1, reader::readString, ""),
                    reader.readRequiredField(2, reader::readHugeInt),
                    optionalIndex(reader, 3)));
            case PREPARE_RESPONSE -> reader.readObject(() -> {
                List<LogicalType> resultTypes = reader.readOptionalField(1,
                        () -> reader.readList(i -> LogicalTypeCodec.decode(reader)), List.of());
                List<String> resultNames = reader.readOptionalField(2,
                        () -> reader.readList(i -> reader.readString()), List.of());
                boolean needsMoreFetch = reader.readOptionalField(3, reader::readBool, false);
                List<DataChunk> results = reader.readOptionalField(4, () -> reader.readList(i -> {
                    DataChunk chunk = reader.readNullable(() -> VectorCodec.decodeDataChunkWrapper(reader));
                    if (chunk == null) {
                        throw new QuackProtocolException("Encountered null DataChunk pointer in result list");
                    }
                    return chunk;
                }), List.of());
                HugeIntParts queryUuid = reader.readRequiredField(5, reader::readHugeInt);
                return new QuackMessage.PrepareResponse(header, resultTypes, resultNames,
                        needsMoreFetch, results, queryUuid);
            });
            case FETCH_REQUEST -> reader.readObject(() -> new QuackMessage.FetchRequest(
                    header,
                    reader.readRequiredField(1, reader::readHugeInt),
                    reader.readOptionalField(2, reader::readUlebLong, 0L),
                    reader.readOptionalField(3, reader::readUlebLong, 0L)));
            case FETCH_RESPONSE -> {
                long[] count = new long[1];
                OptionalLong[] fields = new OptionalLong[2];
                reader.readObject(() -> {
                    count[0] = reader.readOptionalField(1, reader::readUlebLong, 0L);
                    fields[0] = optionalIndex(reader, 2);
                    fields[1] = optionalIndex(reader, 3);
                    return null;
                });
                yield new QuackMessage.FetchResponse(header, readChunkBlob(reader, count[0]), fields[0], fields[1]);
            }
            case SEND_DATA_REQUEST -> {
                Object[] f = new Object[4];
                reader.readObject(() -> {
                    f[0] = reader.readOptionalField(1, reader::readString, "");
                    f[1] = reader.readOptionalField(2, reader::readUlebLong, 0L);
                    f[2] = optionalIndex(reader, 3);
                    f[3] = optionalIndex(reader, 4);
                    return null;
                });
                yield new QuackMessage.SendDataRequest(header, (String) f[0],
                        readChunkBlob(reader, (Long) f[1]), (OptionalLong) f[2], (OptionalLong) f[3]);
            }
            case SEND_DATA_RESPONSE -> reader.readObject(() ->
                    new QuackMessage.SendDataResponse(header, optionalIndex(reader, 1)));
            case SUCCESS_RESPONSE -> reader.readObject(() -> new QuackMessage.SuccessResponse(header));
            case DISCONNECT_MESSAGE -> reader.readObject(() -> new QuackMessage.DisconnectMessage(header));
            case HEARTBEAT_REQUEST -> reader.readObject(() -> new QuackMessage.HeartbeatRequest(header));
            case CANCEL_REQUEST -> reader.readObject(() -> new QuackMessage.CancelRequest(
                    header, reader.readRequiredField(1, reader::readHugeInt)));
            case ACKNOWLEDGEMENT -> reader.readObject(() -> new QuackMessage.Acknowledgement(
                    header, reader.readOptionalField(1, reader::readHugeInt, ZERO)));
            case ERROR_RESPONSE -> reader.readObject(() -> new QuackMessage.ErrorResponse(
                    header,
                    reader.readOptionalField(1, reader::readString, ""),
                    reader.readOptionalField(2, reader::readString, ""),
                    reader.readOptionalField(3, () -> readStringMap(reader), Map.of()),
                    reader.readOptionalField(4, reader::readBool, false)));
            case INVALID -> throw new QuackProtocolException("Cannot decode message type " + header.type());
        };
    }

    private static Map<String, String> readStringMap(BinaryReader reader) {
        Map<String, String> map = new LinkedHashMap<>();
        reader.readList(i -> reader.readObject(() -> {
            String key = reader.readOptionalField(0, reader::readString, "");
            String value = reader.readOptionalField(1, reader::readString, "");
            map.put(key, value);
            return null;
        }));
        return map;
    }

    private static OptionalLong optionalIndex(BinaryReader reader, int fieldId) {
        long v = reader.readOptionalField(fieldId, reader::readUlebLong, QuackConstants.OPTIONAL_INDEX_INVALID);
        return v == QuackConstants.OPTIONAL_INDEX_INVALID ? OptionalLong.empty() : OptionalLong.of(v);
    }

    private static Optional<String> optionalString(BinaryReader reader, int fieldId) {
        return Optional.ofNullable(reader.readOptionalField(fieldId, reader::readString, null));
    }

    private static void writeOptionalString(BinaryWriter writer, int fieldId, Optional<String> value) {
        value.filter(s -> !s.isEmpty()).ifPresent(v -> writer.writeField(fieldId, () -> writer.writeString(v)));
    }

    /** An idx_t property whose default is 0. */
    private static void writeIndexNonZero(BinaryWriter writer, int fieldId, long value) {
        if (value != 0L) {
            writer.writeField(fieldId, () -> writer.writeUleb(value));
        }
    }

    /** An optional_idx property whose default is unset. */
    private static void writeOptionalIndex(BinaryWriter writer, int fieldId, OptionalLong value) {
        if (value.isPresent()) {
            writer.writeField(fieldId, () -> writer.writeUleb(value.getAsLong()));
        }
    }
}
