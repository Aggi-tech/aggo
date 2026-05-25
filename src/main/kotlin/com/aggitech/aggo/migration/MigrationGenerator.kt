package com.aggitech.aggo.migration

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.schema.Table

/**
 * Generates database-agnostic migration plans and SQL DDL from [Table] descriptors.
 *
 * Aggo is only the schema reader at generation time. The emitted
 * [MigrationPlan] contains plain immutable metadata and SQL strings, with no
 * reference back to [Table], columns, codecs, or the runtime. Persist the SQL in
 * your migration tool of choice (Liquibase, Flyway, dbmate, plain psql, etc.)
 * and version that file with the application consuming the database.
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
 * manually in your versioned migration script:
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

/** Immutable database snapshot used to generate migrations without Aggo runtime objects. */
data class MigrationSchema(
    val version: String,
    val tables: List<MigrationTable>,
) {
    init {
        require(SCHEMA_VERSION_REGEX.matches(version)) {
            "migration schema version must match ${SCHEMA_VERSION_REGEX.pattern}"
        }
        require(tables.map { it.name }.toSet().size == tables.size) {
            "duplicate table in migration schema"
        }
    }
}

/** Immutable table metadata materialized from a [Table] descriptor. */
data class MigrationTable(
    val name: String,
    val columns: List<MigrationColumn>,
    val checks: List<MigrationCheck> = emptyList(),
    val primaryKey: List<String> = emptyList(),
) {
    init {
        require(columns.isNotEmpty()) { "migration table '$name' must have at least one column" }
        require(columns.map { it.name }.toSet().size == columns.size) {
            "duplicate column in migration table '$name'"
        }
    }
}

/** Immutable column metadata with the dialect-specific SQL type already resolved. */
data class MigrationColumn(
    val name: String,
    val sqlType: String,
    val nullable: Boolean,
    val generated: Boolean = false,
)

/** Immutable CHECK constraint metadata. */
data class MigrationCheck(
    val name: String,
    val expression: String,
)

/** One migration statement plus a stable change note for review/versioning. */
data class MigrationStep(
    val change: String,
    val sql: String? = null,
    val requiresManualMigration: Boolean = false,
)

/** Complete versioned migration plan, detached from Aggo runtime descriptors. */
data class MigrationPlan(
    val fromVersion: String?,
    val toVersion: String,
    val steps: List<MigrationStep>,
    val checksum: String? = null,
) {
    init {
        fromVersion?.let {
            require(SCHEMA_VERSION_REGEX.matches(it)) {
                "fromVersion must match ${SCHEMA_VERSION_REGEX.pattern}"
            }
        }
        require(SCHEMA_VERSION_REGEX.matches(toVersion)) {
            "toVersion must match ${SCHEMA_VERSION_REGEX.pattern}"
        }
    }

    fun sql(): String = steps.mapNotNull { it.sql }.joinToString("\n\n")
}

/**
 * Materializes this [Table] into immutable migration metadata.
 *
 * After this call returns, generated migration files can be produced without
 * depending on Aggo schema/runtime types.
 */
fun Table<*>.toMigrationTable(dialect: MigrationDialect): MigrationTable =
    MigrationTable(
        name = name,
        columns = columns.map { col ->
            MigrationColumn(
                name = col.name,
                sqlType = dialect.columnSqlType(col.codec),
                nullable = col.isNullable,
                generated = col.isGenerated,
            )
        },
        checks = columns.mapNotNull { col ->
            col.checkExpression?.let { expr ->
                MigrationCheck(
                    name = "chk_${name}_${col.name}",
                    expression = expr(col.name),
                )
            }
        },
        primaryKey = primaryKeys.map { it.name },
    )

/** Materializes a versioned schema snapshot from Aggo table descriptors. */
fun migrationSchema(
    version: String,
    tables: Iterable<Table<*>>,
    dialect: MigrationDialect,
): MigrationSchema =
    MigrationSchema(
        version = version,
        tables = tables.map { it.toMigrationTable(dialect) },
    )

/**
 * Creates a versioned migration plan.
 *
 * With no [previous] schema, the plan contains `CREATE TABLE` statements for
 * every table. With [previous], it emits safe additive SQL and records manual
 * steps for destructive or data-sensitive changes such as type changes, drops,
 * new `NOT NULL` columns, and primary-key rewrites.
 */
fun migrationPlan(
    current: MigrationSchema,
    dialect: MigrationDialect,
    previous: MigrationSchema? = null,
    ifNotExists: Boolean = false,
): MigrationPlan {
    val steps = if (previous == null) {
        current.tables.map { table ->
            MigrationStep(
                change = "create table ${table.name}",
                sql = table.createTableSql(dialect, ifNotExists = ifNotExists),
            )
        }
    } else {
        diffSchemas(previous, current, dialect, ifNotExists)
    }
    return MigrationPlan(previous?.version, current.version, steps)
}

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
    return toMigrationTable(dialect).createTableSql(dialect, ifNotExists)
}

/** Returns a `CREATE TABLE` DDL statement for an Aggo-independent table snapshot. */
fun MigrationTable.createTableSql(dialect: MigrationDialect, ifNotExists: Boolean = false): String {
    val qualifier = if (ifNotExists) "IF NOT EXISTS " else ""
    val tableName = dialect.quoteIdentifier(name)

    val lines = mutableListOf<String>()

    for (col in columns) {
        val colName = dialect.quoteIdentifier(col.name)
        val sqlType = col.sqlType
        val nullability = if (col.nullable) "" else " NOT NULL"
        lines += "    $colName $sqlType$nullability"
    }

    for (check in checks) {
        lines += "    CONSTRAINT ${dialect.quoteIdentifier(check.name)} CHECK (${check.expression})"
    }

    if (primaryKey.isNotEmpty()) {
        val pkCols = primaryKey.joinToString(", ") { dialect.quoteIdentifier(it) }
        lines += "    PRIMARY KEY ($pkCols)"
    }

    return buildString {
        append("CREATE TABLE ${qualifier}${tableName} (\n")
        append(lines.joinToString(",\n"))
        append("\n);")
    }
}

/**
 * Returns `ALTER TABLE … ADD CONSTRAINT …` statements for an Aggo table.
 *
 * Prefer this overload over [Table.addCheckConstraintsSql] because it uses the
 * configured dialect for identifier quoting.
 */
fun Table<*>.addCheckConstraintsSql(dialect: MigrationDialect): List<String> =
    toMigrationTable(dialect).addCheckConstraintsSql(dialect)

/** Returns `ALTER TABLE … ADD CONSTRAINT …` statements for a table snapshot. */
fun MigrationTable.addCheckConstraintsSql(dialect: MigrationDialect): List<String> =
    checks.map { check ->
        "ALTER TABLE ${dialect.quoteIdentifier(name)} ADD CONSTRAINT " +
            "${dialect.quoteIdentifier(check.name)} CHECK (${check.expression});"
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

/** Returns a `DROP TABLE` statement for an Aggo-independent table snapshot. */
fun MigrationTable.dropTableSql(dialect: SqlDialect, ifExists: Boolean = false): String {
    val qualifier = if (ifExists) "IF EXISTS " else ""
    return "DROP TABLE ${qualifier}${dialect.quoteIdentifier(name)};"
}

private fun diffSchemas(
    previous: MigrationSchema,
    current: MigrationSchema,
    dialect: MigrationDialect,
    ifNotExists: Boolean = false,
): List<MigrationStep> {
    val steps = mutableListOf<MigrationStep>()
    val previousTables = previous.tables.associateBy { it.name }
    val currentTables = current.tables.associateBy { it.name }

    for (table in current.tables) {
        val oldTable = previousTables[table.name]
        if (oldTable == null) {
            steps += MigrationStep(
                change = "create table ${table.name}",
                sql = table.createTableSql(dialect, ifNotExists = ifNotExists),
            )
        } else {
            steps += diffTable(oldTable, table, dialect)
        }
    }

    for (table in previous.tables) {
        if (table.name !in currentTables) {
            steps += MigrationStep(
                change = "drop table ${table.name}",
                requiresManualMigration = true,
            )
        }
    }

    return steps
}

private fun diffTable(
    previous: MigrationTable,
    current: MigrationTable,
    dialect: MigrationDialect,
): List<MigrationStep> {
    val steps = mutableListOf<MigrationStep>()
    val previousColumns = previous.columns.associateBy { it.name }
    val currentColumns = current.columns.associateBy { it.name }

    for (column in current.columns) {
        val oldColumn = previousColumns[column.name]
        when {
            oldColumn == null -> {
                steps += if (column.nullable) {
                    MigrationStep(
                        change = "add column ${current.name}.${column.name}",
                        sql = "ALTER TABLE ${dialect.quoteIdentifier(current.name)} ADD COLUMN " +
                            "${dialect.quoteIdentifier(column.name)} ${column.sqlType};",
                    )
                } else {
                    MigrationStep(
                        change = "add non-null column ${current.name}.${column.name}",
                        requiresManualMigration = true,
                    )
                }
            }
            oldColumn.sqlType != column.sqlType -> steps += MigrationStep(
                change = "change column type ${current.name}.${column.name}: " +
                    "${oldColumn.sqlType} -> ${column.sqlType}",
                requiresManualMigration = true,
            )
            oldColumn.nullable != column.nullable -> steps += MigrationStep(
                change = "change column nullability ${current.name}.${column.name}: " +
                    "${if (oldColumn.nullable) "nullable" else "not null"} -> " +
                    if (column.nullable) "nullable" else "not null",
                sql = if (column.nullable) dropNotNullSql(current.name, column, dialect) else null,
                requiresManualMigration = !column.nullable,
            )
        }
    }

    for (column in previous.columns) {
        if (column.name !in currentColumns) {
            steps += MigrationStep(
                change = "drop column ${current.name}.${column.name}",
                requiresManualMigration = true,
            )
        }
    }

    val previousChecks = previous.checks.associateBy { it.name }
    val currentChecks = current.checks.associateBy { it.name }
    for (check in current.checks) {
        val oldCheck = previousChecks[check.name]
        when {
            oldCheck == null -> steps += MigrationStep(
                change = "add check ${current.name}.${check.name}",
                sql = "ALTER TABLE ${dialect.quoteIdentifier(current.name)} ADD CONSTRAINT " +
                    "${dialect.quoteIdentifier(check.name)} CHECK (${check.expression});",
            )
            oldCheck.expression != check.expression -> steps += MigrationStep(
                change = "change check ${current.name}.${check.name}",
                requiresManualMigration = true,
            )
        }
    }
    for (check in previous.checks) {
        if (check.name !in currentChecks) {
            steps += MigrationStep(
                change = "drop check ${current.name}.${check.name}",
                requiresManualMigration = true,
            )
        }
    }

    if (previous.primaryKey != current.primaryKey) {
        steps += MigrationStep(
            change = "change primary key ${current.name}: " +
                "${previous.primaryKey.joinToString(",")} -> ${current.primaryKey.joinToString(",")}",
            requiresManualMigration = true,
        )
    }

    return steps
}

private fun dropNotNullSql(tableName: String, column: MigrationColumn, dialect: MigrationDialect): String =
    "ALTER TABLE ${dialect.quoteIdentifier(tableName)} ALTER COLUMN " +
        "${dialect.quoteIdentifier(column.name)} DROP NOT NULL;"

private val SCHEMA_VERSION_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
