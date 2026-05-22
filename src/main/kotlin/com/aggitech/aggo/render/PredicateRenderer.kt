package com.aggitech.aggo.render

import com.aggitech.aggo.query.Operand
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Codec

internal object PredicateRenderer {

    fun render(predicate: Predicate, ctx: RenderContext): String = when (predicate) {
        is Predicate.Cmp -> {
            val left = renderOperand(predicate.left, ctx)
            val right = renderOperand(predicate.right, ctx)
            "$left ${predicate.op.symbol} $right"
        }

        is Predicate.Like -> {
            val operand = renderOperand(predicate.operand, ctx)
            val placeholder = ctx.bind(predicate.pattern, com.aggitech.aggo.schema.StringCodec)
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

    @Suppress("UNCHECKED_CAST")
    private fun <V> renderIn(predicate: Predicate.In<V>, ctx: RenderContext): String {
        val operand = renderOperand(predicate.operand, ctx)
        if (predicate.values.isEmpty()) {
            // SQL IN () is invalid; for empty list emit a tautology that yields no rows
            // (or all rows if negated). This avoids dialect-specific NULL pitfalls.
            return if (predicate.negated) "1 = 1" else "1 = 0"
        }
        val placeholders = predicate.values.joinToString(", ") { v ->
            ctx.bind(v as V?, predicate.codec)
        }
        val op = if (predicate.negated) "NOT IN" else "IN"
        return "$operand $op ($placeholders)"
    }

    private fun <V> renderBetween(predicate: Predicate.Between<V>, ctx: RenderContext): String {
        val operand = renderOperand(predicate.operand, ctx)
        val lo = ctx.bind(predicate.lower, predicate.codec)
        val hi = ctx.bind(predicate.upper, predicate.codec)
        return "$operand BETWEEN $lo AND $hi"
    }

    @Suppress("UNCHECKED_CAST")
    fun renderOperand(operand: Operand, ctx: RenderContext): String = when (operand) {
        is Operand.Col<*, *> -> {
            val col = operand.column
            "${ctx.dialect.quoteIdentifier(col.table.name)}.${ctx.dialect.quoteIdentifier(col.name)}"
        }
        is Operand.Literal<*> -> {
            // Cast is safe: Operand.Literal pairs value with the matching codec.
            val raw = operand as Operand.Literal<Any?>
            ctx.bind(raw.value, raw.codec as Codec<Any?>)
        }
    }
}
