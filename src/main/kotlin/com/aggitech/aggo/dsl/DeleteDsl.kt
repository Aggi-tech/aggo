package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Table

class DeleteBuilder<E> internal constructor(val table: Table<E>) {
    private var where: Predicate? = null

    fun where(block: WhereScope.() -> Predicate) {
        where = WhereScope.block()
    }

    internal fun build(): Delete<E> = Delete(table, where)
}

fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Delete<E> =
    DeleteBuilder(table).apply(block).build()
