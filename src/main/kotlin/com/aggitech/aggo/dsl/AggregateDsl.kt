package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.AggOrdering
import com.aggitech.aggo.query.AggregateSelect
import com.aggitech.aggo.query.Expr
import com.aggitech.aggo.query.NamedExpr
import com.aggitech.aggo.query.OrderDir
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

/**
 * Sort scope for `aggregate { orderBy { ... } }` blocks.
 *
 * Expressions can be sorted via [asc] and [desc]:
 *
 * ```kotlin
 * orderBy {
 *     avgAmount.desc()
 *     total.asc()
 * }
 * ```
 */
class AggOrderByScope internal constructor(private val sink: MutableList<AggOrdering>) {
    fun <V> Expr<V>.asc() { sink += AggOrdering(this, OrderDir.ASC) }
    fun <V> Expr<V>.desc() { sink += AggOrdering(this, OrderDir.DESC) }
    fun <V> NamedExpr<V>.asc() { sink += AggOrdering(expr, OrderDir.ASC) }
    fun <V> NamedExpr<V>.desc() { sink += AggOrdering(expr, OrderDir.DESC) }
}

/**
 * Builder for [AggregateSelect] queries. Obtained via [aggregate].
 *
 * Every aggregate query must register at least one projection via [project].
 * All other clauses are optional.
 *
 * ```kotlin
 * val total  = count(Orders.id)     `as` "total"
 * val avgAmt = avg(Orders.amount)   `as` "avg_amount"
 *
 * val q = aggregate(Orders) {
 *     project(total)
 *     project(avgAmt)
 *     where   { Orders.active eq true }
 *     groupBy (Orders.customerId)
 *     having  { count(Orders.id) gt 0L }
 *     orderBy { avgAmt.desc() }
 *     limit(50)
 * }
 *
 * aggo.read {
 *     fetchAggregate(q).map { row -> row[total]!! to row[avgAmt]!! }
 * }
 * ```
 */
class AggregateBuilder<E> internal constructor(val table: Table<E>) {
    private val projections = mutableListOf<NamedExpr<*>>()
    private var where: Predicate? = null
    private val groupBy = mutableListOf<Column<E, *>>()
    private var having: Predicate? = null
    private val orderBy = mutableListOf<AggOrdering>()
    private var limit: Int? = null
    private var offset: Int? = null

    /**
     * Register a named expression as a SELECT projection.
     * Returns the same [NamedExpr] so it can be held as a typed reference for result reading.
     *
     * ```kotlin
     * val cnt = project(countStar() `as` "cnt")
     * // later: row[cnt]
     * ```
     */
    fun <V> project(expr: NamedExpr<V>): NamedExpr<V> {
        projections += expr
        return expr
    }

    /**
     * Filter rows before aggregation (SQL `WHERE`).
     *
     * ```kotlin
     * where { Orders.status eq "PAID" }
     * ```
     */
    fun where(block: WhereScope.() -> Predicate) {
        where = WhereScope.block()
    }

    /**
     * Columns to group by (SQL `GROUP BY`). Each column must belong to the query table.
     *
     * ```kotlin
     * groupBy(Orders.customerId, Orders.status)
     * ```
     */
    fun groupBy(vararg columns: Column<E, *>) {
        groupBy += columns
    }

    /**
     * Filter groups after aggregation (SQL `HAVING`).
     * Typically references aggregate functions like [count], [sum], [avg].
     *
     * ```kotlin
     * having { count(Orders.id) gt 5L }
     * ```
     */
    fun having(block: WhereScope.() -> Predicate) {
        having = WhereScope.block()
    }

    /**
     * Sort the aggregate results. Call [AggOrderByScope.asc] / [AggOrderByScope.desc]
     * on any [Expr] or [NamedExpr] inside the block.
     *
     * ```kotlin
     * orderBy { total.desc() }
     * ```
     */
    fun orderBy(block: AggOrderByScope.() -> Unit) {
        AggOrderByScope(orderBy).block()
    }

    /** Maximum number of aggregate result rows to return. */
    fun limit(n: Int) { limit = n }

    /** Number of aggregate result rows to skip. */
    fun offset(n: Int) { offset = n }

    internal fun build(): AggregateSelect<E> =
        AggregateSelect(table, projections.toList(), where, groupBy.toList(), having, orderBy.toList(), limit, offset)
}

/**
 * Builds an aggregate SELECT query for [table].
 *
 * Returns an immutable [AggregateSelect] descriptor. Execute it with
 * [com.aggitech.aggo.runtime.Session.fetchAggregate] or
 * [com.aggitech.aggo.runtime.Session.streamAggregate].
 *
 * ```kotlin
 * // Count active orders per customer
 * val cnt = count(Orders.id) `as` "cnt"
 * val q = aggregate(Orders) {
 *     project(cnt)
 *     where { Orders.active eq true }
 *     groupBy(Orders.customerId)
 *     having { count(Orders.id) gt 0L }
 *     orderBy { cnt.desc() }
 *     limit(10)
 * }
 *
 * aggo.read { fetchAggregate(q).map { it[cnt]!! } }
 * ```
 */
fun <E> aggregate(table: Table<E>, block: AggregateBuilder<E>.() -> Unit): AggregateSelect<E> =
    AggregateBuilder(table).apply(block).build()
