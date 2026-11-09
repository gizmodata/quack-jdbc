package com.gizmodata.quack.jdbc.it;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Spawns a real DuckDB 2.0 CLI as a Quack server for integration tests,
 * listening on 127.0.0.1.
 *
 * <p>These tests gate releases, so a missing or too-old {@code duckdb}
 * binary is a failure, never a skip. Set {@code QUACK_JDBC_TEST_DUCKDB} to
 * override the duckdb binary path.
 */
public final class QuackServerFixture implements AutoCloseable {

    private static final String DEFAULT_TOKEN = "quack-jdbc-it-token";

    private final Process process;
    private final int port;
    private final String token;
    private final BufferedWriter stdin;
    private final Thread stdoutPump;
    private final Path logFile;
    private final String fingerprint;

    private QuackServerFixture(Process process, int port, String token,
                               BufferedWriter stdin, Thread stdoutPump, Path logFile, String fingerprint) {
        this.process = process;
        this.port = port;
        this.token = token;
        this.stdin = stdin;
        this.stdoutPump = stdoutPump;
        this.logFile = logFile;
        this.fingerprint = fingerprint;
    }

    /** Starts a plain-HTTP server; throws if DuckDB 2.0 is not available. Never returns null. */
    public static QuackServerFixture tryStart() throws IOException, InterruptedException {
        return start(false);
    }

    /** Starts an HTTPS server with a fresh self-signed certificate (see {@link #fingerprint()}). */
    public static QuackServerFixture startTls() throws IOException, InterruptedException {
        return start(true);
    }

    static String duckdbBinary() throws IOException, InterruptedException {
        String duckdb = System.getenv().getOrDefault("QUACK_JDBC_TEST_DUCKDB", "duckdb");
        if (!duckdb.contains(java.io.File.separator) && !isOnPath(duckdb)) {
            throw new IOException("The integration tests need the DuckDB 2.0 CLI: '" + duckdb
                    + "' is not on PATH (set QUACK_JDBC_TEST_DUCKDB to its path)");
        }
        String version = runDuckdb(duckdb, "--version").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("v(\\d+)\\.").matcher(version);
        if (!m.find() || Integer.parseInt(m.group(1)) < 2) {
            throw new IOException("The integration tests need DuckDB 2.0 or later (Quack protocol 3); "
                    + duckdb + " reports: " + version);
        }
        return duckdb;
    }

    private static String runDuckdb(String duckdb, String... args) throws IOException, InterruptedException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(duckdb);
        cmd.addAll(java.util.List.of(args));
        if (args.length > 1) cmd.add(1, "-no-agent");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null")));
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(duckdb + " did not finish within 60s");
        }
        String text = new String(out, StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException(duckdb + " " + String.join(" ", args) + " failed: " + text);
        }
        return text;
    }

    private static QuackServerFixture start(boolean tls) throws IOException, InterruptedException {
        String duckdb = duckdbBinary();
        String extra = "";
        String fingerprint = null;
        if (tls) {
            Path keys = Files.createTempDirectory("quack-jdbc-it-keys-");
            String row = runDuckdb(duckdb, "-csv", "-noheader", "-c",
                    "INSTALL quack; LOAD quack; SELECT cert_file, key_file, fingerprint FROM quack_generate_keys(directory := '"
                            + keys.toString().replace("'", "''") + "')").trim();
            String[] parts = row.substring(row.lastIndexOf('\n') + 1).split(",");
            if (parts.length != 3) {
                throw new IOException("Unexpected quack_generate_keys output: " + row);
            }
            fingerprint = parts[2].trim();
            extra = ", disable_ssl => false, ssl_cert_file => '" + parts[0].trim().replace("'", "''")
                    + "', ssl_key_file => '" + parts[1].trim().replace("'", "''") + "'";
        }

        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return launch(duckdb, extra, fingerprint);
            } catch (PortTakenException e) {
                last = e; // another process took the port between pick and bind: try another
            }
        }
        throw last;
    }

    private static final class PortTakenException extends IOException {
        PortTakenException(String message) {
            super(message);
        }
    }

    private static QuackServerFixture launch(String duckdb, String extra, String fingerprint)
            throws IOException, InterruptedException {
        int port = pickFreePort();
        String token = DEFAULT_TOKEN;
        Path logFile = Files.createTempFile("quack-jdbc-it-", ".log");

        ProcessBuilder pb = new ProcessBuilder(duckdb, "-no-agent");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter logWriter = Files.newBufferedWriter(logFile);
        StringBuffer output = new StringBuffer();
        Thread pump = new Thread(() -> {
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    output.append(line).append('\n');
                    logWriter.write(line);
                    logWriter.newLine();
                    logWriter.flush();
                }
            } catch (IOException ignored) {
            } finally {
                try { logWriter.close(); } catch (IOException ignored) {}
            }
        }, "quack-server-stdout");
        pump.setDaemon(true);
        pump.start();

        try {
            writer.write(".mode csv\n");
            writer.write(".headers on\n");
            writer.write("INSTALL quack;\n");
            writer.write("LOAD quack;\n");
            writer.write("CALL quack_serve('quack:127.0.0.1:" + port + "', token=>'" + token + "'" + extra + ");\n");
            writer.flush();
        } catch (IOException e) {
            process.destroyForcibly();
            throw e;
        }

        // Ready means quack_serve reported its listen URL, not merely that
        // something accepts connections on the port (another process may).
        String listening = "://127.0.0.1:" + port;
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            String out = output.toString();
            if (out.contains("Failed to bind")) {
                process.destroyForcibly();
                throw new PortTakenException("Port " + port + " was taken: " + out.trim());
            }
            if (out.contains(listening)) break;
            if (!process.isAlive() || System.currentTimeMillis() > deadline) {
                process.destroyForcibly();
                throw new IOException("Quack server did not start within 60s. Output:\n" + out);
            }
            Thread.sleep(50);
        }
        if (!waitForReady("127.0.0.1", port, 10_000)) {
            process.destroyForcibly();
            throw new IOException("Quack server is not accepting connections. See log: " + logFile);
        }
        return new QuackServerFixture(process, port, token, writer, pump, logFile, fingerprint);
    }

    public String jdbcUrl() {
        return "jdbc:quack://127.0.0.1:" + port + "?token=" + token
                + (fingerprint != null ? "&tls=true" : "");
    }

    /** The TLS server's certificate fingerprint (colon form, as quack_generate_keys prints it); null for HTTP. */
    public String fingerprint() {
        return fingerprint;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public Path logFile() {
        return logFile;
    }

    @Override
    public void close() {
        try {
            stdin.write("CALL quack_stop('quack:127.0.0.1:" + port + "');\n");
            stdin.write(".quit\n");
            stdin.flush();
        } catch (IOException ignored) {
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        stdoutPump.interrupt();
    }

    // ---- helpers ----

    private static int pickFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static boolean waitForReady(String host, int port, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 500);
                return true;
            } catch (IOException ignored) {
                Thread.sleep(250);
            }
        }
        return false;
    }

    private static boolean isOnPath(String binary) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;
        for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(entry, binary);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
