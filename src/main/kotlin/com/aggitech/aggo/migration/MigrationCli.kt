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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Subcommand dispatcher for [AggoMigrateTask].
 *
 * Each subcommand is invoked as the first argv element when running the task:
 *
 * ```
 * mvn compile exec:java -Dexec.args="migrate generate --name add_orders"
 * mvn compile exec:java -Dexec.args="migrate run"
 * mvn compile exec:java -Dexec.args="migrate status"
 * mvn compile exec:java -Dexec.args="migrate dry-run"
 * mvn compile exec:java -Dexec.args="migrate install-cli --runner maven"
 * mvn compile exec:java -Dexec.args="migrate drop --force"
 * mvn compile exec:java -Dexec.args="migrate reset --force"
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
        val cliArgs = args.stripMigrateGroup()
        val subcommand = cliArgs.firstOrNull()
            ?.takeIf { KNOWN_SUBCOMMANDS.contains(it) }
            ?: "generate"

        // Strip the subcommand from argv if it matched a known one; otherwise
        // leave argv intact so the legacy "first arg is the migration name"
        // contract still works.
        val rest = if (subcommand == cliArgs.firstOrNull()) cliArgs.drop(1).toTypedArray() else cliArgs

        when (subcommand) {
            "generate", "gen" -> generate(task, rest)
            "status"          -> status(task, rest)
            "run", "apply", "up" -> apply(task, rest)
            "dry-run", "sql"  -> dryRun(rest)
            "install-cli"      -> installCli(rest)
            "drop"            -> drop(task, rest)
            "reset"           -> reset(task, rest)
            "help", "-h", "--help" -> printHelp()
            else       -> error("unreachable: unknown subcommand '$subcommand'")
        }
    }

    private val KNOWN_SUBCOMMANDS = setOf(
        "generate", "gen", "status", "run", "apply", "up", "dry-run", "sql",
        "drop", "reset", "help", "-h", "--help",
        "install-cli",
    )

    private fun Array<String>.stripMigrateGroup(): Array<String> =
        if (firstOrNull() == "migrate") drop(1).toTypedArray() else this

    // ----- generate -------------------------------------------------------

    private fun generate(task: AggoMigrateTask, args: Array<String>) {
        val name = migrationNameFrom(args)
            ?: System.getProperty("aggo.name")?.takeIf { it.isNotBlank() }

        // DB is the authoritative snapshot source. Sync to the local file so that
        // AggoMigrate.generate (which is file-based) always diffs against the actual
        // applied state. Falls through silently when DB credentials are unavailable
        // (first run, CI without DB access, etc.) so that file-based fallback works.
        if (hasDbConfig(task)) {
            runCatching {
                withAggo(task) { aggo ->
                    val snapshotJson = runBlocking { aggo.session.readLatestSnapshot() }
                    if (snapshotJson != null) {
                        Files.createDirectories(task.snapshotFile.parent)
                        task.snapshotFile.writeText(snapshotJson, Charsets.UTF_8)
                    } else if (task.snapshotFile.exists()) {
                        // DB has no snapshot yet the file exists → DB was dropped and recreated.
                        // Remove the stale file so generate treats this as a fresh schema.
                        task.snapshotFile.deleteIfExists()
                    }
                }
            }.onFailure { ex ->
                System.err.println("aggo: could not read snapshot from DB — using local file (${ex.message})")
            }
        }

        AggoMigrate.generate(
            tables = task.tables,
            dialect = task.dialect,
            snapshotFile = task.snapshotFile,
            migrationsDir = task.migrationsDir,
            migrationName = name,
        )
    }

    private fun migrationNameFrom(args: Array<String>): String? {
        var positional: String? = null
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--name" || arg == "-n" -> {
                    val value = args.getOrNull(i + 1)
                        ?.takeIf { it.isNotBlank() && !it.startsWith("-") }
                        ?: error("$arg requires a migration name")
                    return value
                }
                arg.startsWith("--name=") -> {
                    return arg.substringAfter("=").takeIf { it.isNotBlank() }
                        ?: error("--name requires a migration name")
                }
                arg.isBlank() -> Unit
                positional == null -> positional = arg
            }
            i++
        }
        return positional
    }

    // ----- install-cli ----------------------------------------------------

    private fun installCli(args: Array<String>) {
        val request = unixCliInstallRequestFrom(args)
        val target = writeUnixCliLauncher(request)
        println("Installed Aggo CLI: $target")
        println("Run: ${request.commandName} migrate generate --name add_orders")
        println("If the command is not found, add ${request.installDir} to PATH.")
    }

    private fun unixCliInstallRequestFrom(args: Array<String>): UnixCliInstallRequest {
        var commandName = "aggo"
        var installDir = defaultUnixInstallDir()
        var projectDir = defaultUnixProjectDir(Paths.get("").toAbsolutePath())
        var runner: UnixCliRunner? = null
        var gradleTask = ":aggoCliRun"

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--command" -> commandName = optionValue(args, i, arg).also { i++ }
                arg.startsWith("--command=") -> commandName = valueAfterEquals(arg, "--command")
                arg == "--dir" -> installDir = Paths.get(optionValue(args, i, arg)).also { i++ }
                arg.startsWith("--dir=") -> installDir = Paths.get(valueAfterEquals(arg, "--dir"))
                arg == "--project-dir" -> projectDir = Paths.get(optionValue(args, i, arg)).also { i++ }
                arg.startsWith("--project-dir=") -> projectDir = Paths.get(valueAfterEquals(arg, "--project-dir"))
                arg == "--runner" -> runner = unixRunner(optionValue(args, i, arg)).also { i++ }
                arg.startsWith("--runner=") -> runner = unixRunner(valueAfterEquals(arg, "--runner"))
                arg == "--gradle-task" -> gradleTask = optionValue(args, i, arg).also { i++ }
                arg.startsWith("--gradle-task=") -> gradleTask = valueAfterEquals(arg, "--gradle-task")
                arg.isBlank() -> Unit
                else -> error("Unknown install-cli option: $arg")
            }
            i++
        }

        return UnixCliInstallRequest(
            commandName = commandName,
            installDir = installDir,
            projectDir = defaultUnixProjectDir(projectDir),
            runner = runner ?: UnixCliRunner.AUTO,
            gradleTask = gradleTask,
        )
    }

    private fun optionValue(args: Array<String>, index: Int, option: String): String =
        args.getOrNull(index + 1)
            ?.takeIf { it.isNotBlank() && !it.startsWith("-") }
            ?: error("$option requires a value")

    private fun valueAfterEquals(arg: String, option: String): String =
        arg.substringAfter("=").takeIf { it.isNotBlank() }
            ?: error("$option requires a value")

    private fun unixRunner(value: String): UnixCliRunner = when (value.lowercase()) {
        "auto"   -> UnixCliRunner.AUTO
        "gradle" -> UnixCliRunner.GRADLE
        "maven"  -> UnixCliRunner.MAVEN
        else     -> error("--runner must be auto, gradle or maven, got: $value")
    }

    // ----- status ---------------------------------------------------------

    private fun status(task: AggoMigrateTask, args: Array<String>) {
        val migrationFile = requireMigrationFile(args)
        val file = parseMigrationFile(migrationFile)

        withAggo(task) { aggo ->
            val applied = runBlocking { fetchAppliedVersions(aggo) }
            val marker = if (file.version in applied) "[applied]" else "[pending]"
            println("Migration file: $migrationFile")
            println("  $marker ${file.version}")
        }
    }

    // ----- apply ----------------------------------------------------------

    private fun apply(task: AggoMigrateTask, args: Array<String>) {
        val migrationFile = requireMigrationFile(args)
        val file = parseMigrationFile(migrationFile)
        withAggo(task) { aggo ->
            val results = runBlocking { aggo.tx.applyMigrations(listOf(file)) }
            val executed = results.filter { !it.skipped }
            val skipped = results.count { it.skipped }
            for (r in executed) {
                println("Applied: ${r.fromVersion ?: "<empty>"} -> ${r.toVersion} (${r.statementsExecuted} stmt)")
            }
            if (executed.isEmpty()) {
                println("Database already at latest version. ($skipped skipped)")
            } else {
                println("${executed.size} applied, $skipped already on disk.")
                // Store the current schema snapshot in the DB so the next 'generate'
                // always diffs against the exact state that was applied.
                val lastVersion = executed.last().toVersion
                val snapshotJson = migrationSchema(lastVersion, task.tables, task.dialect).toJson()
                runBlocking { aggo.tx.storeSnapshot(lastVersion, snapshotJson) }
            }
        }
    }

    // ----- dry-run --------------------------------------------------------

    private fun dryRun(args: Array<String>) {
        val migrationFile = requireMigrationFile(args)
        val entry = parseMigrationFile(migrationFile)
        println("-- ${entry.version} (from ${entry.fromVersion ?: "<empty>"})")
        println(entry.sql)
        println()
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

        // Clear the snapshot so the next 'generate' treats the schema as a fresh start.
        // Leaving a stale snapshot after a drop causes 'generate' to diff against the
        // old state and may produce migrations with constraints that no longer exist.
        if (task.snapshotFile.deleteIfExists()) {
            println("Cleared snapshot: ${task.snapshotFile}")
        }
    }

    // ----- reset ----------------------------------------------------------

    private fun reset(task: AggoMigrateTask, args: Array<String>) {
        requireForceInProd(task, "reset", args)
        drop(task, args)
        apply(task, args)
    }

    // ----- help -----------------------------------------------------------

    private fun printHelp() {
        println(
            """
            Aggo migration CLI:

              aggo migrate generate [name]
              aggo migrate generate --name add_orders
              aggo migrate run
              aggo migrate run --migration-file path/to/migration.sql
              aggo migrate status
              aggo migrate dry-run
              aggo migrate install-cli
              aggo migrate drop [--force]
              aggo migrate reset [--force]
              aggo migrate help

            Subcommands:
              generate, gen       Generate a new migration from current Table descriptors.
              run, apply, up      Run pending migrations against the configured database.
              status              List applied vs pending migrations.
              dry-run, sql        Print pending migration SQL without applying.
              install-cli          Install a Unix launcher, usually ~/.local/bin/aggo.
              drop                Drop every declared table plus aggo_schema_versions.
              reset               drop followed by run.
              help                Show this text.

            File-based migration commands:
              run, status, dry-run, and reset require --migration-file path/to/migration.sql.

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

    private fun requireMigrationFile(args: Array<String>): Path =
        migrationFileFrom(args) ?: error("--migration-file is required for file-based migration commands")

    private fun migrationFileFrom(args: Array<String>): Path? {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--migration-file" -> return Paths.get(optionValue(args, i, arg))
                arg.startsWith("--migration-file=") -> return Paths.get(valueAfterEquals(arg, "--migration-file"))
            }
            i++
        }
        return null
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

private fun hasDbConfig(task: AggoMigrateTask): Boolean =
    task.poolConfig != null ||
        (sysOrEnv("aggo.db.user", "AGGO_DB_USER") != null &&
            sysOrEnv("aggo.db.password", "AGGO_DB_PASSWORD") != null &&
            sysOrEnv("aggo.db.database", "AGGO_DB_DATABASE") != null)
