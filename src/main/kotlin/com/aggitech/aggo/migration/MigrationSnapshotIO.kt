package com.aggitech.aggo.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Writes [schema] to [file] as a JSON snapshot, overwriting any existing content.
 * Parent directories are created if absent.
 */
fun writeSnapshot(schema: MigrationSchema, file: Path) {
    Files.createDirectories(file.parent ?: file.fileSystem.getPath("."))
    file.writeText(schema.toJson(), Charsets.UTF_8)
}

/**
 * Reads the snapshot from [file]. Returns `null` when the file does not exist
 * (first-run case — no previous snapshot yet).
 */
fun readSnapshot(file: Path): MigrationSchema? {
    if (!Files.exists(file)) return null
    return migrationSchemaFromJson(file.readText(Charsets.UTF_8))
}

// ---------------------------------------------------------------------------
// Serialization
// ---------------------------------------------------------------------------

internal fun MigrationSchema.toJson(): String = buildString {
    appendLine("{")
    appendLine("  \"version\": ${jsonString(version)},")
    appendLine("  \"tables\": [")
    tables.forEachIndexed { ti, table ->
        appendLine("    {")
        appendLine("      \"name\": ${jsonString(table.name)},")
        appendLine("      \"columns\": [")
        table.columns.forEachIndexed { ci, col ->
            val comma = if (ci < table.columns.lastIndex) "," else ""
            appendLine("        {\"name\":${jsonString(col.name)},\"sqlType\":${jsonString(col.sqlType)},\"nullable\":${col.nullable},\"generated\":${col.generated}}$comma")
        }
        appendLine("      ],")
        appendLine("      \"checks\": [")
        table.checks.forEachIndexed { ci, chk ->
            val comma = if (ci < table.checks.lastIndex) "," else ""
            appendLine("        {\"name\":${jsonString(chk.name)},\"expression\":${jsonString(chk.expression)}}$comma")
        }
        appendLine("      ],")
        append("      \"primaryKey\": [")
        append(table.primaryKey.joinToString(",") { jsonString(it) })
        appendLine("]")
        val tableComma = if (ti < tables.lastIndex) "," else ""
        appendLine("    }$tableComma")
    }
    appendLine("  ]")
    append("}")
}

private fun jsonString(s: String): String = buildString {
    append('"')
    for (ch in s) {
        when (ch) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

// ---------------------------------------------------------------------------
// Deserialization — minimal recursive-descent JSON reader
// ---------------------------------------------------------------------------

internal fun migrationSchemaFromJson(json: String): MigrationSchema {
    val reader = JsonReader(json)
    val root = reader.readObject()

    val version = root["version"] as? String
        ?: throw IllegalArgumentException("snapshot JSON missing 'version'")

    @Suppress("UNCHECKED_CAST")
    val tablesRaw = root["tables"] as? List<*>
        ?: throw IllegalArgumentException("snapshot JSON missing 'tables'")

    val tables = tablesRaw.map { tableRaw ->
        @Suppress("UNCHECKED_CAST")
        val t = tableRaw as Map<String, Any>

        val name = t["name"] as? String
            ?: throw IllegalArgumentException("table entry missing 'name'")

        @Suppress("UNCHECKED_CAST")
        val columnsRaw = t["columns"] as? List<*>
            ?: throw IllegalArgumentException("table '$name' missing 'columns'")

        val columns = columnsRaw.map { colRaw ->
            @Suppress("UNCHECKED_CAST")
            val c = colRaw as Map<String, Any>
            MigrationColumn(
                name = c["name"] as? String ?: throw IllegalArgumentException("column missing 'name'"),
                sqlType = c["sqlType"] as? String ?: throw IllegalArgumentException("column missing 'sqlType'"),
                nullable = c["nullable"] as? Boolean ?: false,
                generated = c["generated"] as? Boolean ?: false,
            )
        }

        @Suppress("UNCHECKED_CAST")
        val checksRaw = t["checks"] as? List<*> ?: emptyList<Any>()
        val checks = checksRaw.map { chkRaw ->
            @Suppress("UNCHECKED_CAST")
            val c = chkRaw as Map<String, Any>
            MigrationCheck(
                name = c["name"] as? String ?: throw IllegalArgumentException("check missing 'name'"),
                expression = c["expression"] as? String ?: throw IllegalArgumentException("check missing 'expression'"),
            )
        }

        @Suppress("UNCHECKED_CAST")
        val primaryKey = (t["primaryKey"] as? List<*> ?: emptyList<Any>())
            .filterIsInstance<String>()

        MigrationTable(name = name, columns = columns, checks = checks, primaryKey = primaryKey)
    }

    return MigrationSchema(version = version, tables = tables)
}

private class JsonReader(private val src: String, private var pos: Int = 0) {

    fun readValue(): Any? {
        skipWhitespace()
        return when {
            pos >= src.length -> throw IllegalArgumentException("Unexpected end of JSON at pos $pos")
            src[pos] == '"'   -> readString()
            src[pos] == '{'   -> readObject()
            src[pos] == '['   -> readArray()
            src.startsWith("true", pos)  -> { pos += 4; true }
            src.startsWith("false", pos) -> { pos += 5; false }
            src.startsWith("null", pos)  -> { pos += 4; null }
            else -> throw IllegalArgumentException("Unexpected char '${src[pos]}' at pos $pos")
        }
    }

    fun readObject(): Map<String, Any> {
        expect('{')
        val map = mutableMapOf<String, Any>()
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') { pos++; return map }
        while (pos < src.length) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            val value = readValue()
            if (value != null) map[key] = value
            skipWhitespace()
            if (pos >= src.length) break
            when (src[pos]) {
                ',' -> { pos++; continue }
                '}' -> { pos++; break }
                else -> throw IllegalArgumentException("Expected ',' or '}' at pos $pos")
            }
        }
        return map
    }

    fun readArray(): List<Any> {
        expect('[')
        val list = mutableListOf<Any>()
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') { pos++; return list }
        while (pos < src.length) {
            val value = readValue()
            if (value != null) list += value
            skipWhitespace()
            if (pos >= src.length) break
            when (src[pos]) {
                ',' -> { pos++; continue }
                ']' -> { pos++; break }
                else -> throw IllegalArgumentException("Expected ',' or ']' at pos $pos")
            }
        }
        return list
    }

    fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < src.length) {
            when (val ch = src[pos++]) {
                '"'  -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw IllegalArgumentException("Unexpected end in escape")
                    when (val esc = src[pos++]) {
                        '"'  -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/'  -> sb.append('/')
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        'b'  -> sb.append('\b')
                        'f'  -> sb.append('')
                        'u'  -> {
                            val hex = src.substring(pos, (pos + 4).coerceAtMost(src.length))
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Unknown escape \\$esc at pos $pos")
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun expect(ch: Char) {
        skipWhitespace()
        if (pos >= src.length || src[pos] != ch)
            throw IllegalArgumentException("Expected '$ch' at pos $pos, got '${src.getOrNull(pos)}'")
        pos++
    }
}
