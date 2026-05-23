package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.JoinClause
import com.aggitech.aggo.query.JoinOrdering
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.JoinType
import com.aggitech.aggo.query.OrderDir
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

class JoinOrderByScope internal constructor(private val sink: MutableList<JoinOrdering>) {
    fun <E, V> Column<E, V>.asc() { sink += JoinOrdering(this, OrderDir.ASC) }
    fun <E, V> Column<E, V>.desc() { sink += JoinOrdering(this, OrderDir.DESC) }
}

fun <L, R> Table<L>.leftJoin(
    right: Table<R>,
    on: WhereScope.() -> Predicate,
): JoinSelect<L, R> =
    JoinSelect(
        leftTable = this,
        join = JoinClause(JoinType.LEFT, right, WhereScope.on()),
    )

fun <L, R> JoinSelect<L, R>.where(block: WhereScope.() -> Predicate): JoinSelect<L, R> =
    copy(where = WhereScope.block())

fun <L, R> JoinSelect<L, R>.orderBy(block: JoinOrderByScope.() -> Unit): JoinSelect<L, R> {
    val next = orderBy.toMutableList()
    JoinOrderByScope(next).block()
    return copy(orderBy = next.toList())
}

fun <L, R> JoinSelect<L, R>.limit(n: Int): JoinSelect<L, R> = copy(limit = n)

fun <L, R> JoinSelect<L, R>.offset(n: Int): JoinSelect<L, R> = copy(offset = n)
