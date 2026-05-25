package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.Assignment
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

/**
 * Builder for UPDATE queries. Obtained via [update].
 *
 * Use [setTo] to specify which columns to change and `where` to restrict which
 * rows are affected. Omitting `where` updates every row in the table.
 */
class UpdateBuilder<E> internal constructor(val table: Table<E>) {
    private val assignments: MutableList<Assignment<E, *>> = mutableListOf()
    private var where: Predicate? = null

    /**
     * Assign [value] to this column in the UPDATE statement.
     *
     * ```kotlin
     * update(UsersTable) {
     *     UsersTable.active setTo false
     *     UsersTable.name   setTo "Deactivated"
     *     where { UsersTable.id eq userId }
     * }
     * ```
     */
    infix fun <V> Column<E, V>.setTo(value: V?) {
        assignments += Assignment(this, value, this.codec)
    }

    /** Filter rows to update. See [WhereScope] and the operators in `Operators.kt`. */
    fun where(block: WhereScope.() -> Predicate) {
        where = WhereScope.block()
    }

    internal fun build(): Update<E> = Update(table, assignments.toList(), where)
}

/**
 * Builds an UPDATE query.
 *
 * Returns an immutable [Update] descriptor. Execute it with [Session.update].
 *
 * ```kotlin
 * aggo.tx {
 *     val affected = update(UsersTable) {
 *         UsersTable.active setTo false
 *         where { UsersTable.id eq userId }
 *     }
 *     check(affected == 1L) { "user not found" }
 * }
 * ```
 */
fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Update<E> =
    UpdateBuilder(table).apply(block).build()
