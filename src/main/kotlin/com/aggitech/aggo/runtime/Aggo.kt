package com.aggitech.aggo.runtime

/**
 * Application-level entry point for Aggo.
 *
 * `Aggo` owns the pool lifecycle and exposes two execution builders:
 *
 * ```kotlin
 * val users = aggo.session { session ->
 *     session.fetchAll(UsersTable) { where { UsersTable.active eq true } }
 * }
 *
 * aggo.tx { tx ->
 *     tx.insert(UsersTable, user)
 * }
 * ```
 *
 * Reads go through [session]. Writes and migrations go through [tx].
 */
class Aggo(private val pool: AggoPool) : AutoCloseable {
    private val coordinator = pool.executionCoordinator()

    val session: SessionBuilder = SessionBuilder(coordinator)
    val tx: TransactionBuilder = TransactionBuilder(coordinator)

    override fun close() = pool.close()
}
