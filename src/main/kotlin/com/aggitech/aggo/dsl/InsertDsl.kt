package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.Assignment
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

/**
 * Builder for INSERT queries. Obtained via [insert].
 *
 * Use [setTo] to specify the columns and values to write.
 * You do not need to include generated columns (`isGenerated = true`) —
 * the database fills those in automatically.
 */
class InsertBuilder<E> internal constructor(val table: Table<E>) {
    private val assignments: MutableList<Assignment<E, *>> = mutableListOf()

    /**
     * Assign [value] to this column in the INSERT statement.
     *
     * ```kotlin
     * insert(UsersTable) {
     *     UsersTable.email  setTo Email("alice@example.com")
     *     UsersTable.name   setTo "Alice"
     *     UsersTable.active setTo true
     * }
     * ```
     */
    infix fun <V> Column<E, V>.setTo(value: V?) {
        assignments += Assignment(this, value, this.codec)
    }

    internal fun build(): Insert<E> = Insert(table, assignments.toList())
}

/**
 * Builds a partial INSERT query via a builder block.
 *
 * Use this when you only need to set specific columns, or when the full entity
 * object is not available. For inserting a complete entity, prefer [insert] with
 * an entity parameter.
 *
 * ```kotlin
 * val rows = aggo.tx {
 *     insert(UsersTable) {
 *         UsersTable.email  setTo Email("alice@example.com")
 *         UsersTable.name   setTo "Alice"
 *         UsersTable.active setTo true
 *     }
 * }
 * ```
 */
fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Insert<E> =
    InsertBuilder(table).apply(block).build()

/**
 * Builds an INSERT query from a full entity by walking the table's writable
 * columns. Columns declared with `isGenerated = true` are skipped — the
 * database fills those in (sequences, DEFAULT expressions, triggers).
 *
 * ```kotlin
 * val user = User(id = Tsid.generate(), email = Email("a@b.com"), name = "Alice", active = true)
 * aggo.tx { insert(UsersTable, user) }
 * ```
 */
fun <E> insert(table: Table<E>, entity: E): Insert<E> {
    val rows: MutableList<Assignment<E, *>> = mutableListOf()
    for (col in table.writableColumns) {
        rows += assignmentFor(col, entity)
    }
    return Insert(table, rows.toList())
}

@Suppress("UNCHECKED_CAST")
private fun <E, V> assignmentFor(col: Column<E, V>, entity: E): Assignment<E, V> =
    Assignment(col, col.getter(entity), col.codec)
