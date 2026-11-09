package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTPS against a quack_serve with a self-signed certificate, pinned by fingerprint. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsIntegrationTest {

    private QuackServerFixture server;

    @BeforeAll
    void start() throws Exception {
        server = QuackServerFixture.startTls();
        assertNotNull(server.fingerprint());
    }

    @AfterAll
    void stop() {
        if (server != null) server.close();
    }

    private String url() {
        return "jdbc:quack://127.0.0.1:" + server.port() + "?token=" + server.token();
    }

    private static Properties pin(String fingerprint) {
        Properties p = new Properties();
        p.setProperty("sslFingerprint", fingerprint);
        return p;
    }

    @Test
    void pinnedFingerprintConnects() throws Exception {
        // the colon form quack_generate_keys prints, and plain hex
        for (String fp : new String[]{server.fingerprint(), server.fingerprint().replace(":", "").toLowerCase()}) {
            try (Connection c = DriverManager.getConnection(url(), pin(fp)); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 41 + 1")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
        }
    }

    @Test
    void wrongFingerprintIsRefused() {
        String wrong = "00".repeat(32);
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(url(), pin(wrong)));
        assertTrue(messages(e).contains("does not match sslFingerprint"), messages(e));
    }

    @Test
    void unpinnedSelfSignedCertificateIsRefused() {
        // TLS without a pin checks the certificate against the JVM's trust
        // store, which does not trust a self-signed quack_serve certificate.
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(url() + "&tls=true"));
        String m = messages(e);
        assertTrue(m.contains("PKIX") || m.contains("certificat"), m);
    }

    @Test
    void plainHttpToATlsServerFails() {
        assertThrows(SQLException.class, () -> DriverManager.getConnection(url() + "&tls=false"));
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) sb.append(t).append(" | ");
        return sb.toString();
    }
}
