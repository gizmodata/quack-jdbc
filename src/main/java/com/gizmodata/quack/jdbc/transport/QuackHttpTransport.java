package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.QuackMessage;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP transport for the Quack protocol. Sends Quack messages as
 * {@code application/duckdb} request bodies to {@code POST /quack} and
 * returns the decoded server response (or raises a
 * {@link QuackServerException} for {@code ERROR_RESPONSE}).
 *
 * <p>For plain HTTP endpoints, every address returned by
 * {@link InetAddress#getAllByName(String)} is tried in order. This is
 * essential for hosts like {@code localhost} that resolve to both IPv4
 * and IPv6 — JDK {@link HttpClient} otherwise gives up after the first
 * address fails, even if a server is reachable on one of the other
 * addresses.
 *
 * <p>HTTPS endpoints keep the original hostname. Replacing the URI host
 * with a resolved IP address breaks TLS SNI and hostname verification.
 */
public final class QuackHttpTransport implements QuackTransport {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Map<String, String> extraHeaders;

    public QuackHttpTransport(URI endpoint) {
        this(endpoint, HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public QuackHttpTransport(URI endpoint, HttpClient httpClient, Duration requestTimeout) {
        this(endpoint, httpClient, requestTimeout, Map.of());
    }

    public QuackHttpTransport(URI endpoint, HttpClient httpClient, Duration requestTimeout,
                              Map<String, String> extraHeaders) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.extraHeaders = Map.copyOf(extraHeaders);
    }

    public static QuackHttpTransport from(QuackUri uri) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(uri.connectTimeout())
                .build();
        return new QuackHttpTransport(uri.httpUri(), client, uri.requestTimeout(),
                uri.extraHttpHeaders());
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    Optional<Duration> connectTimeout() {
        return httpClient.connectTimeout();
    }

    @Override
    public QuackMessage send(QuackMessage request) {
        byte[] body = MessageCodec.encode(request);

        URI[] attempts = endpointCandidates();
        IOException lastFailure = null;
        for (URI attempt : attempts) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(attempt)
                    .timeout(requestTimeout);
            // Extra headers first, protocol headers second: Content-Type
            // and Accept must win (QuackUri validation also rejects them).
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpRequest httpRequest = builder
                    .header("Content-Type", QuackConstants.DUCKDB_MIME_TYPE)
                    .header("Accept", QuackConstants.DUCKDB_MIME_TYPE)
                    .POST(BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(httpRequest, BodyHandlers.ofByteArray());
            } catch (ConnectException | HttpConnectTimeoutException | ClosedChannelException e) {
                lastFailure = e;
                continue;
            } catch (IOException e) {
                throw new QuackException(buildErrorMessage(e, attempt), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QuackException("Quack HTTP request was interrupted", e);
            }

            if (response.statusCode() / 100 != 2) {
                throw new QuackException("Quack HTTP returned status " + response.statusCode()
                        + " from " + attempt);
            }
            QuackMessage decoded = MessageCodec.decode(response.body());
            if (decoded instanceof QuackMessage.ErrorResponse err) {
                throw new QuackServerException(err.message());
            }
            return decoded;
        }

        throw new QuackException(buildExhaustedMessage(attempts, lastFailure), lastFailure);
    }

    URI[] endpointCandidates() {
        if ("https".equalsIgnoreCase(endpoint.getScheme())) {
            return new URI[]{endpoint};
        }
        InetAddress[] addresses = resolveCandidates();
        URI[] endpoints = new URI[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            endpoints[i] = endpointFor(addresses[i]);
        }
        return endpoints;
    }

    private InetAddress[] resolveCandidates() {
        String host = endpoint.getHost();
        if (host == null) {
            throw new QuackException("Quack endpoint has no host: " + endpoint);
        }
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new QuackException("Quack endpoint host could not be resolved: " + host, e);
        }
    }

    private URI endpointFor(InetAddress address) {
        String literal = address instanceof Inet6Address
                ? "[" + address.getHostAddress() + "]"
                : address.getHostAddress();
        try {
            return new URI(endpoint.getScheme(), null, literal, endpoint.getPort(),
                    endpoint.getPath(), endpoint.getQuery(), endpoint.getFragment());
        } catch (URISyntaxException e) {
            throw new QuackException("Failed to build address-specific Quack URI for " + address, e);
        }
    }

    private static String buildErrorMessage(Throwable cause, URI attempted) {
        String detail = cause.getMessage();
        if (detail == null || detail.isEmpty()) {
            detail = cause.getClass().getSimpleName();
        }
        return "Quack HTTP request to " + attempted + " failed: " + detail;
    }

    private String buildExhaustedMessage(URI[] attempts, Throwable cause) {
        StringBuilder sb = new StringBuilder("Quack HTTP connect failed for ")
                .append(endpoint.getHost()).append(":").append(endpoint.getPort())
                .append(" (tried ").append(attempts.length).append(" endpoint(s): ");
        for (int i = 0; i < attempts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(attempts[i]);
        }
        sb.append(")");
        if (cause != null) {
            String detail = cause.getMessage();
            if (detail == null || detail.isEmpty()) detail = cause.getClass().getSimpleName();
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }
}
