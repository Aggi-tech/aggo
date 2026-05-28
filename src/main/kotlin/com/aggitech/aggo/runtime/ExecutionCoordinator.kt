package com.aggitech.aggo.runtime

import com.aggitech.aggo.dialect.SqlDialect
import io.r2dbc.spi.Connection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext

internal class ExecutionCoordinator(
    private val acquire: suspend () -> ExecutionLease,
) {
    suspend fun <T> session(block: suspend (SessionScope) -> T): T {
        val lease = acquire()
        return try {
            block(Session(lease.connection, lease.dialect))
        } finally {
            withContext(NonCancellable) {
                runCatching { lease.release() }
            }
        }
    }

    suspend fun <T> transaction(block: suspend (TransactionScope) -> T): T {
        val lease = acquire()
        return try {
            lease.connection.beginTransaction().awaitFirstOrNull()
            val session = Session(lease.connection, lease.dialect)
            var committed = false
            try {
                val result = block(session)
                lease.connection.commitTransaction().awaitFirstOrNull()
                committed = true
                result
            } catch (t: Throwable) {
                if (!committed) {
                    runCatching {
                        lease.connection.rollbackTransaction().awaitFirstOrNull()
                    }.exceptionOrNull()?.let { rollbackError ->
                        t.addSuppressed(rollbackError)
                    }
                }
                throw t
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { lease.release() }
            }
        }
    }

    suspend fun <T> sessionResult(
        errorMap: ConstraintErrorMap,
        block: suspend (SessionScope) -> T,
    ): Query<T, AggoError> =
        try {
            Query.Success(session(block))
        } catch (t: Throwable) {
            Query.Failure(errorMap.map(t))
        }

    suspend fun <T> transactionResult(
        errorMap: ConstraintErrorMap,
        block: suspend (TransactionScope) -> T,
    ): Transaction<T, AggoError> =
        try {
            Query.Success(transaction(block))
        } catch (t: Throwable) {
            Query.Failure(errorMap.map(t))
        }

    @AggoUnsafe
    suspend fun <T> unsafeTransaction(block: suspend (Session) -> T): T =
        transaction { tx ->
            block(tx as Session)
        }
}

internal data class ExecutionLease(
    val connection: Connection,
    val dialect: SqlDialect,
    val release: suspend () -> Unit,
)

internal fun AggoPool.executionCoordinator(dialect: suspend () -> SqlDialect = { this.dialect }): ExecutionCoordinator =
    ExecutionCoordinator {
        val selectedDialect = dialect()
        val connection = acquire()
        ExecutionLease(
            connection = connection,
            dialect = selectedDialect,
            release = { release(connection) },
        )
    }
