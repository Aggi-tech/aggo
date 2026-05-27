package com.aggitech.aggo.dialect.decorators

import com.aggitech.aggo.dialect.MySqlDialect
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.query.Assignment

class OnDuplicateKeyDecorator(private val base: MySqlDialect) : SqlDialect by base {
    fun renderOnDuplicateKeyUpdate(updateAssignments: List<Assignment<*, *>>): String {
        val updates = updateAssignments.joinToString(", ") { assignment ->
            val column = base.quoteIdentifier(assignment.column.name)
            "$column = VALUES($column)"
        }
        return "ON DUPLICATE KEY UPDATE $updates"
    }
}

fun MySqlDialect.withOnDuplicateKey(): OnDuplicateKeyDecorator = OnDuplicateKeyDecorator(this)
