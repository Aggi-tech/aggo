package com.aggitech.aggo.runtime

import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
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
 * Bound to a single [Connection] for its entire lifetime. Every query method
 * runs against that connection, so when [Aggo.tx] wraps a Session in a
 * transaction every statement actually participates.
 *
 * This is the fix for the upstream "transactions are no-ops" bug.
 *
 * 0.2.0 — methods that accept a [Table] + builder block (e.g. [update],
 * [insert], [delete], [fetchAll]) are the preferred form; the older variants
 * that take a pre-built [Update]/[Insert]/[Delete]/[Select] are kept for
 * callers that want to inspect or persist the query object.
 */
class Session internal constructor(
    private val connection: Connection,
    private val dialect: SqlDialect,
) {

    // ----- SELECT ---------------------------------------------------------

    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        stream(query).toList()

    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        fetchAll(com.aggitech.aggo.dsl.select(table, block))

    /**
     * Returns the first matching row, or null. Internally enforces `LIMIT 1`
     * via the renderer (P-3 — avoids allocating a `Select.copy(limit = 1)`
     * just to pass an int).
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

    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        fetchOne(com.aggitech.aggo.dsl.select(table, block))

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

    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E> =
        stream(com.aggitech.aggo.dsl.select(table, block))

    suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>> =
        streamJoined(query).toList()

    fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>> = flow {
        val rendered = renderJoinSelect(query, dialect)
        executeForResults(rendered).asResultFlow().collect { result ->
            val mapped: Publisher<JoinedRow<L, R>> = result.map { row, _ -> mapJoinedRow(query, row) }
            mapped.collect { value -> emit(value) }
        }
    }

    // ----- INSERT ---------------------------------------------------------

    suspend fun <E> insert(query: Insert<E>): Long = executeUpdate(renderInsert(query, dialect))

    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        insert(com.aggitech.aggo.dsl.insert(table, entity))

    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
        insert(com.aggitech.aggo.dsl.insert(table, block))

    /**
     * Insert and return the generated primary key, decoded via [pkColumn]'s codec.
     * Supports any PK type (UUID, String/TSID, Long, …) — fixes upstream
     * "assume Long" and "drops all but first row" bugs.
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

    suspend fun <E> update(query: Update<E>): Long = executeUpdate(renderUpdate(query, dialect))

    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        update(com.aggitech.aggo.dsl.update(table, block))

    suspend fun <E> delete(query: Delete<E>): Long = executeUpdate(renderDelete(query, dialect))

    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        delete(com.aggitech.aggo.dsl.delete(table, block))

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

/**
 * Bridge `Publisher<? extends Result>` (which Kotlin sees as `Publisher<out Result>`)
 * to `Flow<Result>`. The cast is safe — `out Result` only ever produces Result values.
 */
@Suppress("UNCHECKED_CAST")
private fun Publisher<out Result>.asResultFlow(): Flow<Result> =
    (this as Publisher<Result>).asFlow()
