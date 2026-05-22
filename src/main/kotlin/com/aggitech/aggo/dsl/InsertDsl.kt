package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.Assignment
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

class InsertBuilder<E> internal constructor(val table: Table<E>) {
    private val assignments: MutableList<Assignment<E, *>> = mutableListOf()

    /** Assign a literal value to a column: `Payers.email setTo "x@y"`. */
    infix fun <V> Column<E, V>.setTo(value: V?) {
        assignments += Assignment(this, value, this.codec)
    }

    internal fun build(): Insert<E> = Insert(table, assignments.toList())
}

fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Insert<E> =
    InsertBuilder(table).apply(block).build()

/**
 * Build an [Insert] from a full entity by walking the table's writable columns
 * and calling each column's [Column.getter]. Generated columns are skipped.
 * No reflection.
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
