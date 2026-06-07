package com.aggitech.aggo.runtime

import com.aggitech.aggo.dsl.AggregateBuilder
import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.ProjectionSelectBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.migration.MigrationFileEntry
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.migration.readMigrationFiles
import com.aggitech.aggo.query.AggregateSelect
import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.JoinedRow
import com.aggitech.aggo.query.ProjectionSelect
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow

/**
 * Read-only execution capability passed to `aggo.session { session -> ... }`.
 *
 * This interface intentionally has no insert/update/delete/migration methods,
 * so write operations are impossible in a session scope at compile time.
 */
interface SessionScope {
    suspend fun <E> fetchAll(query: Select<E>): List<E>
    suspend fun <E> fetchAll(query: Select<E>, errorMap: ConstraintErrorMap): Query<List<E>, AggoError>
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E>
    suspend fun <E> fetchAll(
        table: Table<E>,
        errorMap: ConstraintErrorMap,
        block: SelectBuilder<E>.() -> Unit = {},
    ): Query<List<E>, AggoError>

    suspend fun <E> paginate(query: Select<E>, page: Int, size: Int): Triple<List<E>, Long, Int>
    suspend fun <E> paginate(
        query: Select<E>,
        page: Int,
        size: Int,
        errorMap: ConstraintErrorMap,
    ): Query<Triple<List<E>, Long, Int>, AggoError>
    suspend fun <E> paginate(
        table: Table<E>,
        page: Int,
        size: Int,
        block: SelectBuilder<E>.() -> Unit = {},
    ): Triple<List<E>, Long, Int>
    suspend fun <E> paginate(
        table: Table<E>,
        page: Int,
        size: Int,
        errorMap: ConstraintErrorMap,
        block: SelectBuilder<E>.() -> Unit = {},
    ): Query<Triple<List<E>, Long, Int>, AggoError>

    suspend fun <E> fetchOne(query: Select<E>): E?
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E?
    fun <E> stream(query: Select<E>): Flow<E>
    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E>

    suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>>
    fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>>

    /**
     * Joined form of [fetchAll]: executes [query] and maps each [JoinedRow] into a
     * populated domain entity through [map]. The right side is `null` on LEFT JOIN
     * no-match, exactly like [fetchAllJoined].
     *
     * ```kotlin
     * val orders: List<OrderWithCustomer> = fetchAll(
     *     Orders.leftJoin(Customers) { Orders.customerId eq Customers.id },
     * ) { order, customer -> OrderWithCustomer(order, customer) }
     * ```
     */
    suspend fun <L, R, A> fetchAll(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): List<A>

    /**
     * Joined form of [fetchOne]: executes [query] with `LIMIT 1` and maps the first
     * [JoinedRow] into a populated domain entity through [map], or returns `null`
     * when no row matches.
     */
    suspend fun <L, R, A> fetchOne(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): A?

    /** Joined, streaming form of [fetchAll]: maps each [JoinedRow] through [map] lazily. */
    fun <L, R, A> stream(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): Flow<A>

    suspend fun <E> fetchProjection(query: ProjectionSelect<E>): List<ProjectedRow>
    suspend fun <E> fetchProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): List<ProjectedRow>
    suspend fun <E> fetchOneProjection(query: ProjectionSelect<E>): ProjectedRow?
    suspend fun <E> fetchOneProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): ProjectedRow?
    fun <E> streamProjection(query: ProjectionSelect<E>): Flow<ProjectedRow>
    fun <E> streamProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit = {},
    ): Flow<ProjectedRow>

    suspend fun <E> fetchAggregate(query: AggregateSelect<E>): List<AggRow>
    suspend fun <E> fetchAggregate(table: Table<E>, block: AggregateBuilder<E>.() -> Unit): List<AggRow>
    fun <E> streamAggregate(query: AggregateSelect<E>): Flow<AggRow>
    fun <E> streamAggregate(table: Table<E>, block: AggregateBuilder<E>.() -> Unit): Flow<AggRow>

    /**
     * Returns the JSON snapshot stored for the most recently applied migration version,
     * or `null` if no snapshot has been recorded yet (fresh DB, or DB predates snapshot support).
     *
     * Used by [AggoMigrateTask] so `generate` always diffs against the authoritative DB state
     * rather than a local file that may have been deleted by a build-tool clean.
     */
    suspend fun readLatestSnapshot(): String?
}

/**
 * Transactional execution capability passed to `aggo.tx { tx -> ... }`.
 */
interface TransactionScope : SessionScope {
    suspend fun <E> insert(query: Insert<E>): Long
    suspend fun <E> insert(table: Table<E>, entity: E): Long
    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long
    suspend fun <E, V> insertReturning(query: Insert<E>, pkColumn: Column<E, V>): V?
    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V?

    suspend fun <E> update(query: Update<E>): Long
    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long
    suspend fun <E> delete(query: Delete<E>): Long
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long

    suspend fun applyMigration(plan: MigrationPlan): MigrationResult
    suspend fun applyMigrations(entries: List<MigrationFileEntry>): List<MigrationResult>
    suspend fun applyMigrations(migrationsDir: Path): List<MigrationResult> =
        applyMigrations(readMigrationFiles(migrationsDir))

    /**
     * Persists [snapshotJson] (a [com.aggitech.aggo.migration.MigrationSchema] serialised with
     * [com.aggitech.aggo.migration.toJson]) into the `snapshot` column of the
     * `aggo_schema_versions` row identified by [version].
     *
     * Call this after [applyMigrations] so the DB remains the authoritative snapshot source and
     * a subsequent `generate` always diffs against the actual applied state.
     */
    suspend fun storeSnapshot(version: String, snapshotJson: String)
}
