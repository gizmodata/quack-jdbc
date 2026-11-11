# quack-jdbc - by [GizmoData™](https://gizmodata.com)

**A JDBC driver for [DuckDB's Quack remote protocol](https://duckdb.org/docs/current/quack/overview): DBeaver, DataGrip and any Java application on a remote DuckDB 2.0.**

> An independent, open-source project. Not affiliated with, endorsed by, or sponsored by the DuckDB Foundation or DuckDB Labs. "DuckDB" is a trademark of the DuckDB Foundation, used here only to describe compatibility.

[<img src="https://img.shields.io/badge/GitHub-gizmodata%2Fquack--jdbc-blue.svg?logo=Github">](https://github.com/gizmodata/quack-jdbc)
[![CI](https://github.com/gizmodata/quack-jdbc/actions/workflows/ci.yml/badge.svg)](https://github.com/gizmodata/quack-jdbc/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.gizmodata/quack-jdbc?label=Maven%20Central&logo=apachemaven&color=blue)](https://central.sonatype.com/artifact/com.gizmodata/quack-jdbc)
[![Latest Release](https://img.shields.io/github/v/release/gizmodata/quack-jdbc?label=Latest%20Release&logo=github&sort=semver)](https://github.com/gizmodata/quack-jdbc/releases/latest)
[![Download latest jar](https://img.shields.io/badge/download-quack--jdbc.jar-success?logo=java&logoColor=white)](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc.jar)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg?logo=openjdk)](https://adoptium.net)
[![DuckDB 2.0](https://img.shields.io/badge/DuckDB-2.0-fff000.svg?logo=duckdb&logoColor=black)](https://duckdb.org)
[![License](https://img.shields.io/github/license/gizmodata/quack-jdbc)](https://github.com/gizmodata/quack-jdbc/blob/main/LICENSE)
[![Hardcharger](https://img.shields.io/badge/works%20with-Hardcharger%E2%84%A2-f15a24.svg)](https://gizmodata.com/hardcharger)

A pure-Java client for Quack, the remote protocol DuckDB 2.0 serves with `quack_serve`. One
jar with no dependencies (the JDK's own `java.net.http`): connect with a `jdbc:quack://`
URL from DBeaver, DataGrip, IntelliJ, Spark or your own service, browse the catalog, stream
large results, and bulk-load data into DuckDB tables.

> **Status: stable (1.0).** Speaks Quack protocol version 3, the one DuckDB 2.0 ships.
> DuckDB 1.5's preview Quack (protocol version 1) is not compatible; for those servers, pin
> `com.gizmodata:quack-jdbc:0.2.0-alpha.6`, the last release for DuckDB 1.5.
> Companion to [adbc-driver-quack](https://github.com/gizmodata/adbc-driver-quack), the
> Arrow ADBC driver for the same protocol.

## Quickstart

### 1. Start a Quack server (DuckDB 2.0)

```sql
-- in any DuckDB 2.0 session (quack is a core extension)
INSTALL quack;
LOAD quack;
CALL quack_serve('quack:127.0.0.1:9494', token => 'my-secret-token');
```

### 2. Add the driver

**Maven:**

```xml
<dependency>
    <groupId>com.gizmodata</groupId>
    <artifactId>quack-jdbc</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Gradle:**

```groovy
implementation "com.gizmodata:quack-jdbc:1.0.1"
```

**Direct jar download** (for DBeaver, DataGrip, or any tool that takes a `.jar`):

| Asset | Description |
|---|---|
| [`quack-jdbc.jar`](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc.jar) | Latest release, un-versioned file name |
| [`quack-jdbc-sources.jar`](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc-sources.jar) | Latest sources jar |
| [`quack-jdbc-javadoc.jar`](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc-javadoc.jar) | Latest javadoc jar |
| [GitHub releases page](https://github.com/gizmodata/quack-jdbc/releases) | Every versioned jar, with SHA-256 checksums |

### 3. Connect and query

```java
import java.sql.*;
import java.util.Properties;

public class Demo {
    public static void main(String[] args) throws SQLException {
        Properties props = new Properties();
        props.setProperty("token", "my-secret-token");

        try (Connection conn = DriverManager.getConnection("jdbc:quack://127.0.0.1:9494", props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42 AS answer, 'hello duckdb' AS greeting")) {
            while (rs.next()) {
                System.out.println(rs.getInt("answer") + " / " + rs.getString("greeting"));
            }
        }
    }
}
```

Large results stream: the driver holds one server batch at a time and fetches the next as
`ResultSet.next()` reaches it. `Statement.cancel()` stops the statement on the server (the
interrupted call fails with SQLSTATE `57014`), and server errors carry a SQLSTATE derived
from DuckDB's exception type (`42S02` missing table, `42601` syntax error, `23000`
constraint violation, ...) with the matching `SQLException` subclass.

## TLS and production Quack

DuckDB 2.0 serves Quack over **HTTPS on any address but localhost**, and the driver follows
the same rule: plain HTTP to `localhost` / `127.0.0.1` / `::1`, HTTPS everywhere else.
`tls=true|false` overrides it either way.

`quack_serve` generates a self-signed certificate on first use. A client authenticates such
a server by **pinning its certificate's SHA-256 fingerprint**, as DuckDB's own client does:

```sql
-- on the server
SELECT fingerprint FROM quack_generate_keys();   -- or the info of quack_server_list()
CALL quack_serve('quack:0.0.0.0:9494', token => 'my-secret-token', allow_other_hostname => true);
```

```java
Properties props = new Properties();
props.setProperty("token", "my-secret-token");
props.setProperty("sslFingerprint", "2B:31:F0:…:EA");   // the colon form openssl prints works too
try (Connection conn = DriverManager.getConnection("jdbc:quack://db.example.com:9494", props)) {
    // ...
}
```

With a certificate from a CA (`quack_serve(…, ssl_cert_file => …, ssl_key_file => …)`), no
pin is needed: the driver verifies the chain and host name against the JVM's trust store.

The driver also keeps the connection's **heartbeat lease** alive (DuckDB 2.0 drops a client
that has been silent for the lease, 60 s by default, configurable with `heartbeatTimeout`),
and **cancels** the statement on the server when a call times out or its thread is
interrupted, rather than just walking away from it.

## Types

| DuckDB | `getObject` returns | `getColumnType` |
|---|---|---|
| BOOLEAN, TINYINT … BIGINT, FLOAT, DOUBLE | `Boolean`, `Byte`/`Short`/`Integer`/`Long`, `Float`, `Double` | the matching `Types` code |
| UTINYINT, USMALLINT, UINTEGER, UBIGINT | `Short`, `Integer`, `Long`, `Long` (UBIGINT as two's complement) | `SMALLINT`, `INTEGER`, `BIGINT`, `OTHER` |
| HUGEINT, UHUGEINT | `BigInteger` | `OTHER` |
| DECIMAL(p, s) | `BigDecimal` | `DECIMAL` |
| VARCHAR, ENUM | `String` | `VARCHAR`, `OTHER` |
| BLOB, BIT | `byte[]` | `BLOB`, `BIT` |
| **GEOMETRY** | **`byte[]`, WKB** (and written back as WKB by `appendChunk`) | `BLOB` |
| UUID | `java.util.UUID` | `OTHER` |
| DATE | `LocalDate` | `DATE` |
| TIME, TIME_NS | `LocalTime` (TIME_NS keeps nanoseconds) | `TIME` |
| TIMETZ | `OffsetTime` | `TIME_WITH_TIMEZONE` |
| TIMESTAMP_S, TIMESTAMP_MS, TIMESTAMP, TIMESTAMP_NS | `LocalDateTime`, never shifted by the JVM's time zone; TIMESTAMP_NS keeps nanoseconds | `TIMESTAMP` |
| TIMESTAMPTZ, TIMESTAMPTZ_NS | `OffsetDateTime` in UTC; TIMESTAMPTZ_NS keeps nanoseconds | `TIMESTAMP_WITH_TIMEZONE`, `OTHER` |
| INTERVAL | `IntervalValue` (months, days, micros) | `OTHER` |
| **VARIANT** | **`String`: the value's JSON text**, exactly as `variant::JSON` prints it | `OTHER` |
| LIST, ARRAY | `java.sql.Array` | `ARRAY` |
| STRUCT, TUPLE | `java.sql.Struct` (a TUPLE's unnamed fields are keyed `#0`, `#1`, … in nested maps) | `STRUCT` |
| MAP | `java.util.Map` | `OTHER` |

The `getColumnType` codes and type names match DuckDB's own JDBC driver (`duckdb_jdbc`
2.0), which the `-Poracle` tests check. A VARIANT read from shredded storage, or one holding
a GEOMETRY or BIGNUM value, fails with an error that names the type; cast it in the query
(`v::JSON` or `v::VARCHAR`).

## Bulk load

`QuackSession.appendChunks` (reached through `QuackConnection.session()`) streams
column-oriented `DataChunk`s into an existing table, the way DuckDB's own Quack client
inserts into an attached table: one `INSERT … SELECT * FROM scan_data_from_quack_client(…)`
statement, fed by `SEND_DATA` requests of several 2048-row chunks each, committed as one.
Columns bind by position; a chunk whose columns do not fit the table fails with SQLSTATE
`42S01`. For row-at-a-time inserts, `PreparedStatement.executeBatch()` works as usual.

## Share data with Hardcharger

[**Hardcharger™**](https://gizmodata.com/hardcharger) is GizmoData's DuckDB 2.0 server for
production. Moving data between a Quack server and Hardcharger takes no files, in either
direction.

### Hardcharger ↔ Quack, server to server, with `ATTACH`

Hardcharger runs DuckDB 2.0, so it can attach a Quack server with DuckDB's own `quack`
extension and copy between the two without the data passing through your client. From
Java, through the [Hardcharger JDBC driver](https://github.com/gizmodata/hardcharger-jdbc)
(`com.gizmodata:hardcharger-jdbc`):

```java
Properties hc = new Properties();
hc.setProperty("user", "hardcharger");
hc.setProperty("password", "change-me");
hc.setProperty("useEncryption", "false");   // plain HTTP on localhost

try (Connection dst = DriverManager.getConnection("jdbc:hardcharger://127.0.0.1:31337", hc);
     Statement s = dst.createStatement()) {
    s.execute("INSTALL quack");
    s.execute("LOAD quack");
    s.execute("CREATE OR REPLACE SECRET quack_src (TYPE quack, SCOPE 'quack:127.0.0.1:9494', TOKEN 'my-secret-token')");
    s.execute("ATTACH IF NOT EXISTS 'quack:127.0.0.1:9494' AS src (TYPE quack)");

    // Quack -> Hardcharger
    s.execute("CREATE OR REPLACE TABLE orders AS SELECT * FROM src.orders");
    // Hardcharger -> Quack
    s.execute("CREATE OR REPLACE TABLE src.main.big_orders AS SELECT * FROM orders WHERE amount > 100000");
}
```

### Quack → Hardcharger, JDBC to JDBC

When the copy should go through your application (to transform rows on the way), read with
quack-jdbc and write with the Hardcharger JDBC driver. quack-jdbc streams the result, so
the client never holds more than a batch:

```java
try (Connection src = DriverManager.getConnection("jdbc:quack://127.0.0.1:9494?token=my-secret-token");
     Connection dst = DriverManager.getConnection("jdbc:hardcharger://127.0.0.1:31337", hc)) {
    try (Statement ddl = dst.createStatement()) {
        ddl.execute("CREATE OR REPLACE TABLE orders (id BIGINT, customer VARCHAR, amount DECIMAL(12,2), order_date DATE)");
    }
    dst.setAutoCommit(false);
    long rows = 0;
    try (Statement read = src.createStatement();
         ResultSet rs = read.executeQuery("SELECT id, customer, amount, order_date FROM orders");
         PreparedStatement write = dst.prepareStatement("INSERT INTO orders VALUES (?, ?, ?, ?)")) {
        while (rs.next()) {
            write.setLong(1, rs.getLong(1));
            write.setString(2, rs.getString(2));
            write.setBigDecimal(3, rs.getBigDecimal(3));
            write.setObject(4, rs.getObject(4, java.time.LocalDate.class));
            write.addBatch();
            if (++rows % 10_000 == 0) write.executeBatch();
        }
        write.executeBatch();
    }
    dst.commit();
    System.out.printf("Copied %,d rows%n", rows);
}
```

Both examples ran against Hardcharger 2.8.0 and a DuckDB 2.0.0 Quack server. To try them
without a server of your own, `pip install hardcharger` runs a local Hardcharger
(`hardcharger.Server(password="change-me")`), or use the Docker image
`ghcr.io/gizmodata/hardcharger:latest`.

## Why Hardcharger and Chargewire for production

Quack is DuckDB's own remote protocol, and a great fit for DuckDB-to-DuckDB work, sharing a
database with a teammate, and development. For a database many people and services depend
on, [Hardcharger](https://gizmodata.com/hardcharger) speaks
[**Chargewire**](https://github.com/gizmodata/chargewire), an open HTTP protocol built for
it:

| | Quack (DuckDB 2.0) | Hardcharger + Chargewire |
|---|---|---|
| **Access control** | a token checked by an authentication function (one shared token by default), plus an optional SQL authorization callback | users and roles with full **RBAC**, checked inside DuckDB against each statement's own plan (a write hidden in a CTE is still a write), **row policies**, **column masking** and **tenants** |
| **Sign-in** | the token | passwords, scoped tokens, **SSO (OIDC / JWKS)**, **mTLS** client certificates |
| **Wire format** | DuckDB's internal vector serialization, tied to DuckDB versions (1.5 → 2.0 changed it) | **Apache Arrow IPC**, an open standard any Arrow library reads, optionally LZ4/zstd compressed |
| **Bind parameters** | not sent: SQL text only, so this driver substitutes them client-side | sent to the server, with prepared statements |
| **Large results** | batched fetch | streamed, **resumable** results, described as partitions for scale-out |
| **Bulk ingest** | client-built DataChunks | Arrow streamed straight into a table |
| **Audit and operations** | request log | **tamper-evident audit log**, instrumentation (DuckDB or PostgreSQL), Prometheus `/metrics`, query profiles, `KILL SESSION`, a statement queue and timeouts |
| **Clients** | DuckDB, this driver, adbc-driver-quack | JDBC, ADBC (Python, Go, and C/C++, R, Rust through a driver manager), JavaScript, plain HTTP (`curl`), a command-line client, a built-in web UI, desktop and iPhone/iPad apps |

Hardcharger is free, with every feature in every build: see
[gizmodata.com/hardcharger](https://gizmodata.com/hardcharger) and the
[documentation](https://docs.hardcharger.gizmodata.com). For JDBC tools, the
[Hardcharger JDBC driver](https://github.com/gizmodata/hardcharger-jdbc) is a single jar
for Java 17+, and DBeaver connects to it the same way it connects to quack-jdbc. The two
work together: keep Quack where it fits, and move data to Hardcharger with the
[examples above](#share-data-with-hardcharger).

## Connection URL

```
jdbc:quack://host[:port][/database][?token=…&tls=…]
jdbc:quack:host[:port]          also accepted (DuckDB's quack:host:port spelling)
```

| Property | Default | Notes |
|---|---|---|
| `host` | (required) | |
| `port` | 9494 | The default Quack port. |
| `database` | (none) | The catalog `getCatalog()` reports. |
| `token` | (none) | Authentication token. Prefer `tokenFile` / `tokenEnv` for shared configs. |
| `password` | (none) | Alias for `token`, for tools that show a password field. |
| `tokenEnv` | (none) | Environment variable that holds the token. Properties only. |
| `tokenFile` | (none) | Local file that holds the token. Properties only. |
| `tls` | on, except for `localhost`, `127.0.0.1`, `::1` | `true` / `false` forces HTTPS / HTTP. `useEncryption` is an alias. |
| `sslFingerprint` | (none) | Pin the server certificate's SHA-256 fingerprint (64 hex digits; the colon form and a `sha256:` prefix are accepted). Accepts exactly that certificate, so a self-signed `quack_serve` certificate works; implies `tls=true`. `ssl_fingerprint` is an alias. |
| `heartbeatTimeout` | 60 | Connection lease asked of the server, as seconds or an ISO-8601 duration. The driver renews it about every third of it. |
| `clientId` | (none) | Client id sent in the handshake (at least 4 characters). |
| `connectTimeout` | 10 | HTTP connect timeout, as seconds or an ISO-8601 duration like `PT5S`. |
| `requestTimeout` | 60 | Per-request HTTP timeout. A request that times out is cancelled on the server. |
| `httpHeader.<Name>` | (none) | Extra HTTP header on every request (proxy or load-balancer auth). Repeatable. Properties only. |

Options can go on the URL or in the `java.util.Properties` passed to
`DriverManager.getConnection`; URL values take precedence. The token comes from `token`,
then `password`, then `tokenEnv`, then `tokenFile`.

`tokenEnv`, `tokenFile` and `httpHeader.*` are accepted only through `Properties` (or a
tool's driver-properties panel) and rejected on the URL: a pasted or shared URL must not be
able to read a local secret, or add headers, and send them to whatever host it names.

### Safer token configuration

For desktop tools such as DataGrip, avoid putting `token=...` in the JDBC URL: URLs and
plain driver properties are easy to copy, log or commit by accident. Set `tokenFile` or
`tokenEnv` as a driver property instead:

```text
tokenFile=/Users/alice/.config/quack/prod.token
```

### Fully custom HTTP transport

Applications that need to customize the HTTP layer can bypass `DriverManager` and pass a
transport factory to `QuackDriver`. The factory receives the parsed `QuackUri`, so URL
parameters and connection properties are available to it (a custom transport is
responsible for its own TLS settings, `sslFingerprint` included):

```java
QuackDriver driver = new QuackDriver();
try (Connection conn = driver.connect(
        "jdbc:quack://127.0.0.1:9494?token=my-secret-token&connectTimeout=5&requestTimeout=30",
        new Properties(),
        uri -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(uri.connectTimeout())
                    .build();
            return new QuackHttpTransport(uri.httpUri(), httpClient, uri.requestTimeout());
        })) {
    // use the connection normally
}
```

## DBeaver and DataGrip

quack-jdbc implements the `DatabaseMetaData` surface DBeaver and DataGrip use to browse a
catalog, modeled query-for-query on DuckDB's own JDBC driver; the `-Poracle` tests check
`getSchemas`, `getTables`, `getColumns`, `getPrimaryKeys`, `getImportedKeys` and
`getTableTypes` row for row against `duckdb_jdbc` 2.0:

- catalogs, schemas, tables and views
- column types, nullability, defaults and comments
- primary keys, imported / exported / cross-reference foreign keys
- indexes (one row per index; column-level expansion is a DuckDB limitation)
- `getTypeInfo`, `getFunctions`

Register the driver in **DBeaver → Database → Driver Manager**:

| Field | Value |
|---|---|
| Driver Name | DuckDB (Quack) |
| Driver Type | Generic |
| Class Name | `com.gizmodata.quack.jdbc.sql.QuackDriver` |
| URL Template | `jdbc:quack://{host}[:{port}]/[{database}]` |
| Default Port | `9494` |
| Driver Files | `quack-jdbc.jar` |

Add `tokenFile` or `tokenEnv` (and `sslFingerprint` for a self-signed server) as driver
properties.

## Building and testing

```bash
mvn package                        # target/quack-jdbc-<version>.jar, no runtime dependencies
mvn verify                         # unit + integration tests (needs the DuckDB 2.0 CLI)
mvn -Poracle verify                # also the parity tests against DuckDB's own JDBC driver
mvn -Dtest='!*IntegrationTest' test   # unit tests only
```

The integration tests start a real DuckDB 2.0 CLI as a Quack server on `127.0.0.1` (plain
HTTP, and HTTPS with a generated certificate for the TLS tests) and exercise the driver end
to end: multi-batch fetches and ingest, the heartbeat lease, cancel, TLS pinning, every
DuckDB 2.0 type, SQLSTATEs and `DatabaseMetaData`. They **fail**, never skip, when no
DuckDB 2.0 `duckdb` is on `PATH`; point `QUACK_JDBC_TEST_DUCKDB` at another binary.
CI runs everything on JDK 17 and 25 against a pinned DuckDB release.

## Design

```
codec/      BinaryReader/Writer: DuckDB's BinarySerializer
type/       the DuckDB logical type model and its codec
message/    Quack protocol 3 messages, DataChunk and vector codecs, VARIANT -> JSON
transport/  QuackUri, QuackHttpTransport (POST /quack over the JDK HttpClient), TLS pinning
sql/        java.sql.*: Driver, Connection, Statement, PreparedStatement, ResultSet,
            metadata, and QuackSession (handshake, heartbeat, fetch, cancel, SEND_DATA)
```

## Compatibility notes

- DataChunk vector encodings: FLAT, CONSTANT, DICTIONARY and SEQUENCE. FSST and shredded
  VARIANT vectors fail with an error that says so.
- Prepared-statement parameters are substituted client-side as SQL literals: Quack's
  `PREPARE` carries SQL text only.
- The server streams one result per connection. Starting a query while a `ResultSet` on
  the same connection is still streaming first reads the rest of that result into memory,
  so both stay usable; use a second connection to read two large results at once.
- Nested values are wrapped at the top level only: elements inside a LIST, ARRAY, STRUCT or
  MAP are plain `java.util.List` / `java.util.Map` values.

## Credits

- Wire format: [DuckDB's Quack protocol](https://duckdb.org/docs/current/quack/overview)
  ([duckdb/duckdb-quack](https://github.com/duckdb/duckdb-quack), MIT); the original codec
  was ported clean-room from [`@quack-protocol/sdk`](https://github.com/tobilg/quack-protocol)
  by Tobi (MIT)
- `DatabaseMetaData` queries modeled on [`duckdb/duckdb-java`](https://github.com/duckdb/duckdb-java)'s
  `DuckDBDatabaseMetaData` (MIT)
- Nested types and the parity harness contributed by Jayson Minard (@apatrida)

## Trademarks

DuckDB is a trademark of the DuckDB Foundation. **quack-jdbc is not affiliated with,
endorsed by, or sponsored by the DuckDB Foundation or DuckDB Labs.** References to DuckDB
and Quack identify the software this driver interoperates with.

## License

[MIT](https://github.com/gizmodata/quack-jdbc/blob/main/LICENSE): Copyright (c) 2026
GizmoData LLC. See the `LICENSE` file for the full text and attributions.

## Contributing

Issues and PRs welcome at <https://github.com/gizmodata/quack-jdbc>. See
[CLAUDE.md](CLAUDE.md) for contributor notes.

---

<p align="center">
  <a href="https://gizmodata.com"><img src="https://raw.githubusercontent.com/gizmodata/quack-jdbc/main/docs/assets/gizmodata-logo.png" alt="GizmoData™" width="56"></a><br>
  <sub>Built by <a href="https://gizmodata.com">GizmoData™</a>, makers of <a href="https://gizmodata.com/hardcharger">Hardcharger™</a>. Free and open source.</sub>
</p>

<p align="center"><sub>Hardcharger™ and GizmoData™ are trademarks of GizmoData LLC.</sub></p>
