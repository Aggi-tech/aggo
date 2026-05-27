package com.aggitech.aggo.runtime.multitenancy

import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.dialect.forSchema
import com.aggitech.aggo.dialect.requireValidIdentifier
import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.migration.readMigrationFiles
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.runtime.AggoError
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.AggoUnsafe
import com.aggitech.aggo.runtime.ConstraintErrorMap
import com.aggitech.aggo.runtime.MigrationResult
import com.aggitech.aggo.runtime.Query
import com.aggitech.aggo.runtime.Session
import com.aggitech.aggo.runtime.Transaction
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext

/**
 * Schema-per-tenant decorator over a single shared [AggoPool].
 *
 * The current tenant is resolved for each operation and converted into a
 * schema-qualified dialect. SQL uses `"tenant"."table"` references instead of
 * mutating connection state with `SET search_path`.
 *
 * Choose this decorator when tenants share one PostgreSQL database and tenant
 * isolation is implemented with schemas:
 *
 * ```
 * database saas
 * ├── schema acme
 * │   ├── users
 * │   └── aggo_schema_versions
 * └── schema globex
 *     ├── users
 *     └── aggo_schema_versions
 * ```
 *
 * ```kotlin
 * import com.aggitech.aggo.runtime.AggoPool
 * import com.aggitech.aggo.runtime.PostgresConfig
 * import com.aggitech.aggo.runtime.multitenancy.MultiSchemaAggo
 * import com.aggitech.aggo.runtime.multitenancy.TenantContext
 * import kotlinx.coroutines.withContext
 *
 * val aggo = MultiSchemaAggo(
 *     pool = AggoPool.postgres(
 *         PostgresConfig(
 *             host = "db.internal",
 *             database = "saas",
 *             user = "app",
 *             password = System.getenv("DB_PASSWORD"),
 *         )
 *     ),
 *     schema = { tenantId -> tenantId },
 * )
 *
 * withContext(TenantContext("acme")) {
 *     aggo.read {
 *         fetchAll(UsersTable) // SELECT ... FROM "acme"."users"
 *     }
 * }
 * ```
 *
 * @param pool shared pool connected to the database that contains all tenant
 * schemas.
 * @param resolver resolves the current tenant for each operation. Defaults to
 * [CoroutineTenantResolver].
 * @param schema maps an application tenant ID to the PostgreSQL schema name.
 */
class MultiSchemaAggo(
    private val pool: AggoPool,
    private val resolver: TenantResolver = CoroutineTenantResolver,
    private val schema: (tenantId: String) -> String = { it },
) : AutoCloseable {

    private suspend fun tenantDialect(): SqlDialect {
        val tenantId = resolver.currentTenantId()
        return pool.dialect.forSchema(schema(tenantId))
    }

    /**
     * Runs [block] with a tenant-qualified [Session] without opening an
     * explicit transaction.
     *
     * Use this for read-only work or single statements where transaction
     * atomicity is not required. The [Session] uses the tenant dialect for all
     * rendering performed inside the block.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.read {
     *         fetchAll(UsersTable) { where { UsersTable.active eq true } }
     *     }
     * }
     * ```
     */
    suspend fun <T> read(block: suspend Session.() -> T): T {
        val dialect = tenantDialect()
        val conn = pool.acquire()
        return try {
            block(Session(conn, dialect))
        } finally {
            withContext(NonCancellable) {
                runCatching { pool.release(conn) }
            }
        }
    }

    /**
     * Typed-result variant of [read].
     *
     * Exceptions are mapped through [errorMap] into [Query.Failure].
     *
     * ```kotlin
     * val errors = constraintErrorMap(UsersTable)
     *
     * val result = withContext(TenantContext("acme")) {
     *     aggo.readQuery(errors) {
     *         fetchOne(UsersTable) { where { UsersTable.email eq email } }
     *     }
     * }
     * ```
     */
    suspend fun <T> readQuery(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend Session.() -> T,
    ): Query<T, AggoError> =
        try {
            Query.Success(read(block))
        } catch (t: Throwable) {
            Query.Failure(errorMap.map(t))
        }

    /**
     * Runs [block] inside a tenant-qualified transaction.
     *
     * The connection is acquired from the shared pool, the SQL dialect is scoped
     * to the current tenant schema, and rollback errors are attached to the
     * original exception with `addSuppressed`.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.tx {
     *         insert(UsersTable, user)
     *     }
     * }
     * ```
     */
    suspend fun <T> tx(block: suspend Session.() -> T): T {
        val dialect = tenantDialect()
        val conn = pool.acquire()
        return try {
            conn.beginTransaction().awaitFirstOrNull()
            val session = Session(conn, dialect)
            var committed = false
            try {
                val result = block(session)
                conn.commitTransaction().awaitFirstOrNull()
                committed = true
                result
            } catch (t: Throwable) {
                if (!committed) {
                    runCatching { conn.rollbackTransaction().awaitFirstOrNull() }
                        .exceptionOrNull()
                        ?.let { t.addSuppressed(it) }
                }
                throw t
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { pool.release(conn) }
            }
        }
    }

    /**
     * Typed-result variant of [tx].
     *
     * Use this when API handlers should receive a [Transaction] instead of
     * catching database exceptions directly.
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
    ): Transaction<T, AggoError> =
        try {
            Query.Success(tx(block))
        } catch (t: Throwable) {
            Query.Failure(errorMap.map(t))
        }

    /**
     * Executes a pre-built SELECT in the current tenant schema.
     *
     * ```kotlin
     * val activeUsers = select(UsersTable) {
     *     where { UsersTable.active eq true }
     * }
     *
     * val rows = withContext(TenantContext("acme")) {
     *     aggo.fetchAll(activeUsers)
     * }
     * ```
     */
    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        read { fetchAll(query) }

    /**
     * Executes a table SELECT builder in the current tenant schema.
     *
     * ```kotlin
     * val rows = withContext(TenantContext("acme")) {
     *     aggo.fetchAll(UsersTable) {
     *         where { UsersTable.active eq true }
     *         orderBy { UsersTable.createdAt.desc() }
     *     }
     * }
     * ```
     */
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        read { fetchAll(table, block) }

    /**
     * Fetches the first row from a pre-built SELECT in the current tenant schema.
     *
     * ```kotlin
     * val query = select(UsersTable) { where { UsersTable.id eq userId } }
     * val user = withContext(TenantContext("acme")) { aggo.fetchOne(query) }
     * ```
     */
    suspend fun <E> fetchOne(query: Select<E>): E? =
        read { fetchOne(query) }

    /**
     * Fetches the first row from a table SELECT builder in the current tenant schema.
     *
     * ```kotlin
     * val user = withContext(TenantContext("acme")) {
     *     aggo.fetchOne(UsersTable) { where { UsersTable.email eq email } }
     * }
     * ```
     */
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        read { fetchOne(table, block) }

    /**
     * Inserts [entity] inside a tenant-qualified transaction.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.insert(UsersTable, user)
     * }
     * ```
     */
    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        tx { insert(table, entity) }

    /**
     * Inserts a partial row inside a tenant-qualified transaction.
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
        tx { insert(table, block) }

    /**
     * Inserts a row and returns the generated primary key from the tenant schema.
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
    ): V? = tx { insertReturning(table, pkColumn, block) }

    /**
     * Updates rows in the current tenant schema.
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
        tx { update(table, block) }

    /**
     * Deletes rows in the current tenant schema.
     *
     * ```kotlin
     * val deleted = withContext(TenantContext("acme")) {
     *     aggo.delete(UsersTable) { where { UsersTable.id eq userId } }
     * }
     * ```
     */
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        tx { delete(table, block) }

    /**
     * Applies [plan] inside the current tenant schema.
     *
     * The version registry table is also qualified, so each tenant owns an
     * independent `aggo_schema_versions`.
     *
     * ```kotlin
     * val tenantDialect = PostgresDialect.forSchema("acme")
     * val plan = migrationPlan(
     *     migrationSchema("2026.06.01.001", listOf(UsersTable), tenantDialect),
     *     tenantDialect,
     * )
     *
     * withContext(TenantContext("acme")) {
     *     aggo.applyMigration(plan)
     * }
     * ```
     */
    suspend fun applyMigration(plan: MigrationPlan): MigrationResult =
        tx { applyMigration(plan) }

    /**
     * Reads migration files from [migrationsDir] and applies pending ones to the current tenant schema.
     *
     * ```kotlin
     * withContext(TenantContext("acme")) {
     *     aggo.applyMigrations(Paths.get("src/main/resources/aggo/migrations"))
     * }
     * ```
     */
    suspend fun applyMigrations(migrationsDir: Path): List<MigrationResult> =
        tx { applyMigrations(readMigrationFiles(migrationsDir)) }

    /**
     * Creates the tenant schema and applies [plan] inside it.
     *
     * ```kotlin
     * aggo.provisionTenant("acme", initialPlan)
     * ```
     *
     * [tenantId] is mapped through [schema], validated, and then placed in
     * [TenantContext] for the migration call.
     *
     * This is intended for tenant onboarding flows where the schema does not
     * exist yet.
     */
    suspend fun provisionTenant(tenantId: String, plan: MigrationPlan) {
        val schemaName = schema(tenantId)
        requireValidIdentifier(schemaName)
        withContext(TenantContext(tenantId)) {
            tx {
                @OptIn(AggoUnsafe::class)
                executeRaw("CREATE SCHEMA IF NOT EXISTS ${pool.dialect.quoteIdentifier(schemaName)};")
                applyMigration(plan)
            }
        }
    }

    /**
     * Applies [plan] to each tenant in [tenantIds], collecting success or
     * failure per tenant.
     *
     * ```kotlin
     * val results = aggo.applyMigrationToAll(plan, listOf("acme", "globex"))
     * ```
     *
     * Each tenant is wrapped in [TenantContext] before [applyMigration] is
     * called. A failure for one tenant is stored in that tenant's [Result] and
     * does not stop the remaining tenants from running.
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

    /** Closes the shared pool. */
    override fun close() = pool.close()
}
