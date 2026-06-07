package com.aggitech.aggo.runtime

import com.aggitech.aggo.dialect.InsertReturnStrategy
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.dsl.AggregateBuilder
import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.ProjectionSelectBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.dsl.aggregate
import com.aggitech.aggo.dsl.selectProjection
import com.aggitech.aggo.migration.MigrationFileEntry
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.migration.MigrationStep
import com.aggitech.aggo.migration.computeChecksum
import com.aggitech.aggo.query.AggregateSelect
import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.query.JoinSelect
import com.aggitech.aggo.query.JoinedRow
import com.aggitech.aggo.query.ProjectionSelect
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.render.Bound
import com.aggitech.aggo.render.RenderedSql
import com.aggitech.aggo.render.renderAggregateSelect
import com.aggitech.aggo.render.renderCountSelect
import com.aggitech.aggo.render.renderDelete
import com.aggitech.aggo.render.renderInsert
import com.aggitech.aggo.render.renderJoinSelect
import com.aggitech.aggo.render.renderProjectionSelect
import com.aggitech.aggo.render.renderSelect
import com.aggitech.aggo.render.renderUpdate
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import io.r2dbc.spi.Connection
import io.r2dbc.spi.Parameters
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
) : TransactionScope {

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
    override suspend fun <E> fetchAll(query: Select<E>): List<E> =
        stream(query).toList()

    /** Typed-result form of [fetchAll]. Database failures are returned as [Query.Failure]. */
    override suspend fun <E> fetchAll(query: Select<E>, errorMap: ConstraintErrorMap): Query<List<E>, AggoError> =
        capture(errorMap) { fetchAll(query) }

    /** Builder-block form of [fetchAll]. */
    override suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit): List<E> =
        fetchAll(com.aggitech.aggo.dsl.select(table, block))

    /** Builder-block typed-result form of [fetchAll]. */
    override suspend fun <E> fetchAll(
        table: Table<E>,
        errorMap: ConstraintErrorMap,
        block: SelectBuilder<E>.() -> Unit,
    ): Query<List<E>, AggoError> =
        fetchAll(com.aggitech.aggo.dsl.select(table, block), errorMap)

    /**
     * Fetches a high-level page using 1-based [page] and positive [size].
     *
     * Returns `(entities, count, totalPages)`. The count query reuses the same
     * filter as [query] and ignores ordering, limit, and offset.
     */
    override suspend fun <E> paginate(query: Select<E>, page: Int, size: Int): Triple<List<E>, Long, Int> {
        validatePage(page, size)
        val offset = pageOffset(page, size)
        val count = count(query)
        val entities = fetchAll(query.copy(limit = size, offset = offset))
        return Triple(entities, count, totalPages(count, size))
    }

    /** Typed-result form of [paginate]. */
    override suspend fun <E> paginate(
        query: Select<E>,
        page: Int,
        size: Int,
        errorMap: ConstraintErrorMap,
    ): Query<Triple<List<E>, Long, Int>, AggoError> =
        capture(errorMap) { paginate(query, page, size) }

    /** Builder-block form of [paginate]. */
    override suspend fun <E> paginate(
        table: Table<E>,
        page: Int,
        size: Int,
        block: SelectBuilder<E>.() -> Unit,
    ): Triple<List<E>, Long, Int> =
        paginate(com.aggitech.aggo.dsl.select(table, block), page, size)

    /** Builder-block typed-result form of [paginate]. */
    override suspend fun <E> paginate(
        table: Table<E>,
        page: Int,
        size: Int,
        errorMap: ConstraintErrorMap,
        block: SelectBuilder<E>.() -> Unit,
    ): Query<Triple<List<E>, Long, Int>, AggoError> =
        paginate(com.aggitech.aggo.dsl.select(table, block), page, size, errorMap)

    /**
     * Fetches the first matching row, or `null` if none exists.
     * Always sends `LIMIT 1` to the database regardless of any limit in [query].
     *
     * ```kotlin
     * val user = fetchOne(UsersTable) { where { UsersTable.email eq email } }
     * ```
     */
    override suspend fun <E> fetchOne(query: Select<E>): E? =
        flow {
            val rendered = renderSelect(query, dialect, limitOverride = 1)
            val table = query.table
            executeForResults(rendered).asResultFlow().collect { result ->
                val mapped: Publisher<E> = result.map { row, _ -> table.fromRow(row) }
                mapped.collect { emit(it) }
            }
        }.toList().firstOrNull()

    /** Builder-block form of [fetchOne]. Returns `null` when no row matches. */
    override suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit): E? =
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
    override fun <E> stream(query: Select<E>): Flow<E> = flow {
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
    override fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit): Flow<E> =
        stream(com.aggitech.aggo.dsl.select(table, block))

    private suspend fun count(query: Select<*>): Long {
        val rendered = renderCountSelect(query, dialect)
        var count = 0L
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<Long?> = result.map { row, _ -> row.get(0, Long::class.javaObjectType) }
            mapped.collect { value -> count = value ?: 0L }
        }
        return count
    }

    private suspend fun <T> capture(
        errorMap: ConstraintErrorMap,
        block: suspend () -> T,
    ): Query<T, AggoError> =
        try {
            Query.Success(block())
        } catch (t: Throwable) {
            Query.Failure(errorMap.map(t))
        }

    private fun validatePage(page: Int, size: Int) {
        require(page >= 1) { "page must be >= 1: $page" }
        require(size in 1..Select.MAX_LIMIT) { "size out of range: $size" }
    }

    private fun pageOffset(page: Int, size: Int): Int {
        val offset = (page.toLong() - 1L) * size.toLong()
        require(offset <= Int.MAX_VALUE) { "page offset out of range: $offset" }
        return offset.toInt()
    }

    private fun totalPages(count: Long, size: Int): Int {
        if (count == 0L) return 0
        val pages = ((count - 1L) / size.toLong()) + 1L
        require(pages <= Int.MAX_VALUE) { "totalPages out of range: $pages" }
        return pages.toInt()
    }

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
    override suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>> =
        streamJoined(query).toList()

    /** Streaming form of [fetchAllJoined]. Right side of each row is `null` on no-match. */
    override fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>> = flow {
        val rendered = renderJoinSelect(query, dialect)
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<JoinedRow<L, R>> = result.map { row, _ -> mapJoinedRow(query, row) }
            mapped.collect { value -> emit(value) }
        }
    }

    /**
     * Joined form of [fetchAll] — maps every [JoinedRow] into a populated domain
     * entity through [map] in one pass. The right side is `null` on LEFT JOIN no-match.
     *
     * ```kotlin
     * val orders: List<OrderWithCustomer> = fetchAll(
     *     OrdersTable.leftJoin(CustomersTable) { OrdersTable.customerId eq CustomersTable.id },
     * ) { order, customer -> OrderWithCustomer(order, customer) }
     * ```
     */
    override suspend fun <L, R, A> fetchAll(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): List<A> =
        flow {
            val rendered = renderJoinSelect(query, dialect)
            executeForResults(rendered).asResultFlow().collect { result ->
                val mapped: Publisher<A> = result.map { row, _ ->
                    val joined = mapJoinedRow(query, row)
                    map(joined.left, joined.right)
                }
                mapped.collect { value -> emit(value) }
            }
        }.toList()

    /**
     * Joined form of [fetchOne] — sends `LIMIT 1`, maps the first [JoinedRow] into a
     * populated domain entity through [map], or returns `null` when no row matches.
     */
    override suspend fun <L, R, A> fetchOne(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): A? =
        flow {
            val rendered = renderJoinSelect(query, dialect, limitOverride = 1)
            executeForResults(rendered).asResultFlow().collect { result ->
                val mapped: Publisher<A> = result.map { row, _ ->
                    val joined = mapJoinedRow(query, row)
                    map(joined.left, joined.right)
                }
                mapped.collect { value -> emit(value) }
            }
        }.toList().firstOrNull()

    /** Joined, streaming form of [stream] — maps each [JoinedRow] through [map] lazily. */
    override fun <L, R, A> stream(query: JoinSelect<L, R>, map: (left: L, right: R?) -> A): Flow<A> = flow {
        val rendered = renderJoinSelect(query, dialect)
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<A> = result.map { row, _ ->
                val joined = mapJoinedRow(query, row)
                map(joined.left, joined.right)
            }
            mapped.collect { value -> emit(value) }
        }
    }

    // ----- PROJECTION SELECT ----------------------------------------------

    /**
     * Executes a partial-column SELECT and returns all result rows as [ProjectedRow] values.
     *
     * Each row's values are decoded by indexing with the same [Column] references used to build the query:
     *
     * ```kotlin
     * val q = selectProjection(UsersTable, UsersTable.id, UsersTable.email) {
     *     where { UsersTable.active eq true }
     *     orderBy { UsersTable.name.asc() }
     *     limit(100)
     * }
     *
     * aggo.read {
     *     fetchProjection(q).map { row ->
     *         UserDto(id = row[UsersTable.id]!!, email = row[UsersTable.email]!!)
     *     }
     * }
     * ```
     */
    override suspend fun <E> fetchProjection(query: ProjectionSelect<E>): List<ProjectedRow> =
        streamProjection(query).toList()

    /** Inline form of [fetchProjection] — columns and optional block in one call. */
    override suspend fun <E> fetchProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit,
    ): List<ProjectedRow> = fetchProjection(selectProjection(table, *columns, block = block))

    /**
     * Fetches the first projected row, or `null` if none matches.
     * Always sends `LIMIT 1` to the database.
     */
    override suspend fun <E> fetchOneProjection(query: ProjectionSelect<E>): ProjectedRow? =
        flow {
            val rendered = renderProjectionSelect(query, dialect, limitOverride = 1)
            executeForResults(rendered).asResultFlow().collect { result ->
                val mapped: Publisher<ProjectedRow> = result.map { row, _ -> ProjectedRow(row) }
                mapped.collect { emit(it) }
            }
        }.toList().firstOrNull()

    /** Inline form of [fetchOneProjection]. */
    override suspend fun <E> fetchOneProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit,
    ): ProjectedRow? = fetchOneProjection(selectProjection(table, *columns, block = block))

    /**
     * Returns a cold [Flow] that streams projected rows one at a time.
     * Prefer this over [fetchProjection] for large result sets.
     *
     * The flow must be collected inside the same [Aggo.read] or [Aggo.tx] block.
     */
    override fun <E> streamProjection(query: ProjectionSelect<E>): Flow<ProjectedRow> = flow {
        val rendered = renderProjectionSelect(query, dialect)
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<ProjectedRow> = result.map { row, _ -> ProjectedRow(row) }
            mapped.collect { value -> emit(value) }
        }
    }

    /** Inline form of [streamProjection]. */
    override fun <E> streamProjection(
        table: Table<E>,
        vararg columns: Column<E, *>,
        block: ProjectionSelectBuilder<E>.() -> Unit,
    ): Flow<ProjectedRow> = streamProjection(selectProjection(table, *columns, block = block))

    // ----- AGGREGATE SELECT -----------------------------------------------

    /**
     * Executes a GROUP BY / aggregate SELECT and returns all result rows as [AggRow] values.
     *
     * Each row's values are decoded by indexing with [com.aggitech.aggo.query.NamedExpr] keys:
     *
     * ```kotlin
     * val total = count(Orders.id) `as` "total"
     * val avg   = avg(Orders.amount) `as` "avg_amount"
     *
     * val results = aggo.read {
     *     fetchAggregate(aggregate(Orders) {
     *         project(total)
     *         project(avg)
     *         where { Orders.active eq true }
     *         groupBy(Orders.customerId)
     *         having { count(Orders.id) gt 0L }
     *         limit(50)
     *     })
     * }
     *
     * results.map { row -> row[total]!! to row[avg]!! }
     * ```
     */
    override suspend fun <E> fetchAggregate(query: AggregateSelect<E>): List<AggRow> =
        streamAggregate(query).toList()

    /** Builder-block form of [fetchAggregate]. */
    override suspend fun <E> fetchAggregate(
        table: Table<E>,
        block: AggregateBuilder<E>.() -> Unit,
    ): List<AggRow> = fetchAggregate(aggregate(table, block))

    /**
     * Streaming form of [fetchAggregate]. Returns a cold [Flow] of [AggRow] values.
     * Prefer this over [fetchAggregate] when result sets may be very large.
     *
     * The flow must be collected inside the same [Aggo.read] or [Aggo.tx] block.
     */
    override fun <E> streamAggregate(query: AggregateSelect<E>): Flow<AggRow> = flow {
        val rendered = renderAggregateSelect(query, dialect)
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<AggRow> = result.map { row, _ -> AggRow(row, query.projections) }
            mapped.collect { value -> emit(value) }
        }
    }

    /** Builder-block form of [streamAggregate]. */
    override fun <E> streamAggregate(
        table: Table<E>,
        block: AggregateBuilder<E>.() -> Unit,
    ): Flow<AggRow> = streamAggregate(aggregate(table, block))

    // ----- INSERT ---------------------------------------------------------

    /** Execute a pre-built [Insert] query. Returns the number of rows affected. */
    override suspend fun <E> insert(query: Insert<E>): Long =
        executeUpdate(renderInsert(query, dialect).asRenderedSql())

    /**
     * Insert an entity by walking its [Table]'s writable columns (those with
     * `isGenerated = false`). Generated columns (sequences, triggers, DEFAULT)
     * are skipped automatically.
     *
     * ```kotlin
     * aggo.tx { insert(UsersTable, user) }
     * ```
     */
    override suspend fun <E> insert(table: Table<E>, entity: E): Long =
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
    override suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
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
    override suspend fun <E, V> insertReturning(query: Insert<E>, pkColumn: Column<E, V>): V? {
        val rendered = renderInsert(query, dialect, returningPk = true)
        return when (val strategy = rendered.returnStrategy) {
            null -> null
            is InsertReturnStrategy.AppendClause -> executeInsertReturningFromResult(rendered.asRenderedSql(), pkColumn)
            is InsertReturnStrategy.PostInsertSelect -> {
                executeUpdate(rendered.asRenderedSql())
                val pkParams = primaryKeyBounds(query)
                executeInsertReturningFromResult(RenderedSql(strategy.sql, pkParams), pkColumn)
            }
            is InsertReturnStrategy.ReturningInto -> executeInsertReturningFromOutParams(rendered, strategy, pkColumn)
        }
    }

    private suspend fun <E, V> executeInsertReturningFromResult(rendered: RenderedSql, pkColumn: Column<E, V>): V? {
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

    private suspend fun <E, V> executeInsertReturningFromOutParams(
        rendered: com.aggitech.aggo.render.RenderedInsert,
        strategy: InsertReturnStrategy.ReturningInto,
        pkColumn: Column<E, V>,
    ): V? {
        val statement = connection.createStatement(rendered.sql)
        Binder.bind(statement, rendered.params)
        strategy.outParams.forEach { outParam ->
            statement.bind(outParam.bindName, Parameters.out(outParam.sqlType))
        }
        QueryLog.beforeExecute(rendered.sql, rendered.params)
        return try {
            val keys = mutableListOf<V?>()
            statement.execute().asResultFlow().collect { result ->
                val keyPublisher: Publisher<V?> = result.map { readable ->
                    @Suppress("UNCHECKED_CAST")
                    val sqlType = pkColumn.codec.sqlType as Class<Any>
                    pkColumn.codec.decode(readable.get(0, sqlType))
                }
                keyPublisher.collect { keys += it }
            }
            keys.firstOrNull()
        } catch (t: Throwable) {
            QueryLog.onError(rendered.sql, t)
            throw t
        }
    }

    private fun <E> primaryKeyBounds(query: Insert<E>): List<Bound> =
        query.table.primaryKeys.map { pk ->
            val assignment = query.assignments.firstOrNull { it.column == pk }
            requireNotNull(assignment) {
                "MySQL insertReturning requires primary key '${pk.table.name}.${pk.name}' to be assigned in the INSERT"
            }
            Bound(assignment.value, assignment.codec, assignment.column)
        }

    override suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V? = insertReturning(com.aggitech.aggo.dsl.insert(table, block), pkColumn)

    // ----- UPDATE / DELETE -----------------------------------------------

    /** Execute a pre-built [Update] query. Returns the number of rows affected. */
    override suspend fun <E> update(query: Update<E>): Long = executeUpdate(renderUpdate(query, dialect))

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
    override suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        update(com.aggitech.aggo.dsl.update(table, block))

    /** Execute a pre-built [Delete] query. Returns the number of rows deleted. */
    override suspend fun <E> delete(query: Delete<E>): Long = executeUpdate(renderDelete(query, dialect))

    /**
     * Delete rows matching the WHERE clause. Returns the number of rows deleted.
     * Omitting the WHERE clause deletes all rows in the table.
     *
     * ```kotlin
     * delete(UsersTable) { where { UsersTable.id eq userId } }
     * ```
     */
    override suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit): Long =
        delete(com.aggitech.aggo.dsl.delete(table, block))

    // ----- migrations -----------------------------------------------------

    /**
     * Applies an Aggo-generated migration plan on this connection.
     *
     * The caller should run this inside [Aggo.tx] or use [Aggo.applyMigration]
     * so schema changes and version recording commit atomically. Steps marked
     * [MigrationStep.requiresManualMigration] are refused before any SQL runs.
     */
    override suspend fun applyMigration(plan: MigrationPlan): MigrationResult {
        val manualSteps = plan.steps.filter { it.requiresManualMigration }
        require(manualSteps.isEmpty()) {
            "migration ${plan.fromVersion ?: "<empty>"} -> ${plan.toVersion} has manual steps: " +
                manualSteps.joinToString { it.change }
        }

        ensureVersionTable()

        val versionTable = dialect.qualifyTableName(VERSION_TABLE)

        if (queryLong("SELECT COUNT(*) FROM $versionTable WHERE \"version\" = ${sqlLiteral(plan.toVersion)}") > 0L) {
            return MigrationResult(
                fromVersion = plan.fromVersion,
                toVersion = plan.toVersion,
                statementsExecuted = 0,
                skipped = true,
            )
        }

        plan.fromVersion?.let { from ->
            require(queryLong("SELECT COUNT(*) FROM $versionTable WHERE \"version\" = ${sqlLiteral(from)}") > 0L) {
                "migration ${plan.toVersion} requires '$from' to be applied first"
            }
        }

        var executed = 0
        for (step in plan.steps) {
            val sql = step.sql ?: continue
            executeMigrationSql(sql)
            executed += 1
        }

        executeMigrationSql(
            "INSERT INTO $versionTable (\"version\", \"previous_version\", \"description\", \"checksum\") " +
                "VALUES (${sqlLiteral(plan.toVersion)}, ${sqlNullableLiteral(plan.fromVersion)}, " +
                "${sqlLiteral(plan.steps.joinToString("; ") { it.change })}, " +
                "${sqlNullableLiteral(plan.checksum)});",
        )

        return MigrationResult(
            fromVersion = plan.fromVersion,
            toVersion = plan.toVersion,
            statementsExecuted = executed,
        )
    }

    /**
     * Applies all migration file entries that have not yet been recorded in
     * `aggo_schema_versions`, in the order they are provided. Verifies the
     * checksum of each entry before executing its SQL.
     *
     * Call this inside [Aggo.tx] (or use [Aggo.applyMigrations]) so all DDL
     * and version records commit atomically.
     */
    override suspend fun applyMigrations(entries: List<MigrationFileEntry>): List<MigrationResult> {
        ensureVersionTable()
        val versionTable = dialect.qualifyTableName(VERSION_TABLE)
        val results = mutableListOf<MigrationResult>()
        for (entry in entries) {
            if (queryLong("SELECT COUNT(*) FROM $versionTable WHERE \"version\" = ${sqlLiteral(entry.version)}") > 0L) {
                results += MigrationResult(
                    fromVersion = entry.fromVersion,
                    toVersion = entry.version,
                    statementsExecuted = 0,
                    skipped = true,
                )
                continue
            }

            val computed = computeChecksum(entry.sql, entry.generatedAt)
            check(computed == entry.checksum) {
                "Checksum mismatch for migration ${entry.version}: stored=${entry.checksum} computed=$computed"
            }

            entry.fromVersion?.let { from ->
                require(queryLong("SELECT COUNT(*) FROM $versionTable WHERE \"version\" = ${sqlLiteral(from)}") > 0L) {
                    "migration ${entry.version} requires '$from' to be applied first"
                }
            }

            executeMigrationSql(entry.sql)

            executeMigrationSql(
                "INSERT INTO $versionTable (\"version\", \"previous_version\", \"description\", \"checksum\") " +
                    "VALUES (${sqlLiteral(entry.version)}, ${sqlNullableLiteral(entry.fromVersion)}, " +
                    "${sqlLiteral("file-based migration")}, ${sqlLiteral(entry.checksum)});",
            )

            results += MigrationResult(
                fromVersion = entry.fromVersion,
                toVersion = entry.version,
                statementsExecuted = 1,
            )
        }
        return results
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

    override suspend fun readLatestSnapshot(): String? = runCatching {
        val versionTable = dialect.qualifyTableName(VERSION_TABLE)
        val sql = "SELECT \"snapshot\" FROM $versionTable " +
            "WHERE \"snapshot\" IS NOT NULL ORDER BY \"applied_at\" DESC LIMIT 1"
        QueryLog.beforeExecute(sql, emptyList())
        val statement = connection.createStatement(sql)
        var result: String? = null
        statement.execute().asResultFlow().collect { r ->
            val pub: Publisher<String?> = r.map { row, _ -> row.get("snapshot", String::class.java) }
            pub.collect { v -> if (v != null) result = v }
        }
        result
    }.getOrElse { null }

    override suspend fun storeSnapshot(version: String, snapshotJson: String) {
        val versionTable = dialect.qualifyTableName(VERSION_TABLE)
        executeMigrationSql(
            "UPDATE $versionTable SET \"snapshot\" = ${sqlLiteral(snapshotJson)} " +
                "WHERE \"version\" = ${sqlLiteral(version)};",
        )
    }

    private suspend fun ensureVersionTable() {
        val versionTable = dialect.qualifyTableName(VERSION_TABLE)
        executeMigrationSql(
            """
            CREATE TABLE IF NOT EXISTS $versionTable (
                "version" TEXT PRIMARY KEY,
                "previous_version" TEXT,
                "description" TEXT NOT NULL,
                "applied_at" TIMESTAMPTZ NOT NULL DEFAULT now(),
                "checksum" TEXT
            );
            """.trimIndent(),
        )
        executeMigrationSql(
            "ALTER TABLE $versionTable ADD COLUMN IF NOT EXISTS \"checksum\" TEXT;",
        )
        executeMigrationSql(
            "ALTER TABLE $versionTable ADD COLUMN IF NOT EXISTS \"snapshot\" TEXT;",
        )
    }

    private suspend fun queryLong(sql: String): Long {
        QueryLog.beforeExecute(sql, emptyList())
        val statement = connection.createStatement(sql)
        return try {
            var result = 0L
            statement.execute().asResultFlow().collect { r ->
                val pub: Publisher<Long?> = r.map { row, _ -> row.get(0, Long::class.javaObjectType) }
                pub.collect { v -> result += v ?: 0L }
            }
            result
        } catch (t: Throwable) {
            QueryLog.onError(sql, t)
            throw t
        }
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
    val skipped: Boolean = false,
)

private fun sqlNullableLiteral(value: String?): String =
    value?.let(::sqlLiteral) ?: "NULL"

private fun sqlLiteral(value: String): String =
    "'" + value.replace("'", "''") + "'"

private const val VERSION_TABLE = "aggo_schema_versions"

/**
 * Bridge `Publisher<? extends Result>` (which Kotlin sees as `Publisher<out Result>`)
 * to `Flow<Result>`. The cast is safe — `out Result` only ever produces Result values.
 */
@Suppress("UNCHECKED_CAST")
private fun Publisher<out Result>.asResultFlow(): Flow<Result> =
    (this as Publisher<Result>).asFlow()
