package com.aggitech.aggo.query

import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table

/** Direction for ORDER BY clauses. */
enum class OrderDir { ASC, DESC }

data class Ordering<E, V>(val column: Column<E, V>, val direction: OrderDir)

/** A column assignment (e.g. INSERT VALUES or UPDATE SET pair). */
data class Assignment<E, V>(val column: Column<E, V>, val value: V?, val codec: Codec<V>)

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
