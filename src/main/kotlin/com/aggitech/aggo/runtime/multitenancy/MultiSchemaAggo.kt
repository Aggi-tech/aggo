package com.aggitech.aggo.runtime.multitenancy

import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.dialect.forSchema
import com.aggitech.aggo.dialect.requireValidIdentifier
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.AggoUnsafe
import com.aggitech.aggo.runtime.ExecutionCoordinator
import com.aggitech.aggo.runtime.ExecutionLease
import com.aggitech.aggo.runtime.MigrationResult
import com.aggitech.aggo.runtime.SessionBuilder
import com.aggitech.aggo.runtime.TransactionBuilder
import kotlinx.coroutines.withContext

/**
 * Schema-per-tenant decorator over a single shared [AggoPool].
 *
 * The public execution shape mirrors [com.aggitech.aggo.runtime.Aggo]:
 *
 * ```kotlin
 * withContext(TenantContext("acme")) {
 *     aggo.session { session -> session.fetchAll(UsersTable) }
 *     aggo.tx { tx -> tx.insert(UsersTable, user) }
 * }
 * ```
 */
class MultiSchemaAggo(
    private val pool: AggoPool,
    private val resolver: TenantResolver = CoroutineTenantResolver,
    private val schema: (tenantId: String) -> String = { it },
) : AutoCloseable {

    private val coordinator = ExecutionCoordinator {
        val dialect = tenantDialect()
        val connection = pool.acquire()
        ExecutionLease(
            connection = connection,
            dialect = dialect,
            release = { pool.release(connection) },
        )
    }

    val session: SessionBuilder = SessionBuilder(coordinator)
    val tx: TransactionBuilder = TransactionBuilder(coordinator)

    private suspend fun tenantDialect(): SqlDialect {
        val tenantId = resolver.currentTenantId()
        return pool.dialect.forSchema(schema(tenantId))
    }

    /**
     * Creates the tenant schema and applies [plan] inside it.
     */
    @OptIn(AggoUnsafe::class)
    suspend fun provisionTenant(tenantId: String, plan: MigrationPlan) {
        val schemaName = schema(tenantId)
        requireValidIdentifier(schemaName)
        withContext(TenantContext(tenantId)) {
            tx.unsafe { raw ->
                raw.executeRaw("CREATE SCHEMA IF NOT EXISTS ${pool.dialect.quoteIdentifier(schemaName)};")
                raw.applyMigration(plan)
            }
        }
    }

    /**
     * Applies [plan] to each tenant in [tenantIds], collecting success or failure per tenant.
     */
    suspend fun applyMigrationToAll(
        plan: MigrationPlan,
        tenantIds: List<String>,
    ): Map<String, Result<MigrationResult>> =
        tenantIds.associateWith { tenantId ->
            runCatching {
                withContext(TenantContext(tenantId)) { tx.applyMigration(plan) }
            }
        }

    override fun close() = pool.close()
}
