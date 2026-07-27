# Changelog

All notable changes to **quack-jdbc** are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Extra HTTP headers**: `httpHeader.<Name>` connection properties add
  static headers to every request the driver makes (proxy/LB auth),
  mirroring the `EXTRA_HTTP_HEADERS` parameter duckdb-quack added to
  its client secret (duckdb/duckdb-quack#204). Accepted via connection
  `Properties` only — rejected on the JDBC URL so a pasted URL cannot
  inject headers; protocol-owned headers (`Content-Type`, `Accept`,
  `Host`, `Content-Length`) are reserved; an empty value omits the
  header. Same-day parity with adbc-driver-quack's
  `adbc.quack.http.header.<Name>` options.
- **Full nested type names in `ResultSetMetaData`**: `getColumnTypeName`
  now reports the element/field types — `INTEGER[]`, `DOUBLE[2]`,
  `INTEGER[][]`, `STRUCT(name type, ...)`, `MAP(K, V)`, and
  `ENUM('a', 'b', ...)` — instead of bare `LIST`/`ARRAY`/`STRUCT`/`MAP`/
  `ENUM`, recursing through nested types and double-quoting non-identifier
  field names, matching DuckDB's own JDBC driver. ENUM reports its bare
  name at the top level and the expanded form only when nested, mirroring
  duckdb-jdbc.
- **JDBC wrapper objects for nested columns**: `getObject` now returns a
  `java.sql.Array` for LIST/ARRAY, a `java.sql.Struct` (whose
  `getAttributes()` are the field values in declared order) for STRUCT,
  and a `java.util.Map` for MAP — matching duckdb-jdbc.
  `getObject(i, Array.class)` and `getObject(i, Struct.class)` return the
  wrappers.
- **Nested APPEND encoding**: `VectorCodec` now encodes STRUCT, LIST,
  ARRAY, and MAP vectors (previously scalar/VARCHAR/BLOB only), so
  `appendChunk` can bulk-load nested data with arbitrary nesting
  (`INTEGER[][]`, list-of-struct, struct-of-list, ...). MAP rows accept a
  `java.util.Map`; a non-null STRUCT row that is not a `Map` is rejected
  with `QuackProtocolException` rather than silently encoded as all-NULL.
- **duckdb-jdbc oracle parity harness** (`-Poracle`): an opt-in test
  profile asserting `ResultSetMetaData` matches DuckDB's own JDBC driver
  (`duckdb_jdbc` 1.5.5.0) across a shared type matrix. The oracle driver
  is reached via `java.sql.*` + `Class.forName`, so there is no
  compile-time dependency and a plain `mvn test` stays native-free (the
  `oracle` JUnit tag is excluded by default).

### Changed

- Verified against DuckDB v1.5.5 (2026-07-22): full suite green with a
  v1.5.5 CLI as the integration server; wire protocol unchanged, so
  the supported server floor stays v1.5.3.
- **MAP and ENUM `getColumnType` now report `Types.OTHER`** (previously
  `Types.ARRAY` for MAP and `Types.VARCHAR` for ENUM), matching
  duckdb-jdbc.
- **MAP columns now materialize as `java.util.Map`** (a `LinkedHashMap`
  of key to value) instead of a list of key/value entry structs.
  Consequently `getObject` on a MAP column returns a `java.util.Map`, and
  **`getArray()` on a MAP column now throws** instead of returning the
  entry-struct list — MAP is no longer surfaced as a LIST.

### Fixed

- `Statement.execute(...)` no longer wedges tools that drain results in a
  loop (DataGrip, DBeaver) after an INSERT/UPDATE/DELETE. `QuackStatement`
  now overrides `getMoreResults()` (and `getMoreResults(int)`) to advance
  past the current result — closing any open `ResultSet` and resetting the
  update count to `-1`. Per the JDBC contract, end-of-results is signalled
  by `getMoreResults() == false && getUpdateCount() == -1`; previously the
  update count kept reporting the affected-row count, so the tool's drain
  loop never terminated and the statement appeared to "never complete"
  even though the row had already been committed server-side. A failing
  statement (e.g. a duplicate primary key) was unaffected because it throws
  immediately. Covered by new drain-loop integration tests, including the
  reported PRIMARY KEY duplicate-then-new-insert sequence.

## [0.2.0-alpha.4] — 2026-06-12

Contributed in part by Jose Davila-Ciullo (@jdctinuiti) — thanks!

### Added

- Token provider properties for safer shared JDBC configurations:
  `tokenEnv` and `tokenFile`. Both are accepted via connection
  `Properties` only — they are rejected on the JDBC URL so that a pasted
  or shared URL cannot read a local secret and send it to an
  attacker-chosen host.

### Fixed

- Manual-commit transactions now work with DataGrip, DBeaver, and other
  tools (#4). With `autoCommit` off, the driver lazily issues
  `BEGIN TRANSACTION` before the first statement, so a later
  `Connection.commit()` no longer fails with
  "cannot commit - no transaction is active". `commit()`/`rollback()`
  with no pending transaction are harmless no-ops, and re-enabling
  auto-commit mid-transaction commits it per the JDBC spec.
- HTTPS transport now keeps the original hostname in request URIs instead
  of replacing it with a resolved IP address. This preserves TLS SNI and
  certificate hostname verification for gateways and load balancers that
  route by hostname. Plain HTTP endpoints still expand to resolved address
  candidates for the existing localhost IPv4/IPv6 fallback behavior.

## [0.2.0-alpha.3] — 2026-05-24

Supersedes the never-published `v0.2.0-alpha.2` tag (Maven Central
rejected the re-publish after the original tag's deploy was cancelled
mid-flight). Content is identical to what alpha.2 would have shipped,
plus contributor credit in the `[0.2.0-alpha.1]` notes below.

### Changed
- Integration tests now `INSTALL quack;` from the **core** repository,
  matching DuckDB v1.5.3 ("Variegata") where Quack ships as a signed
  core extension. The `-unsigned` flag and `core_nightly` repository are
  no longer required. The full 83-test suite passes against the
  `quack` extension bundled with DuckDB CLI v1.5.3 — no driver code
  changes needed, the wire format is unchanged.
- README and CLAUDE.md updated to reflect the v1.5.3 install path; the
  pre-1.5.3 `INSTALL quack FROM core_nightly` recipe is kept as a note
  for users on older builds.

## [0.2.0-alpha.1] — 2026-05-24

Thanks to **[@mwieczorkiewicz](https://github.com/mwieczorkiewicz)**
(Mikołaj Wieczorkiewicz) for contributing the pluggable transport SPI
and configurable HTTP timeouts in [#1](https://github.com/gizmodata/quack-jdbc/pull/1)
— their first contribution to the project.

### Added — Pluggable transport SPI (#1)

- **`QuackTransport` interface** and **`QuackTransportFactory`**
  (functional, takes a parsed `QuackUri`) let applications supply their
  own transport implementation — a custom `HttpClient` with proxies /
  mTLS / interceptors, or an in-process fake for testing.
- **`QuackDriver.connect(url, properties, transportFactory)`** overload
  threads the factory through `QuackConnection` and `QuackSession`. The
  no-factory `connect(url, properties)` path is unchanged and still uses
  the built-in HTTP transport.
- `QuackHttpTransport` now implements `QuackTransport`; the existing
  `QuackSession(QuackUri, QuackHttpTransport)` constructor is retained
  for binary compatibility.

### Added — Configurable HTTP timeouts

- **`connectTimeout`** and **`requestTimeout`** JDBC URL / `Properties`
  options, accepted as either a plain integer (seconds) or an ISO-8601
  duration like `PT30S`. Defaults remain 10s connect / 60s request.
  Surfaced via `Driver.getPropertyInfo` so DBeaver / DataGrip show them
  in their connection dialogs.
- Invalid / non-positive values raise a `QuackException` at parse time.

### Tests
- New `QuackDriverCustomTransportTest` exercising factory injection,
  null-factory error handling, `getPropertyInfo` shape, and binary
  compatibility of the legacy `QuackSession` constructor.
- New `QuackUriTest` cases for timeout parsing (URL, properties, invalid
  values) and a `QuackHttpTransportTest` case for `from(QuackUri)`.

Total suite: 83 tests, all green.

## [0.1.0-alpha.1] — 2026-05-13

Published to Maven Central as
`com.gizmodata:quack-jdbc:0.1.0-alpha.1` and as a GitHub Release with
both versioned and un-versioned jars at
<https://github.com/gizmodata/quack-jdbc/releases/tag/v0.1.0-alpha.1>.



First public alpha release. The Quack protocol itself is in beta until
DuckDB v2.0 ships in September 2026 — this driver is pinned to
[`duckdb/duckdb-quack@daae4826`](https://github.com/duckdb/duckdb-quack/commit/daae4826f57986fbb6cc2116316f89c673814b23)
(2026-05-10). Don't use this in production; do use it to evaluate
DBeaver / DataGrip / Spark / direct JDBC against a remote DuckDB.

All [Unreleased] entries below shipped in this release.

### Added — Release distribution

- **GitHub Release jars** are now attached to every tagged release by
  CI, in addition to the versioned artifact going to Maven Central.
  Each release gets:
  - `quack-jdbc-<version>.jar` — versioned filename
  - `quack-jdbc.jar` — un-versioned filename (always the latest), so
    DBeaver / DataGrip / curl users can fetch a stable URL:
    `https://github.com/gizmodata/quack-jdbc/releases/latest/download/quack-jdbc.jar`
  - Matching `*-sources.jar` and `*-javadoc.jar` in both forms
  - `SHA256SUMS` for every uploaded asset
- **README badges** added at the top: Maven Central version, latest
  GitHub release, direct download link to the latest un-versioned jar,
  link to the GitHub repo, MIT license.

### Added — APPEND_REQUEST encoder + `appendChunk` bulk-load API

- **`VectorCodec.encodeDataChunkWrapper` / `encodeDataChunk` /
  `encodeVector` / `encodeFlatVectorBody`** are now implemented for
  the common scalar physical types: BOOLEAN, all integer family
  (signed and unsigned, including HUGEINT / UHUGEINT), FLOAT, DOUBLE,
  DECIMAL (width 1-38), VARCHAR / CHAR / BLOB / BIT / GEOMETRY,
  DATE, TIME / TIME_NS, TIMESTAMP variants
  (sec/ms/us/ns/TZ), INTERVAL, UUID. STRUCT / LIST / MAP / ARRAY
  encoding still throws — opens an issue if you need them.
- **`QuackSession.appendChunk(schema, table, chunk)`** sends an
  `APPEND_REQUEST` to the server with a fully-encoded DataChunk.
  This is the bulk-load fast-path: it sends column-oriented binary
  data directly, bypassing per-row INSERT parsing. Typical workloads
  see an order-of-magnitude speed-up over
  `PreparedStatement.executeBatch()`.
- **5 round-trip unit tests** in `VectorCodecRoundTripTest` exercise
  encode → decode against an in-memory buffer (no server needed) for
  primitive vectors, nullable columns, and a mixed scalar payload.
- **5 integration tests** in `AppendIntegrationTest` exercise the
  full APPEND_REQUEST flow against a live DuckDB+Quack server —
  CREATE TABLE → build chunk in code → `appendChunk(...)` → SELECT
  to verify — covering INTEGER+VARCHAR, nullable BIGINT, all-scalar
  mix (BOOLEAN/INT/BIGINT/DOUBLE/DECIMAL/DATE/TIMESTAMP/VARCHAR),
  5000-row bulk, and BLOB round-trip.

Total suite: 75 tests, all green.

### Changed — Bitset validity + typed CONSTANT/DICTIONARY/SEQUENCE paths

- **Bitset-packed validity.** `DecodedVector`'s {@code boolean[] validity}
  is replaced with a {@code long[]} bitmap that holds one bit per row.
  Wire format hasn't changed (still a byte-aligned bitmap), but the
  driver-side memory footprint is now 8× smaller for the validity mask
  (1 bit/row instead of 1 byte/row from a {@code boolean[]}). The
  validity reader produces {@code long[]} directly via a packed
  little-endian read.
- **`Validity` helper** with bit-test, all-valid initializer, byte
  round-trip, and set-valid/set-null helpers — covered by 7 new unit
  tests pinning bit ordering and round-trips.
- **CONSTANT vectors** now broadcast a primitive value into the right
  typed primitive vector (e.g., `SELECT 42::INTEGER FROM range(1000)` →
  `IntVec(int[])`) instead of falling back to `ObjectVec`.
- **DICTIONARY vectors** preserve the dictionary's storage type when
  projecting through the selection vector — typed in → typed out.
- **SEQUENCE vectors** for INTEGER and BIGINT logical types
  materialize directly into `IntVec` / `LongVec` (the common
  `SELECT i FROM range(...)` case).
- 2 new integration tests in `StreamingIntegrationTest` pin the typed
  SEQUENCE and CONSTANT paths (total: 65 tests).

### Changed — Streaming cursor + typed primitive vectors (memory rewrite)

- **Streaming cursor.** `QuackSession.prepare(sql)` is replaced by
  `QuackSession.cursor(sql)`, which returns a `QuackSession.Cursor`
  that holds at most one server batch in memory at a time. The
  initial PREPARE_RESPONSE is parsed eagerly; subsequent
  FETCH_REQUESTs fire only when the local buffer drains. Peak driver
  memory for a million-row SELECT now grows with the server batch
  size (default ~12 chunks ≈ a few hundred KB), not with the total
  result-set row count.
- **Typed primitive vectors.** `DecodedVector` becomes a sealed
  interface with primitive-array records (`BoolVec`, `ByteVec`,
  `ShortVec`, `IntVec`, `LongVec`, `FloatVec`, `DoubleVec`) plus an
  `ObjectVec` fallback. Fixed-width primitive logical types
  (BOOLEAN, TINYINT..BIGINT and unsigned siblings, FLOAT, DOUBLE)
  now decode directly into `int[]`/`long[]`/`double[]`/etc. instead
  of `List<Object>` of boxed wrappers — roughly 4-8× smaller in
  memory for those columns, matching the bytes-on-the-wire footprint.
  Logical types whose materialized Java form is not a primitive
  (DECIMAL, DATE, TIME / TIME_NS / TIME_TZ, TIMESTAMP variants, UUID,
  INTERVAL, HUGEINT/UHUGEINT, ENUM, VARCHAR, BLOB, STRUCT, LIST,
  ARRAY, MAP) stay in `ObjectVec`.
- `ResultSet` getters route through the new typed accessors
  (`DecodedVector.getInt`, `getLong`, `getDouble`, etc.) so reading
  a primitive column no longer triggers an `Integer.valueOf`
  per row.
- `QuackConnection.session()` is now `public` so advanced callers
  can open a `Cursor` directly and drain chunks without going through
  the JDBC `ResultSet` surface.
- 4 new integration tests in `StreamingIntegrationTest` pin the
  lazy-fetch behavior and the typed-vector layout (total: 56 tests
  passing).

### Added — JDBC coverage parity with DuckDB's own driver

Closed the eight method-coverage gaps surfaced by a side-by-side audit
against `org.duckdb.DuckDB*` (full audit at `/tmp/jdbc-coverage-audit.md`
during development). All eight are required by at least one of
DBeaver / IntelliJ DataGrip / dbt / Spark JDBC / HikariCP.

- **`PreparedStatement.getMetaData()`** — returns
  {@link java.sql.ResultSetMetaData} for the prepared query by running
  `SELECT * FROM (<sql>) LIMIT 0` with NULL-filled placeholders.
  Returns {@code null} for non-SELECT statements per JDBC contract.
  Required by `spark.read.jdbc(...)` for schema inference.
- **`PreparedStatement.getParameterMetaData()`** — returns a
  `QuackParameterMetaData` reporting the `?`-marker count (counted
  outside single-quoted strings and double-quoted identifiers). Used
  by Hibernate / Spring JDBC / DataGrip parameter inspectors.
- **`Statement.addBatch(String)` / `clearBatch()` / `executeBatch()`**
  and **`PreparedStatement.addBatch()` / `executeBatch()`** — executed
  as a sequential loop (no native batch protocol exists in Quack today).
  Throws `BatchUpdateException` on individual failures with the partial
  counts array. Required by `dbt seed` and Spark `df.write.jdbc(...)`.
- **`ResultSet.getArray(...)`** — wraps the decoded {@code List<Object>}
  in a {@code QuackArray} (`java.sql.Array`) carrying the element's
  logical type for `getBaseType` / `getBaseTypeName`. Required by
  DBeaver's value editor for LIST / ARRAY columns.
- **`ResultSet.getBlob(...)`** — wraps the decoded {@code byte[]} in a
  {@code QuackBlob} (`java.sql.Blob`). Required by DBeaver's BLOB value
  editor.
- **`Connection.createArrayOf` / `createStruct`** — return opaque
  `QuackArray` / `QuackStruct` wrappers usable in `setObject(_, Array)`
  / `setObject(_, Struct)`. Used by dbt IN-list macros and adapter
  frameworks.
- **`Connection.setCatalog` / `setSchema`** — now emit
  `USE "catalog"."schema"` instead of silently storing a field. DBeaver
  catalog-navigator switching actually changes context server-side.
- **`Connection.isValid(int)`** — now actually runs `SELECT 1` to detect
  dead server-side connections instead of only checking the local
  `closed` flag. Required by HikariCP / pgbouncer-style pool
  health-check semantics.
- **`Connection.setTypeMap(Map)`** — silently accepts `null` and empty
  maps (the call HikariCP makes during eviction), throws only for
  non-empty mappings.
- **`Statement.cancel()`** — degraded from `SQLFeatureNotSupportedException`
  to a best-effort no-op so DBeaver / DataGrip query-timeout buttons
  don't crash the UI. Real protocol cancel will follow when Quack
  surfaces it.

Plus 15 new integration tests exercising every fix end-to-end against
a live DuckDB+Quack server (52 tests total, all green).

### Fixed
- `QuackHttpTransport` now iterates every address returned by
  `InetAddress.getAllByName(host)` instead of relying on JDK
  `HttpClient`'s first-address behavior. Hosts like `localhost` that
  resolve to both `127.0.0.1` and `::1` now succeed against a server
  bound to either family — previously a `ConnectException` on the first
  address (IPv4 by default on macOS) aborted the whole request even
  though an IPv6 listener was reachable.
- Error messages no longer say `Quack HTTP request failed: null` when
  the cause has no message; the exception class name is used as a
  fallback. The exhausted-addresses error names every address that was
  tried, including the underlying failure detail.

### Added
- First cut of the JDBC driver for DuckDB's Quack remote protocol.
- `BinaryReader` / `BinaryWriter` for DuckDB's BinarySerializer wire format
  (little-endian uint16 field ids, ULEB128/SLEB128, fixed-width primitives,
  length-prefixed strings/blobs/lists, nested objects terminated by
  `FIELD_END = 0xFFFF`).
- Logical type model and codec covering BOOLEAN, integer family
  (TINYINT…HUGEINT including unsigned), FLOAT/DOUBLE, DECIMAL, VARCHAR/CHAR,
  BLOB/BIT/GEOMETRY, DATE, TIME / TIME_NS / TIME_TZ, TIMESTAMP variants
  (SEC / MS / default µs / NS / TZ), INTERVAL, UUID, ENUM, STRUCT, LIST,
  MAP, ARRAY, plus all `ExtraTypeInfo` variants.
- `DataChunk` decoder supporting **FLAT**, **CONSTANT**, **DICTIONARY**,
  and **SEQUENCE** vector encodings with validity bitmaps. FSST is not
  yet supported.
- Quack protocol message records and `MessageCodec` for `CONNECTION_*`,
  `PREPARE_*`, `FETCH_*`, `APPEND_REQUEST`, `SUCCESS_RESPONSE`,
  `DISCONNECT_MESSAGE`, and `ERROR_RESPONSE`.
- `QuackHttpTransport` over `java.net.http.HttpClient` (JDK 17+).
- JDBC URL parser accepting `jdbc:quack://host[:port][/database][?token=…&tls=…]`.
- `QuackDriver` (auto-registered via `META-INF/services`), `QuackConnection`,
  `QuackStatement`, `QuackPreparedStatement` (client-side `?` interpolation),
  `QuackResultSet`, `QuackResultSetMetaData`.
- `QuackDatabaseMetaData` modeled directly on DuckDB's own JDBC driver so
  DBeaver and other tools that introspect via `getTables` / `getColumns` /
  `getPrimaryKeys` / `getImportedKeys` / `getExportedKeys` / `getIndexInfo` /
  `getTypeInfo` / `getFunctions` see the same shape they would from a
  native DuckDB connection.
- JUnit 5 integration suite that spawns a real `duckdb -unsigned` process,
  installs the Quack extension from `core_nightly`, calls `quack_serve` on
  a random local port, and exercises the driver end-to-end (connect,
  CRUD, multi-chunk fetch, scalar type round-trips, DatabaseMetaData,
  bad-token auth, concurrent connections).
- Unit test coverage for the BinarySerializer round-trip, URI parsing,
  and message encode/decode.

### Pinned versions
- DuckDB CLI: 1.5.2+ (tested with 1.5.2)
- Quack extension: `duckdb/duckdb-quack@daae4826f57986fbb6cc2116316f89c673814b23`
  (2026-05-10, current `main` — no release tags exist yet at the time of
  writing; will be retargeted as the protocol stabilizes for DuckDB 2.0
  in September 2026)

### Known limitations
- The Quack protocol is beta; breaking changes are expected before DuckDB 2.0.
- `PreparedStatement` parameter binding uses client-side literal
  substitution — the protocol's `PREPARE_REQUEST` does not (yet) carry
  bind parameters.
- `APPEND_REQUEST` (vector encoding) is decoder-complete but not yet
  encoder-complete; the driver does not yet expose the append fast-path.
- FSST-compressed vectors and the TIME WITH TIME ZONE wall-clock decode
  are not yet supported.
- Nested types (STRUCT/LIST/MAP/ARRAY) decode to plain Java collections;
  full `java.sql.Array` / `java.sql.Struct` wrapping is on the roadmap.

## [0.1.0] — _planned_

First public release will be tagged once integration tests have been
exercised against a production-deployed Quack server.
