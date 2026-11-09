package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.transport.QuackUri;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC DatabaseMetaData modeled on DuckDB's own Java driver so that
 * DBeaver — which is built against DuckDB's metadata SQL — sees the same
 * shape from Quack. SQL queries are constructed with literal substitution
 * (matching the rest of this driver's client-side parameter handling) and
 * mirror {@code org.duckdb.DuckDBDatabaseMetaData} closely.
 */
public final class QuackDatabaseMetaData implements DatabaseMetaData {

    private final QuackConnection connection;

    public QuackDatabaseMetaData(QuackConnection connection) {
        this.connection = connection;
    }

    private ResultSet runQuery(String sql) throws SQLException {
        Statement stmt = connection.createStatement();
        return stmt.executeQuery(sql);
    }

    // ---- helpers mirroring DuckDB's appendEqualsQual / appendLikeQual / nullPatternToWildcard ----

    /** Appends {@code AND col = '...' } or {@code AND col IS NULL} or nothing (null = no filter). */
    private static void appendEqualsQual(StringBuilder sb, String col, String value) {
        if (value == null) return;
        sb.append(" AND ").append(col);
        if (value.isEmpty()) sb.append(" IS NULL");
        else sb.append(" = ").append(SqlLiteral.render(value));
        sb.append('\n');
    }

    private static void appendLikeQual(StringBuilder sb, String col, String pattern) {
        if (pattern == null) return;
        sb.append(" AND ").append(col);
        if (pattern.isEmpty()) sb.append(" IS NULL");
        else sb.append(" LIKE ").append(SqlLiteral.render(pattern)).append(" ESCAPE '\\'");
        sb.append('\n');
    }

    private static String nullPatternToWildcard(String pattern) {
        return pattern == null ? "%" : pattern;
    }

    // ---- catalog/schema/table introspection ----

    @Override
    public ResultSet getCatalogs() throws SQLException {
        return runQuery("SELECT DISTINCT catalog_name AS \"TABLE_CAT\" " +
                "FROM information_schema.schemata ORDER BY \"TABLE_CAT\"");
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return runQuery("SELECT schema_name AS \"TABLE_SCHEM\", catalog_name AS \"TABLE_CATALOG\" " +
                "FROM information_schema.schemata ORDER BY \"TABLE_CATALOG\", \"TABLE_SCHEM\"");
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT schema_name AS \"TABLE_SCHEM\", catalog_name AS \"TABLE_CATALOG\" ")
          .append("FROM information_schema.schemata WHERE TRUE\n");
        appendEqualsQual(sb, "catalog_name", catalog);
        appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append("ORDER BY \"TABLE_CATALOG\", \"TABLE_SCHEM\"");
        return runQuery(sb.toString());
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return runQuery("SELECT 'TABLE' AS \"TABLE_TYPE\" UNION ALL SELECT 'LOCAL TEMPORARY' " +
                "UNION ALL SELECT 'VIEW' UNION ALL SELECT 'SYSTEM VIEW' ORDER BY \"TABLE_TYPE\"");
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
                               String[] types) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT table_catalog AS \"TABLE_CAT\", table_schema AS \"TABLE_SCHEM\", ")
          .append("table_name AS \"TABLE_NAME\", table_type AS \"TABLE_TYPE\", ")
          .append("TABLE_COMMENT AS \"REMARKS\", NULL::VARCHAR AS \"TYPE_CAT\", ")
          .append("NULL::VARCHAR AS \"TYPE_SCHEM\", NULL::VARCHAR AS \"TYPE_NAME\", ")
          .append("NULL::VARCHAR AS \"SELF_REFERENCING_COL_NAME\", NULL::VARCHAR AS \"REF_GENERATION\" ")
          .append("FROM (")
          .append("SELECT database_name AS table_catalog, schema_name AS table_schema, table_name, ")
          .append("CASE WHEN (\"temporary\") THEN ('LOCAL TEMPORARY') WHEN (\"internal\") THEN 'SYSTEM TABLE' ")
          .append("ELSE 'TABLE' END AS table_type, comment AS TABLE_COMMENT FROM duckdb_tables() ")
          .append("UNION ALL SELECT database_name, schema_name, view_name, ")
          .append("CASE WHEN (\"internal\") THEN 'SYSTEM VIEW' ELSE 'VIEW' END, comment FROM duckdb_views()")
          .append(") x WHERE table_name LIKE ").append(SqlLiteral.render(nullPatternToWildcard(tableNamePattern)))
          .append(" ESCAPE '\\'\n");
        appendEqualsQual(sb, "table_catalog", catalog);
        appendLikeQual(sb, "table_schema", schemaPattern);
        if (types != null && types.length > 0) {
            sb.append(" AND table_type IN (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(SqlLiteral.render(types[i]));
            }
            sb.append(")\n");
        }
        sb.append("ORDER BY table_type, table_catalog, table_schema, table_name");
        return runQuery(sb.toString());
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
                                String columnNamePattern) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT database_name AS \"TABLE_CAT\", schema_name AS \"TABLE_SCHEM\", ")
          .append("table_name AS \"TABLE_NAME\", column_name AS \"COLUMN_NAME\", ")
          .append("CASE upper(regexp_replace(c.data_type, '\\(.*\\)', '')) ")
          .append("WHEN 'BOOLEAN' THEN 16 WHEN 'TINYINT' THEN -6 WHEN 'SMALLINT' THEN 5 ")
          .append("WHEN 'INTEGER' THEN 4 WHEN 'BIGINT' THEN -5 WHEN 'UTINYINT' THEN 5 ")
          .append("WHEN 'USMALLINT' THEN 4 WHEN 'UINTEGER' THEN -5 WHEN 'FLOAT' THEN 6 ")
          .append("WHEN 'DOUBLE' THEN 8 WHEN 'DECIMAL' THEN 3 WHEN 'VARCHAR' THEN 12 ")
          .append("WHEN 'BLOB' THEN 2004 WHEN 'TIME' THEN 92 WHEN 'TIME NS' THEN 92 ")
          .append("WHEN 'DATE' THEN 91 WHEN 'TIMESTAMP' THEN 93 WHEN 'TIMESTAMP MS' THEN 93 ")
          .append("WHEN 'TIMESTAMP NS' THEN 93 WHEN 'TIMESTAMP S' THEN 93 ")
          .append("WHEN 'TIMESTAMP WITH TIME ZONE' THEN 2014 WHEN 'BIT' THEN -7 ")
          .append("WHEN 'TIME WITH TIME ZONE' THEN 2013 WHEN 'LIST' THEN 2003 ")
          .append("WHEN 'STRUCT' THEN 2002 WHEN 'ARRAY' THEN 2003 WHEN 'GEOMETRY' THEN 2004 ")
          .append("ELSE 1111 END AS \"DATA_TYPE\", ")
          .append("c.data_type AS \"TYPE_NAME\", numeric_precision AS \"COLUMN_SIZE\", ")
          .append("NULL AS \"BUFFER_LENGTH\", numeric_scale AS \"DECIMAL_DIGITS\", ")
          .append("10 AS \"NUM_PREC_RADIX\", CASE WHEN is_nullable=TRUE THEN 1 ELSE 0 END AS \"NULLABLE\", ")
          .append("comment AS \"REMARKS\", column_default AS \"COLUMN_DEF\", ")
          .append("NULL AS \"SQL_DATA_TYPE\", NULL AS \"SQL_DATETIME_SUB\", NULL AS \"CHAR_OCTET_LENGTH\", ")
          .append("column_index AS \"ORDINAL_POSITION\", ")
          .append("CASE WHEN is_nullable=TRUE THEN 'YES' ELSE 'NO' END AS \"IS_NULLABLE\", ")
          .append("NULL AS \"SCOPE_CATALOG\", NULL AS \"SCOPE_SCHEMA\", NULL AS \"SCOPE_TABLE\", ")
          .append("NULL AS \"SOURCE_DATA_TYPE\", '' AS \"IS_AUTOINCREMENT\", '' AS \"IS_GENERATEDCOLUMN\" ")
          .append("FROM duckdb_columns() c WHERE TRUE\n");
        appendEqualsQual(sb, "database_name", catalog);
        appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append(" AND table_name LIKE ").append(SqlLiteral.render(nullPatternToWildcard(tableNamePattern)))
          .append(" ESCAPE '\\'\n");
        sb.append(" AND column_name LIKE ").append(SqlLiteral.render(nullPatternToWildcard(columnNamePattern)))
          .append(" ESCAPE '\\'\n");
        sb.append("ORDER BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\", \"ORDINAL_POSITION\"");
        return runQuery(sb.toString());
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("WITH constraint_columns AS (")
          .append("SELECT database_name AS \"TABLE_CAT\", schema_name AS \"TABLE_SCHEM\", ")
          .append("table_name AS \"TABLE_NAME\", unnest(constraint_column_names) AS \"COLUMN_NAME\", ")
          .append("NULL::VARCHAR AS \"PK_NAME\" FROM duckdb_constraints ")
          .append("WHERE constraint_type='PRIMARY KEY'\n");
        appendEqualsQual(sb, "database_name", catalog);
        appendEqualsQual(sb, "schema_name", schema);
        sb.append(" AND table_name = ").append(SqlLiteral.render(table)).append("\n");
        sb.append(") SELECT \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\", \"COLUMN_NAME\", ")
          .append("CAST(ROW_NUMBER() OVER (PARTITION BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\") AS INT) AS \"KEY_SEQ\", ")
          .append("\"PK_NAME\" FROM constraint_columns ")
          .append("ORDER BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\", \"KEY_SEQ\"");
        return runQuery(sb.toString());
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table,
                                  boolean unique, boolean approximate) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT database_name AS \"TABLE_CAT\", schema_name AS \"TABLE_SCHEM\", ")
          .append("table_name AS \"TABLE_NAME\", index_name AS \"INDEX_NAME\", ")
          .append("CASE WHEN is_unique THEN 0 ELSE 1 END AS \"NON_UNIQUE\", ")
          .append("NULL AS \"TYPE\", NULL AS \"ORDINAL_POSITION\", NULL AS \"COLUMN_NAME\", ")
          .append("NULL AS \"ASC_OR_DESC\", NULL AS \"CARDINALITY\", NULL AS \"PAGES\", ")
          .append("NULL AS \"FILTER_CONDITION\" FROM duckdb_indexes() WHERE TRUE\n");
        appendEqualsQual(sb, "database_name", catalog);
        appendEqualsQual(sb, "schema_name", schema);
        sb.append(" AND table_name = ").append(SqlLiteral.render(table)).append("\n");
        if (unique) sb.append(" AND is_unique = TRUE\n");
        sb.append("ORDER BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\", \"NON_UNIQUE\", \"INDEX_NAME\"");
        return runQuery(sb.toString());
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return getCrossReference(null, null, null, catalog, schema, table);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return getCrossReference(catalog, schema, table, null, null, null);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable)
            throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT pk_tc.table_catalog AS \"PKTABLE_CAT\", pk_tc.table_schema AS \"PKTABLE_SCHEM\", ")
          .append("pk_tc.table_name AS \"PKTABLE_NAME\", pk_kcu.column_name AS \"PKCOLUMN_NAME\", ")
          .append("fk_tc.table_catalog AS \"FKTABLE_CAT\", fk_tc.table_schema AS \"FKTABLE_SCHEM\", ")
          .append("fk_tc.table_name AS \"FKTABLE_NAME\", fk_kcu.column_name AS \"FKCOLUMN_NAME\", ")
          .append("fk_kcu.ordinal_position AS \"KEY_SEQ\", ")
          .append("CASE rc.update_rule WHEN 'CASCADE' THEN 0 WHEN 'RESTRICT' THEN 1 ")
          .append("WHEN 'SET NULL' THEN 2 WHEN 'SET DEFAULT' THEN 4 ELSE 3 END AS \"UPDATE_RULE\", ")
          .append("CASE rc.delete_rule WHEN 'CASCADE' THEN 0 WHEN 'RESTRICT' THEN 1 ")
          .append("WHEN 'SET NULL' THEN 2 WHEN 'SET DEFAULT' THEN 4 ELSE 3 END AS \"DELETE_RULE\", ")
          .append("rc.constraint_name AS \"FK_NAME\", rc.unique_constraint_name AS \"PK_NAME\", ")
          .append("CASE WHEN fk_tc.is_deferrable='YES' AND fk_tc.initially_deferred='YES' THEN 5 ")
          .append("WHEN fk_tc.is_deferrable='YES' AND fk_tc.initially_deferred='NO' THEN 6 ELSE 7 END AS \"DEFERRABILITY\" ")
          .append("FROM information_schema.referential_constraints rc ")
          .append("JOIN information_schema.table_constraints fk_tc ")
          .append("ON fk_tc.constraint_catalog=rc.constraint_catalog AND fk_tc.constraint_schema=rc.constraint_schema ")
          .append("AND fk_tc.constraint_name=rc.constraint_name ")
          .append("JOIN information_schema.key_column_usage fk_kcu ")
          .append("ON fk_kcu.constraint_catalog=rc.constraint_catalog AND fk_kcu.constraint_schema=rc.constraint_schema ")
          .append("AND fk_kcu.constraint_name=rc.constraint_name ")
          .append("JOIN information_schema.table_constraints pk_tc ")
          .append("ON pk_tc.constraint_catalog=rc.unique_constraint_catalog AND pk_tc.constraint_schema=rc.unique_constraint_schema ")
          .append("AND pk_tc.constraint_name=rc.unique_constraint_name ")
          .append("JOIN information_schema.key_column_usage pk_kcu ")
          .append("ON pk_kcu.constraint_catalog=pk_tc.constraint_catalog AND pk_kcu.constraint_schema=pk_tc.constraint_schema ")
          .append("AND pk_kcu.constraint_name=pk_tc.constraint_name AND pk_kcu.ordinal_position=fk_kcu.ordinal_position");
        List<String> wheres = new ArrayList<>();
        if (parentCatalog != null) wheres.add(" pk_tc.table_catalog = " + SqlLiteral.render(parentCatalog));
        if (parentSchema != null) wheres.add(" pk_tc.table_schema = " + SqlLiteral.render(parentSchema));
        if (parentTable != null) wheres.add(" pk_tc.table_name = " + SqlLiteral.render(parentTable));
        if (foreignCatalog != null) wheres.add(" fk_tc.table_catalog = " + SqlLiteral.render(foreignCatalog));
        if (foreignSchema != null) wheres.add(" fk_tc.table_schema = " + SqlLiteral.render(foreignSchema));
        if (foreignTable != null) wheres.add(" fk_tc.table_name = " + SqlLiteral.render(foreignTable));
        if (!wheres.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", wheres));
        }
        if (foreignTable != null) {
            sb.append(" ORDER BY \"PKTABLE_CAT\", \"PKTABLE_SCHEM\", \"PKTABLE_NAME\", \"KEY_SEQ\", \"FKTABLE_CAT\", \"FKTABLE_SCHEM\", \"FKTABLE_NAME\"");
        } else {
            sb.append(" ORDER BY \"FKTABLE_CAT\", \"FKTABLE_SCHEM\", \"FKTABLE_NAME\", \"KEY_SEQ\", \"PKTABLE_CAT\", \"PKTABLE_SCHEM\", \"PKTABLE_NAME\"");
        }
        return runQuery(sb.toString());
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        String[] rows = {
                row("BOOLEAN", -7, 2), row("TINYINT", -6, 2), row("SMALLINT", 5, 2),
                row("INTEGER", 4, 2), row("BIGINT", -5, 2), row("FLOAT", 6, 2),
                row("REAL", 7, 2), row("DOUBLE", 8, 2), row("DECIMAL", 2, 2),
                row("DECIMAL", 3, 2), row("VARCHAR", 1, 3), row("VARCHAR", 12, 3),
                row("VARCHAR", -1, 3), row("DATE", 91, 2), row("TIME", 92, 2),
                row("TIMESTAMP", 93, 2), row("BLOB", -2, 3), row("BLOB", -3, 3),
                row("BLOB", -4, 3), row("NULL", -4, 2)
        };
        return runQuery(String.join(" UNION ALL ", rows));
    }

    private static String row(String name, int sqlType, int searchable) {
        return "SELECT '" + name + "'::VARCHAR AS \"TYPE_NAME\", " + sqlType + "::INTEGER AS \"DATA_TYPE\", " +
                "0::INTEGER AS \"PRECISION\", NULL::VARCHAR AS \"LITERAL_PREFIX\", NULL::VARCHAR AS \"LITERAL_SUFFIX\", " +
                "NULL::VARCHAR AS \"CREATE_PARAMS\", 2::SMALLINT AS \"NULLABLE\", TRUE::BOOLEAN AS \"CASE_SENSITIVE\", " +
                searchable + "::SMALLINT AS \"SEARCHABLE\", FALSE::BOOLEAN AS \"UNSIGNED_ATTRIBUTE\", " +
                "FALSE::BOOLEAN AS \"FIXED_PREC_SCALE\", FALSE::BOOLEAN AS \"AUTO_INCREMENT\", " +
                "NULL::VARCHAR AS \"LOCAL_TYPE_NAME\", 0::SMALLINT AS \"MINIMUM_SCALE\", 0::SMALLINT AS \"MAXIMUM_SCALE\", " +
                "0::INTEGER AS \"SQL_DATA_TYPE\", 0::INTEGER AS \"SQL_DATETIME_SUB\", 10::INTEGER AS \"NUM_PREC_RADIX\"";
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT NULL AS \"FUNCTION_CAT\", function_name AS \"FUNCTION_NAME\", ")
          .append("schema_name AS \"FUNCTION_SCHEM\", description AS \"REMARKS\", ")
          .append("CASE function_type WHEN 'table' THEN 2 WHEN 'table_macro' THEN 2 ELSE 1 END AS \"FUNCTION_TYPE\" ")
          .append("FROM duckdb_functions() WHERE TRUE\n");
        appendEqualsQual(sb, "database_name", catalog);
        appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append(" AND function_name LIKE ").append(SqlLiteral.render(nullPatternToWildcard(functionNamePattern)))
          .append(" ESCAPE '\\'\n");
        sb.append("ORDER BY \"FUNCTION_CAT\", \"FUNCTION_SCHEM\", \"FUNCTION_NAME\"");
        return runQuery(sb.toString());
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
                                        String columnNamePattern) throws SQLException {
        return runQuery("SELECT NULL WHERE FALSE");
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        return runQuery("SELECT NULL WHERE FALSE");
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
                                         String columnNamePattern) throws SQLException {
        return runQuery("SELECT NULL WHERE FALSE");
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT database_name AS \"TYPE_CAT\", schema_name AS \"TYPE_SCHEM\", ")
          .append("type_name AS \"TYPE_NAME\", NULL::VARCHAR AS \"CLASS_NAME\", ")
          .append("CASE WHEN logical_type IN ('STRUCT','UNION') THEN 2002 ELSE 2001 END AS \"DATA_TYPE\", ")
          .append("comment AS \"REMARKS\", ")
          .append("CASE WHEN logical_type IN ('STRUCT','UNION') THEN NULL::SMALLINT ")
          .append("WHEN logical_type='ENUM' THEN 12::SMALLINT ELSE 1111::SMALLINT END AS \"BASE_TYPE\" ")
          .append("FROM duckdb_types() WHERE internal=FALSE\n");
        appendEqualsQual(sb, "database_name", catalog);
        appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append(" AND type_name LIKE ").append(SqlLiteral.render(nullPatternToWildcard(typeNamePattern)))
          .append(" ESCAPE '\\'\n");
        sb.append("ORDER BY \"DATA_TYPE\", \"TYPE_CAT\", \"TYPE_SCHEM\", \"TYPE_NAME\"");
        return runQuery(sb.toString());
    }

    // ---- static identity ----

    @Override public String getURL() { return QuackUri.URL_PREFIX + "//" + connection.uri().host() + ":" + connection.uri().port(); }
    @Override public String getUserName() { return ""; }
    @Override public String getDatabaseProductName() { return "DuckDB (via Quack)"; }
    /**
     * The server's DuckDB version from the Quack handshake (no query: a
     * query here would supersede a result the caller is still reading).
     */
    @Override public String getDatabaseProductVersion() {
        return connection.session().serverVersion().orElse("");
    }
    @Override public String getDriverName() { return QuackDriver.DRIVER_NAME; }
    @Override public String getDriverVersion() { return QuackDriver.VERSION; }
    @Override public int getDriverMajorVersion() { return QuackDriver.MAJOR_VERSION; }
    @Override public int getDriverMinorVersion() { return QuackDriver.MINOR_VERSION; }
    @Override public int getDatabaseMajorVersion() { return serverVersionPart(0, 2); }
    @Override public int getDatabaseMinorVersion() { return serverVersionPart(1, 0); }

    private int serverVersionPart(int index, int fallback) {
        String v = connection.session().serverVersion().orElse("");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(v);
        return m.find() ? Integer.parseInt(m.group(index + 1)) : fallback;
    }
    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 2; }
    @Override public String getIdentifierQuoteString() { return "\""; }
    @Override public String getSearchStringEscape() { return "\\"; }
    @Override public String getExtraNameCharacters() { return ""; }
    @Override public String getSchemaTerm() { return "schema"; }
    @Override public String getProcedureTerm() { return "procedure"; }
    @Override public String getCatalogTerm() { return "catalog"; }
    @Override public String getCatalogSeparator() { return "."; }

    @Override
    public String getSQLKeywords() throws SQLException {
        return commaList("SELECT keyword_name FROM duckdb_keywords()");
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        return commaList("SELECT DISTINCT function_name FROM duckdb_functions() WHERE " +
                "parameter_types[1]='DECIMAL' OR parameter_types[1]='DOUBLE' OR " +
                "parameter_types[1]='SMALLINT' OR parameter_types[1]='BIGINT'");
    }

    @Override
    public String getStringFunctions() throws SQLException {
        return commaList("SELECT DISTINCT function_name FROM duckdb_functions() WHERE parameter_types[1]='VARCHAR'");
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        return commaList("SELECT DISTINCT function_name FROM duckdb_functions() WHERE length(parameter_types)=0");
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        return commaList("SELECT DISTINCT function_name FROM duckdb_functions() WHERE parameter_types[1] LIKE 'TIME%'");
    }

    private String commaList(String sql) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(rs.getString(1));
            }
        }
        return sb.toString();
    }

    // ---- feature booleans (mirrors DuckDB) ----

    @Override public boolean allProceduresAreCallable() { return false; }
    @Override public boolean allTablesAreSelectable() { return true; }
    @Override public boolean isReadOnly() { return false; }
    @Override public boolean nullsAreSortedHigh() { return true; }
    @Override public boolean nullsAreSortedLow() { return false; }
    @Override public boolean nullsAreSortedAtStart() { return true; }
    @Override public boolean nullsAreSortedAtEnd() { return false; }
    @Override public boolean usesLocalFiles() { return true; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return true; }
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean storesLowerCaseIdentifiers() { return false; }
    @Override public boolean storesMixedCaseIdentifiers() { return true; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean supportsAlterTableWithAddColumn() { return true; }
    @Override public boolean supportsAlterTableWithDropColumn() { return true; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean nullPlusNonNullIsNull() { return true; }
    @Override public boolean supportsConvert() { return false; }
    @Override public boolean supportsConvert(int fromType, int toType) { return false; }
    @Override public boolean supportsTableCorrelationNames() { return true; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsExpressionsInOrderBy() { return true; }
    @Override public boolean supportsOrderByUnrelated() { return true; }
    @Override public boolean supportsGroupBy() { return true; }
    @Override public boolean supportsGroupByUnrelated() { return true; }
    @Override public boolean supportsGroupByBeyondSelect() { return true; }
    @Override public boolean supportsLikeEscapeClause() { return true; }
    @Override public boolean supportsMultipleResultSets() { return false; }
    @Override public boolean supportsMultipleTransactions() { return true; }
    @Override public boolean supportsNonNullableColumns() { return true; }
    @Override public boolean supportsMinimumSQLGrammar() { return true; }
    @Override public boolean supportsCoreSQLGrammar() { return true; }
    @Override public boolean supportsExtendedSQLGrammar() { return true; }
    @Override public boolean supportsANSI92EntryLevelSQL() { return true; }
    @Override public boolean supportsANSI92IntermediateSQL() { return true; }
    @Override public boolean supportsANSI92FullSQL() { return true; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return false; }
    @Override public boolean supportsOuterJoins() { return true; }
    @Override public boolean supportsFullOuterJoins() { return true; }
    @Override public boolean supportsLimitedOuterJoins() { return true; }
    @Override public boolean isCatalogAtStart() { return true; }
    @Override public boolean supportsSchemasInDataManipulation() { return true; }
    @Override public boolean supportsSchemasInProcedureCalls() { return true; }
    @Override public boolean supportsSchemasInTableDefinitions() { return true; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return true; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsCatalogsInDataManipulation() { return true; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return true; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return true; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsSubqueriesInComparisons() { return true; }
    @Override public boolean supportsSubqueriesInExists() { return true; }
    @Override public boolean supportsSubqueriesInIns() { return true; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return true; }
    @Override public boolean supportsCorrelatedSubqueries() { return true; }
    @Override public boolean supportsUnion() { return true; }
    @Override public boolean supportsUnionAll() { return true; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return false; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return false; }
    @Override public boolean supportsTransactions() { return true; }
    @Override public boolean supportsTransactionIsolationLevel(int level) { return level < Connection.TRANSACTION_SERIALIZABLE; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return true; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_REPEATABLE_READ; }
    @Override public boolean supportsBatchUpdates() { return true; }
    @Override public boolean supportsResultSetType(int type) { return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }
    @Override public boolean supportsSavepoints() { return false; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return true; }
    @Override public boolean supportsGetGeneratedKeys() { return false; }
    @Override public boolean supportsResultSetHoldability(int holdability) { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }

    // limits — all return 0 (no limit)
    @Override public int getMaxBinaryLiteralLength() { return 0; }
    @Override public int getMaxCharLiteralLength() { return 0; }
    @Override public int getMaxColumnNameLength() { return 0; }
    @Override public int getMaxColumnsInGroupBy() { return 0; }
    @Override public int getMaxColumnsInIndex() { return 0; }
    @Override public int getMaxColumnsInOrderBy() { return 0; }
    @Override public int getMaxColumnsInSelect() { return 0; }
    @Override public int getMaxColumnsInTable() { return 0; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 0; }
    @Override public int getMaxIndexLength() { return 0; }
    @Override public int getMaxSchemaNameLength() { return 0; }
    @Override public int getMaxProcedureNameLength() { return 0; }
    @Override public int getMaxCatalogNameLength() { return 0; }
    @Override public int getMaxRowSize() { return 0; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public int getMaxStatementLength() { return 0; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 0; }
    @Override public int getMaxTablesInSelect() { return 0; }
    @Override public int getMaxUserNameLength() { return 0; }
    @Override public long getMaxLogicalLobSize() { return 0L; }

    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean locatorsUpdateCopy() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return false; }

    // ---- unsupported (throw) ----

    private static SQLException notSupported(String what) {
        return new SQLFeatureNotSupportedException(what + " is not supported by quack-jdbc");
    }

    @Override public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException { throw notSupported("getBestRowIdentifier"); }
    @Override public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException { throw notSupported("getVersionColumns"); }
    @Override public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException { throw notSupported("getColumnPrivileges"); }
    @Override public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException { throw notSupported("getTablePrivileges"); }
    @Override public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException { throw notSupported("getSuperTypes"); }
    @Override public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException { throw notSupported("getSuperTables"); }
    @Override public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException { throw notSupported("getAttributes"); }
    @Override public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException { throw notSupported("getPseudoColumns"); }
    @Override public ResultSet getClientInfoProperties() throws SQLException { throw notSupported("getClientInfoProperties"); }
    @Override public int getResultSetHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getSQLStateType() { return DatabaseMetaData.sqlStateSQL; }
    @Override public RowIdLifetime getRowIdLifetime() { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public boolean ownUpdatesAreVisible(int type) { return false; }
    @Override public boolean ownDeletesAreVisible(int type) { return false; }
    @Override public boolean ownInsertsAreVisible(int type) { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) { return false; }
    @Override public boolean othersDeletesAreVisible(int type) { return false; }
    @Override public boolean othersInsertsAreVisible(int type) { return false; }
    @Override public boolean updatesAreDetected(int type) { return false; }
    @Override public boolean deletesAreDetected(int type) { return false; }
    @Override public boolean insertsAreDetected(int type) { return false; }

    @Override public Connection getConnection() { return connection; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
