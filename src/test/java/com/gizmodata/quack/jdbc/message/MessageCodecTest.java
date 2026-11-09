package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quack protocol 3 (DuckDB 2.0) message round trips. */
class MessageCodecTest {

    private static final HugeIntParts UUID = new HugeIntParts(0x1122334455667788L, 0x99AABBCCDDEEFF00L);

    private static <T extends QuackMessage> T roundTrip(T message, Class<T> type) {
        return assertInstanceOf(type, MessageCodec.decode(MessageCodec.encode(message)));
    }

    @Test
    void connectionRequestCarriesClientIdAndLease() {
        QuackMessage.ConnectionRequest c = roundTrip(new QuackMessage.ConnectionRequest(
                MessageHeader.of(MessageType.CONNECTION_REQUEST).withClientQueryId(7),
                Optional.of("tok"), Optional.of("test/0.1"), Optional.of("linux"), 3, 3,
                Optional.of("client-1"), 45), QuackMessage.ConnectionRequest.class);
        assertEquals("tok", c.authString().orElse(""));
        assertEquals("test/0.1", c.clientDuckdbVersion().orElse(""));
        assertEquals("linux", c.clientPlatform().orElse(""));
        assertEquals(3, c.minSupportedQuackVersion());
        assertEquals(3, c.maxSupportedQuackVersion());
        assertEquals(Optional.of("client-1"), c.clientId());
        assertEquals(45, c.heartbeatTimeoutSeconds());
        assertEquals(Optional.of(7L), c.header().clientQueryId());
        assertEquals(Optional.empty(), c.header().connectionId());
    }

    @Test
    void connectionResponseCarriesVersionAndLease() {
        QuackMessage.ConnectionResponse c = roundTrip(new QuackMessage.ConnectionResponse(
                MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("abc").withClientQueryId(1),
                Optional.of("v2.0.0"), Optional.of("osx_arm64"), 3, 30), QuackMessage.ConnectionResponse.class);
        assertEquals(Optional.of("v2.0.0"), c.serverDuckdbVersion());
        assertEquals(3, c.quackVersion());
        assertEquals(30, c.heartbeatTimeoutSeconds());
        assertEquals(Optional.of("abc"), c.header().connectionId());
    }

    @Test
    void prepareRequestCarriesUuidAndInlineRows() {
        QuackMessage.PrepareRequest p = roundTrip(new QuackMessage.PrepareRequest(
                MessageHeader.of(MessageType.PREPARE_REQUEST).withConnectionId("CONN").withClientQueryId(1),
                "SELECT 1", UUID, OptionalLong.of(0)), QuackMessage.PrepareRequest.class);
        assertEquals("SELECT 1", p.sql());
        assertEquals(UUID, p.queryUuid());
        assertEquals(OptionalLong.of(0), p.inlineRows());
        assertEquals("CONN", p.header().connectionId().orElse(""));

        QuackMessage.PrepareRequest unset = roundTrip(new QuackMessage.PrepareRequest(
                MessageHeader.of(MessageType.PREPARE_REQUEST).withConnectionId("CONN").withClientQueryId(2),
                "SELECT 2", UUID, OptionalLong.empty()), QuackMessage.PrepareRequest.class);
        assertEquals(OptionalLong.empty(), unset.inlineRows());
    }

    @Test
    void fetchRequestCarriesBatchAndAckIndex() {
        QuackMessage.FetchRequest f = roundTrip(new QuackMessage.FetchRequest(
                MessageHeader.of(MessageType.FETCH_REQUEST).withConnectionId("c1").withClientQueryId(2),
                UUID, 5, 4), QuackMessage.FetchRequest.class);
        assertEquals(UUID, f.queryUuid());
        assertEquals(5, f.batchIndex());
        assertEquals(4, f.ackIndex());
    }

    private static DataChunk intChunk(int... values) {
        LogicalType t = LogicalType.of(LogicalTypeId.INTEGER);
        return new DataChunk(values.length, List.of(t), List.of(new DecodedVector.IntVec(t, values, null)));
    }

    @Test
    void fetchResponseCarriesChunkBlobAndIndexes() {
        QuackMessage.FetchResponse batch = roundTrip(new QuackMessage.FetchResponse(
                MessageHeader.of(MessageType.FETCH_RESPONSE).withConnectionId("c1").withClientQueryId(3),
                List.of(intChunk(1, 2), intChunk(3)), OptionalLong.empty(), OptionalLong.of(2)),
                QuackMessage.FetchResponse.class);
        assertEquals(2, batch.results().size());
        assertEquals(3, batch.results().get(1).columns().get(0).getObject(0));
        assertEquals(OptionalLong.of(2), batch.batchIndex());
        assertEquals(OptionalLong.empty(), batch.totalBatches());

        QuackMessage.FetchResponse terminal = roundTrip(new QuackMessage.FetchResponse(
                MessageHeader.of(MessageType.FETCH_RESPONSE).withConnectionId("c1").withClientQueryId(4),
                List.of(), OptionalLong.of(2), OptionalLong.empty()), QuackMessage.FetchResponse.class);
        assertTrue(terminal.results().isEmpty());
        assertEquals(OptionalLong.of(2), terminal.totalBatches());
        assertEquals(OptionalLong.empty(), terminal.batchIndex());
    }

    @Test
    void sendDataRequestCarriesStreamAndChunks() {
        QuackMessage.SendDataRequest s = roundTrip(new QuackMessage.SendDataRequest(
                MessageHeader.of(MessageType.SEND_DATA_REQUEST).withConnectionId("c1").withClientQueryId(5),
                "stream-1", List.of(intChunk(7, 8, 9)), OptionalLong.empty(), OptionalLong.of(1)),
                QuackMessage.SendDataRequest.class);
        assertEquals("stream-1", s.streamId());
        assertEquals(1, s.chunks().size());
        assertEquals(9, s.chunks().get(0).columns().get(0).getObject(2));
        assertEquals(OptionalLong.of(1), s.batchIndex());

        QuackMessage.SendDataRequest end = roundTrip(new QuackMessage.SendDataRequest(
                MessageHeader.of(MessageType.SEND_DATA_REQUEST).withConnectionId("c1").withClientQueryId(6),
                "stream-1", List.of(), OptionalLong.of(1), OptionalLong.empty()),
                QuackMessage.SendDataRequest.class);
        assertTrue(end.chunks().isEmpty());
        assertEquals(OptionalLong.of(1), end.totalBatches());
    }

    @Test
    void sendDataResponseCarriesBudget() {
        QuackMessage.SendDataResponse r = roundTrip(new QuackMessage.SendDataResponse(
                MessageHeader.of(MessageType.SEND_DATA_RESPONSE).withConnectionId("c1").withClientQueryId(6),
                OptionalLong.of(16)), QuackMessage.SendDataResponse.class);
        assertEquals(OptionalLong.of(16), r.acceptBudget());
    }

    @Test
    void errorResponseCarriesTypeExtraInfoAndInvalidate() {
        QuackMessage.ErrorResponse e = roundTrip(new QuackMessage.ErrorResponse(
                MessageHeader.of(MessageType.ERROR_RESPONSE).withClientQueryId(99),
                "Catalog Error: Table with name t does not exist!", "Catalog",
                Map.of("name", "t", "error_subtype", "MISSING_ENTRY"), true), QuackMessage.ErrorResponse.class);
        assertEquals("Catalog Error: Table with name t does not exist!", e.message());
        assertEquals("Catalog", e.exceptionType());
        assertEquals(Map.of("name", "t", "error_subtype", "MISSING_ENTRY"), e.extraInfo());
        assertTrue(e.mustInvalidate());

        QuackMessage.ErrorResponse plain = roundTrip(new QuackMessage.ErrorResponse(
                MessageHeader.of(MessageType.ERROR_RESPONSE).withClientQueryId(100),
                "out of cheese", "", Map.of(), false), QuackMessage.ErrorResponse.class);
        assertFalse(plain.mustInvalidate());
        assertTrue(plain.extraInfo().isEmpty());
    }

    @Test
    void cancelAckAndHeartbeatRoundTrip() {
        assertEquals(UUID, roundTrip(new QuackMessage.CancelRequest(
                MessageHeader.of(MessageType.CANCEL_REQUEST).withConnectionId("c").withClientQueryId(1), UUID),
                QuackMessage.CancelRequest.class).queryUuid());
        assertEquals(UUID, roundTrip(new QuackMessage.Acknowledgement(
                MessageHeader.of(MessageType.ACKNOWLEDGEMENT).withConnectionId("c").withClientQueryId(1), UUID),
                QuackMessage.Acknowledgement.class).queryUuid());
        roundTrip(new QuackMessage.HeartbeatRequest(
                MessageHeader.of(MessageType.HEARTBEAT_REQUEST).withConnectionId("c").withClientQueryId(1)),
                QuackMessage.HeartbeatRequest.class);
    }

    @Test
    void disconnectAndSuccessRoundTrip() {
        roundTrip(new QuackMessage.DisconnectMessage(
                MessageHeader.of(MessageType.DISCONNECT_MESSAGE).withConnectionId("c").withClientQueryId(1)),
                QuackMessage.DisconnectMessage.class);
        roundTrip(new QuackMessage.SuccessResponse(
                MessageHeader.of(MessageType.SUCCESS_RESPONSE).withClientQueryId(1)),
                QuackMessage.SuccessResponse.class);
    }

    @Test
    void prepareResponseCarriesInlineChunksAndUuid() {
        QuackMessage.PrepareResponse pr = roundTrip(new QuackMessage.PrepareResponse(
                MessageHeader.of(MessageType.PREPARE_RESPONSE).withClientQueryId(1),
                List.of(LogicalType.of(LogicalTypeId.INTEGER)), List.of("i"), true, List.of(intChunk(4, 5)),
                UUID), QuackMessage.PrepareResponse.class);
        assertEquals(List.of("i"), pr.resultNames());
        assertTrue(pr.needsMoreFetch());
        assertEquals(5, pr.results().get(0).columns().get(0).getObject(1));
        assertEquals(UUID, pr.queryUuid());
    }

    @Test
    void headerWritesInvalidClientQueryIdWhenUnset() {
        // The header always carries field 3; an unset id is DConstants::INVALID_INDEX (-1).
        QuackMessage.SuccessResponse s = roundTrip(new QuackMessage.SuccessResponse(
                MessageHeader.of(MessageType.SUCCESS_RESPONSE)), QuackMessage.SuccessResponse.class);
        assertTrue(s.header().clientQueryId().isEmpty() || s.header().clientQueryId().get() == -1L);
    }

    @Test
    void varcharVectorDecodesDuckDb2SplitLayout() {
        // DuckDB 2.0 writes VARCHAR as 107 byte length, 108 uint32 lengths, 109 bytes.
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(true));
            obj.writeField(101, () -> obj.writeBlob(Validity.toBytes(new long[]{0b101L}, 3)));
            obj.writeField(107, () -> obj.writeUleb(5));
            obj.writeField(108, () -> obj.writeBlob(new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0}));
            obj.writeField(109, () -> obj.writeBlob("hiyou".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        });
        DecodedVector v = VectorCodec.decodeVector(new com.gizmodata.quack.jdbc.codec.BinaryReader(w.toByteArray()),
                LogicalType.of(LogicalTypeId.VARCHAR), 3);
        assertEquals("hi", v.getObject(0));
        assertTrue(v.isNull(1));
        assertEquals("you", v.getObject(2));
    }

    @Test
    void geometryVectorWritesWkbFormatField() {
        LogicalType geom = LogicalType.of(LogicalTypeId.GEOMETRY);
        byte[] wkb = {1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xF0, 0x3F, 0, 0, 0, 0, 0, 0, 0, 0x40};
        BinaryWriter w = new BinaryWriter();
        VectorCodec.encodeVector(w, geom, new DecodedVector.ObjectVec(geom, new Object[]{wkb}));
        byte[] bytes = w.toByteArray();
        // field ids are little-endian uint16: 99 must come first, with value 1 (WKB)
        assertEquals(99, bytes[0]);
        assertEquals(0, bytes[1]);
        assertEquals(1, bytes[2]);
        DecodedVector back = VectorCodec.decodeVector(new com.gizmodata.quack.jdbc.codec.BinaryReader(bytes), geom, 1);
        assertArrayEquals(wkb, (byte[]) back.getObject(0));
    }
}
