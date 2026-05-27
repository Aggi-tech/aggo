package com.aggitech.aggo.dialect

import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.MigratableCodec
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

object OracleDialect : MigrationDialect {
    override fun placeholder(oneBasedIndex: Int): String = ":$oneBasedIndex"

    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)
        return "\"${name.replace("\"", "\"\"")}\""
    }

    override fun renderPagination(limit: Int?, offset: Int?): String = buildString {
        offset?.let { append("OFFSET ").append(it).append(" ROWS") }
        limit?.let {
            if (isNotEmpty()) append(' ')
            append("FETCH NEXT ").append(it).append(" ROWS ONLY")
        }
    }

    override fun renderLikeIgnoreCase(operand: String, pattern: String, negated: Boolean): String {
        val expression = "REGEXP_LIKE($operand, $pattern, 'i')"
        return if (negated) "NOT $expression" else expression
    }

    override fun renderRegexMatch(
        operand: String,
        pattern: String,
        caseInsensitive: Boolean,
        negated: Boolean,
    ): String {
        val flags = if (caseInsensitive) ", 'i'" else ""
        val expression = "REGEXP_LIKE($operand, $pattern$flags)"
        return if (negated) "NOT $expression" else expression
    }

    override fun insertReturnStrategy(primaryKeyColumns: List<Column<*, *>>): InsertReturnStrategy {
        require(primaryKeyColumns.isNotEmpty()) { "INSERT RETURNING requires at least one primary key column" }
        val columns = primaryKeyColumns.joinToString(", ") { quoteIdentifier(it.name) }
        val outParams = primaryKeyColumns.mapIndexed { index, column ->
            InsertReturnStrategy.OutParam(":out${index + 1}", column.codec.sqlType)
        }
        return InsertReturnStrategy.ReturningInto(
            "RETURNING $columns INTO ${outParams.joinToString(", ") { it.bindName }}",
            outParams,
        )
    }

    override fun columnSqlType(codec: Codec<*>): String {
        if (codec is MigratableCodec<*>) return codec.ddlTypeName
        return when (codec.sqlType) {
            String::class.java -> "VARCHAR2(4000)"
            Int::class.javaObjectType -> "NUMBER(10)"
            Long::class.javaObjectType -> "NUMBER(19)"
            Short::class.javaObjectType -> "NUMBER(5)"
            Float::class.javaObjectType -> "FLOAT(24)"
            Double::class.javaObjectType -> "FLOAT(53)"
            Boolean::class.javaObjectType -> "NUMBER(1)"
            BigDecimal::class.java -> "NUMBER(38, 4)"
            OffsetDateTime::class.java -> "TIMESTAMP WITH TIME ZONE"
            LocalDateTime::class.java -> "TIMESTAMP"
            LocalDate::class.java -> "DATE"
            UUID::class.java -> "CHAR(36)"
            ByteArray::class.java -> "BLOB"
            else -> throw UnsupportedOperationException(
                "No Oracle DDL type mapping for R2DBC driver type '${codec.sqlType.name}'. " +
                    "Implement MigratableCodec or use a supported built-in Codec.",
            )
        }
    }
}
