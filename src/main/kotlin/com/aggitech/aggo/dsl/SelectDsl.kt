package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.OrderDir
import com.aggitech.aggo.query.Ordering
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

/** Scope used inside a `where { ... }` block. Empty — operators live as extensions. */
object WhereScope

class OrderByScope<E> internal constructor(private val sink: MutableList<Ordering<E, *>>) {
    fun <V> Column<E, V>.asc() { sink += Ordering(this, OrderDir.ASC) }
    fun <V> Column<E, V>.desc() { sink += Ordering(this, OrderDir.DESC) }
}

class SelectBuilder<E> internal constructor(val table: Table<E>) {
    private var where: Predicate? = null
    private val orderBy: MutableList<Ordering<E, *>> = mutableListOf()
    private var limit: Int? = null
    private var offset: Int? = null

    fun where(block: WhereScope.() -> Predicate) {
        where = WhereScope.block()
    }

    fun orderBy(block: OrderByScope<E>.() -> Unit) {
        OrderByScope(orderBy).block()
    }

    fun limit(n: Int) { limit = n }
    fun offset(n: Int) { offset = n }

    internal fun build(): Select<E> = Select(table, where, orderBy.toList(), limit, offset)
}

/**
 * Entry point: `select(Payers) { where { Payers.active eq true } ; limit(10) }`.
 *
 * Returns an immutable [Select] descriptor — execution happens later via
 * `Session.fetchAll(select)`.
 */
fun <E> select(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Select<E> =
    SelectBuilder(table).apply(block).build()
