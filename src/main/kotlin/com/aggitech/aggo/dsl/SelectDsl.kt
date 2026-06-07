package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.OrderDir
import com.aggitech.aggo.query.Ordering
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.query.ProjectionSelect
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Scope used inside `where { … }` blocks. Predicate operators are extension functions on this scope. */
object WhereScope

/**
 * Scope used inside `orderBy { … }` blocks.
 *
 * ```kotlin
 * orderBy {
 *     UsersTable.name.asc()
 *     UsersTable.createdAt.desc()
 * }
 * ```
 */
class OrderByScope<E> internal constructor(private val sink: MutableList<Ordering<E, *>>) {
    /** Add an ascending sort on this column. */
    fun <V> Column<E, V>.asc() { sink += Ordering(this, OrderDir.ASC) }
    /** Add a descending sort on this column. */
    fun <V> Column<E, V>.desc() { sink += Ordering(this, OrderDir.DESC) }
}

/**
 * Builder for SELECT queries. Obtained via [select].
 *
 * All methods are optional; calling `select(MyTable)` with no block returns a
 * query that selects all columns with no filter, order, or limit.
 */
class SelectBuilder<E> internal constructor(val table: Table<E>) {
    private var where: Predicate? = null
    private val orderBy: MutableList<Ordering<E, *>> = mutableListOf()
    private var limit: Int? = null
    private var offset: Int? = null
    private var distinct: Boolean = false
    private var alias: String? = null

    /**
     * Filter rows. The block must return a [Predicate] built from the column
     * operators (`eq`, `like`, `inList`, `and`, `or`, etc.).
     *
     * ```kotlin
     * where { (UsersTable.active eq true) and (UsersTable.name like "%alice%") }
     * ```
     */
    @OptIn(ExperimentalContracts::class)
    fun where(block: WhereScope.() -> Predicate) {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        where = WhereScope.block()
    }

    /**
     * Add one or more sort columns. Call [OrderByScope.asc] / [OrderByScope.desc]
     * on any column descriptor inside the block.
     *
     * ```kotlin
     * orderBy { UsersTable.createdAt.desc() }
     * ```
     */
    @OptIn(ExperimentalContracts::class)
    fun orderBy(block: OrderByScope<E>.() -> Unit) {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        OrderByScope(orderBy).block()
    }

    /** Maximum number of rows to return. Passed as `LIMIT n` to the database. */
    fun limit(n: Int) { limit = n }

    /** Number of rows to skip before returning results. Passed as `OFFSET n`. */
    fun offset(n: Int) { offset = n }

    /**
     * Eliminate duplicate rows from the result set. Renders as `SELECT DISTINCT ...`.
     *
     * ```kotlin
     * select(Orders) {
     *     distinct()
     *     where { Orders.status eq "shipped" }
     * }
     * ```
     */
    fun distinct() { distinct = true }

    /**
     * Give the table a SQL alias for this query — renders `FROM "table" AS "p"`
     * and qualifies every column reference (`WHERE`, `ORDER BY`) with `"p"."col"`
     * instead of `"table"."col"`. Useful for self-joins, correlated subqueries,
     * or simply shortening verbose table names in the rendered SQL.
     *
     * ```kotlin
     * select(People) {
     *     alias("p")
     *     where { People.active eq true }
     * }
     * // SELECT "id", ... FROM "people" AS "p" WHERE "p"."active" = $1
     * ```
     */
    fun alias(name: String) { alias = name }

    internal fun build(): Select<E> = Select(table, where, orderBy.toList(), limit, offset, distinct, alias)
}

/**
 * Builds a SELECT query for [table].
 *
 * Returns an immutable [Select] descriptor. Execute it with [Session.fetchAll],
 * [Session.fetchOne], or [Session.stream].
 *
 * ```kotlin
 * // All rows
 * val q = select(UsersTable)
 *
 * // With filter, sort, and paging
 * val q = select(UsersTable) {
 *     where { (UsersTable.active eq true) and (UsersTable.name like "%alice%") }
 *     orderBy { UsersTable.name.asc() }
 *     limit(20)
 *     offset(40)
 * }
 *
 * // Execute inside a session
 * aggo.read { fetchAll(q) }
 *
 * // Or use the inline form directly on the session:
 * aggo.read {
 *     fetchAll(UsersTable) { where { UsersTable.active eq true } }
 * }
 * ```
 */
@OptIn(ExperimentalContracts::class)
fun <E> select(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Select<E> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return SelectBuilder(table).apply(block).build()
}

/**
 * Builder for partial-column SELECT queries. Obtained via [selectProjection].
 *
 * Columns declared in the vararg are projected in order. Additional columns can
 * be added inside the block with `+Column`:
 *
 * ```kotlin
 * selectProjection(UsersTable, UsersTable.id, UsersTable.email) {
 *     +UsersTable.name
 *     where { UsersTable.active eq true }
 *     orderBy { UsersTable.createdAt.desc() }
 *     limit(50)
 * }
 * ```
 */
class ProjectionSelectBuilder<E> internal constructor(
    val table: Table<E>,
    initialColumns: List<Column<E, *>>,
) {
    private val columns: MutableList<Column<E, *>> = initialColumns.toMutableList()
    private var where: Predicate? = null
    private val orderBy: MutableList<Ordering<E, *>> = mutableListOf()
    private var limit: Int? = null
    private var offset: Int? = null

    /** Add a column to the projection list. */
    operator fun <V> Column<E, V>.unaryPlus() { columns += this }

    @OptIn(ExperimentalContracts::class)
    fun where(block: WhereScope.() -> Predicate) {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        where = WhereScope.block()
    }

    @OptIn(ExperimentalContracts::class)
    fun orderBy(block: OrderByScope<E>.() -> Unit) {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        OrderByScope(orderBy).block()
    }
    @Deprecated(
        message = "Use Session.paginate(..., page, size) for application pagination. Keep limit only for low-level query descriptors.",
    )
    fun limit(n: Int) { limit = n }

    @Deprecated(
        message = "Use Session.paginate(..., page, size) for application pagination. Keep offset only for low-level query descriptors.",
    )
    fun offset(n: Int) { offset = n }

    internal fun build(): ProjectionSelect<E> =
        ProjectionSelect(table, columns.toList(), where, orderBy.toList(), limit, offset)
}

/**
 * Builds a partial-column SELECT query.
 *
 * Only the listed [columns] are sent to the database. Results are decoded into
 * [com.aggitech.aggo.runtime.ProjectedRow] and indexed by the same [Column] references.
 *
 * ```kotlin
 * // Simple projection
 * val q = selectProjection(UsersTable, UsersTable.id, UsersTable.email)
 *
 * // With filter and ordering
 * val q = selectProjection(UsersTable, UsersTable.id, UsersTable.email) {
 *     where { UsersTable.active eq true }
 *     orderBy { UsersTable.name.asc() }
 *     limit(100)
 * }
 *
 * // Execute and map to a DTO
 * aggo.read {
 *     fetchProjection(q).map { row ->
 *         UserDto(id = row[UsersTable.id]!!, email = row[UsersTable.email]!!)
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalContracts::class)
fun <E> selectProjection(
    table: Table<E>,
    vararg columns: Column<E, *>,
    block: ProjectionSelectBuilder<E>.() -> Unit = {},
): ProjectionSelect<E> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return ProjectionSelectBuilder(table, columns.toList()).apply(block).build()
}
