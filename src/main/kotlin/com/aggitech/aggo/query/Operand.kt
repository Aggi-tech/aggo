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

    /**
     * `EXTRACT(field FROM source)` — pulls a date/time subfield out of a
     * date, time, or timestamp expression.
     *
     * [field] is a closed [DatePart] enum (never a raw string), so there is
     * no risk of identifier/keyword injection in the generated SQL.
     *
     * Created by [com.aggitech.aggo.dsl.extract] and the shorthand helpers
     * ([com.aggitech.aggo.dsl.year], [com.aggitech.aggo.dsl.month], etc.).
     */
    data class Extract(val field: DatePart, val source: Operand) : Operand

    /**
     * Truncates a date/time expression down to the precision named by [field]
     * (e.g. `DAY`, `MONTH`, `HOUR`) — PostgreSQL's `date_trunc`, MySQL's
     * `DATE_FORMAT`-based equivalent, Oracle's `TRUNC(date, fmt)`, etc.
     *
     * Each [com.aggitech.aggo.dialect.SqlDialect] renders this with the
     * syntax its database understands; [PredicateRenderer] never hard-codes
     * `date_trunc`.
     *
     * Created by [com.aggitech.aggo.dsl.dateTrunc].
     */
    data class DateTrunc(val field: DatePart, val source: Operand) : Operand

    /**
     * A typed `quantity` × `unit` interval — e.g. "30 days" or "2 hours".
     *
     * [quantity] is bound as an ordinary integer parameter; [unit] is a closed
     * [IntervalUnit] enum. Each dialect renders the pair using the syntax its
     * database understands (`$1 * INTERVAL '1 day'` in Postgres,
     * `INTERVAL ? DAY` in MySQL, `NUMTODSINTERVAL(?, 'DAY')` in Oracle, ...) —
     * never a spliced free-form string, so there is no injection surface.
     *
     * Created by [com.aggitech.aggo.dsl.interval] and the
     * [com.aggitech.aggo.dsl.plusInterval] / [com.aggitech.aggo.dsl.minusInterval]
     * column operators.
     */
    data class IntervalLiteral(val quantity: Int, val unit: IntervalUnit) : Operand
}
