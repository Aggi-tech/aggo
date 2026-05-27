package com.aggitech.aggo.render

import com.aggitech.aggo.dialect.InsertReturnStrategy
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.query.AggregateSelect
import com.aggitech.aggo.query.Assignment
import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Expr
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.ProjectionSelect
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column

/**
 * Render a SELECT — `SELECT col, col FROM table [WHERE ...] [ORDER BY ...] [LIMIT/OFFSET]`.
 *
 * [limitOverride] (P-3) lets [com.aggitech.aggo.runtime.Session.fetchOne]
 * force `LIMIT 1` without allocating a `Select.copy(limit = 1)`.
 */
fun renderSelect(query: Select<*>, dialect: SqlDialect, limitOverride: Int? = null): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.qualifyTableName(query.table.name)

    val columns = query.table.columns
        .joinToString(", ") { dialect.quoteIdentifier(it.name) }
        .ifEmpty { "*" }

    // P-1: estimate ~64 base + 16 chars per column + 24 per param slot.
    val estimate = 64 + columns.length + query.table.columns.size * 16
    val effectiveLimit = limitOverride ?: query.limit

    val sql = buildString(estimate) {
        append("SELECT ").append(columns)
        append(" FROM ").append(table)

        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
        if (query.orderBy.isNotEmpty()) {
            append(" ORDER BY ")
            append(query.orderBy.joinToString(", ") { o ->
                val col = "${dialect.qualifyTableName(o.column.table.name)}.${dialect.quoteIdentifier(o.column.name)}"
                "$col ${o.direction.name}"
            })
        }
        appendPagination(dialect, effectiveLimit, query.offset)
    }

    return RenderedSql(sql, ctx.params)
}

/** Render `SELECT COUNT(*)` for the filtered row set of a [Select]. */
fun renderCountSelect(query: Select<*>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.qualifyTableName(query.table.name)

    val sql = buildString(48 + table.length) {
        append("SELECT COUNT(*)")
        append(" FROM ").append(table)
        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
    }

    return RenderedSql(sql, ctx.params)
}

fun renderJoinSelect(query: JoinSelect<*, *>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val leftTable = dialect.qualifyTableName(query.leftTable.name)
    val rightTable = dialect.qualifyTableName(query.rightTable.name)
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
        appendPagination(dialect, query.limit, query.offset)
    }

    return RenderedSql(sql, ctx.params)
}

fun renderInsert(query: Insert<*>, dialect: SqlDialect, returningPk: Boolean = false): RenderedInsert {
    val ctx = RenderContext(dialect)
    val table = dialect.qualifyTableName(query.table.name)

    val cols = query.assignments.joinToString(", ") { dialect.quoteIdentifier(it.column.name) }
    val placeholders = query.assignments.joinToString(", ") { bindAssignment(it, ctx) }

    // P-1: rough size estimate
    val baseSql = buildString(48 + table.length + cols.length + placeholders.length) {
        append("INSERT INTO ").append(table)
        append(" (").append(cols).append(")")
        append(" VALUES (").append(placeholders).append(")")
    }

    if (!returningPk) return RenderedInsert(baseSql, ctx.params, null)

    val pks = query.table.primaryKeys
    require(pks.isNotEmpty()) { "RETURNING requested but table '${query.table.name}' has no primary key" }
    val strategy = dialect.insertReturnStrategy(pks)
    val sql = when (strategy) {
        is InsertReturnStrategy.AppendClause -> "$baseSql ${strategy.clause}"
        is InsertReturnStrategy.ReturningInto -> "$baseSql ${strategy.clause}"
        is InsertReturnStrategy.PostInsertSelect -> baseSql
    }
    return RenderedInsert(sql, ctx.params, strategy)
}

fun renderUpdate(query: Update<*>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.qualifyTableName(query.table.name)

    val setClause = query.assignments.joinToString(", ") { a ->
        "${dialect.quoteIdentifier(a.column.name)} = ${bindAssignment(a, ctx)}"
    }

    val sql = buildString(32 + table.length + setClause.length) {
        append("UPDATE ").append(table).append(" SET ").append(setClause)
        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
    }
    return RenderedSql(sql, ctx.params)
}

fun renderDelete(query: Delete<*>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.qualifyTableName(query.table.name)

    val sql = buildString(32 + table.length) {
        append("DELETE FROM ").append(table)
        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }
    }
    return RenderedSql(sql, ctx.params)
}

/**
 * Render an [AggregateSelect] — `SELECT expr AS alias, ... FROM table
 * [WHERE ...] [GROUP BY ...] [HAVING ...] [ORDER BY ...] [LIMIT/OFFSET]`.
 *
 * Each projection renders as `expr AS "alias"` using the column alias registered via
 * `expr `as` "alias"`. Results are decoded through [com.aggitech.aggo.runtime.AggRow].
 */
fun renderAggregateSelect(query: AggregateSelect<*>, dialect: SqlDialect): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.qualifyTableName(query.table.name)

    val projections = query.projections.joinToString(", ") { ne ->
        "${PredicateRenderer.renderOperand(ne.expr.operand, ctx)} AS ${dialect.quoteIdentifier(ne.label)}"
    }

    val sql = buildString {
        append("SELECT ").append(projections)
        append(" FROM ").append(table)

        query.where?.let {
            append(" WHERE ").append(PredicateRenderer.render(it, ctx))
        }

        if (query.groupBy.isNotEmpty()) {
            append(" GROUP BY ")
            append(query.groupBy.joinToString(", ") { col ->
                "${dialect.qualifyTableName(col.table.name)}.${dialect.quoteIdentifier(col.name)}"
            })
        }

        query.having?.let {
            append(" HAVING ").append(PredicateRenderer.render(it, ctx))
        }

        if (query.orderBy.isNotEmpty()) {
            append(" ORDER BY ")
            append(query.orderBy.joinToString(", ") { o ->
                "${PredicateRenderer.renderOperand(o.expr.operand, ctx)} ${o.direction.name}"
            })
        }

        appendPagination(dialect, query.limit, query.offset)
    }

    return RenderedSql(sql, ctx.params)
}

/**
 * Render a partial-column SELECT —
 * `SELECT col1, col2 FROM table [WHERE ...] [ORDER BY ...] [LIMIT/OFFSET]`.
 *
 * Only the columns listed in [ProjectionSelect.columns] appear in the SELECT list.
 * [limitOverride] lets [com.aggitech.aggo.runtime.Session.fetchOneProjection] force `LIMIT 1`.
 */
fun renderProjectionSelect(
    query: ProjectionSelect<*>,
    dialect: SqlDialect,
    limitOverride: Int? = null,
): RenderedSql {
    val ctx = RenderContext(dialect)
    val table = dialect.qualifyTableName(query.table.name)
    val effectiveLimit = limitOverride ?: query.limit

    val columns = query.columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }

    val sql = buildString(64 + columns.length) {
        append("SELECT ").append(columns)
        append(" FROM ").append(table)
        query.where?.let { append(" WHERE ").append(PredicateRenderer.render(it, ctx)) }
        if (query.orderBy.isNotEmpty()) {
            append(" ORDER BY ")
            append(query.orderBy.joinToString(", ") { o ->
                val col = "${dialect.qualifyTableName(o.column.table.name)}.${dialect.quoteIdentifier(o.column.name)}"
                "$col ${o.direction.name}"
            })
        }
        appendPagination(dialect, effectiveLimit, query.offset)
    }

    return RenderedSql(sql, ctx.params)
}

/** Renders a single [Expr] to SQL string using the given [RenderContext]. */
fun renderExpr(expr: Expr<*>, ctx: RenderContext): String =
    PredicateRenderer.renderOperand(expr.operand, ctx)

private fun <V> bindAssignment(a: Assignment<*, V>, ctx: RenderContext): String =
    ctx.bind(a.value, a.codec, a.column)

private fun renderQualifiedColumn(column: Column<*, *>, dialect: SqlDialect): String =
    "${dialect.qualifyTableName(column.table.name)}.${dialect.quoteIdentifier(column.name)}"

private fun StringBuilder.appendPagination(dialect: SqlDialect, limit: Int?, offset: Int?) {
    val pagination = dialect.renderPagination(limit, offset)
    if (pagination.isNotEmpty()) append(' ').append(pagination)
}
