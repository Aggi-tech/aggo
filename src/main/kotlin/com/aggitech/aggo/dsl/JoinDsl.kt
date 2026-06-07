package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.JoinClause
import com.aggitech.aggo.query.JoinOrdering
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.JoinType
import com.aggitech.aggo.query.OrderDir
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Scope for `orderBy { … }` inside a JOIN query. Columns from either side can be used. */
class JoinOrderByScope internal constructor(private val sink: MutableList<JoinOrdering>) {
    fun <E, V> Column<E, V>.asc() { sink += JoinOrdering(this, OrderDir.ASC) }
    fun <E, V> Column<E, V>.desc() { sink += JoinOrdering(this, OrderDir.DESC) }
}

/**
 * Starts a LEFT JOIN query between this table (left) and [right].
 *
 * The `on` block specifies the join condition using the same column operators as
 * WHERE clauses. You can reference columns from both tables inside the block.
 *
 * The result is an immutable [JoinSelect] that you can further refine with
 * [where], [orderBy], [limit], and [offset] before executing.
 *
 * ```kotlin
 * val query = OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
 *     .where { OrdersTable.status eq "PENDING" }
 *     .orderBy { OrdersTable.createdAt.desc() }
 *     .limit(50)
 *
 * val rows: List<JoinedRow<Order, User?>> = aggo.read { fetchAllJoined(query) }
 * // rows[i].left  → Order (always present)
 * // rows[i].right → User? (null when no matching user)
 * ```
 */
@OptIn(ExperimentalContracts::class)
fun <L, R> Table<L>.leftJoin(
    right: Table<R>,
    on: WhereScope.() -> Predicate,
): JoinSelect<L, R> {
    contract { callsInPlace(on, InvocationKind.EXACTLY_ONCE) }
    return JoinSelect(
        leftTable = this,
        join = JoinClause(JoinType.LEFT, right, WhereScope.on()),
    )
}

/** Add a WHERE filter to a JOIN query. Can reference columns from either joined table. */
@OptIn(ExperimentalContracts::class)
fun <L, R> JoinSelect<L, R>.where(block: WhereScope.() -> Predicate): JoinSelect<L, R> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return copy(where = WhereScope.block())
}

/** Add ORDER BY to a JOIN query. Use [JoinOrderByScope.asc] / [JoinOrderByScope.desc] on any column. */
@OptIn(ExperimentalContracts::class)
fun <L, R> JoinSelect<L, R>.orderBy(block: JoinOrderByScope.() -> Unit): JoinSelect<L, R> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val next = orderBy.toMutableList()
    JoinOrderByScope(next).block()
    return copy(orderBy = next.toList())
}

/** Limit the number of rows returned from a JOIN query. */
fun <L, R> JoinSelect<L, R>.limit(n: Int): JoinSelect<L, R> = copy(limit = n)

/** Skip rows in a JOIN query result. */
fun <L, R> JoinSelect<L, R>.offset(n: Int): JoinSelect<L, R> = copy(offset = n)
