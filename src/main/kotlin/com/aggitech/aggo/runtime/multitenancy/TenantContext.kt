package com.aggitech.aggo.runtime.multitenancy

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element carrying the current tenant identifier.
 *
 * Place it at the request boundary with `withContext(TenantContext("acme"))`.
 * All Aggo calls within that coroutine scope can then resolve the tenant
 * without adding tenant parameters to repository methods.
 *
 * In application code, this normally lives in an HTTP filter, gRPC interceptor,
 * message consumer wrapper, or scheduled-job tenant loop. Repository classes
 * should not receive tenant IDs directly; they keep calling `aggo.read` and
 * `aggo.tx` as if the application were single-tenant.
 *
 * ```kotlin
 * import com.aggitech.aggo.runtime.multitenancy.TenantContext
 * import kotlinx.coroutines.withContext
 *
 * withContext(TenantContext("acme")) {
 *     aggo.read {
 *         fetchAll(UsersTable)
 *     }
 * }
 * ```
 *
 * Example request boundary:
 *
 * ```kotlin
 * suspend fun <T> handleTenantRequest(tenantId: String, block: suspend () -> T): T =
 *     withContext(TenantContext(tenantId)) {
 *         block()
 *     }
 * ```
 *
 * @property id application-level tenant identifier. In schema-per-tenant mode
 * it is usually mapped to a PostgreSQL schema name by `MultiSchemaAggo`.
 */
class TenantContext(val id: String) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TenantContext>
    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * Returns the tenant ID from the current coroutine context, or null if none is set.
 *
 * Use this when absence is a valid branch, for example optional logging or
 * diagnostics:
 *
 * ```kotlin
 * val tenantLabel = currentTenantOrNull() ?: "system"
 * logger.info("tenant=$tenantLabel starting cleanup")
 * ```
 *
 * Prefer [requireCurrentTenant] in database access paths, because missing
 * tenant context should fail fast before a query can run against the wrong
 * tenant.
 */
suspend fun currentTenantOrNull(): String? =
    currentCoroutineContext()[TenantContext]?.id

/**
 * Returns the tenant ID from the current coroutine context.
 *
 * @throws IllegalStateException when no [TenantContext] is present.
 *
 * ```kotlin
 * val tenantId = requireCurrentTenant()
 * val schema = "tenant_$tenantId"
 * ```
 *
 * This function is used by [CoroutineTenantResolver], which is the default
 * resolver for `MultiSchemaAggo` and `MultiDatabaseAggo`.
 */
suspend fun requireCurrentTenant(): String =
    currentTenantOrNull()
        ?: error("No TenantContext in current coroutine context. Wrap the call with withContext(TenantContext(id)) { ... }")
