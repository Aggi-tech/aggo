package com.aggitech.aggo.schema

import io.r2dbc.spi.Row

/**
 * Compile-time descriptor of a relational table mapped to entity [E].
 *
 * Subclasses (typically `object Payers : Table<Payer>("payers") { ... }`)
 * declare columns via [column] and implement [fromRow]. The framework never
 * inspects properties at runtime — every mapping is explicit.
 */
abstract class Table<E>(val name: String) {

    private val mutableColumns: MutableList<Column<E, *>> = mutableListOf()

    /** Read-only view of declared columns, in declaration order. */
    val columns: List<Column<E, *>> get() = mutableColumns

    val primaryKeys: List<Column<E, *>> get() = mutableColumns.filter { it.isPrimaryKey }

    /** Columns the application is expected to provide on INSERT (non-generated). */
    val writableColumns: List<Column<E, *>> get() = mutableColumns.filterNot { it.isGenerated }

    /**
     * Register a column. Returns the descriptor so it can be assigned to a `val`
     * inside the schema object.
     */
    protected fun <V> column(
        name: String,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        getter: (E) -> V?,
    ): Column<E, V> {
        val col = Column(
            table = this,
            name = name,
            codec = codec,
            getter = getter,
            isPrimaryKey = isPrimaryKey,
            isGenerated = isGenerated,
            isNullable = isNullable,
        )
        mutableColumns += col
        return col
    }

    /**
     * Build an entity from a result Row. Implementations should call
     * `column.read(row)` for each declared column. No reflection.
     */
    abstract fun fromRow(row: Row): E

    override fun toString(): String = "Table($name)"
}
