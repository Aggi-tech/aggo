package com.aggitech.aggo.render

import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.query.Assignment
import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column

/** Render a SELECT — currently `SELECT * FROM table [WHERE ...] [ORDER BY ...] [LIMIT/OFFSET]`. */
fun renderSelect(query: Select<*>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.quoteIdentifier(query.table.name)

    val columns = query.table.columns
        .joinToString(", ") { dialect.quoteIdentifier(it.name) }
        .ifEmpty { "*" }

    val sql = buildString {
        append("SELECT ").append(columns)
        append(" FROM ").append(table)

        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
        if (query.orderBy.isNotEmpty()) {
            append(" ORDER BY ")
            append(query.orderBy.joinToString(", ") { o ->
                val col = "${dialect.quoteIdentifier(o.column.table.name)}.${dialect.quoteIdentifier(o.column.name)}"
                "$col ${o.direction.name}"
            })
        }
        query.limit?.let { append(" LIMIT ").append(it) }
        query.offset?.let { append(" OFFSET ").append(it) }
    }

    return RenderedSql(sql, ctx.params)
}

fun renderJoinSelect(query: JoinSelect<*, *>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val leftTable = dialect.quoteIdentifier(query.leftTable.name)
    val rightTable = dialect.quoteIdentifier(query.rightTable.name)
    val columns = (query.leftTable.columns + query.rightTable.columns)
        .joinToString(", ") { renderQualifiedColumn(it, dialect) }
        .ifEmpty { "*" }

    val sql = buildString {
        append("SELECT ").append(columns)
        append(" FROM ").append(leftTable)
        append(" ").append(query.join.type.sql)
        append(" ").append(rightTable)
        append(" ON ").append(PredicateRenderer.render(query.join.on, ctx))

        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
        if (query.orderBy.isNotEmpty()) {
            append(" ORDER BY ")
            append(query.orderBy.joinToString(", ") { o ->
                "${renderQualifiedColumn(o.column, dialect)} ${o.direction.name}"
            })
        }
        query.limit?.let { append(" LIMIT ").append(it) }
        query.offset?.let { append(" OFFSET ").append(it) }
    }

    return RenderedSql(sql, ctx.params)
}

fun renderInsert(query: Insert<*>, dialect: SqlDialect, returningPk: Boolean = false): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.quoteIdentifier(query.table.name)

    val cols = query.assignments.joinToString(", ") { dialect.quoteIdentifier(it.column.name) }
    val placeholders = query.assignments.joinToString(", ") { bindAssignment(it, ctx) }

    val sql = buildString {
        append("INSERT INTO ").append(table)
        append(" (").append(cols).append(")")
        append(" VALUES (").append(placeholders).append(")")

        if (returningPk) {
            val pks = query.table.primaryKeys
            require(pks.isNotEmpty()) { "RETURNING requested but table '${query.table.name}' has no primary key" }
            append(" RETURNING ")
            append(pks.joinToString(", ") { dialect.quoteIdentifier(it.name) })
        }
    }
    return RenderedSql(sql, ctx.params)
}

fun renderUpdate(query: Update<*>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.quoteIdentifier(query.table.name)

    val setClause = query.assignments.joinToString(", ") { a ->
        "${dialect.quoteIdentifier(a.column.name)} = ${bindAssignment(a, ctx)}"
    }

    val sql = buildString {
        append("UPDATE ").append(table).append(" SET ").append(setClause)
        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
    }
    return RenderedSql(sql, ctx.params)
}

fun renderDelete(query: Delete<*>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.quoteIdentifier(query.table.name)

    val sql = buildString {
        append("DELETE FROM ").append(table)
        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
    }
    return RenderedSql(sql, ctx.params)
}

@Suppress("UNCHECKED_CAST")
private fun <V> bindAssignment(a: Assignment<*, V>, ctx: RenderContext): String =
    ctx.bind(a.value, a.codec as Codec<V>)

private fun renderQualifiedColumn(column: Column<*, *>, dialect: SqlDialect): String =
    "${dialect.quoteIdentifier(column.table.name)}.${dialect.quoteIdentifier(column.name)}"
