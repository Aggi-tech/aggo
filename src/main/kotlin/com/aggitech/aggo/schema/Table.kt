package com.aggitech.aggo.schema

import com.aggitech.aggo.dialect.requireValidIdentifier
import io.r2dbc.spi.Row

/**
 * Compile-time descriptor of a relational table mapped to entity [E].
 *
 * Subclasses (typically `object Payers : Table<Payer>("payers") { ... }`)
 * declare columns via [column] and implement [fromRow]. The framework never
 * inspects properties at runtime — every mapping is explicit.
 */
abstract class Table<E>(val name: String) {

    init {
        // V-2: fail at object-init, not at first render — invalid table names
        // would otherwise blow up far from the declaration site.
        requireValidIdentifier(name)
    }

    private val mutableColumns: MutableList<Column<E, *>> = mutableListOf()

    /** Read-only view of declared columns, in declaration order. */
    val columns: List<Column<E, *>> get() = mutableColumns

    val primaryKeys: List<Column<E, *>> get() = mutableColumns.filter { it.isPrimaryKey }

    /** Columns the application is expected to provide on INSERT (non-generated). */
    val writableColumns: List<Column<E, *>> get() = mutableColumns.filterNot { it.isGenerated }

    /**
     * Register a column. Returns the descriptor so it can be assigned to a `val`
     * inside the schema object.
     *
     * Pass [check] to attach a PostgreSQL CHECK constraint to this column.
     * The lambda receives the column name and must return a valid SQL boolean
     * expression. Use [Checks] helpers or a raw lambda:
     *
     * ```kotlin
     * val email = column("email", EmailCodec, check = Checks.email()) { it.email }
     * val id    = column("id",    IdCodec,    check = Checks.tsid())  { it.id }
     * val name  = column("name",  StringCodec, check = { col -> "char_length(\"$col\") <= 100" }) { it.name }
     * ```
     *
     * Retrieve all constraint SQL via [checkConstraintClauses] or [addCheckConstraintsSql].
     */
    protected fun <V> column(
        name: String,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((columnName: String) -> String)? = null,
        sensitive: Boolean = false,
        getter: (E) -> V?,
    ): Column<E, V> {
        // V-2: validate before mutating mutableColumns so a bad name does not
        // half-register a column. Duplicate-name check protects fromRow().
        requireValidIdentifier(name)
        require(mutableColumns.none { it.name == name }) {
            "duplicate column '${this.name}.$name'"
        }
        val col = Column(
            table = this,
            name = name,
            codec = codec,
            getter = getter,
            isPrimaryKey = isPrimaryKey,
            isGenerated = isGenerated,
            isNullable = isNullable,
            checkExpression = check,
            sensitive = sensitive,
        )
        mutableColumns += col
        return col
    }

    /**
     * Returns inline `CONSTRAINT … CHECK (…)` clauses for every column that
     * declared a [check] expression. These can be embedded directly inside a
     * `CREATE TABLE` block or used with [addCheckConstraintsSql].
     */
    fun checkConstraintClauses(): List<String> =
        columns.mapNotNull { col ->
            col.checkExpression?.let { expr ->
                "CONSTRAINT chk_${name}_${col.name} CHECK (${expr(col.name)})"
            }
        }

    /**
     * Returns ready-to-run Postgres `ALTER TABLE … ADD CONSTRAINT …` statements
     * for all columns with a [check] expression. Prefer the dialect-aware
     * `com.aggitech.aggo.migration.addCheckConstraintsSql(dialect)` overload for
     * new code.
     *
     * Example output:
     * ```sql
     * ALTER TABLE "payers" ADD CONSTRAINT chk_payers_email CHECK ("email" ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$');
     * ALTER TABLE "payers" ADD CONSTRAINT chk_payers_id CHECK (char_length("id") = 13);
     * ```
     */
    fun addCheckConstraintsSql(): List<String> =
        checkConstraintClauses().map { clause -> "ALTER TABLE \"$name\" ADD $clause;" }

    /**
     * Build an entity from a result Row. Implementations should call
     * `column.read(row)` for each declared column. No reflection.
     */
    abstract fun fromRow(row: Row): E

    override fun toString(): String = "Table($name)"
}
