package com.aggitech.aggo.migration

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.schema.Table
import java.nio.file.Files
import java.nio.file.Path
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
 * ## Subcommands
 *
 * Pass the subcommand as the first positional argument:
 *
 * | Subcommand   | Effect                                                            |
 * |--------------|-------------------------------------------------------------------|
 * | `generate`   | Generate a migration file (default when no subcommand given).     |
 * | `apply`      | Run pending migrations against the configured database.           |
 * | `status`     | List applied vs pending migrations.                               |
 * | `dry-run`    | Print pending migration SQL without applying.                     |
 * | `drop`       | Drop every declared table plus `aggo_schema_versions`.            |
 * | `reset`      | `drop` followed by `apply`.                                       |
 *
 * `drop` and `reset` require `--force` when `AGGO_ENV` (or `-Daggo.env`)
 * resolves to `prod`, so they cannot accidentally wipe a production database.
 *
 * ```bash
 * mvn compile exec:java -Dexec.args="generate add_orders_table"
 * mvn compile exec:java -Dexec.args="apply"
 * mvn compile exec:java -Dexec.args="status"
 * mvn compile exec:java -Dexec.args="dry-run"
 * mvn compile exec:java -Dexec.args="drop --force"
 * mvn compile exec:java -Dexec.args="reset --force"
 * ```
 *
 * ## Path resolution
 *
 * Paths default to the Maven convention and can be overridden either by system
 * property or by overriding the `open val` in the subclass:
 *
 * | System property       | Default                                          |
 * |-----------------------|--------------------------------------------------|
 * | `aggo.snapshotFile`   | `target/aggo/snapshot.json` for Maven, `build/aggo/snapshot.json` for Gradle |
 * | `aggo.migrationsDir`  | `src/main/resources/aggo/migrations`             |
 * | `aggo.name`           | _(none — timestamp-only version label)_          |
 *
 * ## Database credentials (apply / status / drop / reset)
 *
 * Either override [poolConfig] directly, or expose connection details via
 * system properties / environment variables:
 *
 * | System property       | Env var               |
 * |-----------------------|-----------------------|
 * | `aggo.db.host`        | `AGGO_DB_HOST`        |
 * | `aggo.db.port`        | `AGGO_DB_PORT`        |
 * | `aggo.db.database`    | `AGGO_DB_DATABASE`    |
 * | `aggo.db.user`        | `AGGO_DB_USER`        |
 * | `aggo.db.password`    | `AGGO_DB_PASSWORD`    |
 * | `aggo.db.sslMode`     | `AGGO_DB_SSL_MODE`    |
 * | `aggo.env`            | `AGGO_ENV`            |
 *
 * Prefer the environment variable for the password — system properties are
 * visible in `ps aux` on shared hosts.
 */
abstract class AggoMigrateTask {

    abstract val tables: List<Table<*>>
    abstract val dialect: MigrationDialect

    open val snapshotFile
        get() = Paths.get(
            System.getProperty("aggo.snapshotFile") ?: defaultSnapshotFile().toString()
        )

    open val migrationsDir
        get() = Paths.get(
            System.getProperty("aggo.migrationsDir", "src/main/resources/aggo/migrations")
        )

    /**
     * Explicit pool configuration. When null, [MigrationCli] reads connection
     * settings from `-Daggo.db.*` system properties / `AGGO_DB_*` env vars.
     *
     * Override this when you want to share the application's Spring/Quarkus
     * config rather than duplicating connection settings on the Maven side.
     */
    open val poolConfig: PostgresConfig? = null

    /**
     * Environment label used to guard destructive subcommands. Defaults to the
     * `AGGO_ENV` env var or `-Daggo.env` system property, falling back to `dev`.
     *
     * When this evaluates to `prod` (case-insensitive), [MigrationCli] refuses
     * `drop` and `reset` unless the caller also passes `--force`.
     */
    open val environment: String
        get() = System.getProperty("aggo.env")?.takeIf { it.isNotBlank() }
            ?: System.getenv("AGGO_ENV")?.takeIf { it.isNotBlank() }
            ?: "dev"

    /**
     * Dispatches to the requested subcommand. With no arguments (or an argv
     * whose first element is not a known subcommand) this falls back to
     * `generate` — preserving the original "positional name" contract:
     *
     * ```bash
     * mvn compile exec:java -Dexec.args="add_orders_table"   # generate, name=add_orders_table
     * mvn compile exec:java -Dexec.args="apply"               # apply pending migrations
     * ```
     */
    fun runFromArgs(args: Array<String>) {
        MigrationCli.dispatch(this, args)
    }
}

/**
 * Resolves Aggo's generated schema snapshot path for the current build tool.
 *
 * Maven projects use `target/aggo/snapshot.json`; Gradle projects use
 * `build/aggo/snapshot.json`. If both build descriptors are present, Maven wins
 * because this task is Maven-oriented by default and Gradle users can still
 * override [AggoMigrateTask.snapshotFile] or `-Daggo.snapshotFile`.
 */
fun defaultSnapshotFile(projectDir: Path = Paths.get("").toAbsolutePath()): Path {
    val isMaven = Files.exists(projectDir.resolve("pom.xml"))
    val isGradle = Files.exists(projectDir.resolve("build.gradle.kts")) ||
        Files.exists(projectDir.resolve("build.gradle")) ||
        Files.exists(projectDir.resolve("settings.gradle.kts")) ||
        Files.exists(projectDir.resolve("settings.gradle"))

    val buildDir = when {
        isMaven -> "target"
        isGradle -> "build"
        else -> "target"
    }
    return projectDir.resolve(buildDir).resolve("aggo").resolve("snapshot.json")
}
