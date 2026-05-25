package com.aggitech.aggo.runtime

import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.migration.MigrationStep
import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.JoinedRow
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.render.RenderedSql
import com.aggitech.aggo.render.renderDelete
import com.aggitech.aggo.render.renderInsert
import com.aggitech.aggo.render.renderJoinSelect
import com.aggitech.aggo.render.renderSelect
import com.aggitech.aggo.render.renderUpdate
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import io.r2dbc.spi.Connection
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import org.reactivestreams.Publisher

/**
 * Query execution context bound to a single [Connection].
 *
 * You never instantiate `Session` directly. It is the receiver (`this`) inside
 * [Aggo.read] and [Aggo.tx] blocks:
 *
 * ```kotlin
 * aggo.read {
 *     // `this` is a Session — call fetchAll, fetchOne, stream, fetchAllJoined, etc.
 *     val users = fetchAll(UsersTable) { where { UsersTable.active eq true } }
 * }
 *
 * aggo.tx {
 *     // All statements share the same connection and transaction
 *     update(UsersTable) {
 *         UsersTable.name setTo "New Name"
 *         where { UsersTable.id eq userId }
 *     }
 *     insert(AuditTable) { AuditTable.event setTo "name.changed" }
 * }
 * ```
 *
 * Every method accepts either a pre-built query object (from the `select { }`,
 * `insert { }`, … DSL functions) or a Table + builder block directly.
 * The builder-block form is shorter and preferred for one-off queries.
 *
 * @see Aggo the entry point that creates and manages Session lifetimes
 */
class Session internal constructor(
    private val connection: Connection,
    private val dialect: SqlDialect,
) {

    // ----- SELECT ---------------------------------------------------------

    /**
     * Fetches all matching rows into a [List]. Prefer [stream] for result sets
     * that may be very large (thousands+ rows) to avoid loading them all into memory.
     *
     * ```kotlin
     * val users = fetchAll(UsersTable) {
     *     where { UsersTable.active eq true }
     *     orderBy { UsersTable.name.asc() }
     *     limit(100)
     * }
     * ```
     */
    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        stream(query).toList()

    /** Builder-block form of [fetchAll]. */
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        fetchAll(com.aggitech.aggo.dsl.select(table, block))

    /**
     * Fetches the first matching row, or `null` if none exists.
     * Always sends `LIMIT 1` to the database regardless of any limit in [query].
     *
     * ```kotlin
     * val user = fetchOne(UsersTable) { where { UsersTable.email eq email } }
     * ```
     */
    suspend fun <E> fetchOne(query: Select<E>): E? =
        flow {
            val rendered = renderSelect(query, dialect, limitOverride = 1)
            val table = query.table
            executeForResults(rendered).asResultFlow().collect { result ->
                val mapped: Publisher<E> = result.map { row, _ -> table.fromRow(row) }
                mapped.collect { emit(it) }
            }
        }.toList().firstOrNull()

    /** Builder-block form of [fetchOne]. Returns `null` when no row matches. */
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        fetchOne(com.aggitech.aggo.dsl.select(table, block))

    /**
     * Returns a cold [Flow] that streams rows one at a time — no intermediate
     * list is allocated. Use this instead of [fetchAll] for large result sets.
     *
     * ```kotlin
     * stream(ReportsTable) { where { ReportsTable.year eq 2025 } }
     *     .filter { it.revenue > 0 }
     *     .collect { report -> export(report) }
     * ```
     *
     * The flow must be collected inside the same [Aggo.read] or [Aggo.tx] block
     * that produced it — the underlying connection is released when the block ends.
     */
    fun <E> stream(query: Select<E>): Flow<E> = flow {
        val rendered = renderSelect(query, dialect)
        val table = query.table
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<E> = result.map { row, _ -> table.fromRow(row) }
            // Use kotlinx Publisher.collect (no `T : Any` constraint) so E can be
            // anything — including nullable types if the schema chose to.
            mapped.collect { value -> emit(value) }
        }
    }

    /** Builder-block form of [stream]. */
    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E> =
        stream(com.aggitech.aggo.dsl.select(table, block))

    /**
     * Executes a LEFT JOIN query and returns all result pairs.
     * The right-hand side of each [JoinedRow] is `null` when no matching row exists
     * (standard LEFT JOIN semantics).
     *
     * ```kotlin
     * val rows: List<JoinedRow<Order, User?>> = fetchAllJoined(
     *     OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
     *         .where { OrdersTable.status eq "PENDING" }
     *         .limit(50)
     * )
     * rows.forEach { (order, user) -> println("${order.id} placed by ${user?.name}") }
     * ```
     */
    suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>> =
        streamJoined(query).toList()

    /** Streaming form of [fetchAllJoined]. Right side of each row is `null` on no-match. */
    fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>> = flow {
        val rendered = renderJoinSelect(query, dialect)
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<JoinedRow<L, R>> = result.map { row, _ -> mapJoinedRow(query, row) }
            mapped.collect { value -> emit(value) }
        }
    }

    // ----- INSERT ---------------------------------------------------------

    /** Execute a pre-built [Insert] query. Returns the number of rows affected. */
    suspend fun <E> insert(query: Insert<E>): Long = executeUpdate(renderInsert(query, dialect))

    /**
     * Insert an entity by walking its [Table]'s writable columns (those with
     * `isGenerated = false`). Generated columns (sequences, triggers, DEFAULT)
     * are skipped automatically.
     *
     * ```kotlin
     * aggo.tx { insert(UsersTable, user) }
     * ```
     */
    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        insert(com.aggitech.aggo.dsl.insert(table, entity))

    /**
     * Insert a partial row by specifying only the columns you want to set.
     * Useful when the entity has many columns and only a subset needs to be written,
     * or when the entity object is not available at the call site.
     *
     * ```kotlin
     * insert(UsersTable) {
     *     UsersTable.email  setTo Email("alice@example.com")
     *     UsersTable.name   setTo "Alice"
     *     UsersTable.active setTo true
     * }
     * ```
     */
    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
        insert(com.aggitech.aggo.dsl.insert(table, block))

    /**
     * Insert a row and return the generated primary key decoded via [pkColumn]'s codec.
     * Works with any PK type — UUID, TSID (String), ULID, Long, etc.
     *
     * ```kotlin
     * val newId: Tsid? = insertReturning(UsersTable, UsersTable.id) {
     *     UsersTable.email  setTo email
     *     UsersTable.name   setTo name
     *     UsersTable.active setTo true
     * }
     * ```
     */
    suspend fun <E, V> insertReturning(query: Insert<E>, pkColumn: Column<E, V>): V? {
        val rendered = renderInsert(query, dialect, returningPk = true)
        val statement = connection.createStatement(rendered.sql)
        Binder.bind(statement, rendered.params)
        QueryLog.beforeExecute(rendered.sql, rendered.params)
        return try {
            val keys = mutableListOf<V?>()
            statement.execute().asResultFlow().collect { result ->
                val keyPublisher: Publisher<V?> = result.map { row, _ -> pkColumn.read(row) }
                keyPublisher.collect { keys += it }
            }
            keys.firstOrNull()
        } catch (t: Throwable) {
            QueryLog.onError(rendered.sql, t)
            throw t
        }
    }

    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V? = insertReturning(com.aggitech.aggo.dsl.insert(table, block), pkColumn)

    // ----- UPDATE / DELETE -----------------------------------------------

    /** Execute a pre-built [Update] query. Returns the number of rows affected. */
    suspend fun <E> update(query: Update<E>): Long = executeUpdate(renderUpdate(query, dialect))

    /**
     * Update columns for rows matching the WHERE clause. Returns the number of rows affected.
     *
     * ```kotlin
     * val affected = update(UsersTable) {
     *     UsersTable.active setTo false
     *     UsersTable.name   setTo "Deactivated"
     *     where { UsersTable.id eq userId }
     * }
     * check(affected == 1L) { "user $userId not found" }
     * ```
     */
    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        update(com.aggitech.aggo.dsl.update(table, block))

    /** Execute a pre-built [Delete] query. Returns the number of rows deleted. */
    suspend fun <E> delete(query: Delete<E>): Long = executeUpdate(renderDelete(query, dialect))

    /**
     * Delete rows matching the WHERE clause. Returns the number of rows deleted.
     * Omitting the WHERE clause deletes all rows in the table.
     *
     * ```kotlin
     * delete(UsersTable) { where { UsersTable.id eq userId } }
     * ```
     */
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        delete(com.aggitech.aggo.dsl.delete(table, block))

    // ----- migrations -----------------------------------------------------

    /**
     * Applies an Aggo-generated migration plan on this connection.
     *
     * The caller should run this inside [Aggo.tx] or use [Aggo.applyMigration]
     * so schema changes and version recording commit atomically. Steps marked
     * [MigrationStep.requiresManualMigration] are refused before any SQL runs.
     */
    suspend fun applyMigration(plan: MigrationPlan): MigrationResult {
        val manualSteps = plan.steps.filter { it.requiresManualMigration }
        require(manualSteps.isEmpty()) {
            "migration ${plan.fromVersion ?: "<empty>"} -> ${plan.toVersion} has manual steps: " +
                manualSteps.joinToString { it.change }
        }

        executeMigrationSql(
            """
            CREATE TABLE IF NOT EXISTS "aggo_schema_versions" (
                "version" TEXT PRIMARY KEY,
                "previous_version" TEXT,
                "description" TEXT NOT NULL,
                "applied_at" TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            """.trimIndent(),
        )

        var executed = 0
        for (step in plan.steps) {
            val sql = step.sql ?: continue
            executeMigrationSql(sql)
            executed += 1
        }

        executeMigrationSql(
            "INSERT INTO \"aggo_schema_versions\" (\"version\", \"previous_version\", \"description\") " +
                "VALUES (${sqlLiteral(plan.toVersion)}, ${sqlNullableLiteral(plan.fromVersion)}, " +
                "${sqlLiteral(plan.steps.joinToString("; ") { it.change })});",
        )

        return MigrationResult(
            fromVersion = plan.fromVersion,
            toVersion = plan.toVersion,
            statementsExecuted = executed,
        )
    }

    // ----- internals ------------------------------------------------------

    private suspend fun executeUpdate(rendered: RenderedSql): Long {
        val statement = connection.createStatement(rendered.sql)
        Binder.bind(statement, rendered.params)
        QueryLog.beforeExecute(rendered.sql, rendered.params)
        return try {
            var total = 0L
            statement.execute().asResultFlow().collect { result ->
                val count = result.rowsUpdated.awaitFirstOrNull() ?: 0L
                total += count
            }
            total
        } catch (t: Throwable) {
            QueryLog.onError(rendered.sql, t)
            throw t
        }
    }

    private fun executeForResults(rendered: RenderedSql): Publisher<out Result> {
        val statement = connection.createStatement(rendered.sql)
        Binder.bind(statement, rendered.params)
        QueryLog.beforeExecute(rendered.sql, rendered.params)
        return statement.execute()
    }

    private suspend fun executeMigrationSql(sql: String): Long {
        QueryLog.beforeExecute(sql, emptyList())
        val statement = connection.createStatement(sql)
        return try {
            var total = 0L
            statement.execute().asResultFlow().collect { result ->
                total += result.rowsUpdated.awaitFirstOrNull() ?: 0L
            }
            total
        } catch (t: Throwable) {
            QueryLog.onError(sql, t)
            throw t
        }
    }

    /**
     * Execute arbitrary SQL. V-3: gated behind [AggoUnsafe] because it bypasses
     * identifier validation, codec safety, and the immutable query AST. Reach
     * for it only for DDL in tests/migrations — never on a production hot path.
     */
    @AggoUnsafe
    suspend fun executeRaw(sql: String): Long {
        QueryLog.beforeExecute(sql, emptyList())
        val statement = connection.createStatement(sql)
        var total = 0L
        statement.execute().asResultFlow().collect { result ->
            total += result.rowsUpdated.awaitFirstOrNull() ?: 0L
        }
        return total
    }

    /** V-3: exposing the bare [Connection] lets callers do anything; opt-in required. */
    @AggoUnsafe
    fun rawConnection(): Connection = connection

    private fun <L, R> mapJoinedRow(query: JoinSelect<L, R>, row: Row): JoinedRow<L, R> {
        val leftCount = query.leftTable.columns.size
        val leftRow = PositionalRow(row, nameToIndex(query.leftTable.columns, offset = 0))
        val rightRow = PositionalRow(row, nameToIndex(query.rightTable.columns, offset = leftCount))
        val left = query.leftTable.fromRow(leftRow)
        val right = if (rightSideIsNull(query, row, leftCount)) null else query.rightTable.fromRow(rightRow)
        return JoinedRow(left, right)
    }

    private fun nameToIndex(columns: List<Column<*, *>>, offset: Int): Map<String, Int> =
        columns.mapIndexed { index, column -> column.name to offset + index }.toMap()

    private fun <L, R> rightSideIsNull(query: JoinSelect<L, R>, row: Row, leftCount: Int): Boolean {
        val rightColumns = query.rightTable.columns
        val nullCheckColumns = query.rightTable.primaryKeys.ifEmpty { rightColumns }
        return nullCheckColumns.all { column ->
            val index = leftCount + rightColumns.indexOf(column)
            row.get(index, Any::class.java) == null
        }
    }
}

data class MigrationResult(
    val fromVersion: String?,
    val toVersion: String,
    val statementsExecuted: Int,
)

private fun sqlNullableLiteral(value: String?): String =
    value?.let(::sqlLiteral) ?: "NULL"

private fun sqlLiteral(value: String): String =
    "'" + value.replace("'", "''") + "'"

/**
 * Bridge `Publisher<? extends Result>` (which Kotlin sees as `Publisher<out Result>`)
 * to `Flow<Result>`. The cast is safe — `out Result` only ever produces Result values.
 */
@Suppress("UNCHECKED_CAST")
private fun Publisher<out Result>.asResultFlow(): Flow<Result> =
    (this as Publisher<Result>).asFlow()
