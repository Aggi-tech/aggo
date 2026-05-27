package com.aggitech.aggo.query

import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

/** Ordering on an [Expr] for use in `aggregate { orderBy { ... } }` blocks. */
data class AggOrdering(val expr: Expr<*>, val direction: OrderDir)

/**
 * A GROUP BY / aggregate SELECT query. Produces typed rows readable through
 * [com.aggitech.aggo.runtime.AggRow]. Execute with
 * [com.aggitech.aggo.runtime.Session.fetchAggregate] or
 * [com.aggitech.aggo.runtime.Session.streamAggregate].
 *
 * Build with the [com.aggitech.aggo.dsl.aggregate] DSL function.
 *
 * ```kotlin
 * val total = count(Orders.id) `as` "total"
 * val avgAmt = avg(Orders.amount) `as` "avg_amount"
 *
 * val q = aggregate(Orders) {
 *     project(total)
 *     project(avgAmt)
 *     where { Orders.active eq true }
 *     groupBy(Orders.customerId)
 *     having { count(Orders.id) gt 0L }
 *     orderBy { avgAmt.desc() }
 *     limit(100)
 * }
 *
 * aggo.read {
 *     fetchAggregate(q).map { row -> row[total]!! to row[avgAmt]!! }
 * }
 * ```
 */
data class AggregateSelect<E>(
    val table: Table<E>,
    val projections: List<NamedExpr<*>>,
    val where: Predicate? = null,
    val groupBy: List<Column<E, *>> = emptyList(),
    val having: Predicate? = null,
    val orderBy: List<AggOrdering> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
) {
    init {
        require(projections.isNotEmpty()) { "AggregateSelect requires at least one projection" }
        if (limit != null) require(limit in 0..Select.MAX_LIMIT) { "limit out of range: $limit" }
        if (offset != null) require(offset >= 0) { "offset must be >= 0: $offset" }
    }
}

/** Direction for ORDER BY clauses. */
enum class OrderDir { ASC, DESC }

data class Ordering<E, V>(val column: Column<E, V>, val direction: OrderDir)

/** A column assignment (e.g. INSERT VALUES or UPDATE SET pair). */
data class Assignment<E, V>(val column: Column<E, V>, val value: V?, val codec: Codec<V>)

data class JoinOrdering(val column: Column<*, *>, val direction: OrderDir)

enum class JoinType(val sql: String) {
    LEFT("LEFT JOIN"),
}

data class JoinClause<R>(
    val type: JoinType,
    val table: Table<R>,
    val on: Predicate,
)

data class JoinedRow<L, R>(
    val left: L,
    val right: R?,
)

/** Sealed root for all query shapes — lets `when` be exhaustive in renderers. */
sealed interface Query<E> {
    val table: Table<E>
}

data class Select<E>(
    override val table: Table<E>,
    val where: Predicate? = null,
    val orderBy: List<Ordering<E, *>> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
) : Query<E> {
    init {
        if (limit != null) require(limit in 0..MAX_LIMIT) { "limit out of range: $limit" }
        if (offset != null) require(offset >= 0) { "offset must be >= 0: $offset" }
    }

    companion object { const val MAX_LIMIT = 10_000_000 }
}

data class JoinSelect<L, R>(
    val leftTable: Table<L>,
    val join: JoinClause<R>,
    val where: Predicate? = null,
    val orderBy: List<JoinOrdering> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
) {
    init {
        if (limit != null) require(limit in 0..Select.MAX_LIMIT) { "limit out of range: $limit" }
        if (offset != null) require(offset >= 0) { "offset must be >= 0: $offset" }
    }

    val rightTable: Table<R> get() = join.table
}

data class Insert<E>(
    override val table: Table<E>,
    val assignments: List<Assignment<E, *>>,
) : Query<E> {
    init { require(assignments.isNotEmpty()) { "INSERT requires at least one assignment" } }
}

data class Update<E>(
    override val table: Table<E>,
    val assignments: List<Assignment<E, *>>,
    val where: Predicate? = null,
) : Query<E> {
    init { require(assignments.isNotEmpty()) { "UPDATE requires at least one assignment" } }
}

data class Delete<E>(
    override val table: Table<E>,
    val where: Predicate? = null,
) : Query<E>

/**
 * A SELECT query that returns only a subset of columns, decoded into [com.aggitech.aggo.runtime.ProjectedRow].
 *
 * Build with [com.aggitech.aggo.dsl.selectProjection]. Execute with
 * [com.aggitech.aggo.runtime.Session.fetchProjection] or [com.aggitech.aggo.runtime.Session.streamProjection].
 *
 * ```kotlin
 * val q = selectProjection(UsersTable, UsersTable.id, UsersTable.email) {
 *     where { UsersTable.active eq true }
 *     orderBy { UsersTable.name.asc() }
 *     limit(50)
 * }
 *
 * aggo.read {
 *     fetchProjection(q).map { row ->
 *         UserDto(id = row[UsersTable.id]!!, email = row[UsersTable.email]!!)
 *     }
 * }
 * ```
 */
data class ProjectionSelect<E>(
    override val table: Table<E>,
    val columns: List<Column<E, *>>,
    val where: Predicate? = null,
    val orderBy: List<Ordering<E, *>> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
) : Query<E> {
    init {
        require(columns.isNotEmpty()) { "ProjectionSelect requires at least one column" }
        if (limit != null) require(limit in 0..Select.MAX_LIMIT) { "limit out of range: $limit" }
        if (offset != null) require(offset >= 0) { "offset must be >= 0: $offset" }
    }
}
