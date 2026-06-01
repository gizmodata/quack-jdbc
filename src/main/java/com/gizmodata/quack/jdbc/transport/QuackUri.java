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

/** Parsed {@code jdbc:quack://...} URL. */
public record QuackUri(String host,
                       int port,
                       Optional<String> database,
                       boolean tls,
                       Optional<String> token,
                       Map<String, String> properties) {

    public static final String URL_PREFIX = "jdbc:quack:";

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
            throw new QuackException("Quack JDBC URL must start with jdbc:quack:// — got " + url);
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
        for (String key : properties.stringPropertyNames()) {
            params.putIfAbsent(key, properties.getProperty(key));
        }

        boolean tls = parseBool(params.getOrDefault("tls", params.getOrDefault("useEncryption", "false")));
        Optional<String> token = QuackTokenResolver.resolve(params);

        return new QuackUri(host, port, database, tls, token, params);
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
