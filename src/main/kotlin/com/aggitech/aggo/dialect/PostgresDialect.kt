package com.aggitech.aggo.dialect

import com.aggitech.aggo.schema.Codec
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

object PostgresDialect : MigrationDialect {
    override fun placeholder(oneBasedIndex: Int): String = "\$$oneBasedIndex"

    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)
        // The regex above already excludes the double-quote character, so the
        // doubling is defense-in-depth in case the validator is ever loosened.
        val escaped = name.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * Maps an R2DBC driver type ([Codec.sqlType]) to a Postgres DDL column type.
     *
     * The mapping is intentionally closed — only types with a known,
     * unambiguous Postgres equivalent are listed. [ValueClassCodec] delegates
     * [Codec.sqlType] to its raw codec, so domain value classes (e.g. `Email`,
     * `UserId`) resolve automatically via their underlying primitive codec.
     *
     * [InstantCodec] uses [OffsetDateTime] as its driver type because the
     * Postgres R2DBC driver does not accept [java.time.Instant] directly;
     * that driver type maps to `TIMESTAMPTZ`.
     */
    override fun columnSqlType(codec: Codec<*>): String = when (codec.sqlType) {
        String::class.java              -> "TEXT"
        Int::class.javaObjectType       -> "INTEGER"
        Long::class.javaObjectType      -> "BIGINT"
        Short::class.javaObjectType     -> "SMALLINT"
        Double::class.javaObjectType    -> "DOUBLE PRECISION"
        Boolean::class.javaObjectType   -> "BOOLEAN"
        BigDecimal::class.java          -> "NUMERIC"
        OffsetDateTime::class.java      -> "TIMESTAMPTZ"
        LocalDateTime::class.java       -> "TIMESTAMP"
        LocalDate::class.java           -> "DATE"
        UUID::class.java                -> "UUID"
        ByteArray::class.java           -> "BYTEA"
        else -> throw UnsupportedOperationException(
            "No Postgres DDL type mapping for R2DBC driver type '${codec.sqlType.name}'. " +
            "Provide a custom MigrationDialect subclass or use a supported built-in Codec."
        )
    }
}
