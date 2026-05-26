package com.aggitech.aggo.render

import com.aggitech.aggo.query.Operand
import com.aggitech.aggo.query.Predicate

internal object PredicateRenderer {

    fun render(predicate: Predicate, ctx: RenderContext): String = when (predicate) {
        is Predicate.Cmp -> {
            // V-6: when one side is a column reference and the other is a
            // literal, attribute the bound parameter to that column so log
            // redaction can honour Column.sensitive.
            val sourceColumn = predicate.left.columnRef() ?: predicate.right.columnRef()
            val left = renderOperand(predicate.left, ctx, sourceColumn)
            val right = renderOperand(predicate.right, ctx, sourceColumn)
            "$left ${predicate.op.symbol} $right"
        }

        is Predicate.Like -> {
            val sourceColumn = predicate.operand.columnRef()
            val operand = renderOperand(predicate.operand, ctx, sourceColumn)
            val placeholder = ctx.bind(predicate.pattern, com.aggitech.aggo.schema.StringCodec, sourceColumn)
            val op = if (predicate.negated) "NOT LIKE" else "LIKE"
            "$operand $op $placeholder"
        }

        is Predicate.In<*> -> renderIn(predicate, ctx)

        is Predicate.Between<*> -> renderBetween(predicate, ctx)

        is Predicate.IsNull -> {
            val operand = renderOperand(predicate.operand, ctx)
            if (predicate.negated) "$operand IS NOT NULL" else "$operand IS NULL"
        }

        is Predicate.And -> "(${render(predicate.left, ctx)} AND ${render(predicate.right, ctx)})"
        is Predicate.Or -> "(${render(predicate.left, ctx)} OR ${render(predicate.right, ctx)})"
        is Predicate.Not -> "NOT (${render(predicate.inner, ctx)})"
    }

    private fun <V> renderIn(predicate: Predicate.In<V>, ctx: RenderContext): String {
        val sourceColumn = predicate.operand.columnRef()
        val operand = renderOperand(predicate.operand, ctx, sourceColumn)
        if (predicate.values.isEmpty()) {
            // SQL IN () is invalid; for empty list emit a tautology that yields no rows
            // (or all rows if negated). This avoids dialect-specific NULL pitfalls.
            return if (predicate.negated) "1 = 1" else "1 = 0"
        }
        val placeholders = predicate.values.joinToString(", ") { v ->
            ctx.bind(v, predicate.codec, sourceColumn)
        }
        val op = if (predicate.negated) "NOT IN" else "IN"
        return "$operand $op ($placeholders)"
    }

    private fun <V> renderBetween(predicate: Predicate.Between<V>, ctx: RenderContext): String {
        val sourceColumn = predicate.operand.columnRef()
        val operand = renderOperand(predicate.operand, ctx, sourceColumn)
        val lo = ctx.bind(predicate.lower, predicate.codec, sourceColumn)
        val hi = ctx.bind(predicate.upper, predicate.codec, sourceColumn)
        return "$operand BETWEEN $lo AND $hi"
    }

    @Suppress("UNCHECKED_CAST")
    fun renderOperand(operand: Operand, ctx: RenderContext, column: com.aggitech.aggo.schema.Column<*, *>? = null): String = when (operand) {
        is Operand.Col<*, *> -> {
            val col = operand.column
            "${ctx.dialect.quoteIdentifier(col.table.name)}.${ctx.dialect.quoteIdentifier(col.name)}"
        }
        is Operand.Literal<*> -> {
            // Cast is safe: Operand.Literal pairs value with the matching codec.
            val raw = operand as Operand.Literal<Any?>
            ctx.bind(raw.value, raw.codec, column)
        }
        is Operand.BinaryExpr -> {
            // Always parenthesised to guarantee precedence — (left op right).
            val left = renderOperand(operand.left, ctx)
            val right = renderOperand(operand.right, ctx)
            "($left ${operand.op.symbol} $right)"
        }
        is Operand.FunctionCall -> {
            val distinctStr = if (operand.distinct) "DISTINCT " else ""
            val args = operand.args.joinToString(", ") { arg -> renderOperand(arg, ctx) }
            "${operand.name}($distinctStr$args)"
        }
        is Operand.Star -> "*"
    }

    private fun Operand.columnRef(): com.aggitech.aggo.schema.Column<*, *>? =
        (this as? Operand.Col<*, *>)?.column
}
