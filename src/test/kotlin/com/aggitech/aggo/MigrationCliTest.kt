package com.aggitech.aggo

import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.AggoMigrateTask
import com.aggitech.aggo.migration.MigrationFileEntry
import com.aggitech.aggo.migration.writeMigrationFile
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.r2dbc.spi.Row
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText

private object MiniTable : Table<Unit>("mini") {
    @Suppress("unused")
    val id = uuid("id", isPrimaryKey = true) { null }
    override fun fromRow(row: Row): Unit = Unit
}

private class TestTask(
    override val snapshotFile: Path,
    override val migrationsDir: Path,
    override val poolConfig: PostgresConfig? = null,
    private val envValue: String = "dev",
) : AggoMigrateTask() {
    override val tables: List<Table<*>> = listOf(MiniTable)
    override val dialect: MigrationDialect = PostgresDialect
    override val environment: String get() = envValue
}

private fun captureStdout(block: () -> Unit): String {
    val original = System.out
    val sink = ByteArrayOutputStream()
    System.setOut(PrintStream(sink))
    try {
        block()
    } finally {
        System.setOut(original)
    }
    return sink.toString()
}

class MigrationCliTest : StringSpec({

    "help subcommand prints the subcommand list without touching the database" {
        val tmp = Files.createTempDirectory("aggo-cli-help")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        val out = captureStdout { task.runFromArgs(arrayOf("help")) }
        out shouldContain "aggo migrate generate"
        out shouldContain "aggo migrate run"
        out shouldContain "status"
        out shouldContain "drop"
        out shouldContain "reset"
        out shouldContain "AGGO_ENV"
    }

    "dry-run prints the SQL body from the required migration file without DB access" {
        val tmp = Files.createTempDirectory("aggo-cli-dryrun")
        val migDir = Files.createDirectories(tmp.resolve("migrations"))
        val migrationFile = writeMigrationFile(
            MigrationFileEntry(
                version = "2026.05.26.000001",
                fromVersion = null,
                generatedAt = Instant.parse("2026-05-26T10:00:00Z"),
                checksum = com.aggitech.aggo.migration.computeChecksum(
                    "CREATE TABLE \"mini\" (\"id\" UUID NOT NULL);",
                    Instant.parse("2026-05-26T10:00:00Z"),
                ),
                sql = "CREATE TABLE \"mini\" (\"id\" UUID NOT NULL);",
            ),
            migDir,
        )

        val task = TestTask(tmp.resolve("snapshot.json"), migDir)
        val out = captureStdout { task.runFromArgs(arrayOf("dry-run", "--migration-file", migrationFile.toString())) }
        out shouldContain "2026.05.26.000001"
        out shouldContain "CREATE TABLE \"mini\""
    }

    "dry-run requires --migration-file" {
        val tmp = Files.createTempDirectory("aggo-cli-empty")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        val ex = shouldThrow<IllegalStateException> { task.runFromArgs(arrayOf("dry-run")) }
        ex.message!! shouldContain "--migration-file is required"
    }

    "drop subcommand without --force is refused when environment=prod" {
        val tmp = Files.createTempDirectory("aggo-cli-prod")
        val task = TestTask(
            snapshotFile = tmp.resolve("snapshot.json"),
            migrationsDir = tmp.resolve("migrations"),
            envValue = "prod",
        )
        val ex = shouldThrow<IllegalStateException> { task.runFromArgs(arrayOf("drop")) }
        ex.message!! shouldContain "Refusing to drop"
    }

    "reset subcommand without --force is refused when environment=prod" {
        val tmp = Files.createTempDirectory("aggo-cli-prod2")
        val task = TestTask(
            snapshotFile = tmp.resolve("snapshot.json"),
            migrationsDir = tmp.resolve("migrations"),
            envValue = "prod",
        )
        val ex = shouldThrow<IllegalStateException> { task.runFromArgs(arrayOf("reset")) }
        ex.message!! shouldContain "Refusing to reset"
    }

    "argv with no known subcommand falls through to generate (back-compat)" {
        val tmp = Files.createTempDirectory("aggo-cli-backcompat")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        // "my_label" is not a subcommand → generate runs and treats it as the name.
        // Generation requires no DB access, so this works in unit tests.
        captureStdout { task.runFromArgs(arrayOf("my_label")) }
        val files = Files.list(tmp.resolve("migrations")).toList()
        files.size shouldBe 1
        files.single().fileName.toString() shouldContain "my_label"
    }

    "generate subcommand (explicit) accepts a name argument" {
        val tmp = Files.createTempDirectory("aggo-cli-explicit")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        captureStdout { task.runFromArgs(arrayOf("generate", "add_things")) }
        val files = Files.list(tmp.resolve("migrations")).toList()
        files.size shouldBe 1
        files.single().fileName.toString() shouldContain "add_things"
    }

    "generate accepts --name option for Gradle JavaExec wiring" {
        val tmp = Files.createTempDirectory("aggo-cli-name-option")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        captureStdout { task.runFromArgs(arrayOf("migrate", "generate", "--name", "add_widgets")) }
        val files = Files.list(tmp.resolve("migrations")).toList()
        files.size shouldBe 1
        files.single().fileName.toString() shouldContain "add_widgets"
    }

    "generate accepts --name=value option" {
        val tmp = Files.createTempDirectory("aggo-cli-name-equals")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        captureStdout { task.runFromArgs(arrayOf("generate", "--name=add_reports")) }
        val files = Files.list(tmp.resolve("migrations")).toList()
        files.size shouldBe 1
        files.single().fileName.toString() shouldContain "add_reports"
    }

    "gen alias accepts short -n option" {
        val tmp = Files.createTempDirectory("aggo-cli-gen-alias")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        captureStdout { task.runFromArgs(arrayOf("gen", "-n", "add_events")) }
        val files = Files.list(tmp.resolve("migrations")).toList()
        files.size shouldBe 1
        files.single().fileName.toString() shouldContain "add_events"
    }

    "migrate run requires --migration-file before DB access" {
        val tmp = Files.createTempDirectory("aggo-cli-migrate-run")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        val ex = shouldThrow<IllegalStateException> { task.runFromArgs(arrayOf("migrate", "run")) }
        ex.message!! shouldContain "--migration-file is required"
    }

    "migrate install-cli writes an executable Unix launcher using aggoCliRun by default" {
        val tmp = Files.createTempDirectory("aggo-cli-install-default")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val project = Files.createDirectories(tmp.resolve("project"))
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))

        captureStdout {
            task.runFromArgs(
                arrayOf(
                    "migrate",
                    "install-cli",
                    "--dir",
                    bin.toString(),
                    "--project-dir",
                    project.toString(),
                    "--runner",
                    "gradle",
                )
            )
        }

        val launcher = bin.resolve("aggo")
        Files.exists(launcher) shouldBe true
        launcher.toFile().canExecute() shouldBe true
        launcher.readText() shouldContain "start_dir=\"\${AGGO_PROJECT_DIR:-\$(pwd)}\""
        launcher.readText() shouldContain "find_project_root"
        launcher.readText() shouldContain "exec ./gradlew -q ':aggoCliRun' --args=\"\$*\""
        launcher.readText() shouldContain "exec gradle -q ':aggoCliRun' --args=\"\$*\""
    }

    "migrate install-cli auto-detects a Gradle root above the requested project directory" {
        val tmp = Files.createTempDirectory("aggo-cli-install-gradle-root")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val project = Files.createDirectories(tmp.resolve("project"))
        val srcDir = Files.createDirectories(project.resolve("infrastructure/src"))
        Files.writeString(project.resolve("build.gradle.kts"), "")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))

        captureStdout {
            task.runFromArgs(
                arrayOf(
                    "migrate",
                    "install-cli",
                    "--dir",
                    bin.toString(),
                    "--project-dir",
                    srcDir.toString(),
                )
            )
        }

        val launcher = bin.resolve("aggo")
        Files.exists(launcher) shouldBe true
        launcher.readText() shouldContain "start_dir=\"\${AGGO_PROJECT_DIR:-\$(pwd)}\""
        launcher.readText() shouldContain "exec gradle -q ':aggoCliRun' --args=\"\$*\""
    }

    "migrate install-cli allows overriding the Gradle task" {
        val tmp = Files.createTempDirectory("aggo-cli-install")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val project = Files.createDirectories(tmp.resolve("project"))
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))

        val out = captureStdout {
            task.runFromArgs(
                arrayOf(
                    "migrate",
                    "install-cli",
                    "--dir",
                    bin.toString(),
                    "--project-dir",
                    project.toString(),
                    "--runner",
                    "gradle",
                    "--gradle-task",
                    ":infrastructure:aggo",
                )
            )
        }

        val launcher = bin.resolve("aggo")
        Files.exists(launcher) shouldBe true
        launcher.toFile().canExecute() shouldBe true
        launcher.readText() shouldContain "start_dir=\"\${AGGO_PROJECT_DIR:-\$(pwd)}\""
        launcher.readText() shouldContain "exec ./gradlew -q ':infrastructure:aggo' --args=\"\$*\""
        launcher.readText() shouldContain "exec gradle -q ':infrastructure:aggo' --args=\"\$*\""
        out shouldContain "Installed Aggo CLI"
    }

    "environment defaults to dev when no env or system property is set" {
        val tmp = Files.createTempDirectory("aggo-cli-env")
        val task = TestTask(tmp.resolve("snapshot.json"), tmp.resolve("migrations"))
        task.environment shouldBe "dev"
    }
})
