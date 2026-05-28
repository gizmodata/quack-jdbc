package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the address-iteration behavior in
 * {@link QuackHttpTransport}. The core scenario is:
 * {@code localhost} resolves to both 127.0.0.1 and ::1, and a server is
 * only bound to one family. JDK {@link HttpClient} alone gives up after
 * the first address fails to connect; our transport must fall through
 * to the remaining addresses.
 */
class QuackHttpTransportTest {

    /** Skip the IPv6-fallback test on hosts where {@code ::1} is not configured. */
    static boolean ipv6LoopbackAvailable() {
        try {
            InetAddress addr = InetAddress.getByName("::1");
            for (InetAddress resolved : InetAddress.getAllByName("localhost")) {
                if (resolved instanceof Inet6Address) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static QuackHttpTransport transportWith(URI endpoint) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return new QuackHttpTransport(endpoint, client, Duration.ofSeconds(5));
    }

    private static HttpServer startServer(String bindAddr, AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(bindAddr), 0), 0);
        server.createContext("/quack", handler(hits));
        server.setExecutor(null);
        server.start();
        return server;
    }

    private static com.sun.net.httpserver.HttpHandler handler(AtomicInteger hits) {
        return (HttpExchange exchange) -> {
            hits.incrementAndGet();
            // Drain request body; respond with a valid Quack SUCCESS_RESPONSE so
            // the transport's MessageCodec.decode step succeeds.
            exchange.getRequestBody().readAllBytes();
            byte[] response = MessageCodec.encode(new QuackMessage.SuccessResponse(
                    MessageHeader.of(MessageType.SUCCESS_RESPONSE).withClientQueryId(1)));
            exchange.getResponseHeaders().add("Content-Type", "application/duckdb");
            exchange.sendResponseHeaders(200, response.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        };
    }

    private static QuackMessage probe(QuackHttpTransport transport) {
        return transport.send(new QuackMessage.DisconnectMessage(
                MessageHeader.of(MessageType.DISCONNECT_MESSAGE).withClientQueryId(1)));
    }

    @Test
    @EnabledIf("ipv6LoopbackAvailable")
    void fallsThroughToIpv6WhenIpv4HasNoListener() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = startServer("::1", hits);
        try {
            int port = server.getAddress().getPort();
            URI endpoint = URI.create("http://localhost:" + port + "/quack");
            QuackMessage reply = probe(transportWith(endpoint));
            assertTrue(reply instanceof QuackMessage.SuccessResponse,
                    "expected SuccessResponse, got " + reply.getClass().getSimpleName());
            assertEquals(1, hits.get(), "test HTTP server should have been hit exactly once");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesIpv4WhenAvailableAsFirstAddress() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = startServer("127.0.0.1", hits);
        try {
            int port = server.getAddress().getPort();
            URI endpoint = URI.create("http://localhost:" + port + "/quack");
            QuackMessage reply = probe(transportWith(endpoint));
            assertTrue(reply instanceof QuackMessage.SuccessResponse);
            assertEquals(1, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exhaustedAddressesErrorListsEveryAttempt() {
        // Port 1 reliably refuses connections on macOS/Linux/Windows.
        URI endpoint = URI.create("http://localhost:1/quack");
        QuackException e = assertThrows(QuackException.class,
                () -> probe(transportWith(endpoint)));
        String msg = e.getMessage();
        assertTrue(msg.contains("Quack HTTP connect failed"),
                "expected exhausted message, got: " + msg);
        assertTrue(msg.contains("localhost:1"),
                "expected host:port in message, got: " + msg);
        // Should list at least one literal address that was actually tried.
        assertTrue(msg.contains("127.0.0.1") || msg.contains("0:0:0:0:0:0:0:1"),
                "expected resolved address in message, got: " + msg);
    }

    @Test
    void unknownHostFailsCleanly() {
        URI endpoint = URI.create("http://no-such-host-quack-jdbc-test.invalid:9494/quack");
        QuackException e = assertThrows(QuackException.class,
                () -> probe(transportWith(endpoint)));
        assertTrue(e.getMessage().toLowerCase().contains("resolve")
                        || e.getMessage().toLowerCase().contains("host"),
                "expected resolution error, got: " + e.getMessage());
    }

    @Test
    void factoryUsesTimeoutsFromUri() {
        QuackUri uri = QuackUri.parse("jdbc:quack://localhost:9494?connectTimeout=3&requestTimeout=PT9S");

        QuackHttpTransport transport = QuackHttpTransport.from(uri);

        assertEquals(Duration.ofSeconds(3), transport.connectTimeout().orElseThrow());
        assertEquals(Duration.ofSeconds(9), transport.requestTimeout());
    }

    @Test
    void httpsEndpointKeepsOriginalHostnameForSniAndCertificateVerification() {
        URI endpoint = URI.create("https://localhost:9494/quack");
        QuackHttpTransport transport = transportWith(endpoint);

        URI[] endpoints = transport.endpointCandidates();

        assertEquals(1, endpoints.length);
        assertEquals(endpoint, endpoints[0]);
    }

    @Test
    void httpEndpointStillExpandsToResolvedAddressCandidates() {
        URI endpoint = URI.create("http://localhost:9494/quack");
        QuackHttpTransport transport = transportWith(endpoint);

        URI[] endpoints = transport.endpointCandidates();

        assertTrue(endpoints.length >= 1);
        assertTrue(Arrays.stream(endpoints).allMatch(uri -> uri.getHost() != null));
        assertTrue(Arrays.stream(endpoints).noneMatch(endpoint::equals),
                "plain HTTP endpoint should still be rewritten to resolved address candidates");
    }
}
