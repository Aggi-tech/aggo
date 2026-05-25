package com.aggitech.aggo.schema

import io.r2dbc.spi.Row

/**
 * Compile-time descriptor of a column belonging to [Table] [E].
 *
 * - [getter] extracts the value from the domain entity without reflection.
 * - [codec] knows how to encode/decode against R2DBC.
 * - [isPrimaryKey], [isGenerated], [isNullable] are explicit flags the user
 *   declares (the framework never guesses via annotation scanning).
 * - [checkExpression] is an optional lambda that receives the column name and
 *   returns a PostgreSQL CHECK expression. Use [Checks] helpers or a raw lambda.
 */
class Column<E, V>(
    val table: Table<E>,
    val name: String,
    val codec: Codec<V>,
    val getter: (E) -> V?,
    val isPrimaryKey: Boolean = false,
    val isGenerated: Boolean = false,
    val isNullable: Boolean = false,
    val checkExpression: ((columnName: String) -> String)? = null,
    /**
     * V-6: when true, every [com.aggitech.aggo.render.Bound] originating from
     * this column (UPDATE/INSERT assignment or `column eq value` predicate)
     * is logged as `<redacted>` instead of its raw value. Mark password
     * digests, tokens, PII you cannot afford in trace logs.
     */
    val sensitive: Boolean = false,
) {
    /** Read this column out of an R2DBC [Row]. Required nulls are caller's problem. */
    fun read(row: Row): V? = codec.read(row, name)

    /**
     * Read and assert non-null. Use when the column is declared NOT NULL and the
     * decode should fail loudly rather than silently returning null.
     */
    fun readRequired(row: Row): V =
        read(row) ?: error("Column '${table.name}.$name' is null but was read as required")

    override fun toString(): String = "${table.name}.$name"

    /** Identity-based equality on (table.name, column.name) — safe for use in sets/maps. */
    override fun equals(other: Any?): Boolean =
        other is Column<*, *> && other.table.name == table.name && other.name == name

    override fun hashCode(): Int = 31 * table.name.hashCode() + name.hashCode()
}
