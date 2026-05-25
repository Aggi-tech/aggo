package com.aggitech.aggo.runtime

import com.aggitech.aggo.dsl.DeleteBuilder
import com.aggitech.aggo.dsl.InsertBuilder
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.UpdateBuilder
import com.aggitech.aggo.migration.MigrationPlan
import com.aggitech.aggo.query.Select
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.Table
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext

/**
 * Application-level entry point for Aggo — one instance per database.
 *
 * Aggo is a reflection-free, type-safe R2DBC DSL for PostgreSQL. All column
 * mappings are explicit lambdas; no annotation scanning or runtime reflection
 * ever happens. This makes it fully compatible with GraalVM native-image.
 *
 * ## 1 — Setup
 *
 * Create the pool once (application startup) and close it on shutdown:
 *
 * ```kotlin
 * val aggo = Aggo(
 *     AggoPool.postgres(
 *         PostgresConfig(
 *             host     = "localhost",
 *             database = "mydb",
 *             user     = "app",
 *             password = System.getenv("DB_PASSWORD"),
 *         )
 *     )
 * )
 * // aggo.close() on shutdown (or use try-with-resources)
 * ```
 *
 * ## 2 — Define a table
 *
 * Subclass [com.aggitech.aggo.schema.Table] once per relational table.
 * Declare each column with an explicit [com.aggitech.aggo.schema.Codec] and
 * a getter lambda. Optionally attach a CHECK constraint via `check = Checks.*`.
 *
 * ```kotlin
 * object UsersTable : Table<User>("users") {
 *     val id    = column("id",    TsidCodec,   isPrimaryKey = true,
 *                     check = Checks.tsid())                         { it.id }
 *     val email = column("email", EmailCodec,  check = Checks.email()) { it.email }
 *     val name  = column("name",  StringCodec,
 *                     check = Checks.all(Checks.notBlank(), Checks.length(max = 100))) { it.name }
 *     val active = column("active", BooleanCodec)                    { it.active }
 *
 *     override fun fromRow(row: Row): User = User(
 *         id     = id.readRequired(row),
 *         email  = email.readRequired(row),
 *         name   = name.readRequired(row),
 *         active = active.readRequired(row),
 *     )
 * }
 * ```
 *
 * ## 3 — Read data (no transaction)
 *
 * Use [read] when no writes are needed. The connection is released automatically:
 *
 * ```kotlin
 * // All rows
 * val users: List<User> = aggo.read { fetchAll(UsersTable) }
 *
 * // With WHERE / ORDER BY / LIMIT
 * val active = aggo.read {
 *     fetchAll(UsersTable) {
 *         where { (UsersTable.active eq true) and (UsersTable.name like "%alice%") }
 *         orderBy { UsersTable.name.asc() }
 *         limit(20)
 *     }
 * }
 *
 * // Single row — internally adds LIMIT 1
 * val user: User? = aggo.read {
 *     fetchOne(UsersTable) { where { UsersTable.id eq userId } }
 * }
 *
 * // Stream large result sets without loading them all into memory
 * aggo.read {
 *     stream(UsersTable).collect { user -> process(user) }
 * }
 * ```
 *
 * ## 4 — Write data (transactional)
 *
 * Use [tx] for all inserts, updates, and deletes. The block runs inside a single
 * `BEGIN … COMMIT` transaction; any exception triggers automatic rollback:
 *
 * ```kotlin
 * // Insert an entity (generated columns are skipped automatically)
 * aggo.tx { insert(UsersTable, user) }
 *
 * // Insert and retrieve the generated primary key
 * val newId: Tsid? = aggo.tx {
 *     insertReturning(UsersTable, UsersTable.id) {
 *         UsersTable.email setTo Email("alice@example.com")
 *         UsersTable.name  setTo "Alice"
 *         UsersTable.active setTo true
 *     }
 * }
 *
 * // Update specific columns
 * aggo.tx {
 *     update(UsersTable) {
 *         UsersTable.active setTo false
 *         where { UsersTable.id eq userId }
 *     }
 * }
 *
 * // Delete rows
 * aggo.tx { delete(UsersTable) { where { UsersTable.id eq userId } } }
 * ```
 *
 * ## 5 — Multi-statement transactions
 *
 * All statements inside a single [tx] block share the same connection and
 * transaction. Either all commit or all roll back:
 *
 * ```kotlin
 * aggo.tx {
 *     val affected = update(OrdersTable) {
 *         OrdersTable.status setTo "COMPLETED"
 *         where { OrdersTable.id eq orderId }
 *     }
 *     check(affected == 1L) { "order $orderId not found" }
 *     insert(AuditTable) {
 *         AuditTable.orderId setTo orderId
 *         AuditTable.event   setTo "order.completed"
 *     }
 * }
 * ```
 *
 * ## 6 — JOIN queries
 *
 * ```kotlin
 * val rows: List<JoinedRow<Order, User?>> = aggo.read {
 *     fetchAllJoined(
 *         OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
 *             .where { OrdersTable.status eq "PENDING" }
 *             .orderBy { OrdersTable.createdAt.desc() }
 *             .limit(50)
 *     )
 * }
 * rows.forEach { (order, user) -> println("${order.id} → ${user?.name}") }
 * ```
 *
 * ## 7 — Generate migrations
 *
 * Table descriptors can generate and Aggo can apply versioned migration plans:
 *
 * ```kotlin
 * import com.aggitech.aggo.dialect.PostgresDialect
 * import com.aggitech.aggo.migration.migrationPlan
 * import com.aggitech.aggo.migration.migrationSchema
 *
 * val schema = migrationSchema("2026.05.25.001", listOf(UsersTable), PostgresDialect)
 * val plan = migrationPlan(schema, PostgresDialect)
 *
 * aggo.applyMigration(plan)
 * ```
 *
 * @see Session for the full query API available inside [read] and [tx] blocks
 * @see AggoPool.Companion.postgres to configure the connection pool
 * @see com.aggitech.aggo.schema.Table to define your schema
 * @see com.aggitech.aggo.schema.Checks for built-in CHECK constraint helpers
 * @see com.aggitech.aggo.migration.createTableSql to generate DDL migrations
 */
class Aggo(private val pool: AggoPool) : AutoCloseable {

    /**
     * Acquires a connection, runs [block] with a [Session] as receiver, then
     * releases the connection. No transaction — the driver uses autocommit.
     *
     * Use this for read-only operations or single-statement writes where
     * atomicity is not required. For multi-statement atomicity, use [tx].
     */
    suspend fun <T> read(block: suspend Session.() -> T): T {
        val conn = pool.acquire()
        return try {
            block(Session(conn, pool.dialect))
        } finally {
            // V-5: never lose the connection on coroutine cancellation.
            withContext(NonCancellable) {
                runCatching { pool.release(conn) }
            }
        }
    }

    /**
     * Runs [block] inside a single `BEGIN … COMMIT` transaction.
     *
     * If [block] throws, the transaction is rolled back automatically. Rollback
     * errors are attached to the original exception via `addSuppressed` so the
     * caller always catches the real cause.
     *
     * All [Session] calls inside [block] share the same connection, guaranteeing
     * that every statement participates in the same transaction.
     *
     * ```kotlin
     * val newId = aggo.tx {
     *     val id = insertReturning(UsersTable, UsersTable.id) {
     *         UsersTable.email setTo email
     *         UsersTable.name  setTo name
     *     }
     *     insert(WelcomeEmailsTable) { WelcomeEmailsTable.userId setTo id }
     *     id
     * }
     * ```
     */
    suspend fun <T> tx(block: suspend Session.() -> T): T {
        val conn = pool.acquire()
        return try {
            conn.beginTransaction().awaitFirstOrNull()
            val session = Session(conn, pool.dialect)
            var committed = false
            try {
                val result = block(session)
                conn.commitTransaction().awaitFirstOrNull()
                committed = true
                result
            } catch (t: Throwable) {
                if (!committed) {
                    runCatching {
                        conn.rollbackTransaction().awaitFirstOrNull()
                    }.exceptionOrNull()?.let { rollbackError ->
                        t.addSuppressed(rollbackError)
                    }
                }
                throw t
            }
        } finally {
            // V-5: same rationale as read().
            withContext(NonCancellable) {
                runCatching { pool.release(conn) }
            }
        }
    }

    // ----- One-shot convenience helpers -----------------------------------
    //
    // Each helper opens its own scope (tx for writes, read for reads) and is
    // equivalent to aggo.read { fetchAll(…) } / aggo.tx { insert(…) }.
    // Use the block form (read/tx) when you need multiple statements in one scope.

    /** Equivalent to `aggo.read { fetchAll(query) }`. */
    suspend fun <E> fetchAll(query: Select<E>): List<E> =
        read { fetchAll(query) }

    /** Equivalent to `aggo.read { fetchAll(table) { … } }`. */
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        read { fetchAll(table, block) }

    /** Equivalent to `aggo.read { fetchOne(query) }`. Returns null when no row matches. */
    suspend fun <E> fetchOne(query: Select<E>): E? =
        read { fetchOne(query) }

    /** Equivalent to `aggo.read { fetchOne(table) { … } }`. Returns null when no row matches. */
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        read { fetchOne(table, block) }

    /** Insert [entity] inside a new transaction. Returns the number of rows affected (always 1). */
    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        tx { insert(table, entity) }

    /** Insert a partial row via builder inside a new transaction. Returns the number of rows affected. */
    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
        tx { insert(table, block) }

    /**
     * Insert a row and return the generated primary key, decoded via [pkColumn]'s codec.
     *
     * ```kotlin
     * val id: Tsid? = aggo.insertReturning(UsersTable, UsersTable.id) {
     *     UsersTable.email setTo email
     *     UsersTable.name  setTo name
     * }
     * ```
     */
    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V? = tx { insertReturning(table, pkColumn, block) }

    /**
     * Update rows matching the WHERE clause inside a new transaction.
     * Returns the number of rows affected. Always provide a WHERE clause
     * unless you intentionally want to update every row.
     */
    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        tx { update(table, block) }

    /**
     * Delete rows matching the WHERE clause inside a new transaction.
     * Returns the number of rows deleted. Always provide a WHERE clause
     * unless you intentionally want to delete every row.
     */
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        tx { delete(table, block) }

    /**
     * Applies an Aggo-generated migration plan in a transaction and records the
     * applied schema version in `aggo_schema_versions`.
     */
    suspend fun applyMigration(plan: MigrationPlan): MigrationResult =
        tx { applyMigration(plan) }

    override fun close() = pool.close()
}
