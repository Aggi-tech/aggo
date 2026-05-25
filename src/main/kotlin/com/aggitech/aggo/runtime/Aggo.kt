package com.aggitech.aggo.runtime

import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext

/**
 * Application entry point. Inject one instance per database; for the typical
 * Dabble microservice that's a single `@Produces @ApplicationScoped Aggo`.
 *
 * 0.2.0 — the `read`/`tx` block now receives [Session] as a receiver (`this`),
 * not as a parameter. Inside the block you call the executable DSL directly:
 *
 * ```kotlin
 * aggo.tx {
 *     update(Projects) {
 *         Projects.active setTo false
 *         where { Projects.id eq projectId }
 *     }
 * }
 * ```
 *
 * One-shot helpers ([selectAll], [update], [insert], [delete]) wrap the above
 * pattern when the call site only needs a single statement.
 */
class Aggo(private val pool: AggoPool) : AutoCloseable {

    /**
     * Lease a connection, run [block], release. No transaction wrapping —
     * the underlying driver is in autocommit unless [tx] is used.
     */
    suspend fun <T> read(block: suspend Session.() -> T): T {
        val conn = pool.acquire()
        return try {
            block(Session(conn, pool.dialect))
        } finally {
            // V-5: never lose the connection on coroutine cancellation.
            withContext(NonCancellable) {
                runCatching { pool.release(conn) }
            }
        }
    }

    /**
     * Atomic transactional scope. Begin → block(session) → commit, with
     * automatic rollback on any throwable. Rollback failures are added as
     * suppressed exceptions, never masking the original error.
     *
     * V-4: tracks `committed` so a throw *after* a successful commit does not
     * spuriously attempt rollback (which would otherwise log a confusing
     * "no transaction in progress" suppressed exception).
     */
    suspend fun <T> tx(block: suspend Session.() -> T): T {
        val conn = pool.acquire()
        return try {
            conn.beginTransaction().awaitFirstOrNull()
            val session = Session(conn, pool.dialect)
            var committed = false
            try {
                val result = block(session)
                conn.commitTransaction().awaitFirstOrNull()
                committed = true
                result
            } catch (t: Throwable) {
                if (!committed) {
                    runCatching {
                        conn.rollbackTransaction().awaitFirstOrNull()
                    }.exceptionOrNull()?.let { rollbackError ->
                        t.addSuppressed(rollbackError)
                    }
                }
                throw t
            }
        } finally {
            // V-5: same rationale as read().
            withContext(NonCancellable) {
                runCatching { pool.release(conn) }
            }
        }
    }

    // ----- One-shot convenience helpers -----------------------------------
    //
    // Each helper opens its own scope (`tx` for writes, `read` for reads)
    // so the consistency story is the same as if the caller had spelt it out.

    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        read { fetchAll(query) }

    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        read { fetchAll(table, block) }

    suspend fun <E> fetchOne(query: Select<E>): E? =
        read { fetchOne(query) }

    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        read { fetchOne(table, block) }

    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        tx { insert(table, entity) }

    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
        tx { insert(table, block) }

    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V? = tx { insertReturning(table, pkColumn, block) }

    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        tx { update(table, block) }

    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        tx { delete(table, block) }

    override fun close() = pool.close()
}
