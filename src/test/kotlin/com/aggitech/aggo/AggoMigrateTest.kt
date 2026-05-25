package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.AggoMigrate
import com.aggitech.aggo.migration.readMigrationFiles
import com.aggitech.aggo.migration.readSnapshot
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.r2dbc.spi.Row
import java.nio.file.Files

private object SampleTableA : Table<Unit>("sample_a") {
    val id = column("id", IntCodec, isPrimaryKey = true) { null }
    override fun fromRow(row: Row): Unit = Unit
}

private object SampleTableB : Table<Unit>("sample_b") {
    val id = column("id", IntCodec, isPrimaryKey = true) { null }
    val label = column("label", StringCodec) { null }
    override fun fromRow(row: Row): Unit = Unit
}

class AggoMigrateTest : StringSpec({

    fun tempDirs() = Pair(
        Files.createTempDirectory("aggo-codegen-snapshot"),
        Files.createTempDirectory("aggo-codegen-migrations"),
    )

    "generate creates snapshot and migration file on first run" {
        val (snapshotDir, migrationsDir) = tempDirs()
        val snapshotFile = snapshotDir.resolve("snapshot.json")

        AggoMigrate.generate(
            tables = listOf(SampleTableA),
            dialect = PostgresDialect,
            snapshotFile = snapshotFile,
            migrationsDir = migrationsDir,
        )

        val snapshot = readSnapshot(snapshotFile)
        snapshot shouldNotBe null
        snapshot!!.tables.map { it.name } shouldBe listOf("sample_a")

        val files = readMigrationFiles(migrationsDir)
        files shouldHaveSize 1
        files.single().sql shouldContain "CREATE TABLE"
        files.single().sql shouldContain "sample_a"
        files.single().fromVersion shouldBe null
    }

    "generate prints 'No changes detected' when tables match snapshot" {
        val (snapshotDir, migrationsDir) = tempDirs()
        val snapshotFile = snapshotDir.resolve("snapshot.json")

        AggoMigrate.generate(listOf(SampleTableA), PostgresDialect, snapshotFile, migrationsDir)
        val firstCount = readMigrationFiles(migrationsDir).size

        // Second call with same tables — no new file
        AggoMigrate.generate(listOf(SampleTableA), PostgresDialect, snapshotFile, migrationsDir)
        readMigrationFiles(migrationsDir).size shouldBe firstCount
    }

    "generate detects added table and writes migration with fromVersion" {
        val (snapshotDir, migrationsDir) = tempDirs()
        val snapshotFile = snapshotDir.resolve("snapshot.json")

        AggoMigrate.generate(listOf(SampleTableA), PostgresDialect, snapshotFile, migrationsDir)
        val firstVersion = readSnapshot(snapshotFile)!!.version

        AggoMigrate.generate(listOf(SampleTableA, SampleTableB), PostgresDialect, snapshotFile, migrationsDir)

        val files = readMigrationFiles(migrationsDir)
        files shouldHaveSize 2
        val second = files.last()
        second.fromVersion shouldBe firstVersion
        second.sql shouldContain "sample_b"
    }

    "generate uses migrationName suffix in version" {
        val (snapshotDir, migrationsDir) = tempDirs()
        val snapshotFile = snapshotDir.resolve("snapshot.json")

        AggoMigrate.generate(
            tables = listOf(SampleTableA),
            dialect = PostgresDialect,
            snapshotFile = snapshotFile,
            migrationsDir = migrationsDir,
            migrationName = "initial_setup",
        )

        val file = readMigrationFiles(migrationsDir).single()
        file.version shouldContain "_initial_setup"
    }

    "generate throws on invalid migrationName characters" {
        val (snapshotDir, migrationsDir) = tempDirs()
        shouldThrow<IllegalArgumentException> {
            AggoMigrate.generate(
                tables = listOf(SampleTableA),
                dialect = PostgresDialect,
                snapshotFile = snapshotDir.resolve("s.json"),
                migrationsDir = migrationsDir,
                migrationName = "bad name!",
            )
        }
    }

    "generate updates snapshot version after each run" {
        val (snapshotDir, migrationsDir) = tempDirs()
        val snapshotFile = snapshotDir.resolve("snapshot.json")

        AggoMigrate.generate(listOf(SampleTableA), PostgresDialect, snapshotFile, migrationsDir)
        val v1 = readSnapshot(snapshotFile)!!.version

        Thread.sleep(1100) // ensure timestamp differs

        AggoMigrate.generate(listOf(SampleTableA, SampleTableB), PostgresDialect, snapshotFile, migrationsDir)
        val v2 = readSnapshot(snapshotFile)!!.version

        v2 shouldNotBe v1
    }

    "generate writes snapshot even when no migrationName is given" {
        val (snapshotDir, migrationsDir) = tempDirs()
        val snapshotFile = snapshotDir.resolve("snapshot.json")

        readSnapshot(snapshotFile) shouldBe null
        AggoMigrate.generate(listOf(SampleTableA), PostgresDialect, snapshotFile, migrationsDir)
        readSnapshot(snapshotFile) shouldNotBe null
    }
})
