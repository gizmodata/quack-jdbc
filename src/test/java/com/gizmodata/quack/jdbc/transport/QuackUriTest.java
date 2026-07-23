package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
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
    void rejectsTokenEnvAndTokenFileOnTheUrl() {
        // A pasted URL must not be able to read a local secret and ship it
        // to whatever host the URL names.
        QuackException envEx = assertThrows(QuackException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?tokenEnv=AWS_SECRET_ACCESS_KEY"));
        assertTrue(envEx.getMessage().contains("tokenEnv"));

        QuackException fileEx = assertThrows(QuackException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?tokenFile=/etc/passwd"));
        assertTrue(fileEx.getMessage().contains("tokenFile"));
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

    @Test
    void extraHttpHeadersComeFromProperties() {
        Properties props = new Properties();
        props.setProperty("httpHeader.X-Proxy-Auth", "s3cret");
        props.setProperty("httpHeader.X-Trace-Id", "abc123");
        props.setProperty("httpHeader.X-Cleared", "");

        QuackUri u = QuackUri.parse("jdbc:quack://h:9494", props);

        assertEquals("s3cret", u.extraHttpHeaders().get("X-Proxy-Auth"));
        assertEquals("abc123", u.extraHttpHeaders().get("X-Trace-Id"));
        assertFalse(u.extraHttpHeaders().containsKey("X-Cleared"),
                "empty-valued header should be omitted");
    }

    @Test
    void extraHttpHeadersRejectedOnUrl() {
        QuackException e = assertThrows(QuackException.class,
                () -> QuackUri.parse("jdbc:quack://h:9494?httpHeader.X-Evil=1"));
        assertTrue(e.getMessage().contains("connection Properties"));
    }

    @Test
    void extraHttpHeadersValidated() {
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.", "v")));                       // empty name
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.Bad Name", "v")));                // space in name
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.X-H", "a\r\nInjected: 1")));      // CRLF in value
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.Content-Type", "text/plain")));   // reserved
        assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://h:9494",
                propsOf("httpHeader.host", "evil.example")));         // reserved, case-insensitive
    }

    private static Properties propsOf(String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        return props;
    }
}
