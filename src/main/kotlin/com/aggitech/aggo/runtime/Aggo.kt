package com.aggitech.aggo.runtime

import kotlinx.coroutines.reactive.awaitFirstOrNull

/**
 * Application entry point. Inject one instance per database; for the typical
 * Dabble microservice that's a single `@Produces @ApplicationScoped Aggo`.
 *
 * Two execution scopes are exposed:
 *
 *  - [read] runs the block in a Session backed by a single pooled connection
 *    (auto-commit), suitable for reads / single-statement writes.
 *
 *  - [tx] runs the block inside a real database transaction on the same
 *    connection from begin to commit/rollback — every Session call inside
 *    participates. **This is the fix for the upstream transaction bug.**
 */
class Aggo(private val pool: AggoPool) : AutoCloseable {

    /**
     * Lease a connection, run [block], release. No transaction wrapping —
     * the underlying driver is in autocommit unless [tx] is used.
     */
    suspend fun <T> read(block: suspend (Session) -> T): T {
        val conn = pool.acquire()
        return try {
            block(Session(conn, pool.dialect))
        } finally {
            runCatching { pool.release(conn) }
        }
    }

    /**
     * Atomic transactional scope. Begin → block(session) → commit, with
     * automatic rollback on any throwable. Rollback failures are added as
     * suppressed exceptions, never masking the original error.
     */
    suspend fun <T> tx(block: suspend (Session) -> T): T {
        val conn = pool.acquire()
        return try {
            conn.beginTransaction().awaitFirstOrNull()
            val session = Session(conn, pool.dialect)
            try {
                val result = block(session)
                conn.commitTransaction().awaitFirstOrNull()
                result
            } catch (t: Throwable) {
                runCatching {
                    conn.rollbackTransaction().awaitFirstOrNull()
                }.exceptionOrNull()?.let { rollbackError ->
                    t.addSuppressed(rollbackError)
                }
                throw t
            }
        } finally {
            runCatching { pool.release(conn) }
        }
    }

    override fun close() = pool.close()
}
