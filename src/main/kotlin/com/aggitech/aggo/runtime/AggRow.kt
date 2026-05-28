package com.aggitech.aggo.runtime

import com.aggitech.aggo.query.NamedExpr
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.read
import io.r2dbc.spi.Row

/**
 * A single row from an [com.aggitech.aggo.query.AggregateSelect] result set.
 *
 * Values are decoded via [NamedExpr] keys — the same references created by
 * `expr `as` "alias"` inside the `aggregate { }` block. This gives compile-time
 * type safety without reflection.
 *
 * ```kotlin
 * val total = count(Orders.id) `as` "total"
 * val avg   = avg(Orders.amount) `as` "avg_amount"
 *
 * val results = aggo.read {
 *     fetchAggregate(aggregate(Orders) {
 *         project(total)
 *         project(avg)
 *         groupBy(Orders.customerId)
 *     })
 * }
 *
 * results.forEach { row ->
 *     println("total=${row[total]}, avg=${row[avg]}")
 * }
 * ```
 */
class AggRow internal constructor(
    private val row: Row,
    @Suppress("unused") private val projections: List<NamedExpr<*>>,
) {
    /**
     * Decode the value of [expr] from this row. Returns `null` when the database value is NULL.
     *
     * The codec is the one carried by [NamedExpr.expr] — the same codec used to declare
     * the projection, so types always match.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <V> get(expr: NamedExpr<V>): V? =
        expr.expr.codec.read(row, expr.label)

    /**
     * Decode the value of [expr] and assert it is non-null.
     * Use for NOT NULL aggregate results (e.g. `COUNT(*)` always returns a value).
     *
     * @throws IllegalStateException when the value is null.
     */
    fun <V> required(expr: NamedExpr<V>): V =
        get(expr) ?: error("Aggregate column '${expr.label}' is null but was read as required")
}
