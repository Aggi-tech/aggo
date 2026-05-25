package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.OrderDir
import com.aggitech.aggo.query.Ordering
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

/** Scope used inside `where { … }` blocks. Predicate operators are extension functions on this scope. */
object WhereScope

/**
 * Scope used inside `orderBy { … }` blocks.
 *
 * ```kotlin
 * orderBy {
 *     UsersTable.name.asc()
 *     UsersTable.createdAt.desc()
 * }
 * ```
 */
class OrderByScope<E> internal constructor(private val sink: MutableList<Ordering<E, *>>) {
    /** Add an ascending sort on this column. */
    fun <V> Column<E, V>.asc() { sink += Ordering(this, OrderDir.ASC) }
    /** Add a descending sort on this column. */
    fun <V> Column<E, V>.desc() { sink += Ordering(this, OrderDir.DESC) }
}

/**
 * Builder for SELECT queries. Obtained via [select].
 *
 * All methods are optional; calling `select(MyTable)` with no block returns a
 * query that selects all columns with no filter, order, or limit.
 */
class SelectBuilder<E> internal constructor(val table: Table<E>) {
    private var where: Predicate? = null
    private val orderBy: MutableList<Ordering<E, *>> = mutableListOf()
    private var limit: Int? = null
    private var offset: Int? = null

    /**
     * Filter rows. The block must return a [Predicate] built from the column
     * operators (`eq`, `like`, `inList`, `and`, `or`, etc.).
     *
     * ```kotlin
     * where { (UsersTable.active eq true) and (UsersTable.name like "%alice%") }
     * ```
     */
    fun where(block: WhereScope.() -> Predicate) {
        where = WhereScope.block()
    }

    /**
     * Add one or more sort columns. Call [OrderByScope.asc] / [OrderByScope.desc]
     * on any column descriptor inside the block.
     *
     * ```kotlin
     * orderBy { UsersTable.createdAt.desc() }
     * ```
     */
    fun orderBy(block: OrderByScope<E>.() -> Unit) {
        OrderByScope(orderBy).block()
    }

    /** Maximum number of rows to return. Passed as `LIMIT n` to the database. */
    fun limit(n: Int) { limit = n }

    /** Number of rows to skip before returning results. Passed as `OFFSET n`. */
    fun offset(n: Int) { offset = n }

    internal fun build(): Select<E> = Select(table, where, orderBy.toList(), limit, offset)
}

/**
 * Builds a SELECT query for [table].
 *
 * Returns an immutable [Select] descriptor. Execute it with [Session.fetchAll],
 * [Session.fetchOne], or [Session.stream].
 *
 * ```kotlin
 * // All rows
 * val q = select(UsersTable)
 *
 * // With filter, sort, and paging
 * val q = select(UsersTable) {
 *     where { (UsersTable.active eq true) and (UsersTable.name like "%alice%") }
 *     orderBy { UsersTable.name.asc() }
 *     limit(20)
 *     offset(40)
 * }
 *
 * // Execute inside a session
 * aggo.read { fetchAll(q) }
 *
 * // Or use the inline form directly on the session:
 * aggo.read {
 *     fetchAll(UsersTable) { where { UsersTable.active eq true } }
 * }
 * ```
 */
fun <E> select(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Select<E> =
    SelectBuilder(table).apply(block).build()
