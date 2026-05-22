package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.Assignment
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

class UpdateBuilder<E> internal constructor(val table: Table<E>) {
    private val assignments: MutableList<Assignment<E, *>> = mutableListOf()
    private var where: Predicate? = null

    infix fun <V> Column<E, V>.setTo(value: V?) {
        assignments += Assignment(this, value, this.codec)
    }

    fun where(block: WhereScope.() -> Predicate) {
        where = WhereScope.block()
    }

    internal fun build(): Update<E> = Update(table, assignments.toList(), where)
}

fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Update<E> =
    UpdateBuilder(table).apply(block).build()
