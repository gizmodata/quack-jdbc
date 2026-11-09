package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Trusts exactly one server certificate: the leaf whose SHA-256 fingerprint
 * matches {@code sslFingerprint}. CA and hostname checks are replaced by the
 * pin, as in DuckDB's own pinned Quack client, so a self-signed
 * {@code quack_serve} certificate works without a trust store.
 */
final class PinnedTrustManager extends X509ExtendedTrustManager {

    private final String fingerprint;

    PinnedTrustManager(String fingerprint) {
        this.fingerprint = fingerprint.toUpperCase(Locale.ROOT);
    }

    static SSLContext sslContext(String fingerprint) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{new PinnedTrustManager(fingerprint)}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new QuackException("Could not set up the pinned TLS context: " + e.getMessage(), e);
        }
    }

    static String sha256(X509Certificate cert) throws CertificateEncodingException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void check(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("The Quack server sent no certificate");
        }
        String got = sha256(chain[0]);
        if (!MessageDigest.isEqual(got.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new CertificateException("The Quack server's certificate does not match sslFingerprint"
                    + " (expected " + fingerprint + ", got " + got + ")");
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("client certificates are not used");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException("client certificates are not used");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("client certificates are not used");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
