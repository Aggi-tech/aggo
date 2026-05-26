package com.aggitech.aggo.migration

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.schema.MigratableCodec
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

/** Custom PostgreSQL type (ENUM, DOMAIN, etc.) tracked in the migration snapshot. */
data class MigrationCustomType(
    val name: String,
    val createDdl: String,
)

/** Immutable database snapshot used to generate migrations without Aggo runtime objects. */
data class MigrationSchema(
    val version: String,
    val tables: List<MigrationTable>,
    val customTypes: List<MigrationCustomType> = emptyList(),
) {
    init {
        require(SCHEMA_VERSION_REGEX.matches(version)) {
            "migration schema version must match ${SCHEMA_VERSION_REGEX.pattern}"
        }
        require(tables.map { it.name }.toSet().size == tables.size) {
            "duplicate table in migration schema"
        }
        require(customTypes.map { it.name }.toSet().size == customTypes.size) {
            "duplicate custom type in migration schema"
        }
    }
}

/** Immutable table metadata materialized from a [Table] descriptor. */
data class MigrationTable(
    val name: String,
    val columns: List<MigrationColumn>,
    val checks: List<MigrationCheck> = emptyList(),
    val primaryKey: List<String> = emptyList(),
    val foreignKeys: List<MigrationForeignKey> = emptyList(),
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

/**
 * Immutable FOREIGN KEY constraint metadata materialized from a [com.aggitech.aggo.schema.ForeignKey].
 *
 * Stored in [MigrationTable] and persisted in migration snapshots so the diff engine can
 * detect added, modified, and dropped FK constraints between schema versions.
 */
data class MigrationForeignKey(
    val name: String,
    val column: String,
    val referencedTable: String,
    val referencedColumn: String,
    val onDelete: String,
    val onUpdate: String,
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
                sqlType = col.sqlType ?: dialect.columnSqlType(col.codec),
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
        foreignKeys = foreignKeys.map { fk ->
            MigrationForeignKey(
                name = fk.effectiveName,
                column = fk.column.name,
                referencedTable = fk.referencedColumn.table.name,
                referencedColumn = fk.referencedColumn.name,
                onDelete = fk.onDelete.sql,
                onUpdate = fk.onUpdate.sql,
            )
        },
    )

/** Materializes a versioned schema snapshot from Aggo table descriptors. */
fun migrationSchema(
    version: String,
    tables: Iterable<Table<*>>,
    dialect: MigrationDialect,
): MigrationSchema {
    val tableList = tables.toList()
    val customTypes = tableList
        .flatMap { it.columns }
        .map { it.codec }
        .filterIsInstance<MigratableCodec<*>>()
        .filter { it.createDdl != null }
        .distinctBy { it.ddlTypeName }
        .map { MigrationCustomType(it.ddlTypeName, it.createDdl!!) }

    return MigrationSchema(
        version = version,
        tables = tableList.map { it.toMigrationTable(dialect) },
        customTypes = customTypes,
    )
}

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
    // Custom type steps come first — types must exist before CREATE TABLE references them.
    val customTypeSteps = diffCustomTypes(
        previous = previous?.customTypes ?: emptyList(),
        current = current.customTypes,
    )

    val tableSteps: List<MigrationStep>
    val fkSteps: List<MigrationStep>

    if (previous == null) {
        tableSteps = current.tables.map { table ->
            MigrationStep(
                change = "create table ${table.name}",
                sql = table.createTableSql(dialect, ifNotExists = ifNotExists),
            )
        }
        // FK constraints come after all CREATE TABLE steps to avoid forward-reference issues.
        fkSteps = current.tables.flatMap { table ->
            table.foreignKeys.map { fk ->
                MigrationStep(
                    change = "add foreign key ${table.name}.${fk.name}",
                    sql = buildFkAlterTable(table.name, fk, dialect),
                )
            }
        }
    } else {
        tableSteps = diffSchemas(previous, current, dialect, ifNotExists)
        fkSteps = emptyList()  // FK diffs are handled inside diffSchemas → diffTable
    }

    return MigrationPlan(previous?.version, current.version, customTypeSteps + tableSteps + fkSteps)
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
 * Returns `ALTER TABLE … ADD CONSTRAINT … CHECK …` statements for an Aggo table.
 *
 * Uses the configured dialect for identifier quoting. This is the preferred entry point
 * for generating CHECK constraint DDL — it belongs to the migration layer, not the schema layer.
 *
 * ```kotlin
 * UsersTable.addCheckConstraintsSql(PostgresDialect).forEach { println(it) }
 * ```
 */
fun Table<*>.addCheckConstraintsSql(dialect: MigrationDialect): List<String> =
    toMigrationTable(dialect).addCheckConstraintsSql(dialect)

/** Returns `ALTER TABLE … ADD CONSTRAINT … CHECK …` statements for a table snapshot. */
fun MigrationTable.addCheckConstraintsSql(dialect: MigrationDialect): List<String> =
    checks.map { check ->
        "ALTER TABLE ${dialect.quoteIdentifier(name)} ADD CONSTRAINT " +
            "${dialect.quoteIdentifier(check.name)} CHECK (${check.expression});"
    }

/**
 * Returns `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY …` statements for an Aggo table.
 *
 * Emits one `ALTER TABLE` statement per declared [com.aggitech.aggo.schema.ForeignKey].
 * Because FK constraints reference other tables they are always emitted separately —
 * never inline in `CREATE TABLE` — to avoid forward-reference issues.
 *
 * Use this in manual migration scripts when you need to add FK constraints to an
 * existing database, or when generating a migration outside of [migrationPlan].
 *
 * ```kotlin
 * OrdersTable.addForeignKeyConstraintsSql(PostgresDialect).forEach { println(it) }
 * // → ALTER TABLE "orders" ADD CONSTRAINT "fk_orders_customer_id"
 * //     FOREIGN KEY ("customer_id") REFERENCES "customers" ("id")
 * //     ON DELETE CASCADE ON UPDATE RESTRICT;
 * ```
 */
fun Table<*>.addForeignKeyConstraintsSql(dialect: MigrationDialect): List<String> =
    toMigrationTable(dialect).addForeignKeyConstraintsSql(dialect)

/** Returns `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY …` statements for a table snapshot. */
fun MigrationTable.addForeignKeyConstraintsSql(dialect: MigrationDialect): List<String> =
    foreignKeys.map { fk -> buildFkAlterTable(name, fk, dialect) }

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

    val previousFks = previous.foreignKeys.associateBy { it.name }
    val currentFks = current.foreignKeys.associateBy { it.name }
    for (fk in current.foreignKeys) {
        val old = previousFks[fk.name]
        when {
            old == null -> steps += MigrationStep(
                change = "add foreign key ${current.name}.${fk.name}",
                sql = buildFkAlterTable(current.name, fk, dialect),
            )
            old != fk -> steps += MigrationStep(
                change = "change foreign key ${current.name}.${fk.name}",
                requiresManualMigration = true,
            )
        }
    }
    for (fk in previous.foreignKeys) {
        if (fk.name !in currentFks) {
            steps += MigrationStep(
                change = "drop foreign key ${current.name}.${fk.name}",
                requiresManualMigration = true,
            )
        }
    }

    return steps
}

private fun dropNotNullSql(tableName: String, column: MigrationColumn, dialect: MigrationDialect): String =
    "ALTER TABLE ${dialect.quoteIdentifier(tableName)} ALTER COLUMN " +
        "${dialect.quoteIdentifier(column.name)} DROP NOT NULL;"

internal fun buildFkAlterTable(tableName: String, fk: MigrationForeignKey, dialect: SqlDialect): String =
    "ALTER TABLE ${dialect.quoteIdentifier(tableName)} ADD CONSTRAINT ${dialect.quoteIdentifier(fk.name)} " +
        "FOREIGN KEY (${dialect.quoteIdentifier(fk.column)}) " +
        "REFERENCES ${dialect.quoteIdentifier(fk.referencedTable)} (${dialect.quoteIdentifier(fk.referencedColumn)}) " +
        "ON DELETE ${fk.onDelete} ON UPDATE ${fk.onUpdate};"

private fun diffCustomTypes(
    previous: List<MigrationCustomType>,
    current: List<MigrationCustomType>,
): List<MigrationStep> {
    val steps = mutableListOf<MigrationStep>()
    val previousMap = previous.associateBy { it.name }
    val currentMap = current.associateBy { it.name }

    for (type in current) {
        val old = previousMap[type.name]
        when {
            old == null -> steps += MigrationStep(
                change = "create custom type ${type.name}",
                sql = type.createDdl,
            )
            old.createDdl != type.createDdl -> steps += MigrationStep(
                change = "custom type '${type.name}' DDL changed — review and migrate manually",
                requiresManualMigration = true,
            )
        }
    }

    for (type in previous) {
        if (type.name !in currentMap) {
            steps += MigrationStep(
                change = "custom type '${type.name}' removed — drop manually if safe",
                requiresManualMigration = true,
            )
        }
    }

    return steps
}

private val SCHEMA_VERSION_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
