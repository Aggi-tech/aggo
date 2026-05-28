package com.aggitech.aggo.migration

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.schema.Table
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val VERSION_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmmss")
private val VALID_MIGRATION_NAME = Regex("^[A-Za-z0-9_-]+$")

/**
 * EF Core-style migration code generator for Aggo.
 *
 * ## Typical usage in a project's migration generator class:
 *
 * ```kotlin
 * fun main() {
 *     AggoMigrate.generate(
 *         tables       = listOf(UsersTable, OrdersTable),
 *         dialect      = PostgresDialect,
 *         snapshotFile = Paths.get("target/aggo/snapshot.json"), // Maven
 *         migrationsDir = Paths.get("src/main/resources/aggo/migrations"),
 *         migrationName = "add_orders_table",   // optional label
 *     )
 * }
 * ```
 *
 * Run via Maven:
 * ```bash
 * mvn compile exec:java -Dexec.mainClass=com.example.db.MigrationsKt
 * ```
 *
 * ## Workflow
 *
 * 1. Reads the snapshot from [snapshotFile] (null on first run).
 * 2. Materializes [tables] against [dialect] into the current schema state.
 * 3. Diffs the current state against the snapshot.
 * 4. If no DDL changes exist, prints "No changes detected." and returns.
 * 5. Generates a `{timestamp}[_name].sql` migration file in [migrationsDir].
 * 6. Writes the updated snapshot to [snapshotFile].
 *
 * Steps that require manual SQL (e.g. adding a NOT NULL column with a default,
 * changing a column type) are printed to stdout as warnings. The generated file
 * contains audit comments plus safe auto-SQL; manual steps must be added by the
 * developer.
 */
object AggoMigrate {

    fun generate(
        tables: List<Table<*>>,
        dialect: MigrationDialect,
        snapshotFile: Path,
        migrationsDir: Path,
        migrationName: String? = null,
    ) {
        require(migrationName == null || VALID_MIGRATION_NAME.matches(migrationName)) {
            "migrationName must match [A-Za-z0-9_-]+, got: '$migrationName'"
        }

        val previousSchema = readSnapshot(snapshotFile)
        val newVersion = generateVersion(migrationName)
        val currentSchema = migrationSchema(newVersion, tables, dialect)

        val plan = migrationPlan(
            current = currentSchema,
            dialect = dialect,
            previous = previousSchema,
            ifNotExists = previousSchema == null,
        )

        if (plan.steps.isEmpty()) {
            println("No changes detected.")
            return
        }

        val manualSteps = plan.steps.filter { it.requiresManualMigration }
        if (manualSteps.isNotEmpty()) {
            println("WARNING: the following changes require manual SQL (not generated automatically):")
            manualSteps.forEach { println("  - ${it.change}") }
        }

        val autoSqlBody = plan.sql()
        if (autoSqlBody.isBlank()) {
            println("No auto-SQL to generate (all ${plan.steps.size} changes require manual migration).")
            return
        }
        val sqlBody = plan.toAuditedSql()

        val generatedAt = Instant.now()
        val checksum = computeChecksum(sqlBody, generatedAt)

        val entry = MigrationFileEntry(
            version = newVersion,
            fromVersion = previousSchema?.version,
            generatedAt = generatedAt,
            checksum = checksum,
            sql = sqlBody,
        )

        val file = writeMigrationFile(entry, migrationsDir)
        writeSnapshot(currentSchema, snapshotFile)

        val autoCount = plan.steps.count { !it.requiresManualMigration }
        println("Generated: $newVersion ($autoCount auto, ${manualSteps.size} manual) → $file")
    }
}

private fun MigrationPlan.toAuditedSql(): String =
    steps.joinToString("\n\n") { step ->
        buildString {
            appendLine("-- aggo:change=${step.change}")
            step.audit?.let { audit ->
                appendLine(
                    "-- aggo:audit=${audit.operation} ${audit.targetType} ${audit.targetName} " +
                        "reversible=${audit.reversible}"
                )
                audit.reverseSql?.let { appendLine("-- aggo:reverse=$it") }
                audit.note?.let { appendLine("-- aggo:note=$it") }
            }
            if (step.requiresManualMigration) {
                appendLine("-- aggo:manual=true")
            }
            step.sql?.let { append(it) }
        }.trimEnd()
    }

private fun generateVersion(name: String?): String {
    val ts = LocalDateTime.now(ZoneId.systemDefault()).format(VERSION_FORMAT)
    return if (name != null) "${ts}_$name" else ts
}
