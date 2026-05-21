# quack-jdbc

**A JDBC driver for [DuckDB's Quack remote protocol](https://duckdb.org/docs/current/quack/overview).**

Lets any JVM tool — DBeaver, IntelliJ DataGrip, dbt, Spark, your own
service — connect to a DuckDB server over the Quack wire protocol with a
familiar `jdbc:quack://` URL.

[![Maven Central](https://img.shields.io/maven-central/v/com.gizmodata/quack-jdbc?label=Maven%20Central&logo=apachemaven&color=blue)](https://central.sonatype.com/artifact/com.gizmodata/quack-jdbc)
[![Latest Release](https://img.shields.io/github/v/release/gizmodata/quack-jdbc?label=Latest%20Release&logo=github&sort=semver)](https://github.com/gizmodata/quack-jdbc/releases/latest)
[![Download latest jar](https://img.shields.io/badge/download-quack--jdbc.jar-success?logo=java&logoColor=white)](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc.jar)
[![GitHub Repo](https://img.shields.io/badge/github-gizmodata%2Fquack--jdbc-181717?logo=github)](https://github.com/gizmodata/quack-jdbc)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Status:** Experimental / alpha. The Quack protocol itself shipped on
> 2026-05-12 and will stabilize as part of DuckDB v2.0 in September 2026.
> Expect breaking changes between now and then. This driver pins to
> [`duckdb/duckdb-quack@daae4826`](https://github.com/duckdb/duckdb-quack/commit/daae4826f57986fbb6cc2116316f89c673814b23)
> (2026-05-10) and is tested against DuckDB CLI v1.5.2.

## Quickstart

### 1. Start a Quack server (any DuckDB v1.5.2+)

```sql
-- in any DuckDB session, with the unsigned extensions flag enabled (`duckdb -unsigned`)
INSTALL quack FROM core_nightly;
LOAD quack;
CALL quack_serve('quack:127.0.0.1:9494', token=>'my-secret-token');
```

### 2. Add the driver to your project

**Maven:**

```xml
<dependency>
    <groupId>com.gizmodata</groupId>
    <artifactId>quack-jdbc</artifactId>
    <version>0.1.0-alpha.1</version>
</dependency>
```

**Gradle:**

```groovy
implementation "com.gizmodata:quack-jdbc:0.1.0-alpha.1"
```

**Direct jar download** (for DBeaver, DataGrip, or any tool that takes a `.jar`):

| Asset                                                                                                          | Description                                                    |
|----------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| [`quack-jdbc.jar`](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc.jar)            | Latest release — un-versioned filename, always the newest jar  |
| [`quack-jdbc-sources.jar`](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc-sources.jar) | Latest sources jar                                            |
| [`quack-jdbc-javadoc.jar`](https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc-javadoc.jar) | Latest javadoc jar                                            |
| [GitHub releases page](https://github.com/gizmodata/quack-jdbc/releases)                                       | All versioned jars + SHA256 checksums for every release        |

### 3. Connect and query

```java
import java.sql.*;
import java.util.Properties;

public class Demo {
    public static void main(String[] args) throws SQLException {
        Properties props = new Properties();
        props.setProperty("token", "my-secret-token");

        try (Connection conn = DriverManager.getConnection(
                     "jdbc:quack://127.0.0.1:9494", props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT 42 AS answer, 'hello duckdb' AS greeting")) {
            while (rs.next()) {
                System.out.println(rs.getInt("answer") + " / " + rs.getString("greeting"));
            }
        }
    }
}
```

## Connection URL

```
jdbc:quack://host[:port][/database][?token=…&tls=…]
```

| Property         | Default | Notes                                                                    |
|------------------|---------|--------------------------------------------------------------------------|
| `host`           | —       | Required.                                                                |
| `port`           | 9494    | Default Quack port.                                                      |
| `database`       | (none)  | Reserved; passed through to the server when provided.                    |
| `token`          | (none)  | Authentication token. Also accepted via JDBC `Properties` as `token`.    |
| `tls`            | false   | `true` → use `https://` for the underlying HTTP transport.               |
| `useEncryption`  | false   | Alias for `tls` (matches the gizmosql-jdbc-driver convention).           |
| `connectTimeout` | 10      | HTTP connect timeout, as seconds or an ISO-8601 duration like `PT5S`.    |
| `requestTimeout` | 60      | Per-request HTTP timeout, as seconds or an ISO-8601 duration like `PT30S`. |

`token` and `tls` can be set on the URL or via `java.util.Properties`
passed to `DriverManager.getConnection`. URL values take precedence.
The timeout properties follow the same rule.

### Basic timeout configuration

The built-in HTTP transport reads `connectTimeout` and `requestTimeout`
directly from the JDBC URL or connection properties:

```java
try (Connection conn = DriverManager.getConnection(
        "jdbc:quack://127.0.0.1:9494?token=my-secret-token&connectTimeout=5&requestTimeout=30")) {
    // use the connection normally
}
```

The same options can be supplied with `Properties`:

```java
Properties props = new Properties();
props.setProperty("token", "my-secret-token");
props.setProperty("connectTimeout", "5");
props.setProperty("requestTimeout", "PT30S");

try (Connection conn = DriverManager.getConnection("jdbc:quack://127.0.0.1:9494", props)) {
    // use the connection normally
}
```

### Fully custom HTTP transport

Applications that need to customize the HTTP layer can bypass
`DriverManager` and pass a transport factory to `QuackDriver`. The factory
receives the same parsed `QuackUri`, so URL parameters and connection
properties are available to custom transports too:

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

## DBeaver

`quack-jdbc` implements the full JDBC `DatabaseMetaData` surface that
DBeaver uses for catalog browsing, modeled query-for-query on DuckDB's
own JDBC driver. That means:

- Catalog / schema / table / view listing
- Column types, nullability, defaults, comments
- Primary keys
- Imported / exported / cross-reference foreign keys
- Index listing (per index; column-level expansion is a DuckDB limitation)
- `getTypeInfo`, `getFunctions`

Register the driver in **DBeaver → Database → Driver Manager**:

| Field                 | Value                                                |
|-----------------------|------------------------------------------------------|
| Driver Name           | DuckDB (Quack)                                       |
| Driver Type           | Generic                                              |
| Class Name            | `com.gizmodata.quack.jdbc.sql.QuackDriver`          |
| URL Template          | `jdbc:quack://{host}[:{port}]/[{database}]`         |
| Default Port          | `9494`                                               |
| Driver Files          | `quack-jdbc-<version>.jar`                          |

Add `token` as a connection property (or include `?token=…` on the URL).

## Building from source

```bash
mvn package
```

Produces `target/quack-jdbc-<version>.jar` with no runtime dependencies
(uses JDK 17 `java.net.http.HttpClient`). On release tags, CI also
publishes an un-versioned `quack-jdbc.jar` to the GitHub release so tools
that want "the latest jar" can fetch a stable URL.

## Testing

```bash
mvn test                       # unit + integration tests
mvn -Dtest='!*Integration*' test   # unit only (no duckdb required)
```

Integration tests spawn a real DuckDB CLI as a Quack server and exercise
the driver end-to-end: connection handshake, CRUD, multi-chunk fetches,
scalar type round-trips, `DatabaseMetaData` listings, bad-token auth,
concurrent connections, and more. They auto-skip when `duckdb` is not on
PATH. Override the binary with `QUACK_IT_DUCKDB=/path/to/duckdb`.

## Design

```
codec/      BinaryReader/Writer — DuckDB BinarySerializer (LE uint16 field ids,
            ULEB128/SLEB128 ints, length-prefixed strings/blobs/lists,
            objects terminated by FIELD_END = 0xFFFF)
type/       Logical type model and codec (full DuckDB type system)
message/    Quack message records, MessageCodec, DataChunk vector decoder
            (FLAT / CONSTANT / DICTIONARY / SEQUENCE encodings)
transport/  QuackUri parser, QuackHttpTransport (POST /quack via JDK HttpClient)
sql/        java.sql.* surface (Driver, Connection, Statement,
            PreparedStatement, ResultSet, ResultSetMetaData, DatabaseMetaData,
            with Skeletal* bases that throw SQLFeatureNotSupportedException
            for the parts we don't yet implement)
```

The `codec/type/message/transport` layers are reusable for an ADBC driver
in Go (or any other language); a companion `quack-adbc` is on the
GizmoData roadmap.

## Compatibility notes

- DataChunk vector encodings supported: **FLAT**, **CONSTANT**,
  **DICTIONARY**, **SEQUENCE**. **FSST** is not yet supported.
- Nested types (STRUCT / LIST / MAP / ARRAY) decode to Java collections
  (`Map<String,Object>` / `List<Object>`); full `java.sql.Array` /
  `java.sql.Struct` wrapping is on the roadmap.
- Prepared-statement parameters use client-side literal substitution.
  Native parameter binding will follow once the Quack protocol surfaces
  bind parameters.
- The `APPEND_REQUEST` fast-path (sending DataChunks directly) is
  decoder-complete but not encoder-complete; surfaced as a `RuntimeException`
  in `VectorCodec.encodeDataChunk` until done.

## Credits

- Wire-format codec ported clean-room from
  [`@quack-protocol/sdk`](https://github.com/tobilg/quack-protocol) by Tobi (MIT)
- `DatabaseMetaData` queries modeled on
  [`duckdb/duckdb-java`](https://github.com/duckdb/duckdb-java)'s
  `DuckDBDatabaseMetaData` (MIT)
- Many thanks to the DuckDB team for shipping a refreshingly small remote
  protocol

## License

[MIT](LICENSE) — see `LICENSE` for the full text plus attribution.

## Contributing

Issues and PRs welcome at <https://github.com/gizmodata/quack-jdbc>.
See [CLAUDE.md](CLAUDE.md) for contributor notes (layout, conventions,
DBeaver-compat expectations).
