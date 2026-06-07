package com.aggitech.aggo.migration

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.dialect.OUTBOX_TABLE
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.dialect.TriggerDialect
import com.aggitech.aggo.schema.MigratableCodec
import com.aggitech.aggo.schema.NotifyTrigger
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
    /** Notification triggers tracked alongside this schema version — see [NotifyTrigger]. */
    val triggers: List<NotifyTrigger<*>> = emptyList(),
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
        require(triggers.map { it.name }.toSet().size == triggers.size) {
            "duplicate trigger in migration schema"
        }
    }
}

/** Immutable table metadata materialized from a [Table] descriptor. */
data class MigrationTable(
    val name: String,
    val columns: List<MigrationColumn>,
    val checks: List<MigrationCheck> = emptyList(),
    val uniques: List<MigrationUnique> = emptyList(),
    val primaryKey: List<String> = emptyList(),
    val foreignKeys: List<MigrationForeignKey> = emptyList(),
    val indexes: List<MigrationIndex> = emptyList(),
) {
    init {
        require(columns.isNotEmpty()) { "migration table '$name' must have at least one column" }
        require(columns.map { it.name }.toSet().size == columns.size) {
            "duplicate column in migration table '$name'"
        }
        require(indexes.map { it.name }.toSet().size == indexes.size) {
            "duplicate index in migration table '$name'"
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

/** Immutable UNIQUE constraint metadata. */
data class MigrationUnique(
    val name: String,
    val columns: List<String>,
)

/**
 * Immutable index metadata — emits a `CREATE INDEX … USING <method>` statement.
 *
 * Only non-unique indexes are modelled here. Unique constraints are tracked in
 * [MigrationUnique] and emitted as `CONSTRAINT … UNIQUE` clauses instead.
 */
data class MigrationIndex(
    val name: String,
    val columns: List<String>,
    val method: String = "btree",
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

/** Reviewable metadata for one migration step. */
data class MigrationAudit(
    val operation: String,
    val targetType: String,
    val targetName: String,
    val reversible: Boolean,
    val reverseSql: String? = null,
    val note: String? = null,
)

/** One migration statement plus a stable change note for review/versioning. */
data class MigrationStep(
    val change: String,
    val sql: String? = null,
    val requiresManualMigration: Boolean = false,
    val audit: MigrationAudit? = null,
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
        checks = columns.flatMap { col ->
            val constraints = col.checkConstraints
            constraints.mapIndexed { index, check ->
                MigrationCheck(
                    name = check.effectiveName(name, col.name, index, constraints.size),
                    expression = check.expression(col.name),
                )
            }
        },
        uniques = columns.mapNotNull { col ->
            col.uniqueConstraint?.let { unique ->
                MigrationUnique(
                    name = unique.effectiveName(name, col.name),
                    columns = listOf(col.name),
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
        indexes = columns.mapNotNull { col ->
            col.indexDeclaration?.let { idx ->
                MigrationIndex(
                    name = idx.effectiveName(name, col.name),
                    columns = listOf(col.name),
                    method = idx.method.sql,
                )
            }
        },
    )

/** Materializes a versioned schema snapshot from Aggo table descriptors. */
fun migrationSchema(
    version: String,
    tables: Iterable<Table<*>>,
    dialect: MigrationDialect,
    triggers: Iterable<NotifyTrigger<*>> = emptyList(),
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
        triggers = triggers.toList(),
    )
}

/**
 * Creates a versioned migration plan.
 *
 * With no [previous] schema, the plan contains `CREATE TABLE` statements for
 * every table. With [previous], it emits safe additive SQL and records manual
 * steps for destructive or data-sensitive changes such as type changes, drops,
 * new `NOT NULL` columns, and primary-key rewrites.
 *
 * Pass [triggerDialect] when either schema declares [NotifyTrigger]s — it renders
 * the trigger/function/outbox-table DDL. Omitting it while triggers are present
 * throws [IllegalArgumentException]; this is a configuration error that must
 * surface immediately, not produce a plan silently missing trigger DDL.
 *
 * [outboxTableName] overrides the managed outbox table's name for dialects that
 * need one (MySQL/Oracle — see [TriggerDialect.renderOutboxTableDdl]). Pass the
 * same name to [com.aggitech.aggo.notify.OutboxListener] so the generated DDL
 * and the runtime poller agree on which table to use.
 */
fun migrationPlan(
    current: MigrationSchema,
    dialect: MigrationDialect,
    previous: MigrationSchema? = null,
    ifNotExists: Boolean = false,
    triggerDialect: TriggerDialect? = null,
    outboxTableName: String = OUTBOX_TABLE,
): MigrationPlan {
    // Custom type steps come first — types must exist before CREATE TABLE references them.
    val customTypeSteps = diffCustomTypes(
        previous = previous?.customTypes ?: emptyList(),
        current = current.customTypes,
    )

    val tableSteps: List<MigrationStep>
    val fkSteps: List<MigrationStep>
    val indexSteps: List<MigrationStep>

    if (previous == null) {
        tableSteps = current.tables.map { table ->
            MigrationStep(
                change = "create table ${table.name}",
                sql = table.createTableSql(dialect, ifNotExists = ifNotExists),
                audit = audit(
                    operation = "create",
                    targetType = "table",
                    targetName = table.name,
                    reverseSql = table.dropTableSql(dialect, ifExists = true),
                ),
            )
        }
        // FK constraints come after all CREATE TABLE steps to avoid forward-reference issues.
        fkSteps = current.tables.flatMap { table ->
            table.foreignKeys.map { fk ->
                MigrationStep(
                    change = "add foreign key ${table.name}.${fk.name}",
                    sql = buildFkAlterTable(table.name, fk, dialect),
                    audit = audit(
                        operation = "add",
                        targetType = "foreign key",
                        targetName = "${table.name}.${fk.name}",
                        reverseSql = dropConstraintSql(table.name, fk.name, dialect),
                    ),
                )
            }
        }
        // Indexes come after FKs — tables and their columns must exist first.
        indexSteps = current.tables.flatMap { table ->
            table.indexes.map { idx ->
                MigrationStep(
                    change = "create index ${table.name}.${idx.name}",
                    sql = buildIndexSql(table.name, idx, dialect),
                    audit = audit(
                        operation = "create",
                        targetType = "index",
                        targetName = "${table.name}.${idx.name}",
                        reverseSql = dropIndexSql(idx.name, dialect),
                    ),
                )
            }
        }
    } else {
        tableSteps = diffSchemas(previous, current, dialect, ifNotExists)
        fkSteps = emptyList()     // FK diffs are handled inside diffSchemas → diffTable
        indexSteps = emptyList()  // index diffs are handled inside diffSchemas → diffTable
    }

    // Triggers come last — their DDL references tables (and, for MySQL/Oracle, the
    // outbox table) that earlier steps must have already created.
    val triggerSteps = diffTriggers(
        previous = previous?.triggers ?: emptyList(),
        current = current.triggers,
        triggerDialect = triggerDialect,
        outboxTableName = outboxTableName,
    )

    return MigrationPlan(
        previous?.version,
        current.version,
        customTypeSteps + tableSteps + fkSteps + indexSteps + triggerSteps,
    )
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
    val tableName = dialect.qualifyTableName(name)

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

    for (unique in uniques) {
        val uniqueCols = unique.columns.joinToString(", ") { dialect.quoteIdentifier(it) }
        lines += "    CONSTRAINT ${dialect.quoteIdentifier(unique.name)} UNIQUE ($uniqueCols)"
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
        "ALTER TABLE ${dialect.qualifyTableName(name)} ADD CONSTRAINT " +
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

/** Returns `ALTER TABLE … ADD CONSTRAINT … UNIQUE …` statements for an Aggo table. */
fun Table<*>.addUniqueConstraintsSql(dialect: MigrationDialect): List<String> =
    toMigrationTable(dialect).addUniqueConstraintsSql(dialect)

/** Returns `ALTER TABLE … ADD CONSTRAINT … UNIQUE …` statements for a table snapshot. */
fun MigrationTable.addUniqueConstraintsSql(dialect: MigrationDialect): List<String> =
    uniques.map { unique -> buildUniqueAlterTable(name, unique, dialect) }

/**
 * Returns a `DROP TABLE` statement for this table.
 *
 * @param dialect used for identifier quoting only; no type mapping needed for DROP.
 * @param ifExists when true, emits `DROP TABLE IF EXISTS` to skip the error when
 *   the table does not exist (useful for repeatable migration scripts).
 */
fun Table<*>.dropTableSql(dialect: SqlDialect, ifExists: Boolean = false): String {
    val qualifier = if (ifExists) "IF EXISTS " else ""
    return "DROP TABLE ${qualifier}${dialect.qualifyTableName(name)};"
}

/** Returns a `DROP TABLE` statement for an Aggo-independent table snapshot. */
fun MigrationTable.dropTableSql(dialect: SqlDialect, ifExists: Boolean = false): String {
    val qualifier = if (ifExists) "IF EXISTS " else ""
    return "DROP TABLE ${qualifier}${dialect.qualifyTableName(name)};"
}

private fun diffSchemas(
    previous: MigrationSchema,
    current: MigrationSchema,
    dialect: MigrationDialect,
    ifNotExists: Boolean = false,
): List<MigrationStep> {
    val steps = mutableListOf<MigrationStep>()
    // FK and index steps for brand-new tables must come after ALL CREATE TABLE steps
    // to avoid forward-reference failures when multiple tables reference each other.
    val newTableFkSteps = mutableListOf<MigrationStep>()
    val newTableIndexSteps = mutableListOf<MigrationStep>()
    val previousTables = previous.tables.associateBy { it.name }
    val currentTables = current.tables.associateBy { it.name }

    for (table in current.tables) {
        val oldTable = previousTables[table.name]
        if (oldTable == null) {
            steps += MigrationStep(
                change = "create table ${table.name}",
                sql = table.createTableSql(dialect, ifNotExists = ifNotExists),
                audit = audit(
                    operation = "create",
                    targetType = "table",
                    targetName = table.name,
                    reverseSql = table.dropTableSql(dialect, ifExists = true),
                ),
            )
            for (fk in table.foreignKeys) {
                newTableFkSteps += MigrationStep(
                    change = "add foreign key ${table.name}.${fk.name}",
                    sql = buildFkAlterTable(table.name, fk, dialect),
                    audit = audit(
                        operation = "add",
                        targetType = "foreign key",
                        targetName = "${table.name}.${fk.name}",
                        reverseSql = dropConstraintSql(table.name, fk.name, dialect),
                    ),
                )
            }
            for (idx in table.indexes) {
                newTableIndexSteps += MigrationStep(
                    change = "create index ${table.name}.${idx.name}",
                    sql = buildIndexSql(table.name, idx, dialect),
                    audit = audit(
                        operation = "create",
                        targetType = "index",
                        targetName = "${table.name}.${idx.name}",
                        reverseSql = dropIndexSql(idx.name, dialect),
                    ),
                )
            }
        } else {
            steps += diffTable(oldTable, table, dialect)
        }
    }

    for (table in previous.tables) {
        if (table.name !in currentTables) {
            steps += MigrationStep(
                change = "drop table ${table.name}",
                requiresManualMigration = true,
                audit = audit(
                    operation = "drop",
                    targetType = "table",
                    targetName = table.name,
                    reversible = false,
                    note = "Dropping a table is destructive and must be reviewed manually.",
                ),
            )
        }
    }

    return steps + newTableFkSteps + newTableIndexSteps
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
                        sql = "ALTER TABLE ${dialect.qualifyTableName(current.name)} ADD COLUMN " +
                            "${dialect.quoteIdentifier(column.name)} ${column.sqlType};",
                        audit = audit(
                            operation = "add",
                            targetType = "column",
                            targetName = "${current.name}.${column.name}",
                            reverseSql = dropColumnSql(current.name, column.name, dialect),
                        ),
                    )
                } else {
                    MigrationStep(
                        change = "add non-null column ${current.name}.${column.name}",
                        requiresManualMigration = true,
                        audit = audit(
                            operation = "add",
                            targetType = "column",
                            targetName = "${current.name}.${column.name}",
                            reversible = false,
                            note = "Adding a NOT NULL column requires a default or backfill strategy.",
                        ),
                    )
                }
            }
            oldColumn.sqlType != column.sqlType -> steps += MigrationStep(
                change = "change column type ${current.name}.${column.name}: " +
                    "${oldColumn.sqlType} -> ${column.sqlType}",
                requiresManualMigration = true,
                audit = audit(
                    operation = "alter",
                    targetType = "column",
                    targetName = "${current.name}.${column.name}",
                    reversible = false,
                    note = "Column type changes require an explicit USING expression and rollback plan.",
                ),
            )
            oldColumn.nullable != column.nullable -> steps += MigrationStep(
                change = "change column nullability ${current.name}.${column.name}: " +
                    "${if (oldColumn.nullable) "nullable" else "not null"} -> " +
                    if (column.nullable) "nullable" else "not null",
                sql = if (column.nullable) dropNotNullSql(current.name, column, dialect) else null,
                requiresManualMigration = !column.nullable,
                audit = audit(
                    operation = "alter",
                    targetType = "column",
                    targetName = "${current.name}.${column.name}",
                    reversible = column.nullable,
                    reverseSql = if (column.nullable) setNotNullSql(current.name, column, dialect) else null,
                    note = if (column.nullable) null else "Setting NOT NULL requires data validation before applying.",
                ),
            )
        }
    }

    for (column in previous.columns) {
        if (column.name !in currentColumns) {
            steps += MigrationStep(
                change = "drop column ${current.name}.${column.name}",
                requiresManualMigration = true,
                audit = audit(
                    operation = "drop",
                    targetType = "column",
                    targetName = "${current.name}.${column.name}",
                    reversible = false,
                    reverseSql = addColumnSql(current.name, column, dialect),
                    note = "Dropping a column loses data; reverse SQL can restore shape but not data.",
                ),
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
                sql = "ALTER TABLE ${dialect.qualifyTableName(current.name)} ADD CONSTRAINT " +
                    "${dialect.quoteIdentifier(check.name)} CHECK (${check.expression});",
                audit = audit(
                    operation = "add",
                    targetType = "check",
                    targetName = "${current.name}.${check.name}",
                    reverseSql = dropConstraintSql(current.name, check.name, dialect),
                ),
            )
            oldCheck.expression != check.expression -> steps += MigrationStep(
                change = "change check ${current.name}.${check.name}",
                requiresManualMigration = true,
                audit = audit(
                    operation = "alter",
                    targetType = "check",
                    targetName = "${current.name}.${check.name}",
                    reversible = false,
                    note = "Changing a CHECK constraint may fail against existing data.",
                ),
            )
        }
    }
    for (check in previous.checks) {
        if (check.name !in currentChecks) {
            steps += MigrationStep(
                change = "drop check ${current.name}.${check.name}",
                sql = dropConstraintSql(current.name, check.name, dialect),
                audit = audit(
                    operation = "drop",
                    targetType = "check",
                    targetName = "${current.name}.${check.name}",
                    reverseSql = buildCheckAlterTable(current.name, check, dialect),
                ),
            )
        }
    }

    val previousUniques = previous.uniques.associateBy { it.name }
    val currentUniques = current.uniques.associateBy { it.name }
    for (unique in current.uniques) {
        val old = previousUniques[unique.name]
        when {
            old == null -> steps += MigrationStep(
                change = "add unique ${current.name}.${unique.name}",
                sql = buildUniqueAlterTable(current.name, unique, dialect),
                audit = audit(
                    operation = "add",
                    targetType = "unique",
                    targetName = "${current.name}.${unique.name}",
                    reverseSql = dropConstraintSql(current.name, unique.name, dialect),
                ),
            )
            old != unique -> steps += MigrationStep(
                change = "change unique ${current.name}.${unique.name}",
                requiresManualMigration = true,
                audit = audit(
                    operation = "alter",
                    targetType = "unique",
                    targetName = "${current.name}.${unique.name}",
                    reversible = false,
                    note = "Changing a UNIQUE constraint may fail against existing data.",
                ),
            )
        }
    }
    for (unique in previous.uniques) {
        if (unique.name !in currentUniques) {
            steps += MigrationStep(
                change = "drop unique ${current.name}.${unique.name}",
                sql = dropConstraintSql(current.name, unique.name, dialect),
                audit = audit(
                    operation = "drop",
                    targetType = "unique",
                    targetName = "${current.name}.${unique.name}",
                    reverseSql = buildUniqueAlterTable(current.name, unique, dialect),
                ),
            )
        }
    }

    if (previous.primaryKey != current.primaryKey) {
        steps += MigrationStep(
            change = "change primary key ${current.name}: " +
                "${previous.primaryKey.joinToString(",")} -> ${current.primaryKey.joinToString(",")}",
            requiresManualMigration = true,
            audit = audit(
                operation = "alter",
                targetType = "primary key",
                targetName = current.name,
                reversible = false,
                note = "Primary key rewrites require an explicit migration plan.",
            ),
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
                audit = audit(
                    operation = "add",
                    targetType = "foreign key",
                    targetName = "${current.name}.${fk.name}",
                    reverseSql = dropConstraintSql(current.name, fk.name, dialect),
                ),
            )
            old != fk -> steps += MigrationStep(
                change = "change foreign key ${current.name}.${fk.name}",
                requiresManualMigration = true,
                audit = audit(
                    operation = "alter",
                    targetType = "foreign key",
                    targetName = "${current.name}.${fk.name}",
                    reversible = false,
                    note = "Changing a FOREIGN KEY can affect referential integrity and requires review.",
                ),
            )
        }
    }
    for (fk in previous.foreignKeys) {
        if (fk.name !in currentFks) {
            steps += MigrationStep(
                change = "drop foreign key ${current.name}.${fk.name}",
                sql = dropConstraintSql(current.name, fk.name, dialect),
                audit = audit(
                    operation = "drop",
                    targetType = "foreign key",
                    targetName = "${current.name}.${fk.name}",
                    reverseSql = buildFkAlterTable(current.name, fk, dialect),
                ),
            )
        }
    }

    val previousIndexes = previous.indexes.associateBy { it.name }
    val currentIndexes = current.indexes.associateBy { it.name }
    for (idx in current.indexes) {
        val old = previousIndexes[idx.name]
        when {
            old == null -> steps += MigrationStep(
                change = "create index ${current.name}.${idx.name}",
                sql = buildIndexSql(current.name, idx, dialect),
                audit = audit(
                    operation = "create",
                    targetType = "index",
                    targetName = "${current.name}.${idx.name}",
                    reverseSql = dropIndexSql(idx.name, dialect),
                ),
            )
            old != idx -> steps += MigrationStep(
                change = "change index ${current.name}.${idx.name}",
                requiresManualMigration = true,
                audit = audit(
                    operation = "alter",
                    targetType = "index",
                    targetName = "${current.name}.${idx.name}",
                    reversible = false,
                    note = "Index rewrites should be planned explicitly for the target database.",
                ),
            )
        }
    }
    for (idx in previous.indexes) {
        if (idx.name !in currentIndexes) {
            steps += MigrationStep(
                change = "drop index ${idx.name}",
                sql = dropIndexSql(idx.name, dialect),
                audit = audit(
                    operation = "drop",
                    targetType = "index",
                    targetName = idx.name,
                    reverseSql = buildIndexSql(current.name, idx, dialect),
                ),
            )
        }
    }

    return steps
}

private fun dropNotNullSql(tableName: String, column: MigrationColumn, dialect: MigrationDialect): String =
    "ALTER TABLE ${dialect.qualifyTableName(tableName)} ALTER COLUMN " +
        "${dialect.quoteIdentifier(column.name)} DROP NOT NULL;"

private fun setNotNullSql(tableName: String, column: MigrationColumn, dialect: MigrationDialect): String =
    "ALTER TABLE ${dialect.qualifyTableName(tableName)} ALTER COLUMN " +
        "${dialect.quoteIdentifier(column.name)} SET NOT NULL;"

private fun addColumnSql(tableName: String, column: MigrationColumn, dialect: MigrationDialect): String {
    val nullability = if (column.nullable) "" else " NOT NULL"
    return "ALTER TABLE ${dialect.qualifyTableName(tableName)} ADD COLUMN " +
        "${dialect.quoteIdentifier(column.name)} ${column.sqlType}$nullability;"
}

private fun dropColumnSql(tableName: String, columnName: String, dialect: SqlDialect): String =
    "ALTER TABLE ${dialect.qualifyTableName(tableName)} DROP COLUMN ${dialect.quoteIdentifier(columnName)};"

private fun dropConstraintSql(tableName: String, constraintName: String, dialect: SqlDialect): String =
    "ALTER TABLE ${dialect.qualifyTableName(tableName)} DROP CONSTRAINT ${dialect.quoteIdentifier(constraintName)};"

private fun buildCheckAlterTable(tableName: String, check: MigrationCheck, dialect: SqlDialect): String =
    "ALTER TABLE ${dialect.qualifyTableName(tableName)} ADD CONSTRAINT " +
        "${dialect.quoteIdentifier(check.name)} CHECK (${check.expression});"

internal fun buildFkAlterTable(tableName: String, fk: MigrationForeignKey, dialect: SqlDialect): String =
    "ALTER TABLE ${dialect.qualifyTableName(tableName)} ADD CONSTRAINT ${dialect.quoteIdentifier(fk.name)} " +
        "FOREIGN KEY (${dialect.quoteIdentifier(fk.column)}) " +
        "REFERENCES ${dialect.qualifyTableName(fk.referencedTable)} (${dialect.quoteIdentifier(fk.referencedColumn)}) " +
        "ON DELETE ${fk.onDelete} ON UPDATE ${fk.onUpdate};"

internal fun buildUniqueAlterTable(tableName: String, unique: MigrationUnique, dialect: SqlDialect): String {
    val cols = unique.columns.joinToString(", ") { dialect.quoteIdentifier(it) }
    return "ALTER TABLE ${dialect.qualifyTableName(tableName)} ADD CONSTRAINT ${dialect.quoteIdentifier(unique.name)} " +
        "UNIQUE ($cols);"
}

internal fun buildIndexSql(tableName: String, index: MigrationIndex, dialect: SqlDialect): String {
    val cols = index.columns.joinToString(", ") { dialect.quoteIdentifier(it) }
    return "CREATE INDEX ${dialect.quoteIdentifier(index.name)} " +
        "ON ${dialect.qualifyTableName(tableName)} USING ${index.method} ($cols);"
}

private fun dropIndexSql(indexName: String, dialect: SqlDialect): String =
    "DROP INDEX ${dialect.quoteIdentifier(indexName)};"

private fun audit(
    operation: String,
    targetType: String,
    targetName: String,
    reversible: Boolean = true,
    reverseSql: String? = null,
    note: String? = null,
): MigrationAudit = MigrationAudit(
    operation = operation,
    targetType = targetType,
    targetName = targetName,
    reversible = reversible,
    reverseSql = reverseSql,
    note = note,
)

/** Returns `CREATE INDEX` statements for all declared indexes on an Aggo table. */
fun Table<*>.addIndexesSql(dialect: MigrationDialect): List<String> =
    toMigrationTable(dialect).addIndexesSql(dialect)

/** Returns `CREATE INDEX` statements for all declared indexes on a table snapshot. */
fun MigrationTable.addIndexesSql(dialect: SqlDialect): List<String> =
    indexes.map { idx -> buildIndexSql(name, idx, dialect) }

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
                audit = audit(
                    operation = "create",
                    targetType = "custom type",
                    targetName = type.name,
                    reverseSql = "DROP TYPE ${type.name};",
                ),
            )
            old.createDdl != type.createDdl -> steps += MigrationStep(
                change = "custom type '${type.name}' DDL changed — review and migrate manually",
                requiresManualMigration = true,
                audit = audit(
                    operation = "alter",
                    targetType = "custom type",
                    targetName = type.name,
                    reversible = false,
                    note = "Custom type DDL changes are database-specific and require manual SQL.",
                ),
            )
        }
    }

    for (type in previous) {
        if (type.name !in currentMap) {
            steps += MigrationStep(
                change = "custom type '${type.name}' removed — drop manually if safe",
                requiresManualMigration = true,
                audit = audit(
                    operation = "drop",
                    targetType = "custom type",
                    targetName = type.name,
                    reversible = false,
                    note = "Dropping a custom type can break dependent columns or data.",
                ),
            )
        }
    }

    return steps
}

/**
 * Diffs [NotifyTrigger] declarations between schema versions, emitting:
 *  - an outbox-table creation step the first time any trigger needs one
 *    (PostgreSQL's [TriggerDialect.renderOutboxTableDdl] returns an empty list,
 *    so this is a no-op there),
 *  - one [MigrationStep] per DDL statement for new or changed triggers
 *    (idempotent — `CREATE OR REPLACE FUNCTION` / `DROP TRIGGER IF EXISTS`),
 *  - a manual, irreversible drop step for triggers removed from [current].
 *
 * Triggers are compared by full equality (they're data classes), so any field
 * change — channel, payload, events, timing — re-renders the full DDL.
 *
 * Mirrors the existing flat [MigrationStep]/[MigrationAudit] shape used by
 * [diffCustomTypes] and `diffTable`'s FK/index/check diffs — trigger DDL is
 * purely additive to that model, not a new [MigrationStep] variant.
 */
private fun diffTriggers(
    previous: List<NotifyTrigger<*>>,
    current: List<NotifyTrigger<*>>,
    triggerDialect: TriggerDialect?,
    outboxTableName: String,
): List<MigrationStep> {
    if (previous.isEmpty() && current.isEmpty()) return emptyList()

    requireNotNull(triggerDialect) {
        "Schema declares NotifyTrigger(s) but no TriggerDialect was supplied to migrationPlan()."
    }

    val steps = mutableListOf<MigrationStep>()
    val previousMap = previous.associateBy { it.name }
    val currentMap = current.associateBy { it.name }

    val outboxDdl = triggerDialect.renderOutboxTableDdl(outboxTableName)
    val hadOutbox = previous.isNotEmpty() && outboxDdl.isNotEmpty()
    val needsOutbox = current.isNotEmpty() && outboxDdl.isNotEmpty()
    if (needsOutbox && !hadOutbox) {
        outboxDdl.forEach { sql ->
            steps += MigrationStep(
                change = "create outbox table $outboxTableName",
                sql = sql,
                audit = audit(
                    operation = "create",
                    targetType = "outbox table",
                    targetName = outboxTableName,
                    reverseSql = "DROP TABLE IF EXISTS $outboxTableName;",
                ),
            )
        }
    }

    for (trigger in current) {
        val old = previousMap[trigger.name]
        if (old == null || old != trigger) {
            triggerDialect.renderNotifyTriggerDdl(trigger, outboxTableName).forEach { sql ->
                steps += MigrationStep(
                    change = if (old == null) {
                        "create trigger ${trigger.name}"
                    } else {
                        "update trigger ${trigger.name}"
                    },
                    sql = sql,
                    audit = audit(
                        operation = if (old == null) "create" else "alter",
                        targetType = "trigger",
                        targetName = trigger.name,
                        reverseSql = triggerDialect.renderDropTriggerDdl(trigger).joinToString("\n"),
                    ),
                )
            }
        }
    }

    for (trigger in previous) {
        if (trigger.name !in currentMap) {
            steps += MigrationStep(
                change = "trigger '${trigger.name}' removed — drop manually if safe",
                requiresManualMigration = true,
                audit = audit(
                    operation = "drop",
                    targetType = "trigger",
                    targetName = trigger.name,
                    reversible = false,
                    note = "Dropping a notification trigger can silently stop downstream listeners.",
                ),
            )
        }
    }

    return steps
}

private val SCHEMA_VERSION_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
