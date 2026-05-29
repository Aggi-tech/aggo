package com.aggitech.aggo.runtime

import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.ProjectionSelectBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.migration.MigrationFileEntry
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.migration.readMigrationFiles
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.JoinedRow
import com.aggitech.aggo.query.ProjectionSelect
import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.query.AggregateSelect
import com.aggitech.aggo.dsl.AggregateBuilder
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class TransactionBuilder internal constructor(
    private val coordinator: ExecutionCoordinator,
) {
    suspend operator fun <T> invoke(block: suspend (TransactionScope) -> T): T =
        coordinator.transaction(block)

    suspend fun <T> result(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend (TransactionScope) -> T,
    ): Transaction<T, AggoError> =
        coordinator.transactionResult(errorMap, block)

    @AggoUnsafe
    suspend fun <T> unsafe(block: suspend (Session) -> T): T =
        coordinator.unsafeTransaction(block)

    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        invoke { tx -> tx.fetchAll(query) }

    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        invoke { tx -> tx.fetchAll(table, block) }

    suspend fun <E> fetchOne(query: Select<E>): E? =
        invoke { tx -> tx.fetchOne(query) }

    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        invoke { tx -> tx.fetchOne(table, block) }

    suspend fun <E> paginate(query: Select<E>, page: Int, size: Int): Triple<List<E>, Long, Int> =
        invoke { tx -> tx.paginate(query, page, size) }

    suspend fun <E> paginate(
        table: Table<E>,
        page: Int,
        size: Int,
        block: SelectBuilder<E>.() -> Unit = {},
    ): Triple<List<E>, Long, Int> =
        invoke { tx -> tx.paginate(table, page, size, block) }

    fun <E> stream(query: Select<E>): Flow<E> =
        flow {
            invoke { tx ->
                tx.stream(query).collect { value -> emit(value) }
            }
        }

    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E> =
        flow {
            invoke { tx ->
                tx.stream(table, block).collect { value -> emit(value) }
            }
        }

    suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>> =
        invoke { tx -> tx.fetchAllJoined(query) }

    fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>> =
        flow {
            invoke { tx ->
                tx.streamJoined(query).collect { value -> emit(value) }
            }
        }

    suspend fun <E> fetchProjection(query: ProjectionSelect<E>): List<ProjectedRow> =
        invoke { tx -> tx.fetchProjection(query) }

    suspend fun <E> fetchProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): List<ProjectedRow> =
        invoke { tx -> tx.fetchProjection(table, *columns, block = block) }

    suspend fun <E> fetchOneProjection(query: ProjectionSelect<E>): ProjectedRow? =
        invoke { tx -> tx.fetchOneProjection(query) }

    suspend fun <E> fetchOneProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): ProjectedRow? =
        invoke { tx -> tx.fetchOneProjection(table, *columns, block = block) }

    fun <E> streamProjection(query: ProjectionSelect<E>): Flow<ProjectedRow> =
        flow {
            invoke { tx ->
                tx.streamProjection(query).collect { value -> emit(value) }
            }
        }

    fun <E> streamProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): Flow<ProjectedRow> =
        flow {
            invoke { tx ->
                tx.streamProjection(table, *columns, block = block).collect { value -> emit(value) }
            }
        }

    suspend fun <E> fetchAggregate(query: AggregateSelect<E>): List<AggRow> =
        invoke { tx -> tx.fetchAggregate(query) }

    suspend fun <E> fetchAggregate(table: Table<E>, block: AggregateBuilder<E>.() -> Unit): List<AggRow> =
        invoke { tx -> tx.fetchAggregate(table, block) }

    fun <E> streamAggregate(query: AggregateSelect<E>): Flow<AggRow> =
        flow {
            invoke { tx ->
                tx.streamAggregate(query).collect { value -> emit(value) }
            }
        }

    fun <E> streamAggregate(table: Table<E>, block: AggregateBuilder<E>.() -> Unit): Flow<AggRow> =
        flow {
            invoke { tx ->
                tx.streamAggregate(table, block).collect { value -> emit(value) }
            }
        }

    suspend fun <E> insert(query: Insert<E>): Long =
        invoke { tx -> tx.insert(query) }

    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        invoke { tx -> tx.insert(table, entity) }

    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
        invoke { tx -> tx.insert(table, block) }

    suspend fun <E, V> insertReturning(query: Insert<E>, pkColumn: Column<E, V>): V? =
        invoke { tx -> tx.insertReturning(query, pkColumn) }

    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V? =
        invoke { tx -> tx.insertReturning(table, pkColumn, block) }

    suspend fun <E> update(query: Update<E>): Long =
        invoke { tx -> tx.update(query) }

    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        invoke { tx -> tx.update(table, block) }

    suspend fun <E> delete(query: Delete<E>): Long =
        invoke { tx -> tx.delete(query) }

    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        invoke { tx -> tx.delete(table, block) }

    suspend fun applyMigration(plan: MigrationPlan): MigrationResult =
        invoke { tx -> tx.applyMigration(plan) }

    suspend fun applyMigrations(entries: List<MigrationFileEntry>): List<MigrationResult> =
        invoke { tx -> tx.applyMigrations(entries) }

    suspend fun applyMigrations(migrationsDir: Path): List<MigrationResult> =
        applyMigrations(readMigrationFiles(migrationsDir))

    suspend fun storeSnapshot(version: String, snapshotJson: String) =
        invoke { tx -> tx.storeSnapshot(version, snapshotJson) }
}
