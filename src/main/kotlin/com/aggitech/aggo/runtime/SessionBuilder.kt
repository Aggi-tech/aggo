package com.aggitech.aggo.runtime

import com.aggitech.aggo.dsl.AggregateBuilder
import com.aggitech.aggo.dsl.ProjectionSelectBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.query.AggregateSelect
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.JoinedRow
import com.aggitech.aggo.query.ProjectionSelect
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class SessionBuilder internal constructor(
    private val coordinator: ExecutionCoordinator,
) {
    suspend operator fun <T> invoke(block: suspend (SessionScope) -> T): T =
        coordinator.session(block)

    suspend fun <T> query(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend (SessionScope) -> T,
    ): Query<T, AggoError> =
        coordinator.sessionResult(errorMap, block)

    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        invoke { session -> session.fetchAll(query) }

    suspend fun <E> fetchAll(query: Select<E>, errorMap: ConstraintErrorMap): Query<List<E>, AggoError> =
        query(errorMap) { session -> session.fetchAll(query) }

    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        invoke { session -> session.fetchAll(table, block) }

    suspend fun <E> fetchAll(
        table: Table<E>,
        errorMap: ConstraintErrorMap,
        block: SelectBuilder<E>.() -> Unit = {},
    ): Query<List<E>, AggoError> =
        query(errorMap) { session -> session.fetchAll(table, block) }

    suspend fun <E> paginate(query: Select<E>, page: Int, size: Int): Triple<List<E>, Long, Int> =
        invoke { session -> session.paginate(query, page, size) }

    suspend fun <E> paginate(
        query: Select<E>,
        page: Int,
        size: Int,
        errorMap: ConstraintErrorMap,
    ): Query<Triple<List<E>, Long, Int>, AggoError> =
        query(errorMap) { session -> session.paginate(query, page, size) }

    suspend fun <E> paginate(
        table: Table<E>,
        page: Int,
        size: Int,
        block: SelectBuilder<E>.() -> Unit = {},
    ): Triple<List<E>, Long, Int> =
        invoke { session -> session.paginate(table, page, size, block) }

    suspend fun <E> paginate(
        table: Table<E>,
        page: Int,
        size: Int,
        errorMap: ConstraintErrorMap,
        block: SelectBuilder<E>.() -> Unit = {},
    ): Query<Triple<List<E>, Long, Int>, AggoError> =
        query(errorMap) { session -> session.paginate(table, page, size, block) }

    suspend fun <E> fetchOne(query: Select<E>): E? =
        invoke { session -> session.fetchOne(query) }

    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        invoke { session -> session.fetchOne(table, block) }

    fun <E> stream(query: Select<E>): Flow<E> =
        flow {
            invoke { session ->
                session.stream(query).collect { value -> emit(value) }
            }
        }

    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E> =
        flow {
            invoke { session ->
                session.stream(table, block).collect { value -> emit(value) }
            }
        }

    suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>> =
        invoke { session -> session.fetchAllJoined(query) }

    fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>> =
        flow {
            invoke { session ->
                session.streamJoined(query).collect { value -> emit(value) }
            }
        }

    suspend fun <L, R, A> fetchAll(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): List<A> =
        invoke { session -> session.fetchAll(query, map) }

    suspend fun <L, R, A> fetchOne(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): A? =
        invoke { session -> session.fetchOne(query, map) }

    fun <L, R, A> stream(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): Flow<A> =
        flow {
            invoke { session ->
                session.stream(query, map).collect { value -> emit(value) }
            }
        }

    suspend fun <E> fetchProjection(query: ProjectionSelect<E>): List<ProjectedRow> =
        invoke { session -> session.fetchProjection(query) }

    suspend fun <E> fetchProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): List<ProjectedRow> =
        invoke { session -> session.fetchProjection(table, *columns, block = block) }

    suspend fun <E> fetchOneProjection(query: ProjectionSelect<E>): ProjectedRow? =
        invoke { session -> session.fetchOneProjection(query) }

    suspend fun <E> fetchOneProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): ProjectedRow? =
        invoke { session -> session.fetchOneProjection(table, *columns, block = block) }

    fun <E> streamProjection(query: ProjectionSelect<E>): Flow<ProjectedRow> =
        flow {
            invoke { session ->
                session.streamProjection(query).collect { value -> emit(value) }
            }
        }

    fun <E> streamProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): Flow<ProjectedRow> =
        flow {
            invoke { session ->
                session.streamProjection(table, *columns, block = block).collect { value -> emit(value) }
            }
        }

    suspend fun <E> fetchAggregate(query: AggregateSelect<E>): List<AggRow> =
        invoke { session -> session.fetchAggregate(query) }

    suspend fun <E> fetchAggregate(table: Table<E>, block: AggregateBuilder<E>.() -> Unit): List<AggRow> =
        invoke { session -> session.fetchAggregate(table, block) }

    fun <E> streamAggregate(query: AggregateSelect<E>): Flow<AggRow> =
        flow {
            invoke { session ->
                session.streamAggregate(query).collect { value -> emit(value) }
            }
        }

    fun <E> streamAggregate(table: Table<E>, block: AggregateBuilder<E>.() -> Unit): Flow<AggRow> =
        flow {
            invoke { session ->
                session.streamAggregate(table, block).collect { value -> emit(value) }
            }
        }

    suspend fun readLatestSnapshot(): String? =
        invoke { session -> session.readLatestSnapshot() }
}
