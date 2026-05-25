package com.aggitech.aggo

import com.aggitech.aggo.migration.MigrationFileEntry
import com.aggitech.aggo.migration.computeChecksum
import com.aggitech.aggo.migration.parseMigrationFile
import com.aggitech.aggo.migration.readMigrationFiles
import com.aggitech.aggo.migration.writeMigrationFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MigrationFileIOTest : StringSpec({

    val sampleSql = "CREATE TABLE \"payers\" (\n    \"id\" UUID NOT NULL,\n    PRIMARY KEY (\"id\")\n);"
    val sampleAt = Instant.parse("2026-05-25T10:30:45Z")

    fun sampleEntry(version: String = "2026.05.25.103045", fromVersion: String? = null) = MigrationFileEntry(
        version = version,
        fromVersion = fromVersion,
        generatedAt = sampleAt,
        checksum = computeChecksum(sampleSql, sampleAt),
        sql = sampleSql,
    )

    "computeChecksum is deterministic for same inputs" {
        computeChecksum(sampleSql, sampleAt) shouldBe computeChecksum(sampleSql, sampleAt)
    }

    "computeChecksum starts with sha256:" {
        computeChecksum(sampleSql, sampleAt) shouldStartWith "sha256:"
    }

    "computeChecksum differs for different SQL" {
        computeChecksum(sampleSql, sampleAt) shouldNotBe computeChecksum("SELECT 1", sampleAt)
    }

    "computeChecksum differs for different generatedAt" {
        val other = Instant.parse("2026-05-25T11:00:00Z")
        computeChecksum(sampleSql, sampleAt) shouldNotBe computeChecksum(sampleSql, other)
    }

    "writeMigrationFile creates a file named {version}.sql" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val entry = sampleEntry()
        val path = writeMigrationFile(entry, dir)
        path.fileName.toString() shouldBe "${entry.version}.sql"
        Files.exists(path) shouldBe true
    }

    "writeMigrationFile header contains all required fields" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val entry = sampleEntry(fromVersion = "2026.05.25.091200")
        val path = writeMigrationFile(entry, dir)
        val content = path.readText()
        content shouldContain "-- aggo:version=${entry.version}"
        content shouldContain "-- aggo:from-version=2026.05.25.091200"
        content shouldContain "-- aggo:generated-at=${entry.generatedAt}"
        content shouldContain "-- aggo:checksum=${entry.checksum}"
    }

    "writeMigrationFile uses 'none' for null fromVersion" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val path = writeMigrationFile(sampleEntry(fromVersion = null), dir)
        path.readText() shouldContain "-- aggo:from-version=none"
    }

    "writeMigrationFile appends _2 on version collision" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val entry = sampleEntry()
        val first = writeMigrationFile(entry, dir)
        val second = writeMigrationFile(entry, dir)
        first.fileName.toString() shouldBe "${entry.version}.sql"
        second.fileName.toString() shouldBe "${entry.version}_2.sql"
    }

    "parseMigrationFile round-trips through writeMigrationFile" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val entry = sampleEntry(fromVersion = "2026.05.24.000000")
        val path = writeMigrationFile(entry, dir)
        val parsed = parseMigrationFile(path)
        parsed shouldBe entry
    }

    "parseMigrationFile round-trips with null fromVersion" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val entry = sampleEntry(fromVersion = null)
        val path = writeMigrationFile(entry, dir)
        val parsed = parseMigrationFile(path)
        parsed.fromVersion shouldBe null
        parsed shouldBe entry
    }

    "parseMigrationFile throws on checksum mismatch (tampered SQL)" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val path = writeMigrationFile(sampleEntry(), dir)
        val tampered = path.readText().replace("PRIMARY KEY", "UNIQUE")
        path.writeText(tampered)
        shouldThrow<IllegalStateException> {
            parseMigrationFile(path)
        }.message shouldContain "Checksum mismatch"
    }

    "parseMigrationFile throws with descriptive message on missing header field" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val path = dir.resolve("bad.sql")
        path.writeText("-- aggo:version=2026.05.25.001\n\nSELECT 1;")
        shouldThrow<IllegalStateException> {
            parseMigrationFile(path)
        }.message shouldContain "aggo:from-version"
    }

    "readMigrationFiles returns empty list when directory does not exist" {
        val dir = Files.createTempDirectory("aggo-fileio").resolve("nonexistent")
        readMigrationFiles(dir) shouldBe emptyList()
    }

    "readMigrationFiles returns files sorted lexicographically" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val versions = listOf("2026.05.25.120000", "2026.05.24.080000", "2026.05.25.090000")
        versions.forEach { v ->
            val at = sampleAt
            val e = MigrationFileEntry(v, null, at, computeChecksum(sampleSql, at), sampleSql)
            writeMigrationFile(e, dir)
        }
        val entries = readMigrationFiles(dir)
        entries.map { it.version } shouldBe versions.sorted()
    }

    "readMigrationFiles verifies all checksums on read" {
        val dir = Files.createTempDirectory("aggo-fileio")
        val path = writeMigrationFile(sampleEntry(), dir)
        val tampered = path.readText().replace("PRIMARY KEY", "UNIQUE")
        path.writeText(tampered)
        shouldThrow<IllegalStateException> {
            readMigrationFiles(dir)
        }
    }
})
