package com.aggitech.aggo.runtime.multitenancy

import com.aggitech.aggo.runtime.AggoPool
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe LRU cache of [AggoPool] instances, one per tenant.
 *
 * Pools that have not been accessed within [evictionTtl] are closed and
 * removed lazily on the next access.
 *
 * ```kotlin
 * val aggo = MultiDatabaseAggo(
 *     poolFactory = { tenantId -> AggoPool.postgres(config.copy(database = "tenant_$tenantId")) },
 *     cache = TenantPoolCache.lru(maxSize = 100, evictionTtl = Duration.ofMinutes(15)),
 * )
 * ```
 *
 * @param maxSize maximum number of tenant pools kept open at the same time.
 * @param evictionTtl idle duration after which a pool is eligible for lazy
 * eviction.
 */
class TenantPoolCache(
    val maxSize: Int = 50,
    val evictionTtl: Duration = Duration.ofMinutes(30),
) : AutoCloseable {
    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(!evictionTtl.isNegative && !evictionTtl.isZero) { "evictionTtl must be positive" }
    }

    private data class Entry(val pool: AggoPool, val lastAccessed: Instant)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Entry>(maxSize, 0.75f, true)

    /**
     * Returns a cached tenant pool or creates one with [factory].
     *
     * Stale entries are evicted before lookup. If [maxSize] is reached, the
     * least recently accessed pool is closed and removed before the new pool is
     * stored.
     *
     * ```kotlin
     * val pool = cache.getOrCreate("acme") {
     *     AggoPool.postgres(config.copy(database = "tenant_acme"))
     * }
     * ```
     */
    suspend fun getOrCreate(tenantId: String, factory: suspend () -> AggoPool): AggoPool =
        mutex.withLock {
            evictStaleLocked()

            cache[tenantId]?.let { entry ->
                val touched = entry.copy(lastAccessed = Instant.now())
                cache[tenantId] = touched
                return@withLock touched.pool
            }

            if (cache.size >= maxSize) evictOldestLocked()
            val pool = factory()
            cache[tenantId] = Entry(pool, Instant.now())
            pool
        }

    /**
     * Removes and closes the cached pool for [tenantId], if present.
     *
     * Call this after tenant deletion or after rotating credentials for a
     * tenant database.
     *
     * ```kotlin
     * cache.invalidate("acme")
     * ```
     */
    fun invalidate(tenantId: String) {
        val pool = runBlocking { mutex.withLock { cache.remove(tenantId)?.pool } }
        pool?.close()
    }

    /**
     * Closes every cached pool and clears the cache.
     *
     * This is normally called by [MultiDatabaseAggo.close] during application
     * shutdown.
     */
    override fun close() {
        val pools = runBlocking {
            mutex.withLock {
                cache.values.map { it.pool }.also { cache.clear() }
            }
        }
        pools.forEach { pool -> runCatching { pool.close() } }
    }

    private fun evictStaleLocked() {
        val threshold = Instant.now().minus(evictionTtl)
        val stale = cache.entries
            .filter { it.value.lastAccessed.isBefore(threshold) }
            .map { it.key to it.value.pool }
        stale.forEach { (tenantId, pool) ->
            runCatching { pool.close() }
            cache.remove(tenantId)
        }
    }

    private fun evictOldestLocked() {
        val oldest = cache.entries.minByOrNull { it.value.lastAccessed } ?: return
        runCatching { oldest.value.pool.close() }
        cache.remove(oldest.key)
    }

    companion object {
        /**
         * Creates an LRU cache for tenant database pools.
         *
         * ```kotlin
         * val cache = TenantPoolCache.lru(maxSize = 50, evictionTtl = Duration.ofMinutes(30))
         * ```
         */
        fun lru(maxSize: Int = 50, evictionTtl: Duration = Duration.ofMinutes(30)): TenantPoolCache =
            TenantPoolCache(maxSize, evictionTtl)
    }
}
