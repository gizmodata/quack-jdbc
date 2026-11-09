package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;

/** Quack message-type ids for protocol version 3 (DuckDB 2.0); gaps are retired types. */
public enum MessageType {
    INVALID(0),
    CONNECTION_REQUEST(1),
    CONNECTION_RESPONSE(2),
    PREPARE_REQUEST(3),
    PREPARE_RESPONSE(4),
    FETCH_REQUEST(7),
    FETCH_RESPONSE(8),
    SEND_DATA_REQUEST(9),
    SUCCESS_RESPONSE(10),
    DISCONNECT_MESSAGE(11),
    CANCEL_REQUEST(12),
    SEND_DATA_RESPONSE(14),
    ACKNOWLEDGEMENT(15),
    HEARTBEAT_REQUEST(16),
    ERROR_RESPONSE(100);

    private final int wireId;

    MessageType(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static MessageType fromWireId(int wireId) {
        for (MessageType t : values()) {
            if (t.wireId == wireId) {
                return t;
            }
        }
        throw new QuackProtocolException("Unknown message type id " + wireId);
    }
}
