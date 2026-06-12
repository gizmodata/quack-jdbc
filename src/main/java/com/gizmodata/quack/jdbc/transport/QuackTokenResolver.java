package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

final class QuackTokenResolver {

    static final String TOKEN = "token";
    static final String PASSWORD = "password";
    static final String TOKEN_ENV = "tokenEnv";
    static final String TOKEN_FILE = "tokenFile";

    private QuackTokenResolver() {
    }

    static Optional<String> resolve(Map<String, String> properties) {
        Optional<String> token = nonBlank(properties.get(TOKEN));
        if (token.isPresent()) {
            return token;
        }
        token = nonBlank(properties.get(PASSWORD));
        if (token.isPresent()) {
            return token;
        }
        token = resolveFromEnv(properties.get(TOKEN_ENV));
        if (token.isPresent()) {
            return token;
        }
        token = resolveFromFile(properties.get(TOKEN_FILE));
        if (token.isPresent()) {
            return token;
        }
        return Optional.empty();
    }

    private static Optional<String> resolveFromEnv(String envName) {
        Optional<String> name = nonBlank(envName);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String value = System.getenv(name.get());
        return Optional.of(nonBlank(value).orElseThrow(
                () -> new QuackException("Quack token environment variable is unset or empty: " + name.get())));
    }

    private static Optional<String> resolveFromFile(String tokenFile) {
        Optional<String> file = nonBlank(tokenFile);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            String value = Files.readString(Path.of(file.get()), StandardCharsets.UTF_8);
            return Optional.of(nonBlank(value).orElseThrow(
                    () -> new QuackException("Quack token file is empty: " + file.get())));
        } catch (IOException e) {
            throw new QuackException("Failed to read Quack token file: " + file.get(), e);
        }
    }

    private static Optional<String> nonBlank(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}
