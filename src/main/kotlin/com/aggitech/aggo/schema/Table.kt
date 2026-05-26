package com.aggitech.aggo.schema

import com.aggitech.aggo.dialect.requireValidIdentifier
import com.aggitech.aggo.schema.ids.Tsid
import com.aggitech.aggo.schema.ids.Ulid
import io.r2dbc.spi.Row
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Strict allowlist for column DDL type strings supplied via
 * `Table.column(..., sqlType = ...)` or one of the typed column builders.
 *
 * Permits a SQL type keyword (one or more space-separated words) optionally
 * followed by a parenthesised argument list of digits and commas — e.g.
 * `TEXT`, `VARCHAR(100)`, `NUMERIC(10, 2)`, `DOUBLE PRECISION`,
 * `TIMESTAMP WITH TIME ZONE`. Quotes, semicolons, comments, parentheses
 * containing identifiers, and SQL keywords like `AS` / `CHECK` are rejected.
 *
 * This blocks injection through the sqlType surface even when the value
 * originates from compile-time code, mirroring the same defence-in-depth
 * stance as `IDENTIFIER_REGEX` for table/column names.
 */
val SQL_TYPE_REGEX = Regex("^[A-Za-z][A-Za-z ]*(\\([0-9]+(,\\s*[0-9]+)?\\))?$")

internal fun requireValidSqlType(sqlType: String) {
    require(sqlType.isNotBlank()) { "sqlType must not be blank" }
    require(SQL_TYPE_REGEX.matches(sqlType)) {
        "sqlType '$sqlType' is not a recognised DDL type form. " +
            "Allowed: a type keyword (letters and spaces) optionally followed by " +
            "a numeric (precision) or (precision, scale) qualifier."
    }
}

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
    private val mutableForeignKeys: MutableList<ForeignKey> = mutableListOf()

    /** Read-only view of declared columns, in declaration order. */
    val columns: List<Column<E, *>> get() = mutableColumns

    val primaryKeys: List<Column<E, *>> get() = mutableColumns.filter { it.isPrimaryKey }

    /** Columns the application is expected to provide on INSERT (non-generated). */
    val writableColumns: List<Column<E, *>> get() = mutableColumns.filterNot { it.isGenerated }

    /**
     * All foreign key constraints declared on this table via [references].
     * Used for DDL generation and migration diff.
     */
    val foreignKeys: List<ForeignKey> get() = mutableForeignKeys

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
        sqlType: String? = null,
        getter: (E) -> V?,
    ): Column<E, V> = column(
        name = name,
        codec = codec,
        isPrimaryKey = isPrimaryKey,
        isGenerated = isGenerated,
        isNullable = isNullable,
        checks = check?.let { listOf(CheckConstraint(expression = it)) } ?: emptyList(),
        unique = null,
        sensitive = sensitive,
        sqlType = sqlType,
        getter = getter,
    )

    private fun <V> column(
        name: String,
        codec: Codec<V>,
        isPrimaryKey: Boolean,
        isGenerated: Boolean,
        isNullable: Boolean,
        checks: List<CheckConstraint>,
        unique: UniqueConstraint?,
        sensitive: Boolean,
        sqlType: String?,
        getter: (E) -> V?,
        indexDeclaration: IndexDeclaration? = null,
    ): Column<E, V> {
        // V-2: validate before mutating mutableColumns so a bad name does not
        // half-register a column. Duplicate-name check protects fromRow().
        requireValidIdentifier(name)
        require(mutableColumns.none { it.name == name }) {
            "duplicate column '${this.name}.$name'"
        }
        sqlType?.let { requireValidSqlType(it) }
        val col = Column(
            table = this,
            name = name,
            codec = codec,
            getter = getter,
            isPrimaryKey = isPrimaryKey,
            isGenerated = isGenerated,
            isNullable = isNullable,
            checkExpression = checks.firstOrNull()?.expression,
            checkConstraints = checks,
            uniqueConstraint = unique,
            indexDeclaration = indexDeclaration,
            sensitive = sensitive,
            sqlType = sqlType,
        )
        mutableColumns += col
        return col
    }

    /** Starts a fluent column declaration with an explicit [codec] and optional SQL type override. */
    protected fun <V> typed(
        name: String,
        codec: Codec<V>,
        sqlType: String? = null,
    ): ColumnBuilder<E, V> = ColumnBuilder(this, name, codec, sqlType)

    /** Starts a fluent `VARCHAR(length)` column declaration. */
    protected fun varchar(name: String, length: Int = 255): ColumnBuilder<E, String> {
        require(length > 0) { "varchar length must be > 0, got $length" }
        return typed(name, StringCodec, sqlType = "VARCHAR($length)")
    }

    /** Starts a fluent `VARCHAR(length)` declaration backed by a custom [codec]. */
    protected fun <V> varchar(name: String, length: Int = 255, codec: Codec<V>): ColumnBuilder<E, V> {
        require(length > 0) { "varchar length must be > 0, got $length" }
        return typed(name, codec, sqlType = "VARCHAR($length)")
    }

    /** Starts a fluent `TEXT` column declaration. */
    protected fun text(name: String): ColumnBuilder<E, String> = typed(name, StringCodec, sqlType = "TEXT")

    /** Starts a fluent `TEXT` declaration backed by a custom [codec]. */
    protected fun <V> text(name: String, codec: Codec<V>): ColumnBuilder<E, V> = typed(name, codec, sqlType = "TEXT")

    /** Starts a fluent `INTEGER` column declaration. */
    protected fun integer(name: String): ColumnBuilder<E, Int> = typed(name, IntCodec, sqlType = "INTEGER")

    /** Starts a fluent `BIGINT` column declaration. */
    protected fun bigint(name: String): ColumnBuilder<E, Long> = typed(name, LongCodec, sqlType = "BIGINT")

    /** Starts a fluent `SMALLINT` column declaration. */
    protected fun smallint(name: String): ColumnBuilder<E, Short> = typed(name, ShortCodec, sqlType = "SMALLINT")

    /** Starts a fluent `REAL` column declaration. */
    protected fun real(name: String): ColumnBuilder<E, Float> = typed(name, FloatCodec, sqlType = "REAL")

    /** Starts a fluent `DOUBLE PRECISION` column declaration. */
    protected fun doublePrecision(name: String): ColumnBuilder<E, Double> =
        typed(name, DoubleCodec, sqlType = "DOUBLE PRECISION")

    /** Starts a fluent `NUMERIC(precision, scale)` column declaration. */
    protected fun decimal(name: String, precision: Int, scale: Int): ColumnBuilder<E, BigDecimal> {
        require(precision > 0) { "decimal precision must be > 0, got $precision" }
        require(scale in 0..precision) { "decimal scale must be in 0..precision, got scale=$scale precision=$precision" }
        return typed(name, BigDecimalCodec, sqlType = "NUMERIC($precision, $scale)")
    }

    /** Starts a fluent `BOOLEAN` column declaration. */
    protected fun boolean(name: String): ColumnBuilder<E, Boolean> = typed(name, BooleanCodec, sqlType = "BOOLEAN")

    /** Starts a fluent `UUID` column declaration. */
    protected fun uuid(name: String): ColumnBuilder<E, UUID> = typed(name, UuidCodec, sqlType = "UUID")

    /** Starts a fluent `TIMESTAMPTZ` column declaration. */
    protected fun timestamptz(name: String): ColumnBuilder<E, Instant> =
        typed(name, InstantCodec, sqlType = "TIMESTAMPTZ")

    /** Starts a fluent `TIMESTAMP` column declaration. */
    protected fun timestamp(name: String): ColumnBuilder<E, LocalDateTime> =
        typed(name, LocalDateTimeCodec, sqlType = "TIMESTAMP")

    /** Starts a fluent `DATE` column declaration. */
    protected fun date(name: String): ColumnBuilder<E, LocalDate> = typed(name, LocalDateCodec, sqlType = "DATE")

    /** Starts a fluent `BYTEA` column declaration. */
    protected fun bytea(name: String): ColumnBuilder<E, ByteArray> = typed(name, ByteArrayCodec, sqlType = "BYTEA")

    /** Starts a fluent TSID declaration and adds the default TSID CHECK constraint. */
    protected fun tsid(name: String): ColumnBuilder<E, Tsid> =
        typed(name, TsidCodec, sqlType = "VARCHAR(13)").check(Checks.tsid())

    /** Starts a fluent TSID declaration backed by a custom [codec]. */
    protected fun <V> tsid(name: String, codec: Codec<V>): ColumnBuilder<E, V> =
        typed(name, codec, sqlType = "VARCHAR(13)").check(Checks.tsid())

    /** Starts a fluent ULID declaration and adds the default ULID CHECK constraint. */
    protected fun ulid(name: String): ColumnBuilder<E, Ulid> =
        typed(name, UlidCodec, sqlType = "VARCHAR(26)").check(Checks.ulid())

    /** Starts a fluent ULID declaration backed by a custom [codec]. */
    protected fun <V> ulid(name: String, codec: Codec<V>): ColumnBuilder<E, V> =
        typed(name, codec, sqlType = "VARCHAR(26)").check(Checks.ulid())

    /** Starts a fluent enum column stored by [Enum.name] and constrained to declared enum values. */
    protected inline fun <reified V : Enum<V>> enumName(name: String, length: Int = 64): ColumnBuilder<E, V> =
        varchar(name, length, enumNameCodec<V>()).check(Checks.oneOf(*enumValues<V>().map { it.name }.toTypedArray()))

    // ----- Typed column builders ------------------------------------------
    //
    // Each builder wraps `column(...)` with a fixed codec and an explicit
    // sqlType so the generated DDL is precise (sized VARCHAR, NUMERIC with
    // precision/scale, REAL vs DOUBLE PRECISION, etc.) instead of falling
    // back to the dialect's default mapping.
    //
    // The overloads taking an explicit `codec` exist for `@JvmInline value`
    // classes wrapped via `ValueClassCodec` — the SQL type stays the same
    // (e.g. VARCHAR(100)), but the domain type is preserved.

    /** `VARCHAR(length)` column. */
    protected fun varchar(
        name: String,
        length: Int,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        sensitive: Boolean = false,
        getter: (E) -> String?,
    ): Column<E, String> = varchar(name, length, StringCodec, isPrimaryKey, isGenerated, isNullable, check, sensitive, getter)

    /** `VARCHAR(length)` column backed by [codec] — for value classes wrapping a String. */
    protected fun <V> varchar(
        name: String,
        length: Int,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        sensitive: Boolean = false,
        getter: (E) -> V?,
    ): Column<E, V> {
        require(length > 0) { "varchar length must be > 0, got $length" }
        return column(name, codec, isPrimaryKey, isGenerated, isNullable, check, sensitive,
            sqlType = "VARCHAR($length)", getter = getter)
    }

    /** `TEXT` column (no length cap). Equivalent to `column(name, StringCodec, ...)`. */
    protected fun text(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        sensitive: Boolean = false,
        getter: (E) -> String?,
    ): Column<E, String> = text(name, StringCodec, isPrimaryKey, isGenerated, isNullable, check, sensitive, getter)

    /** `TEXT` column backed by [codec] — for value classes wrapping a String. */
    protected fun <V> text(
        name: String,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        sensitive: Boolean = false,
        getter: (E) -> V?,
    ): Column<E, V> = column(name, codec, isPrimaryKey, isGenerated, isNullable, check, sensitive,
        sqlType = "TEXT", getter = getter)

    /** `INTEGER` column. */
    protected fun integer(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> Int?,
    ): Column<E, Int> = column(name, IntCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "INTEGER", getter = getter)

    /** `BIGINT` column (8-byte signed). */
    protected fun bigint(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> Long?,
    ): Column<E, Long> = column(name, LongCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "BIGINT", getter = getter)

    /** `SMALLINT` column (2-byte signed). */
    protected fun smallint(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> Short?,
    ): Column<E, Short> = column(name, ShortCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "SMALLINT", getter = getter)

    /** `REAL` column (single-precision float, 4 bytes). */
    protected fun real(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> Float?,
    ): Column<E, Float> = column(name, FloatCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "REAL", getter = getter)

    /** `DOUBLE PRECISION` column (double-precision float, 8 bytes). */
    protected fun doublePrecision(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> Double?,
    ): Column<E, Double> = column(name, DoubleCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "DOUBLE PRECISION", getter = getter)

    /**
     * `NUMERIC(precision, scale)` column. Use for money and any fixed-point value
     * where Double's binary rounding is unacceptable.
     */
    protected fun decimal(
        name: String,
        precision: Int,
        scale: Int,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> BigDecimal?,
    ): Column<E, BigDecimal> = decimal(name, precision, scale, BigDecimalCodec,
        isPrimaryKey, isGenerated, isNullable, check, getter)

    /** `NUMERIC(precision, scale)` column backed by [codec] — for value classes wrapping a BigDecimal. */
    protected fun <V> decimal(
        name: String,
        precision: Int,
        scale: Int,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> V?,
    ): Column<E, V> {
        require(precision > 0) { "decimal precision must be > 0, got $precision" }
        require(scale in 0..precision) { "decimal scale must be in 0..precision, got scale=$scale precision=$precision" }
        return column(name, codec, isPrimaryKey, isGenerated, isNullable, check,
            sqlType = "NUMERIC($precision, $scale)", getter = getter)
    }

    /** Alias for [decimal] — Postgres treats `DECIMAL` and `NUMERIC` as synonyms. */
    protected fun numeric(
        name: String,
        precision: Int,
        scale: Int,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> BigDecimal?,
    ): Column<E, BigDecimal> = decimal(name, precision, scale, isPrimaryKey, isGenerated, isNullable, check, getter)

    /** `BOOLEAN` column. */
    protected fun boolean(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> Boolean?,
    ): Column<E, Boolean> = column(name, BooleanCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "BOOLEAN", getter = getter)

    /** `UUID` column. */
    protected fun uuid(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> UUID?,
    ): Column<E, UUID> = uuid(name, UuidCodec, isPrimaryKey, isGenerated, isNullable, check, getter)

    /** `UUID` column backed by [codec] — for value classes wrapping a UUID. */
    protected fun <V> uuid(
        name: String,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> V?,
    ): Column<E, V> = column(name, codec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "UUID", getter = getter)

    /** `TIMESTAMPTZ` column. Maps to [java.time.Instant] via [InstantCodec]. */
    protected fun timestamptz(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> Instant?,
    ): Column<E, Instant> = column(name, InstantCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "TIMESTAMPTZ", getter = getter)

    /** `TIMESTAMP` column (no time zone). */
    protected fun timestamp(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> LocalDateTime?,
    ): Column<E, LocalDateTime> = column(name, LocalDateTimeCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "TIMESTAMP", getter = getter)

    /** `DATE` column. */
    protected fun date(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        getter: (E) -> LocalDate?,
    ): Column<E, LocalDate> = column(name, LocalDateCodec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "DATE", getter = getter)

    /** `BYTEA` column for raw binary blobs. */
    protected fun bytea(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = null,
        sensitive: Boolean = false,
        getter: (E) -> ByteArray?,
    ): Column<E, ByteArray> = column(name, ByteArrayCodec, isPrimaryKey, isGenerated, isNullable, check, sensitive,
        sqlType = "BYTEA", getter = getter)

    /**
     * 13-character TSID column stored as `VARCHAR(13)`. Defaults to attaching a
     * [Checks.tsid] format constraint so the database refuses values that don't
     * match the Crockford base-32 grammar — pass `check = null` to opt out.
     */
    protected fun tsid(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = Checks.tsid(),
        getter: (E) -> Tsid?,
    ): Column<E, Tsid> = tsid(name, TsidCodec, isPrimaryKey, isGenerated, isNullable, check, getter)

    /** TSID column backed by [codec] — for value classes wrapping a Tsid. */
    protected fun <V> tsid(
        name: String,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = Checks.tsid(),
        getter: (E) -> V?,
    ): Column<E, V> = column(name, codec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "VARCHAR(13)", getter = getter)

    /** 26-character ULID column stored as `VARCHAR(26)`. */
    protected fun ulid(
        name: String,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = Checks.ulid(),
        getter: (E) -> Ulid?,
    ): Column<E, Ulid> = ulid(name, UlidCodec, isPrimaryKey, isGenerated, isNullable, check, getter)

    /** ULID column backed by [codec] — for value classes wrapping a Ulid. */
    protected fun <V> ulid(
        name: String,
        codec: Codec<V>,
        isPrimaryKey: Boolean = false,
        isGenerated: Boolean = false,
        isNullable: Boolean = false,
        check: ((String) -> String)? = Checks.ulid(),
        getter: (E) -> V?,
    ): Column<E, V> = column(name, codec, isPrimaryKey, isGenerated, isNullable, check,
        sqlType = "VARCHAR(26)", getter = getter)

    /**
     * Declares a FOREIGN KEY relationship from this column to [target].
     *
     * Must be called inside the `Table` object body immediately after the column
     * declaration it relates to. Returns the same column so it can be chained
     * fluently with the `column(...)` call.
     *
     * ```kotlin
     * object OrdersTable : Table<Order>("orders") {
     *     val id         = column("id", OrderIdCodec, isPrimaryKey = true) { it.id }
     *     val customerId = column("customer_id", IntCodec) { it.customerId }
     *         .references(CustomersTable.id, onDelete = ForeignKeyAction.CASCADE)
     *     val productId  = column("product_id", IntCodec) { it.productId }
     *         .references(ProductsTable.id)  // defaults: RESTRICT / RESTRICT
     * }
     * ```
     *
     * The value type [V] must match on both sides — the compiler enforces that
     * you can only reference a column that holds the same Kotlin type.
     *
     * [target] must be a `isPrimaryKey = true` or `UNIQUE` column on the parent
     * table; Aggo does not enforce this at compile time but the database will.
     *
     * Generate DDL via `com.aggitech.aggo.migration.addForeignKeyConstraintsSql(dialect)`
     * in your migration scripts — DDL generation belongs to the migration layer.
     */
    protected fun <V, R> Column<E, V>.references(
        target: Column<R, V>,
        onDelete: ForeignKeyAction = ForeignKeyAction.RESTRICT,
        onUpdate: ForeignKeyAction = ForeignKeyAction.RESTRICT,
        constraintName: String? = null,
        key: String? = null,
    ): Column<E, V> {
        mutableForeignKeys += ForeignKey(
            column = this,
            referencedColumn = target,
            onDelete = onDelete,
            onUpdate = onUpdate,
            constraintName = constraintName,
            key = key,
        )
        return this
    }

    protected inner class ColumnBuilder<T, V> internal constructor(
        private val owner: Table<T>,
        private val name: String,
        private val codec: Codec<V>,
        private val sqlType: String?,
        private val isPrimaryKey: Boolean = false,
        private val isGenerated: Boolean = false,
        private val isNullable: Boolean = true,
        private val checks: List<CheckConstraint> = emptyList(),
        private val unique: UniqueConstraint? = null,
        private val sensitive: Boolean = false,
        private val pendingForeignKey: PendingForeignKey<V>? = null,
        private val indexDeclaration: IndexDeclaration? = null,
    ) {
        /** Marks the column as `NOT NULL` and keeps building the column. */
        fun required(): ColumnBuilder<T, V> = copy(isNullable = false)

        /** Marks the column as `NOT NULL` and registers it with [getter]. */
        fun required(getter: (T) -> V?): Column<T, V> = required().map(getter)

        /** Marks the column as nullable and keeps building the column. */
        fun optional(): ColumnBuilder<T, V> = copy(isNullable = true)

        /** Marks the column as nullable and registers it with [getter]. */
        fun optional(getter: (T) -> V?): Column<T, V> = optional().map(getter)

        /** Marks the column as part of the table primary key. Primary keys are always not-null. */
        fun primaryKey(): ColumnBuilder<T, V> = copy(isPrimaryKey = true, isNullable = false)

        /** Marks the column as database-generated so INSERT builders skip it by default. */
        fun generated(): ColumnBuilder<T, V> = copy(isGenerated = true)

        /** Redacts values from this column in query logs. */
        fun sensitive(): ColumnBuilder<T, V> = copy(sensitive = true)

        /**
         * Adds a named CHECK constraint.
         *
         * [key] is the stable application-facing key returned by typed error
         * mapping when the database reports this constraint.
         */
        fun check(
            check: (String) -> String,
            name: String? = null,
            key: String? = null,
        ): ColumnBuilder<T, V> = copy(checks = checks + CheckConstraint(check, name, key))

        /** Adds a CHECK constraint and registers the column with [getter]. */
        fun check(
            check: (String) -> String,
            name: String? = null,
            key: String? = null,
            getter: (T) -> V?,
        ): Column<T, V> = check(check, name, key).map(getter)

        /**
         * Adds a single-column UNIQUE constraint.
         *
         * [key] is used by `constraintErrorMap(...)` to map violations into a
         * typed [com.aggitech.aggo.runtime.ConstraintError].
         */
        fun unique(name: String? = null, key: String? = null): ColumnBuilder<T, V> =
            copy(unique = UniqueConstraint(name, key))

        /**
         * Declares a database index on this column.
         *
         * Emits a `CREATE INDEX … USING <method>` statement in the migration plan.
         * Use [IndexMethod.GIN] for full-text / JSONB columns, [IndexMethod.BTREE]
         * (the default) for equality and range queries.
         *
         * ```kotlin
         * val email = text("email").index().required { it.email }
         * val body  = text("body").index(method = IndexMethod.GIN).required { it.body }
         * ```
         */
        fun index(
            name: String? = null,
            method: IndexMethod = IndexMethod.BTREE,
        ): ColumnBuilder<T, V> = copy(indexDeclaration = IndexDeclaration(name, method))

        /**
         * Declares a foreign key to [target] and keeps building the column.
         *
         * The Kotlin value type must match the referenced column type.
         */
        fun <R> references(
            target: Column<R, V>,
            onDelete: ForeignKeyAction = ForeignKeyAction.RESTRICT,
            onUpdate: ForeignKeyAction = ForeignKeyAction.RESTRICT,
            constraintName: String? = null,
            key: String? = null,
        ): ColumnBuilder<T, V> = copy(
            pendingForeignKey = PendingForeignKey(target, onDelete, onUpdate, constraintName, key)
        )

        /** Declares a foreign key and registers the column with [getter]. */
        fun <R> references(
            target: Column<R, V>,
            onDelete: ForeignKeyAction = ForeignKeyAction.RESTRICT,
            onUpdate: ForeignKeyAction = ForeignKeyAction.RESTRICT,
            constraintName: String? = null,
            key: String? = null,
            getter: (T) -> V?,
        ): Column<T, V> = references(target, onDelete, onUpdate, constraintName, key).map(getter)

        /** Registers the final column in the owning table. */
        fun map(getter: (T) -> V?): Column<T, V> {
            val col = owner.column(
                name = name,
                codec = codec,
                isPrimaryKey = isPrimaryKey,
                isGenerated = isGenerated,
                isNullable = isNullable,
                checks = checks,
                unique = unique,
                sensitive = sensitive,
                sqlType = sqlType,
                getter = getter,
                indexDeclaration = indexDeclaration,
            )
            pendingForeignKey?.let { fk ->
                @Suppress("UNCHECKED_CAST")
                val self = col as Column<E, V>
                self.references(
                    target = fk.target,
                    onDelete = fk.onDelete,
                    onUpdate = fk.onUpdate,
                    constraintName = fk.constraintName,
                    key = fk.key,
                )
            }
            return col
        }

        /** Shorthand for [map], enabling `varchar("name").required() { it.name }`. */
        operator fun invoke(getter: (T) -> V?): Column<T, V> = map(getter)

        private fun copy(
            isPrimaryKey: Boolean = this.isPrimaryKey,
            isGenerated: Boolean = this.isGenerated,
            isNullable: Boolean = this.isNullable,
            checks: List<CheckConstraint> = this.checks,
            unique: UniqueConstraint? = this.unique,
            sensitive: Boolean = this.sensitive,
            pendingForeignKey: PendingForeignKey<V>? = this.pendingForeignKey,
            indexDeclaration: IndexDeclaration? = this.indexDeclaration,
        ): ColumnBuilder<T, V> = ColumnBuilder(
            owner = owner,
            name = name,
            codec = codec,
            sqlType = sqlType,
            isPrimaryKey = isPrimaryKey,
            isGenerated = isGenerated,
            isNullable = isNullable,
            checks = checks,
            unique = unique,
            sensitive = sensitive,
            pendingForeignKey = pendingForeignKey,
            indexDeclaration = indexDeclaration,
        )
    }

    protected data class PendingForeignKey<V>(
        val target: Column<*, V>,
        val onDelete: ForeignKeyAction,
        val onUpdate: ForeignKeyAction,
        val constraintName: String?,
        val key: String?,
    )

    /**
     * Build an entity from a result Row. Implementations should call
     * `column.read(row)` for each declared column. No reflection.
     */
    abstract fun fromRow(row: Row): E

    override fun toString(): String = "Table($name)"
}
