package com.aggitech.aggo.runtime.multitenancy

import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.runtime.AggoError
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.ConstraintErrorMap
import com.aggitech.aggo.runtime.MigrationResult
import com.aggitech.aggo.runtime.Query
import com.aggitech.aggo.runtime.Session
import com.aggitech.aggo.runtime.Transaction
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import java.nio.file.Path
import kotlinx.coroutines.withContext

/**
 * Database-per-tenant decorator.
 *
 * Each tenant gets an isolated [AggoPool] built lazily by [poolFactory] and
 * cached by [TenantPoolCache]. Tenant identity is resolved per operation.
 *
 * Choose this decorator when each tenant has its own PostgreSQL database:
 *
 * ```
 * tenant_acme   -> pool A
 * tenant_globex -> pool B
 * tenant_initech -> pool C
 * ```
 *
 * ```kotlin
 * import com.aggitech.aggo.runtime.AggoPool
 * import com.aggitech.aggo.runtime.PostgresConfig
 * import com.aggitech.aggo.runtime.multitenancy.MultiDatabaseAggo
 * import com.aggitech.aggo.runtime.multitenancy.TenantContext
 * import kotlinx.coroutines.withContext
 *
 * val aggo = MultiDatabaseAggo(
 *     poolFactory = { tenantId ->
 *         AggoPool.postgres(
 *             PostgresConfig(
 *                 host = "db.internal",
 *                 database = "tenant_$tenantId",
 *                 user = "app",
 *                 password = System.getenv("DB_PASSWORD"),
 *             )
 *         )
 *     },
 * )
 *
 * withContext(TenantContext("acme")) {
 *     aggo.tx {
 *         insert(OrdersTable, order)
 *     }
 * }
 * ```
 *
 * @param poolFactory builds a fresh [AggoPool] for a tenant ID. It is called
 * lazily and only while the tenant is absent from [cache].
 * @param resolver resolves the current tenant for each operation.
 * @param cache manages tenant pool reuse and eviction.
 */
class MultiDatabaseAggo(
    private val poolFactory: suspend (tenantId: String) -> AggoPool,
    private val resolver: TenantResolver = CoroutineTenantResolver,
    private val cache: TenantPoolCache = TenantPoolCache.lru(),
) : AutoCloseable {

    private suspend fun tenantAggo(): Aggo {
        val tenantId = resolver.currentTenantId()
        return Aggo(cache.getOrCreate(tenantId) { poolFactory(tenantId) })
    }

    /**
     * Runs [block] on the database pool resolved for the current tenant.
     *
     * Use this for read-only work or single autocommit statements:
     *
     * ```kotlin
     * val rows = withContext(TenantContext("acme")) {
     *     aggo.read {
     *         fetchAll(UsersTable)
     *     }
     * }
     * ```
     */
    suspend fun <T> read(block: suspend Session.() -> T): T =
        tenantAggo().read(block)

    /**
     * Runs [block] in a transaction on the database pool resolved for the
     * current tenant.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.tx {
     *         insert(OrdersTable, order)
     *         update(UsersTable) {
     *             UsersTable.lastOrderAt setTo order.createdAt
     *             where { UsersTable.id eq order.userId }
     *         }
     *     }
     * }
     * ```
     */
    suspend fun <T> tx(block: suspend Session.() -> T): T =
        tenantAggo().tx(block)

    /**
     * Typed-result variant of [read].
     *
     * ```kotlin
     * val result = withContext(TenantContext("acme")) {
     *     aggo.readQuery(constraintErrorMap(UsersTable)) {
     *         fetchOne(UsersTable) { where { UsersTable.email eq email } }
     *     }
     * }
     * ```
     */
    suspend fun <T> readQuery(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend Session.() -> T,
    ): Query<T, AggoError> = tenantAggo().readQuery(errorMap, block)

    /**
     * Typed-result variant of [tx].
     *
     * ```kotlin
     * val result = withContext(TenantContext("acme")) {
     *     aggo.transaction(constraintErrorMap(UsersTable)) {
     *         insert(UsersTable, user)
     *     }
     * }
     * ```
     */
    suspend fun <T> transaction(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend Session.() -> T,
    ): Transaction<T, AggoError> = tenantAggo().transaction(errorMap, block)

    /**
     * Executes a pre-built SELECT in the current tenant database.
     *
     * ```kotlin
     * val query = select(OrdersTable) {
     *     where { OrdersTable.status eq "OPEN" }
     * }
     *
     * val openOrders = withContext(TenantContext("acme")) {
     *     aggo.fetchAll(query)
     * }
     * ```
     */
    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        tenantAggo().fetchAll(query)

    /**
     * Executes a table SELECT builder in the current tenant database.
     *
     * ```kotlin
     * val users = withContext(TenantContext("acme")) {
     *     aggo.fetchAll(UsersTable) {
     *         where { UsersTable.active eq true }
     *     }
     * }
     * ```
     */
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        tenantAggo().fetchAll(table, block)

    /**
     * Fetches the first row from a pre-built SELECT in the current tenant database.
     *
     * ```kotlin
     * val query = select(UsersTable) { where { UsersTable.id eq userId } }
     * val user = withContext(TenantContext("acme")) { aggo.fetchOne(query) }
     * ```
     */
    suspend fun <E> fetchOne(query: Select<E>): E? =
        tenantAggo().fetchOne(query)

    /**
     * Fetches the first row from a table SELECT builder in the current tenant database.
     *
     * ```kotlin
     * val user = withContext(TenantContext("acme")) {
     *     aggo.fetchOne(UsersTable) { where { UsersTable.email eq email } }
     * }
     * ```
     */
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        tenantAggo().fetchOne(table, block)

    /**
     * Inserts [entity] in the current tenant database.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.insert(UsersTable, user)
     * }
     * ```
     */
    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        tenantAggo().insert(table, entity)

    /**
     * Inserts a partial row in the current tenant database.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.insert(UsersTable) {
     *         UsersTable.email setTo email
     *         UsersTable.name setTo name
     *     }
     * }
     * ```
     */
    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
        tenantAggo().insert(table, block)

    /**
     * Inserts a row and returns the generated primary key from the current tenant database.
     *
     * ```kotlin
     * val id = withContext(TenantContext("acme")) {
     *     aggo.insertReturning(UsersTable, UsersTable.id) {
     *         UsersTable.email setTo email
     *         UsersTable.name setTo name
     *     }
     * }
     * ```
     */
    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V? = tenantAggo().insertReturning(table, pkColumn, block)

    /**
     * Updates rows in the current tenant database.
     *
     * ```kotlin
     * val updated = withContext(TenantContext("acme")) {
     *     aggo.update(UsersTable) {
     *         UsersTable.active setTo false
     *         where { UsersTable.id eq userId }
     *     }
     * }
     * ```
     */
    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        tenantAggo().update(table, block)

    /**
     * Deletes rows in the current tenant database.
     *
     * ```kotlin
     * val deleted = withContext(TenantContext("acme")) {
     *     aggo.delete(UsersTable) { where { UsersTable.id eq userId } }
     * }
     * ```
     */
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        tenantAggo().delete(table, block)

    /**
     * Applies [plan] in the current tenant database.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.applyMigration(plan)
     * }
     * ```
     */
    suspend fun applyMigration(plan: MigrationPlan): MigrationResult =
        tenantAggo().applyMigration(plan)

    /**
     * Reads migration files from [migrationsDir] and applies pending ones to the current tenant database.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.applyMigrations(Paths.get("src/main/resources/aggo/migrations"))
     * }
     * ```
     */
    suspend fun applyMigrations(migrationsDir: Path): List<MigrationResult> =
        tenantAggo().applyMigrations(migrationsDir)

    /**
     * Applies [plan] to each tenant database in [tenantIds], collecting success
     * or failure per tenant.
     *
     * ```kotlin
     * val results = aggo.applyMigrationToAll(plan, listOf("acme", "globex"))
     *
     * results["acme"]?.onFailure { error ->
     *     logger.error("tenant acme failed", error)
     * }
     * ```
     */
    suspend fun applyMigrationToAll(
        plan: MigrationPlan,
        tenantIds: List<String>,
    ): Map<String, Result<MigrationResult>> =
        tenantIds.associateWith { tenantId ->
            runCatching {
                withContext(TenantContext(tenantId)) { applyMigration(plan) }
            }
        }

    /**
     * Closes and removes the cached pool for [tenantId].
     *
     * Use this after tenant removal, database recreation, or credential
     * rotation.
     *
     * ```kotlin
     * aggo.evictTenant("acme")
     * ```
     */
    fun evictTenant(tenantId: String) {
        cache.invalidate(tenantId)
    }

    /**
     * Closes every cached tenant pool.
     *
     * ```kotlin
     * fun shutdown() {
     *     aggo.close()
     * }
     * ```
     */
    override fun close() = cache.close()
}
