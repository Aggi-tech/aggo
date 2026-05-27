package com.aggitech.aggo.dialect

import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.MigratableCodec
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

object MySqlDialect : MigrationDialect {
    private const val OFFSET_ONLY_LIMIT = "18446744073709551615"

    override fun placeholder(oneBasedIndex: Int): String = "?"

    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)
        return "`${name.replace("`", "``")}`"
    }

    override fun renderPagination(limit: Int?, offset: Int?): String = buildString {
        when {
            limit != null && offset != null -> append("LIMIT ").append(limit).append(" OFFSET ").append(offset)
            limit != null -> append("LIMIT ").append(limit)
            offset != null -> append("LIMIT ").append(OFFSET_ONLY_LIMIT).append(" OFFSET ").append(offset)
        }
    }

    override fun renderLikeIgnoreCase(operand: String, pattern: String, negated: Boolean): String =
        if (negated) "$operand NOT LIKE $pattern" else "$operand LIKE $pattern"

    override fun renderRegexMatch(
        operand: String,
        pattern: String,
        caseInsensitive: Boolean,
        negated: Boolean,
    ): String {
        val operator = if (negated) "NOT REGEXP" else "REGEXP"
        return "$operand $operator $pattern"
    }

    override fun insertReturnStrategy(primaryKeyColumns: List<Column<*, *>>): InsertReturnStrategy {
        require(primaryKeyColumns.isNotEmpty()) { "INSERT RETURNING requires at least one primary key column" }
        val firstPk = primaryKeyColumns.first()
        val columns = primaryKeyColumns.joinToString(", ") { quoteIdentifier(it.name) }
        val where = primaryKeyColumns.joinToString(" AND ") { "${quoteIdentifier(it.name)} = ?" }
        return InsertReturnStrategy.PostInsertSelect(
            "SELECT $columns FROM ${quoteIdentifier(firstPk.table.name)} WHERE $where",
        )
    }

    override fun columnSqlType(codec: Codec<*>): String {
        if (codec is MigratableCodec<*>) return codec.ddlTypeName
        return when (codec.sqlType) {
            String::class.java -> "LONGTEXT"
            Int::class.javaObjectType -> "INT"
            Long::class.javaObjectType -> "BIGINT"
            Short::class.javaObjectType -> "SMALLINT"
            Float::class.javaObjectType -> "FLOAT"
            Double::class.javaObjectType -> "DOUBLE"
            Boolean::class.javaObjectType -> "TINYINT(1)"
            BigDecimal::class.java -> "DECIMAL(38, 4)"
            OffsetDateTime::class.java -> "DATETIME"
            LocalDateTime::class.java -> "DATETIME"
            LocalDate::class.java -> "DATE"
            UUID::class.java -> "CHAR(36)"
            ByteArray::class.java -> "LONGBLOB"
            else -> throw UnsupportedOperationException(
                "No MySQL DDL type mapping for R2DBC driver type '${codec.sqlType.name}'. " +
                    "Implement MigratableCodec or use a supported built-in Codec.",
            )
        }
    }
}
