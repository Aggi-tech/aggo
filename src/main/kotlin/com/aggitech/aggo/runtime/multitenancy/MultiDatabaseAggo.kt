package com.aggitech.aggo.runtime.multitenancy

import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.ExecutionCoordinator
import com.aggitech.aggo.runtime.ExecutionLease
import com.aggitech.aggo.runtime.MigrationResult
import com.aggitech.aggo.runtime.SessionBuilder
import com.aggitech.aggo.runtime.TransactionBuilder
import kotlinx.coroutines.withContext

/**
 * Database-per-tenant decorator.
 *
 * Each operation resolves the current tenant, acquires a connection from that
 * tenant's pool, and exposes the same builder API as [com.aggitech.aggo.runtime.Aggo].
 */
class MultiDatabaseAggo(
    private val poolFactory: suspend (tenantId: String) -> AggoPool,
    private val resolver: TenantResolver = CoroutineTenantResolver,
    private val cache: TenantPoolCache = TenantPoolCache.lru(),
) : AutoCloseable {

    private val coordinator = ExecutionCoordinator {
        val tenantId = resolver.currentTenantId()
        val pool = cache.getOrCreate(tenantId) { poolFactory(tenantId) }
        val connection = pool.acquire()
        ExecutionLease(
            connection = connection,
            dialect = pool.dialect,
            release = { pool.release(connection) },
        )
    }

    val session: SessionBuilder = SessionBuilder(coordinator)
    val tx: TransactionBuilder = TransactionBuilder(coordinator)

    /**
     * Applies [plan] to each tenant database in [tenantIds], collecting success
     * or failure per tenant.
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

    fun evictTenant(tenantId: String) {
        cache.invalidate(tenantId)
    }

    override fun close() = cache.close()
}
