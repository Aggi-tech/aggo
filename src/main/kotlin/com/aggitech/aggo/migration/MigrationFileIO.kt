package com.aggitech.aggo.migration

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val HDR_VERSION = "aggo:version"
private const val HDR_FROM = "aggo:from-version"
private const val HDR_AT = "aggo:generated-at"
private const val HDR_CHECKSUM = "aggo:checksum"
private const val FROM_NONE = "none"

/**
 * An immutable, parsed representation of a generated `.sql` migration file.
 *
 * The [sql] field contains only the DDL body — header comment lines are stripped.
 * The [checksum] is `sha256:<hex>` covering `"$sql|$generatedAt"`.
 */
data class MigrationFileEntry(
    val version: String,
    val fromVersion: String?,
    val generatedAt: Instant,
    val checksum: String,
    val sql: String,
)

/**
 * Computes the SHA-256 checksum used for migration integrity verification.
 *
 * Input is `"$sqlBody|$generatedAt"` so the header itself is excluded (the
 * checksum is embedded in the header, so it can't cover the header).
 */
internal fun computeChecksum(sqlBody: String, generatedAt: Instant): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$sqlBody|$generatedAt"
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Writes a migration file as `{version}.sql` under [outputDir].
 *
 * If the target filename already exists a numeric suffix is appended (`_2`,
 * `_3`, …) to avoid collisions when `generate` is called twice within the
 * same second.
 *
 * Returns the [Path] of the created file.
 */
fun writeMigrationFile(entry: MigrationFileEntry, outputDir: Path): Path {
    Files.createDirectories(outputDir)

    val baseName = entry.version
    var candidate = outputDir.resolve("$baseName.sql")
    var counter = 2
    while (Files.exists(candidate)) {
        candidate = outputDir.resolve("${baseName}_$counter.sql")
        counter++
    }

    val fromValue = entry.fromVersion ?: FROM_NONE
    val content = buildString {
        appendLine("-- $HDR_VERSION=${entry.version}")
        appendLine("-- $HDR_FROM=$fromValue")
        appendLine("-- $HDR_AT=${entry.generatedAt}")
        appendLine("-- $HDR_CHECKSUM=${entry.checksum}")
        appendLine()
        append(entry.sql)
    }
    candidate.writeText(content, Charsets.UTF_8)
    return candidate
}

/**
 * Reads and verifies all `*.sql` migration files from [dir], sorted
 * lexicographically by filename (which equals version-order given the
 * timestamp-based naming scheme).
 *
 * Returns an empty list if [dir] does not exist.
 */
fun readMigrationFiles(dir: Path): List<MigrationFileEntry> {
    if (!Files.exists(dir)) return emptyList()
    return Files.list(dir)
        .filter { it.extension == "sql" }
        .sorted(Comparator.comparing { it.nameWithoutExtension })
        .map { parseMigrationFile(it) }
        .toList()
}

/**
 * Parses a single `.sql` migration file and verifies its checksum.
 *
 * The file must begin with a block of `-- aggo:key=value` header lines
 * followed by a blank line, then the SQL body. Any missing required header
 * causes an [IllegalStateException] with a descriptive message naming the
 * file and the missing field.
 */
internal fun parseMigrationFile(path: Path): MigrationFileEntry {
    val text = path.readText(Charsets.UTF_8)
    val lines = text.lines()

    val headers = mutableMapOf<String, String>()
    var bodyStart = 0
    for ((i, line) in lines.withIndex()) {
        when {
            line.startsWith("-- aggo:") -> {
                val eq = line.indexOf('=')
                if (eq >= 0) headers[line.substring(3, eq)] = line.substring(eq + 1)
            }
            line.isBlank() && headers.isNotEmpty() -> {
                bodyStart = i + 1
                break
            }
        }
    }

    fun require(key: String): String = headers[key]
        ?: error("Missing required header '$key' in $path")

    val version = require(HDR_VERSION)
    val fromRaw = require(HDR_FROM)
    val fromVersion = if (fromRaw == FROM_NONE) null else fromRaw
    val generatedAt = runCatching { Instant.parse(require(HDR_AT)) }
        .getOrElse { error("Invalid '$HDR_AT' value in $path: ${it.message}") }
    val storedChecksum = require(HDR_CHECKSUM)

    val sqlBody = lines.drop(bodyStart).joinToString("\n").trimEnd('\n')

    val computed = computeChecksum(sqlBody, generatedAt)
    check(computed == storedChecksum) {
        "Checksum mismatch in $path: stored=$storedChecksum computed=$computed"
    }

    return MigrationFileEntry(
        version = version,
        fromVersion = fromVersion,
        generatedAt = generatedAt,
        checksum = storedChecksum,
        sql = sqlBody,
    )
}
