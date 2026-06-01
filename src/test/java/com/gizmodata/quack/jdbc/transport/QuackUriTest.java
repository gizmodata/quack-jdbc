package com.gizmodata.quack.jdbc.transport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackUriTest {

    @Test
    void acceptsCanonicalUrl() {
        assertTrue(QuackUri.acceptsUrl("jdbc:quack://localhost:9494"));
        assertTrue(QuackUri.acceptsUrl("jdbc:quack://h"));
        assertFalse(QuackUri.acceptsUrl("jdbc:postgresql://localhost:9494"));
        assertFalse(QuackUri.acceptsUrl(null));
    }

    @Test
    void parsesHostAndDefaultPort() {
        QuackUri u = QuackUri.parse("jdbc:quack://duck.example.com");
        assertEquals("duck.example.com", u.host());
        assertEquals(9494, u.port());
        assertTrue(u.database().isEmpty());
        assertFalse(u.tls());
    }

    @Test
    void parsesHostPortDatabaseToken() {
        QuackUri u = QuackUri.parse("jdbc:quack://h.example:1234/mydb?token=abc&tls=true");
        assertEquals("h.example", u.host());
        assertEquals(1234, u.port());
        assertEquals("mydb", u.database().orElseThrow());
        assertEquals("abc", u.token().orElseThrow());
        assertTrue(u.tls());
        assertEquals("https://h.example:1234/quack", u.httpUri().toString());
    }

    @Test
    void propertiesFillInMissingValues() {
        Properties props = new Properties();
        props.setProperty("token", "from-props");
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);
        assertEquals("from-props", u.token().orElseThrow());
    }

    @Test
    void acceptsPasswordAsTokenAlias() {
        Properties props = new Properties();
        props.setProperty("password", "from-password");
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);
        assertEquals("from-password", u.token().orElseThrow());
    }

    @Test
    void tlsFlagTogglesScheme() {
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494?tls=false");
        assertEquals("http://h:9494/quack", u.httpUri().toString());
        QuackUri u2 = QuackUri.parse("jdbc:quack://h:9494?tls=true");
        assertEquals("https://h:9494/quack", u2.httpUri().toString());
    }

    @Test
    void parsesTimeoutProperties() {
        QuackUri u = QuackUri.parse("jdbc:quack://h:9494?connectTimeout=5&requestTimeout=PT30S");
        assertEquals(Duration.ofSeconds(5), u.connectTimeout());
        assertEquals(Duration.ofSeconds(30), u.requestTimeout());
    }

    @Test
    void timeoutPropertiesCanComeFromProperties() {
        Properties props = new Properties();
        props.setProperty("connectTimeout", "7");
        props.setProperty("requestTimeout", "PT45S");

        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);

        assertEquals(Duration.ofSeconds(7), u.connectTimeout());
        assertEquals(Duration.ofSeconds(45), u.requestTimeout());
    }

    @Test
    void timeoutPropertiesRejectInvalidValues() {
        assertThrows(RuntimeException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?connectTimeout=0").connectTimeout());
        assertThrows(RuntimeException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?requestTimeout=forever").requestTimeout());
    }
}
