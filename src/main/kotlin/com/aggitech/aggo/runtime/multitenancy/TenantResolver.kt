package com.aggitech.aggo.runtime.multitenancy

/**
 * Resolves the current tenant identifier.
 *
 * Implement this to read the tenant from coroutine context, a framework
 * request context, a JWT claim, or another explicit request-scoped source.
 *
 * Most applications should use [CoroutineTenantResolver]. Custom resolvers are
 * useful when the framework already exposes request context through its own
 * APIs and you do not want to wrap every call in [TenantContext].
 *
 * ```kotlin
 * val resolver = TenantResolver {
 *     requestContext.tenantId
 *         ?: error("missing tenant")
 * }
 *
 * val aggo = MultiSchemaAggo(pool = pool, resolver = resolver)
 * ```
 */
fun interface TenantResolver {
    /**
     * Returns the current tenant identifier for the running operation.
     *
     * Implementations should fail fast when no tenant is available; silently
     * falling back to a shared tenant can break isolation.
     *
     * ```kotlin
     * class HeaderTenantResolver(private val headers: Headers) : TenantResolver {
     *     override suspend fun currentTenantId(): String =
     *         headers["X-Tenant-Id"] ?: error("missing X-Tenant-Id")
     * }
     * ```
     */
    suspend fun currentTenantId(): String
}

/**
 * Reads the tenant ID from [TenantContext] in the current coroutine context.
 *
 * ```kotlin
 * val aggo = MultiSchemaAggo(pool, resolver = CoroutineTenantResolver)
 *
 * withContext(TenantContext("acme")) {
 *     aggo.fetchAll(UsersTable)
 * }
 * ```
 */
object CoroutineTenantResolver : TenantResolver {
    override suspend fun currentTenantId(): String = requireCurrentTenant()
}
