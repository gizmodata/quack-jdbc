# CLAUDE.md

Project notes for Claude and other contributors.

## What this is

A JDBC driver for [DuckDB's Quack remote protocol](https://duckdb.org/docs/current/quack/overview),
protocol version 3 (DuckDB 2.0). 1.0.0 mirrors adbc-driver-quack 1.0.0
(`gizmodata/adbc-driver-quack`, Go): when the protocol changes, port the Go
driver's `internal/message`, `internal/transport` and `driver/quack/session.go`
changes here. 0.2.0-alpha.6 was the last release for DuckDB 1.5 (protocol 1).
Lets JVM tools (DBeaver, IntelliJ, dbt, Spark, etc.) connect to a remote
DuckDB server with a familiar `jdbc:quack://host:port?token=…` URL.

## Package layout

```
com.gizmodata.quack.jdbc
├── codec/        BinaryReader, BinaryWriter, HugeIntParts, QuackConstants
├── type/         LogicalType, LogicalTypeId, PhysicalType, ExtraTypeInfo, codec
├── message/      MessageType, MessageHeader, QuackMessage (sealed), MessageCodec,
│                 DataChunk, DecodedVector, VectorCodec
├── transport/    QuackUri (URL parser), QuackHttpTransport (java.net.http)
├── sql/          QuackDriver, QuackConnection, QuackStatement,
│                 QuackPreparedStatement, QuackResultSet,
│                 QuackResultSetMetaData, QuackDatabaseMetaData,
│                 SqlLiteral (parameter interpolation),
│                 SkeletalConnection/Statement/ResultSet (throws-by-default bases)
└── Quack*Exception   error hierarchy
```

The `codec`, `type`, `message`, and `transport` packages are reusable for
an ADBC driver (Go or otherwise). The `sql` package is the JDBC-specific
surface.

## Wire format reference

Wire-format details ported clean-room from
[`@quack-protocol/sdk`](https://github.com/tobilg/quack-protocol) by Tobi
(MIT). The TypeScript reference lives at `/tmp/quack-ref/` after running:

```bash
mkdir -p /tmp/quack-ref
for f in binary constants messages logical-types vector protocol values \
         client errors sql builders index; do
  gh api repos/tobilg/quack-protocol/contents/src/$f.ts --jq .content \
    | base64 -d > /tmp/quack-ref/$f.ts
done
```

## Build & test

```bash
mvn package          # build
mvn verify           # unit + integration (needs the DuckDB 2.0 CLI on PATH)
mvn -Poracle verify  # also parity with duckdb_jdbc (what CI runs)
mvn -Dtest='!*IntegrationTest' test    # unit only
```

Integration tests spawn a real DuckDB 2.0 CLI as a Quack server on
127.0.0.1 (`QuackServerFixture`; `startTls()` for HTTPS with a generated
certificate). They FAIL, never skip, when no DuckDB 2.0 `duckdb` is on PATH:
they gate releases. Override the binary with `QUACK_JDBC_TEST_DUCKDB`.
CI pins the DuckDB CLI version (`DUCKDB_VERSION` in ci.yml); bump it with
DuckDB releases.

Protocol 3 facts that bit during the port:
- The server streams one result per connection; a new PREPARE supersedes an
  unfinished one ("Result has been closed"). `QuackSession` buffers the open
  cursor before starting another query.
- Server batches are cut by bytes (`quack_target_batch_bytes`, 32 MiB) and
  PREPARE inlines up to `quack_prepare_inline_rows`; a bare `range()` is tiny
  SEQUENCE vectors and fits one batch. Tests that need many batches set both
  settings low and use incompressible data (`md5(i::VARCHAR)`).
- VARIANT -> JSON must match DuckDB's `variant::JSON`: object keys sorted
  bytewise, yyjson's double format. Reference source: DuckDB's
  `src/function/cast/variant/to_json.cpp` at the 2.0 release.

## Quack extension version

`quack` is a core extension of DuckDB 2.0; the fixture runs
`INSTALL quack; LOAD quack;` against a stock `duckdb` 2.0 binary. The driver
was ported to protocol 3 against DuckDB v2.0.0 (`d54cbbe3df`). Re-run the
full suite (with `-Poracle`) when DuckDB cuts a new release.

## DBeaver metadata reference

`QuackDatabaseMetaData` mirrors DuckDB's own JDBC driver
(`org.duckdb.DuckDBDatabaseMetaData`) query-for-query so DBeaver sees the
same shape it does from a native DuckDB connection. When updating queries,
cross-reference DuckDB's `src/main/java/org/duckdb/DuckDBDatabaseMetaData.java`.

## Conventions

- Java 17 floor — broadest runtime compatibility (DBeaver ships with JRE 17).
  No pattern-matching switches; use `instanceof` chains.
- No runtime dependencies. JDK `java.net.http.HttpClient` for transport.
- No emoji in code, javadocs, or commit messages.
- Throw `SQLFeatureNotSupportedException` for unimplemented JDBC surface
  (see the `Skeletal*` base classes).
- DBeaver compatibility is a hard requirement — when changing
  `QuackDatabaseMetaData`, verify against DuckDB's reference before
  diverging.

## Release process

1. Verify CI is green on `main` (both JDK jobs present and passing).
2. Bump `version` in `pom.xml`, move `[Unreleased]` in `CHANGELOG.md` into a
   `## [X.Y.Z] - YYYY-MM-DD` section, merge to `main` through a PR.
3. Tag `vX.Y.Z` on the merged `main` commit and push the tag. The tag's CI
   run tests that commit, publishes to Maven Central only if the tests
   pass, then creates the GitHub Release from the CHANGELOG section.
4. Confirm the version on repo1.maven.org and the GitHub Release.

## Roadmap

- FSST vector decoding (if servers start sending it)
- Native parameter binding once the Quack protocol carries bind values
- Recursive `java.sql.Array` / `java.sql.Struct` wrapping for nested elements
