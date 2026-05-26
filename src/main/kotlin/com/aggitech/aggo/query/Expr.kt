package com.aggitech.aggo.query

import com.aggitech.aggo.schema.Codec

/**
 * Arithmetic / string operators used in [Operand.BinaryExpr].
 *
 * - [ADD], [SUB], [MUL], [DIV], [MOD] — standard numeric arithmetic
 * - [CONCAT] — SQL string concatenation (`||`); produced by `+` on `Column<E, String>`
 */
enum class ArithmeticOp(val symbol: String) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"),
    CONCAT("||"),
}

/**
 * A type-safe SQL expression with a known result codec.
 *
 * Instances are built by:
 * - **Arithmetic operators** on [com.aggitech.aggo.schema.Column] and [Expr]:
 *   `column + value`, `expr1 - expr2`, `column * 2`, etc.
 * - **Aggregate functions**: [com.aggitech.aggo.dsl.sum], [com.aggitech.aggo.dsl.count],
 *   [com.aggitech.aggo.dsl.avg], [com.aggitech.aggo.dsl.max], [com.aggitech.aggo.dsl.min],
 *   [com.aggitech.aggo.dsl.countStar], [com.aggitech.aggo.dsl.countDistinct],
 *   [com.aggitech.aggo.dsl.sumDistinct]
 * - **Scalar functions**: [com.aggitech.aggo.dsl.upper], [com.aggitech.aggo.dsl.lower],
 *   [com.aggitech.aggo.dsl.length], [com.aggitech.aggo.dsl.trim],
 *   [com.aggitech.aggo.dsl.abs], [com.aggitech.aggo.dsl.coalesce],
 *   [com.aggitech.aggo.dsl.concat]
 * - **Column promotion**: [com.aggitech.aggo.dsl.asExpr]
 *
 * An [Expr] can appear:
 * - On either side of a `WHERE` / `HAVING` comparison via `eq`, `gt`, `lt`, etc.
 * - As a `SELECT` projection inside `aggregate { project(expr `as` "alias") }` blocks.
 *
 * ## Examples
 *
 * ```kotlin
 * // Arithmetic in WHERE
 * select(People) { where { (People.age + 1) gt 18 } }
 *
 * // Aggregate projection
 * val total = count(People.id) `as` "total"
 * val q = aggregate(People) {
 *     project(total)
 *     groupBy(People.active)
 * }
 * aggo.read { fetchAggregate(q).map { row -> row[total]!! } }
 * ```
 */
data class Expr<V>(val operand: Operand, val codec: Codec<V>) {

    /**
     * Assign a SQL alias, turning this expression into a [NamedExpr] ready for
     * use as an `aggregate { project(...) }` projection.
     *
     * ```kotlin
     * val total = count(Orders.id) `as` "total"
     * val avg   = avg(Orders.amount) `as` "avg_amount"
     * ```
     */
    infix fun `as`(label: String): NamedExpr<V> = NamedExpr(label, this)
}

/**
 * A named SQL projection — an [Expr] paired with a column alias for `SELECT` and result reading.
 *
 * Create with `expr `as` "alias"`. Use as a typed key in [com.aggitech.aggo.runtime.AggRow]
 * to decode the aggregate result.
 *
 * ```kotlin
 * val cnt: NamedExpr<Long> = countStar() `as` "cnt"
 * val results = aggo.read {
 *     fetchAggregate(aggregate(Orders) { project(cnt) }).map { it[cnt]!! }
 * }
 * ```
 */
data class NamedExpr<V>(val label: String, val expr: Expr<V>)
