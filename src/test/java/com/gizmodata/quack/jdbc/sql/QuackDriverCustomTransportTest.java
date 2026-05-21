package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackDriverCustomTransportTest {

    @Test
    void driverConnectUsesCustomTransportFactory() throws SQLException {
        QuackDriver driver = new QuackDriver();
        RecordingTransport transport = new RecordingTransport("custom-connection");
        AtomicReference<QuackUri> factoryUri = new AtomicReference<>();

        Properties properties = new Properties();
        properties.setProperty("token", "prop-token");
        properties.setProperty("tls", "true");
        properties.setProperty("connectTimeout", "99");
        properties.setProperty("requestTimeout", "99");

        try (Connection connection = driver.connect(
                "jdbc:quack://example.test:1234/db?token=url-token&connectTimeout=3&requestTimeout=PT4S",
                properties,
                uri -> {
                    factoryUri.set(uri);
                    return transport;
                })) {
            QuackConnection quackConnection = assertInstanceOf(QuackConnection.class, connection);
            assertEquals("custom-connection", quackConnection.session().connectionId());
        }

        QuackUri uri = factoryUri.get();
        assertEquals("example.test", uri.host());
        assertEquals(1234, uri.port());
        assertEquals(Optional.of("db"), uri.database());
        assertTrue(uri.tls());
        assertEquals(Optional.of("url-token"), uri.token());
        assertEquals(Duration.ofSeconds(3), uri.connectTimeout());
        assertEquals(Duration.ofSeconds(4), uri.requestTimeout());

        assertEquals(2, transport.requests.size());
        QuackMessage firstRequest = transport.requests.get(0);
        QuackMessage secondRequest = transport.requests.get(1);
        assertInstanceOf(QuackMessage.ConnectionRequest.class, firstRequest);
        assertEquals(Optional.of("url-token"),
                ((QuackMessage.ConnectionRequest) firstRequest).authString());
        assertInstanceOf(QuackMessage.DisconnectMessage.class, secondRequest);
        assertEquals(Optional.of("custom-connection"), secondRequest.header().connectionId());
    }

    @Test
    void nullTransportFromFactoryFailsCleanly() {
        QuackDriver driver = new QuackDriver();

        SQLException exception = assertThrows(SQLException.class,
                () -> driver.connect("jdbc:quack://example.test:1234", new Properties(), uri -> null));

        assertTrue(exception.getMessage().contains("transportFactory returned null"),
                "expected null factory result message, got: " + exception.getMessage());
    }

    @Test
    void propertyInfoIncludesTimeouts() {
        QuackDriver driver = new QuackDriver();

        DriverPropertyInfo[] propertyInfo = driver.getPropertyInfo("jdbc:quack://example.test:1234",
                new Properties());

        assertEquals("token", propertyInfo[0].name);
        assertEquals("tls", propertyInfo[1].name);
        assertEquals("connectTimeout", propertyInfo[2].name);
        assertEquals("requestTimeout", propertyInfo[3].name);
    }

    @Test
    void sessionKeepsQuackHttpTransportConstructorForBinaryCompatibility() throws Exception {
        Constructor<QuackSession> constructor = QuackSession.class.getConstructor(
                QuackUri.class, QuackHttpTransport.class);
        QuackUri uri = QuackUri.parse("jdbc:quack://example.test:1234");
        QuackHttpTransport transport = new QuackHttpTransport(URI.create("http://example.test:1234/quack"));

        QuackSession session = constructor.newInstance(uri, transport);

        assertEquals(uri, session.uri());
    }

    private static final class RecordingTransport implements QuackTransport {

        private final String connectionId;
        private final List<QuackMessage> requests = new ArrayList<>();

        RecordingTransport(String connectionId) {
            this.connectionId = connectionId;
        }

        @Override
        public QuackMessage send(QuackMessage request) {
            requests.add(request);
            if (request instanceof QuackMessage.ConnectionRequest) {
                long clientQueryId = request.header().clientQueryId().orElse(0L);
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                                .withConnectionId(connectionId)
                                .withClientQueryId(clientQueryId),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
            }
            if (request instanceof QuackMessage.DisconnectMessage) {
                long clientQueryId = request.header().clientQueryId().orElse(0L);
                return new QuackMessage.SuccessResponse(
                        MessageHeader.of(MessageType.SUCCESS_RESPONSE)
                                .withConnectionId(connectionId)
                                .withClientQueryId(clientQueryId));
            }
            throw new AssertionError("unexpected request: " + request.getClass().getSimpleName());
        }
    }
}
