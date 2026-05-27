package com.aggitech.aggo.runtime

import com.aggitech.aggo.schema.Column
import io.r2dbc.spi.Row

/**
 * A single row from a [com.aggitech.aggo.query.ProjectionSelect] result set.
 *
 * Values are decoded via [Column] keys — the same references passed to [com.aggitech.aggo.dsl.selectProjection].
 * This gives compile-time type safety without reflection.
 *
 * ```kotlin
 * val q = selectProjection(UsersTable, UsersTable.id, UsersTable.email) {
 *     where { UsersTable.active eq true }
 * }
 *
 * aggo.read {
 *     fetchProjection(q).map { row ->
 *         UserDto(id = row[UsersTable.id]!!, email = row[UsersTable.email]!!)
 *     }
 * }
 * ```
 */
class ProjectedRow internal constructor(private val row: Row) {
    /**
     * Decode the value of [column] from this row. Returns `null` when the database value is NULL.
     *
     * The type [V] is inferred from the [Column] declaration — no cast needed at the call site.
     */
    operator fun <E, V> get(column: Column<E, V>): V? = column.read(row)

    /**
     * Decode the value of [column] and assert it is non-null.
     * Use for NOT NULL columns when you want to fail loudly rather than return null.
     *
     * @throws IllegalStateException when the value is null.
     */
    fun <E, V> required(column: Column<E, V>): V =
        get(column) ?: error("Projected column '${column.table.name}.${column.name}' is null but was read as required")
}
