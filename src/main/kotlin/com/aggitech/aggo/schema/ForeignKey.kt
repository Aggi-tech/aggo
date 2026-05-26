package com.aggitech.aggo.schema

/**
 * The action a referencing table should take when the referenced row is deleted or updated.
 *
 * Passed to [Table.references] when declaring a foreign key column.
 *
 * | Variant     | SQL             | Behaviour                                          |
 * |-------------|-----------------|----------------------------------------------------|
 * | RESTRICT    | `RESTRICT`      | Refuse the operation if any referencing row exists |
 * | NO_ACTION   | `NO ACTION`     | Like RESTRICT but checked at end of statement      |
 * | CASCADE     | `CASCADE`       | Propagate delete/update to referencing rows        |
 * | SET_NULL    | `SET NULL`      | Null out the FK column in referencing rows         |
 * | SET_DEFAULT | `SET DEFAULT`   | Reset FK column to its DEFAULT in referencing rows |
 */
enum class ForeignKeyAction(val sql: String) {
    RESTRICT("RESTRICT"),
    NO_ACTION("NO ACTION"),
    CASCADE("CASCADE"),
    SET_NULL("SET NULL"),
    SET_DEFAULT("SET DEFAULT"),
}

/**
 * Metadata for a FOREIGN KEY constraint declared on a [Table].
 *
 * Instances are created by calling the [Table.references] extension on a column:
 *
 * ```kotlin
 * object OrdersTable : Table<Order>("orders") {
 *     val customerId = column("customer_id", IntCodec) { it.customerId }
 *         .references(CustomersTable.id, onDelete = ForeignKeyAction.CASCADE)
 * }
 * ```
 *
 * The constraint name defaults to `fk_<fromTable>_<fromColumn>`. Override it
 * with [constraintName] when the generated name exceeds Postgres's 63-character limit
 * or conflicts with an existing constraint.
 */
data class ForeignKey(
    /** The column on the child (referencing) table. */
    val column: Column<*, *>,
    /** The column on the parent (referenced) table. Must be PRIMARY KEY or UNIQUE. */
    val referencedColumn: Column<*, *>,
    val onDelete: ForeignKeyAction = ForeignKeyAction.RESTRICT,
    val onUpdate: ForeignKeyAction = ForeignKeyAction.RESTRICT,
    val constraintName: String? = null,
) {
    /** The effective constraint name used in DDL. */
    val effectiveName: String
        get() = constraintName ?: "fk_${column.table.name}_${column.name}"
}
