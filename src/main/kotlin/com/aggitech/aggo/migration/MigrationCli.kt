package com.aggitech.aggo.migration

import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.AggoUnsafe
import com.aggitech.aggo.runtime.PoolConfig
import com.aggitech.aggo.runtime.PostgresConfig
import io.r2dbc.spi.Result
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.runBlocking
import org.reactivestreams.Publisher

/**
 * Subcommand dispatcher for [AggoMigrateTask].
 *
 * Each subcommand is invoked as the first argv element when running the task:
 *
 * ```
 * mvn compile exec:java -Dexec.args="generate -Daggo.name=add_orders"
 * mvn compile exec:java -Dexec.args="apply"
 * mvn compile exec:java -Dexec.args="status"
 * mvn compile exec:java -Dexec.args="dry-run"
 * mvn compile exec:java -Dexec.args="drop --force"
 * mvn compile exec:java -Dexec.args="reset --force"
 * ```
 *
 * Destructive subcommands (`drop`, `reset`) require `--force` when `AGGO_ENV`
 * resolves to `prod`. They print a confirmation line listing the tables that
 * will be dropped before doing anything.
 *
 * Database credentials for `apply` / `drop` / `reset` are read from the
 * subclass's `poolConfig` override; if that is null, from `-Daggo.db.*` system
 * properties; otherwise from `AGGO_DB_*` environment variables. Password
 * sources are checked in the same order — prefer the environment variable on
 * shared machines so the value does not appear in `ps aux`.
 */
internal object MigrationCli {

    fun dispatch(task: AggoMigrateTask, args: Array<String>) {
        val subcommand = args.firstOrNull()
            ?.takeIf { KNOWN_SUBCOMMANDS.contains(it) }
            ?: "generate"

        // Strip the subcommand from argv if it matched a known one; otherwise
        // leave argv intact so the legacy "first arg is the migration name"
        // contract still works.
        val rest = if (subcommand == args.firstOrNull()) args.drop(1).toTypedArray() else args

        when (subcommand) {
            "generate" -> generate(task, rest)
            "status"   -> status(task)
            "apply"    -> apply(task)
            "dry-run"  -> dryRun(task)
            "drop"     -> drop(task, rest)
            "reset"    -> reset(task, rest)
            "help", "-h", "--help" -> printHelp()
            else       -> error("unreachable: unknown subcommand '$subcommand'")
        }
    }

    private val KNOWN_SUBCOMMANDS = setOf(
        "generate", "status", "apply", "dry-run", "drop", "reset", "help", "-h", "--help",
    )

    // ----- generate -------------------------------------------------------

    private fun generate(task: AggoMigrateTask, args: Array<String>) {
        val name = args.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("aggo.name")?.takeIf { it.isNotBlank() }
        AggoMigrate.generate(
            tables = task.tables,
            dialect = task.dialect,
            snapshotFile = task.snapshotFile,
            migrationsDir = task.migrationsDir,
            migrationName = name,
        )
    }

    // ----- status ---------------------------------------------------------

    private fun status(task: AggoMigrateTask) {
        val files = readMigrationFiles(task.migrationsDir)
        if (files.isEmpty()) {
            println("No migration files found under ${task.migrationsDir}.")
            return
        }

        withAggo(task) { aggo ->
            val applied = runBlocking { fetchAppliedVersions(aggo) }
            println("Migrations under ${task.migrationsDir}:")
            for (entry in files) {
                val marker = if (entry.version in applied) "[applied]" else "[pending]"
                println("  $marker ${entry.version}")
            }
            val pending = files.count { it.version !in applied }
            val orphans = applied.filter { v -> files.none { it.version == v } }
            println()
            println("${files.size} total, ${files.size - pending} applied, $pending pending.")
            if (orphans.isNotEmpty()) {
                println("Applied in DB but missing on disk: ${orphans.joinToString()}")
            }
        }
    }

    // ----- apply ----------------------------------------------------------

    private fun apply(task: AggoMigrateTask) {
        val files = readMigrationFiles(task.migrationsDir)
        if (files.isEmpty()) {
            println("No migration files found under ${task.migrationsDir}.")
            return
        }
        withAggo(task) { aggo ->
            val results = runBlocking { aggo.tx.applyMigrations(task.migrationsDir) }
            val executed = results.filter { !it.skipped }
            val skipped = results.count { it.skipped }
            for (r in executed) {
                println("Applied: ${r.fromVersion ?: "<empty>"} -> ${r.toVersion} (${r.statementsExecuted} stmt)")
            }
            if (executed.isEmpty()) {
                println("Database already at latest version. ($skipped skipped)")
            } else {
                println("${executed.size} applied, $skipped already on disk.")
            }
        }
    }

    // ----- dry-run --------------------------------------------------------

    private fun dryRun(task: AggoMigrateTask) {
        val files = readMigrationFiles(task.migrationsDir)
        if (files.isEmpty()) {
            println("No migration files found under ${task.migrationsDir}.")
            return
        }
        for (entry in files) {
            println("-- ${entry.version} (from ${entry.fromVersion ?: "<empty>"})")
            println(entry.sql)
            println()
        }
    }

    // ----- drop -----------------------------------------------------------

    @OptIn(AggoUnsafe::class)
    private fun drop(task: AggoMigrateTask, args: Array<String>) {
        requireForceInProd(task, "drop", args)

        val tableNames = task.tables.map { it.name } + "aggo_schema_versions"
        println("DROP TABLE will remove ${tableNames.size} tables in ${task.environment}:")
        tableNames.forEach { println("  - $it") }

        withAggo(task) { aggo ->
            runBlocking {
                aggo.tx.unsafe { raw ->
                    for (table in task.tables.reversed()) {
                        // Reversed so FK-children drop before parents in the common case.
                        raw.executeRaw(table.dropTableSql(task.dialect, ifExists = true))
                    }
                    raw.executeRaw("DROP TABLE IF EXISTS \"aggo_schema_versions\";")
                }
            }
        }
        println("Dropped ${tableNames.size} tables.")
    }

    // ----- reset ----------------------------------------------------------

    private fun reset(task: AggoMigrateTask, args: Array<String>) {
        requireForceInProd(task, "reset", args)
        drop(task, args)
        apply(task)
    }

    // ----- help -----------------------------------------------------------

    private fun printHelp() {
        println(
            """
            Aggo migration task — subcommands:

              generate [name]     Generate a new migration from current Table descriptors.
                                  Optional positional name (or -Daggo.name=) appended to the
                                  timestamp version.
              apply               Run pending migrations against the configured database.
              status              List applied vs pending migrations.
              dry-run             Print pending migration SQL without applying.
              drop [--force]      Drop every declared table plus aggo_schema_versions.
                                  Requires --force when AGGO_ENV=prod.
              reset [--force]     drop followed by apply. Same prod guard as drop.
              help                Show this text.

            Database configuration (apply, status, drop, reset):
              Override poolConfig in your AggoMigrateTask subclass, or set
                -Daggo.db.host  -Daggo.db.port  -Daggo.db.database
                -Daggo.db.user  -Daggo.db.password  -Daggo.db.sslMode
              or the equivalent AGGO_DB_HOST / AGGO_DB_PORT / AGGO_DB_DATABASE /
              AGGO_DB_USER / AGGO_DB_PASSWORD / AGGO_DB_SSL_MODE env vars.
              AGGO_ENV=prod (or -Daggo.env=prod) blocks drop/reset without --force.
            """.trimIndent()
        )
    }

    // ----- helpers --------------------------------------------------------

    private fun requireForceInProd(task: AggoMigrateTask, op: String, args: Array<String>) {
        val isProd = task.environment.equals("prod", ignoreCase = true)
        val hasForce = args.contains("--force")
        check(!isProd || hasForce) {
            "Refusing to $op in production. Set AGGO_ENV=dev/staging or pass --force to override."
        }
    }

    private fun withAggo(task: AggoMigrateTask, block: (Aggo) -> Unit) {
        val config = resolvePoolConfig(task)
        Aggo(AggoPool.postgres(config)).use(block)
    }

    @OptIn(AggoUnsafe::class)
    private suspend fun fetchAppliedVersions(aggo: Aggo): Set<String> = aggo.tx.unsafe { raw ->
        // The schema-versions table may not exist yet on a fresh DB; treat any
        // failure as "nothing applied" so `status` works on an uninitialised DB.
        runCatching {
            val versions = mutableSetOf<String>()
            val statement = raw.rawConnection().createStatement(
                "SELECT \"version\" FROM \"aggo_schema_versions\" ORDER BY \"version\";",
            )
            @Suppress("UNCHECKED_CAST")
            (statement.execute() as Publisher<Result>).collect { result ->
                val mapped: Publisher<String?> = result.map { row, _ -> row.get(0, String::class.java) }
                mapped.collect { v -> v?.let(versions::add) }
            }
            versions.toSet()
        }.getOrElse { emptySet() }
    }

    internal fun resolvePoolConfig(task: AggoMigrateTask): PostgresConfig {
        task.poolConfig?.let { return it }
        return PostgresConfig(
            host = sysOrEnv("aggo.db.host", "AGGO_DB_HOST") ?: "localhost",
            port = sysOrEnv("aggo.db.port", "AGGO_DB_PORT")?.toInt() ?: 5432,
            database = requireSetting("aggo.db.database", "AGGO_DB_DATABASE"),
            user = requireSetting("aggo.db.user", "AGGO_DB_USER"),
            password = requireSetting("aggo.db.password", "AGGO_DB_PASSWORD"),
            sslMode = sysOrEnv("aggo.db.sslMode", "AGGO_DB_SSL_MODE"),
            pool = PoolConfig(maxSize = 4),
        )
    }
}

private fun sysOrEnv(systemProperty: String, envVar: String): String? =
    System.getProperty(systemProperty)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envVar)?.takeIf { it.isNotBlank() }

private fun requireSetting(systemProperty: String, envVar: String): String =
    sysOrEnv(systemProperty, envVar)
        ?: error("Database setting not provided. Set -D$systemProperty=… or env $envVar.")
