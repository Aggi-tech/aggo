package com.aggitech.aggo.query

import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column

/**
 * Anything that can appear on either side of a comparison in a WHERE/HAVING clause,
 * or as a projection term in an aggregate SELECT.
 *
 * Sealed — renderers use exhaustive `when` so the compiler catches any missing branch.
 */
sealed interface Operand {
    /** A reference to a typed column. */
    data class Col<E, V>(val column: Column<E, V>) : Operand

    /** A literal value plus the codec that knows how to bind it. */
    data class Literal<V>(val value: V?, val codec: Codec<V>) : Operand

    /**
     * A binary arithmetic or concatenation expression: `left op right`.
     *
     * Created by the arithmetic operator extensions on [Column] and [Expr]:
     * `column + value`, `expr1 - expr2`, `column * 2`, `stringCol + " suffix"`, etc.
     *
     * Renders as `(left op right)` — always parenthesised to guarantee precedence.
     */
    data class BinaryExpr(
        val left: Operand,
        val op: ArithmeticOp,
        val right: Operand,
    ) : Operand

    /**
     * A SQL function call: `NAME(args)` or `NAME(DISTINCT args)`.
     *
     * Created by aggregate helpers ([com.aggitech.aggo.dsl.sum], [com.aggitech.aggo.dsl.count],
     * etc.) and scalar helpers ([com.aggitech.aggo.dsl.upper], [com.aggitech.aggo.dsl.coalesce], etc.).
     *
     * When [args] is empty, renders as `NAME()`.
     */
    data class FunctionCall(
        val name: String,
        val args: List<Operand>,
        val distinct: Boolean = false,
    ) : Operand

    /**
     * The SQL `*` wildcard, used exclusively as the argument to `COUNT(*)`.
     *
     * Created by [com.aggitech.aggo.dsl.countStar].
     */
    object Star : Operand
}
