package com.aggitech.aggo.runtime

import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.query.Delete
import com.aggitech.aggo.query.Insert
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.query.Update
import com.aggitech.aggo.render.RenderedSql
import com.aggitech.aggo.render.renderDelete
import com.aggitech.aggo.render.renderInsert
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
 */
class Session internal constructor(
    private val connection: Connection,
    private val dialect: SqlDialect,
) {

    // ----- SELECT ---------------------------------------------------------

    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        stream(query).toList()

    suspend fun <E> fetchOne(query: Select<E>): E? {
        val limited = if (query.limit == null) query.copy(limit = 1) else query
        return stream(limited).toList().firstOrNull()
    }

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

    // ----- INSERT ---------------------------------------------------------

    suspend fun <E> insert(query: Insert<E>): Long = executeUpdate(renderInsert(query, dialect))

    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        insert(com.aggitech.aggo.dsl.insert(table, entity))

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

    // ----- UPDATE / DELETE -----------------------------------------------

    suspend fun <E> update(query: Update<E>): Long = executeUpdate(renderUpdate(query, dialect))

    suspend fun <E> delete(query: Delete<E>): Long = executeUpdate(renderDelete(query, dialect))

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

    /** Exposed for advanced callers (e.g. raw DDL in tests). */
    suspend fun executeRaw(sql: String): Long {
        QueryLog.beforeExecute(sql, emptyList())
        val statement = connection.createStatement(sql)
        var total = 0L
        statement.execute().asResultFlow().collect { result ->
            total += result.rowsUpdated.awaitFirstOrNull() ?: 0L
        }
        return total
    }

    @Suppress("unused") // expose connection for advanced raw operations
    fun rawConnection(): Connection = connection
}

/**
 * Bridge `Publisher<? extends Result>` (which Kotlin sees as `Publisher<out Result>`)
 * to `Flow<Result>`. The cast is safe — `out Result` only ever produces Result values.
 */
@Suppress("UNCHECKED_CAST")
private fun Publisher<out Result>.asResultFlow(): Flow<Result> =
    (this as Publisher<Result>).asFlow()

private fun <E> Select<E>.copy(limit: Int? = this.limit): Select<E> =
    Select(table, where, orderBy, limit, offset)
