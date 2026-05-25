package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Table

/**
 * Builder for DELETE queries. Obtained via [delete].
 *
 * Omitting `where` produces a `DELETE FROM table` with no filter,
 * which deletes every row. Always provide a WHERE clause unless that
 * is intentional.
 */
class DeleteBuilder<E> internal constructor(val table: Table<E>) {
    private var where: Predicate? = null

    /** Filter rows to delete. See [WhereScope] and the operators in `Operators.kt`. */
    fun where(block: WhereScope.() -> Predicate) {
        where = WhereScope.block()
    }

    internal fun build(): Delete<E> = Delete(table, where)
}

/**
 * Builds a DELETE query.
 *
 * Returns an immutable [Delete] descriptor. Execute it with [Session.delete].
 *
 * ```kotlin
 * // Delete a single row by primary key
 * aggo.tx { delete(UsersTable) { where { UsersTable.id eq userId } } }
 *
 * // Delete all rows (no WHERE — be careful!)
 * aggo.tx { delete(UsersTable) }
 * ```
 */
fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Delete<E> =
    DeleteBuilder(table).apply(block).build()
