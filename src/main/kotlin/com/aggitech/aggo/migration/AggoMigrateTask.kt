package com.aggitech.aggo.migration

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.schema.Table
import java.nio.file.Paths

/**
 * Base class for a project's migration entry point.
 *
 * Subclass this as a Kotlin `object`, declare [tables] and [dialect], and wire
 * it to `exec-maven-plugin` with a one-line `fun main`:
 *
 * ```kotlin
 * // db/Migrations.kt
 * object Migrations : AggoMigrateTask() {
 *     override val tables  = listOf(UsersTable, OrdersTable)
 *     override val dialect = PostgresDialect
 * }
 *
 * fun main(args: Array<String>) = Migrations.runFromArgs(args)
 * ```
 *
 * ```xml
 * <!-- pom.xml -->
 * <plugin>
 *   <groupId>org.codehaus.mojo</groupId>
 *   <artifactId>exec-maven-plugin</artifactId>
 *   <configuration>
 *     <mainClass>com.example.db.MigrationsKt</mainClass>
 *   </configuration>
 * </plugin>
 * ```
 *
 * ```bash
 * mvn compile exec:java -Daggo.name=add_orders_table
 * ```
 *
 * ## Path resolution
 *
 * Paths default to the Maven convention and can be overridden either by system
 * property or by overriding the `open val` in the subclass:
 *
 * | System property       | Default                                          |
 * |-----------------------|--------------------------------------------------|
 * | `aggo.snapshotFile`   | `src/main/resources/aggo/snapshot.json`          |
 * | `aggo.migrationsDir`  | `src/main/resources/aggo/migrations`             |
 * | `aggo.name`           | _(none — timestamp-only version label)_          |
 */
abstract class AggoMigrateTask {

    abstract val tables: List<Table<*>>
    abstract val dialect: MigrationDialect

    open val snapshotFile
        get() = Paths.get(
            System.getProperty("aggo.snapshotFile", "src/main/resources/aggo/snapshot.json")
        )

    open val migrationsDir
        get() = Paths.get(
            System.getProperty("aggo.migrationsDir", "src/main/resources/aggo/migrations")
        )

    /**
     * Parses [args] for an optional migration name label, then falls back to the
     * `aggo.name` system property. Delegates to [AggoMigrate.generate].
     */
    fun runFromArgs(args: Array<String>) {
        val name = args.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("aggo.name")?.takeIf { it.isNotBlank() }
        AggoMigrate.generate(
            tables = tables,
            dialect = dialect,
            snapshotFile = snapshotFile,
            migrationsDir = migrationsDir,
            migrationName = name,
        )
    }
}
