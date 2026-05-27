package com.aggitech.aggo.dialect.decorators

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.query.Assignment
import com.aggitech.aggo.schema.Column

class OnConflictDecorator(private val base: PostgresDialect) : SqlDialect by base {
    fun renderOnConflictUpdate(
        conflictColumns: List<Column<*, *>>,
        updateAssignments: List<Assignment<*, *>>,
    ): String {
        val target = conflictColumns.joinToString(", ") { base.quoteIdentifier(it.name) }
        val updates = updateAssignments.joinToString(", ") { assignment ->
            val column = base.quoteIdentifier(assignment.column.name)
            "$column = EXCLUDED.$column"
        }
        return "ON CONFLICT ($target) DO UPDATE SET $updates"
    }

    fun renderOnConflictDoNothing(conflictColumns: List<Column<*, *>>): String {
        val target = conflictColumns.joinToString(", ") { base.quoteIdentifier(it.name) }
        return "ON CONFLICT ($target) DO NOTHING"
    }
}

fun PostgresDialect.withOnConflict(): OnConflictDecorator = OnConflictDecorator(this)
