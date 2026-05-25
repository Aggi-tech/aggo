package com.aggitech.aggo.migration

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.schema.Table

/**
 * Generates `CREATE TABLE` and `DROP TABLE` DDL from [Table] descriptors.
 *
 * ## Safety guarantees
 *
 * - **Identifiers**: every table/column name passes through
 *   [SqlDialect.quoteIdentifier], which validates against the strict
 *   [com.aggitech.aggo.dialect.IDENTIFIER_REGEX] allowlist before any
 *   interpolation — identifier injection is not possible.
 * - **Column types**: resolved from the closed mapping inside
 *   [MigrationDialect.columnSqlType]; no user-supplied strings are embedded.
 * - **CHECK expressions**: produced by [com.aggitech.aggo.schema.Checks] helpers
 *   that quote column names internally. Custom `check = { col -> … }` lambdas are
 *   developer code evaluated at schema construction time, not runtime user input.
 *
 * ## Generated columns
 *
 * Columns declared with `isGenerated = true` appear in the DDL with their SQL
 * type and nullability, but without a DEFAULT or GENERATED expression — the
 * framework has no way to know the generation strategy. Supply the expression
 * manually in your migration script:
 *
 * ```sql
 * "id" INTEGER NOT NULL   -- add: DEFAULT nextval('…') or GENERATED ALWAYS AS IDENTITY
 * "created_at" TIMESTAMPTZ NOT NULL  -- add: DEFAULT now()
 * ```
 *
 * ## Multi-database support
 *
 * Pass a different [MigrationDialect] to target other engines. Implement
 * [MigrationDialect.columnSqlType] and [SqlDialect.quoteIdentifier] for the
 * target database; the rest of the logic is dialect-agnostic.
 */

/**
 * Returns a `CREATE TABLE` DDL statement for this table.
 *
 * Columns appear in declaration order. CHECK constraints declared via
 * `Table.column(…, check = …)` are emitted as named inline `CONSTRAINT` clauses.
 * Primary key columns are collected in a trailing `PRIMARY KEY (…)` clause; if
 * no columns are marked `isPrimaryKey`, the clause is omitted.
 *
 * @param dialect the target SQL dialect, used for identifier quoting and type mapping.
 * @param ifNotExists when true, emits `CREATE TABLE IF NOT EXISTS` (Postgres 9.1+).
 */
fun Table<*>.createTableSql(dialect: MigrationDialect, ifNotExists: Boolean = false): String {
    val qualifier = if (ifNotExists) "IF NOT EXISTS " else ""
    val tableName = dialect.quoteIdentifier(name)

    val lines = mutableListOf<String>()

    for (col in columns) {
        val colName = dialect.quoteIdentifier(col.name)
        val sqlType = dialect.columnSqlType(col.codec)
        val nullability = if (col.isNullable) "" else " NOT NULL"
        lines += "    $colName $sqlType$nullability"
    }

    for (clause in checkConstraintClauses()) {
        lines += "    $clause"
    }

    if (primaryKeys.isNotEmpty()) {
        val pkCols = primaryKeys.joinToString(", ") { dialect.quoteIdentifier(it.name) }
        lines += "    PRIMARY KEY ($pkCols)"
    }

    return buildString {
        append("CREATE TABLE ${qualifier}${tableName} (\n")
        append(lines.joinToString(",\n"))
        append("\n);")
    }
}

/**
 * Returns a `DROP TABLE` statement for this table.
 *
 * @param dialect used for identifier quoting only; no type mapping needed for DROP.
 * @param ifExists when true, emits `DROP TABLE IF EXISTS` to skip the error when
 *   the table does not exist (useful for repeatable migration scripts).
 */
fun Table<*>.dropTableSql(dialect: SqlDialect, ifExists: Boolean = false): String {
    val qualifier = if (ifExists) "IF EXISTS " else ""
    return "DROP TABLE ${qualifier}${dialect.quoteIdentifier(name)};"
}
