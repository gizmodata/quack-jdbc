package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.codec.QuackConstants;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Parsed {@code jdbc:quack://host[:port][/database][?props]} URL (DuckDB's
 * {@code jdbc:quack:host[:port]} spelling is accepted too).
 *
 * <p>{@code tls} defaults to on for every host except {@code localhost},
 * {@code 127.0.0.1} and {@code ::1}, as DuckDB's own Quack client does.
 */
public record QuackUri(String host,
                       int port,
                       Optional<String> database,
                       boolean tls,
                       Optional<String> token,
                       Map<String, String> properties) {

    public static final String URL_PREFIX = "jdbc:quack:";

    /**
     * Prefix for extra-HTTP-header connection properties:
     * {@code httpHeader.<Header-Name>=<value>} sends the header with
     * every request (proxy/LB auth). Mirrors duckdb-quack's
     * {@code EXTRA_HTTP_HEADERS} secret parameter. Accepted via
     * connection {@link Properties} only — rejected on the JDBC URL so
     * a pasted URL cannot inject headers into the driver's requests.
     */
    public static final String HTTP_HEADER_PREFIX = "httpHeader.";

    /** Connection lease requested from the server when {@code heartbeatTimeout} is unset. */
    public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofSeconds(60);

    public static boolean acceptsUrl(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    public static QuackUri parse(String url) {
        return parse(url, new Properties());
    }

    public static QuackUri parse(String url, Properties properties) {
        if (!acceptsUrl(url)) {
            throw new QuackException("Not a Quack JDBC URL: " + url);
        }
        String stripped = url.substring(URL_PREFIX.length());
        if (!stripped.startsWith("//")) {
            // DuckDB's `quack:host[:port]` spelling.
            if (stripped.isEmpty() || stripped.startsWith("/")) {
                throw new QuackException("Quack JDBC URL must start with jdbc:quack:// — got " + url);
            }
            stripped = "//" + stripped;
        }

        URI parsed;
        try {
            parsed = new URI("http:" + stripped);
        } catch (URISyntaxException e) {
            throw new QuackException("Invalid Quack JDBC URL: " + url, e);
        }

        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new QuackException("Quack JDBC URL is missing a host: " + url);
        }
        int port = parsed.getPort();
        if (port <= 0) {
            port = QuackConstants.DEFAULT_QUACK_PORT;
        }

        Optional<String> database = Optional.empty();
        String path = parsed.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            String trimmed = path.startsWith("/") ? path.substring(1) : path;
            if (!trimmed.isEmpty()) {
                database = Optional.of(URLDecoder.decode(trimmed, StandardCharsets.UTF_8));
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        if (parsed.getRawQuery() != null) {
            for (String pair : parsed.getRawQuery().split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        // tokenEnv/tokenFile make the driver read a local secret and send it
        // to the host in the URL — accepting them from the URL would let a
        // pasted or shared URL exfiltrate an arbitrary env var or file to an
        // attacker-chosen server. Connection Properties only.
        for (String key : new String[]{QuackTokenResolver.TOKEN_ENV, QuackTokenResolver.TOKEN_FILE}) {
            if (params.containsKey(key)) {
                throw new QuackException("Quack JDBC property " + key
                        + " is only accepted via connection Properties, not the JDBC URL");
            }
        }
        for (String key : params.keySet()) {
            if (key.startsWith(HTTP_HEADER_PREFIX)) {
                throw new QuackException("Quack JDBC property " + key
                        + " is only accepted via connection Properties, not the JDBC URL");
            }
        }
        for (String key : properties.stringPropertyNames()) {
            params.putIfAbsent(key, properties.getProperty(key));
            if (key.startsWith(HTTP_HEADER_PREFIX)) {
                validateHeader(key.substring(HTTP_HEADER_PREFIX.length()),
                        properties.getProperty(key));
            }
        }

        String fingerprint = normalizeFingerprint(firstNonEmpty(
                params.get("sslFingerprint"), params.get("ssl_fingerprint")));
        String tlsRaw = firstNonEmpty(params.get("tls"), params.get("useEncryption"));
        boolean tls;
        if (tlsRaw != null) {
            tls = parseBool(tlsRaw);
            if (!tls && fingerprint != null) {
                throw new QuackException("sslFingerprint pins the server's TLS certificate"
                        + " and cannot be combined with tls=false");
            }
        } else {
            tls = fingerprint != null || !isLocalHost(host);
        }
        String clientId = firstNonEmpty(params.get("clientId"), params.get("client_id"));
        if (clientId != null && clientId.length() < 4) {
            throw new QuackException("Quack JDBC property clientId must be empty or at least 4 characters");
        }
        Optional<String> token = QuackTokenResolver.resolve(params);

        QuackUri uri = new QuackUri(host, port, database, tls, token, params);
        if (uri.heartbeatTimeout().compareTo(Duration.ofSeconds(1)) < 0) {
            throw new QuackException("Quack JDBC property heartbeatTimeout must be at least 1 second");
        }
        uri.connectTimeout();
        uri.requestTimeout();
        return uri;
    }

    /** True for the loopback names DuckDB serves over plain HTTP by default. */
    public static boolean isLocalHost(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1");
    }

    /**
     * The pinned server certificate's SHA-256 fingerprint (64 uppercase
     * hex digits), from {@code sslFingerprint}. When set, the driver
     * accepts exactly that certificate and skips CA and hostname checks,
     * which is how a self-signed {@code quack_serve} certificate is trusted.
     */
    public Optional<String> sslFingerprint() {
        return Optional.ofNullable(normalizeFingerprint(firstNonEmpty(
                properties.get("sslFingerprint"), properties.get("ssl_fingerprint"))));
    }

    /** Client id sent in the handshake ({@code clientId}); empty when unset. */
    public Optional<String> clientId() {
        return Optional.ofNullable(firstNonEmpty(properties.get("clientId"), properties.get("client_id")));
    }

    /** Connection lease requested from the server ({@code heartbeatTimeout}, seconds or ISO-8601). */
    public Duration heartbeatTimeout() {
        return parseDurationProperty("heartbeatTimeout", DEFAULT_HEARTBEAT_TIMEOUT);
    }

    /**
     * Accept the colon-separated form {@code openssl x509 -fingerprint -sha256}
     * prints, with or without a {@code sha256:} prefix; return 64 uppercase
     * hex digits, or null when unset.
     */
    static String normalizeFingerprint(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.toLowerCase(java.util.Locale.ROOT).startsWith("sha256:")) s = s.substring(7);
        StringBuilder sb = new StringBuilder(64);
        for (char c : s.toCharArray()) {
            if (c == ':' || c == ' ') continue;
            if (Character.digit(c, 16) < 0) {
                throw new QuackException("Invalid sslFingerprint: expected the certificate's SHA-256"
                        + " fingerprint as hex digits");
            }
            sb.append(Character.toUpperCase(c));
        }
        if (sb.length() != 64) {
            throw new QuackException("Invalid sslFingerprint: a SHA-256 fingerprint has 64 hex digits, got "
                    + sb.length());
        }
        return sb.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    public URI httpUri() {
        try {
            return new URI((tls ? "https" : "http") + "://" + host + ":" + port + QuackConstants.QUACK_ENDPOINT);
        } catch (URISyntaxException e) {
            throw new QuackException("Failed to build HTTP URI for " + this, e);
        }
    }

    public Duration connectTimeout() {
        return parseDurationProperty("connectTimeout", QuackHttpTransport.DEFAULT_CONNECT_TIMEOUT);
    }

    public Duration requestTimeout() {
        return parseDurationProperty("requestTimeout", QuackHttpTransport.DEFAULT_REQUEST_TIMEOUT);
    }

    public String quackUri() {
        return "quack:" + host + ":" + port;
    }

    /**
     * Extra HTTP headers from {@code httpHeader.*} properties, in
     * property order. An empty value clears/omits the header.
     */
    public Map<String, String> extraHttpHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!entry.getKey().startsWith(HTTP_HEADER_PREFIX)) {
                continue;
            }
            String name = entry.getKey().substring(HTTP_HEADER_PREFIX.length()).trim();
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            headers.put(name, value);
        }
        return headers;
    }

    private static void validateHeader(String name, String value) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.chars().anyMatch(c ->
                c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ':')) {
            throw new QuackException("Invalid HTTP header name in property "
                    + HTTP_HEADER_PREFIX + name);
        }
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new QuackException("HTTP header " + trimmed + " value must not contain CR/LF");
        }
        switch (trimmed.toLowerCase()) {
            case "content-type", "accept", "content-length", "host" ->
                    throw new QuackException("HTTP header " + trimmed
                            + " is reserved by the Quack protocol");
            default -> { }
        }
    }

    private static boolean parseBool(String value) {
        if (value == null) return false;
        return switch (value.trim().toLowerCase()) {
            case "true", "1", "yes", "on" -> true;
            default -> false;
        };
    }

    private Duration parseDurationProperty(String key, Duration defaultValue) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String trimmed = value.trim();
        try {
            Duration duration;
            if (trimmed.chars().allMatch(Character::isDigit)) {
                duration = Duration.ofSeconds(Long.parseLong(trimmed));
            } else {
                duration = Duration.parse(trimmed);
            }
            if (duration.isZero() || duration.isNegative()) {
                throw new QuackException("Quack JDBC property " + key + " must be positive: " + value);
            }
            return duration;
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new QuackException("Quack JDBC property " + key
                    + " must be a positive number of seconds or ISO-8601 duration: " + value, e);
        }
    }
}
