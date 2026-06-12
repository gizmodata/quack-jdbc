package com.gizmodata.quack.jdbc.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuackTokenResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitTokenPrecedesIndirectTokenSources() throws IOException {
        Path tokenFile = tempDir.resolve("token.txt");
        Files.writeString(tokenFile, "from-file\n", StandardCharsets.UTF_8);
        Properties props = new Properties();
        props.setProperty("token", "explicit");
        props.setProperty("tokenFile", tokenFile.toString());

        QuackUri uri = QuackUri.parse("jdbc:quack://example.test:9494", props);

        assertEquals("explicit", uri.token().orElseThrow());
    }

    @Test
    void resolvesTokenFromFile() throws IOException {
        Path tokenFile = tempDir.resolve("token.txt");
        Files.writeString(tokenFile, "from-file\n", StandardCharsets.UTF_8);
        Properties props = new Properties();
        props.setProperty("tokenFile", tokenFile.toString());

        QuackUri uri = QuackUri.parse("jdbc:quack://example.test:9494", props);

        assertEquals("from-file", uri.token().orElseThrow());
    }

}
