# spec-9 — Multitenancy: schema-per-tenant e database-per-tenant

> Status: **planned**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

---

## Motivação

Aggo é hoje estritamente single-tenant: uma instância de `Aggo` → um `AggoPool` → um banco de dados.
Aplicações SaaS precisam isolar dados de tenants distintos sem duplicar a camada de acesso a dados.

Dois modelos de isolamento são suportados por esta spec — a escolha é feita via **decorator** na inicialização:

| Modelo | Isolamento | Densidade | Quando usar |
|---|---|---|---|
| **Schema-per-tenant** | PostgreSQL schema (namespace) | Alto — um banco, N schemas | SaaS com centenas de tenants, mesma instância PG |
| **Database-per-tenant** | Banco de dados separado | Médio — N bancos, N pools | Compliance rígido, tenants de tamanhos muito distintos |

Ambos preservam a API `aggo.read { }` / `aggo.tx { }` intacta para o código de negócio.
O tenant é resolvido do **contexto de coroutine** — sem parâmetros extras nas chamadas de negócio.

---

## Decisões de design

1. **Decorator, não herança.** `MultiSchemaAggo` e `MultiDatabaseAggo` são classes independentes com a mesma API de `Aggo`. Um `AggoGateway` interface pode unificá-las numa refatoração futura (0.4.0+).
2. **SQL prefix, não `SET search_path`.** O qualificador de schema é embutido no SQL gerado (`"acme"."users"` em vez de `"users"`). Isso evita contaminação de estado de conexão no pool — conexões retornam limpas.
3. **Coroutine context para tenant ID.** `TenantContext` é um elemento de `CoroutineContext`; o código de negócio não vê o tenant. Frameworks reativos (Quarkus, Ktor) populam o contexto por request.
4. **Pool cache com evicção LRU** para o modo multi-database. Pools inativos por mais de um TTL são fechados graciosamente.
5. **Migrations isoladas por tenant.** `aggo_schema_versions` vive dentro do schema/banco de cada tenant — o rastreamento de versão é automaticamente por-tenant.

---

## M-1 — `SqlDialect.qualifyTableName` (render-time hook)

**Arquivo:** `dialect/SqlDialect.kt`

Adicionar um único método com implementação padrão:

```kotlin
interface SqlDialect {
    fun placeholder(oneBasedIndex: Int): String
    fun quoteIdentifier(name: String): String

    /**
     * Returns the fully-qualified table reference used in FROM / JOIN / UPDATE /
     * INSERT INTO / DELETE FROM clauses.
     *
     * Default: bare quoted identifier — `"users"`.
     * Multi-schema override: `"tenant_schema"."users"`.
     */
    fun qualifyTableName(tableName: String): String = quoteIdentifier(tableName)
}
```

Implementações existentes (`PostgresDialect`, `MySqlDialect`, `OracleDialect`) herdam o default — **zero breaking change**.

---

## M-2 — Atualizar renderers e Session

**Arquivos:** `render/Renderers.kt`, `render/PredicateRenderer.kt`, `runtime/Session.kt`

### M-2a: `Renderers.kt`

Substituir `dialect.quoteIdentifier(query.table.name)` pelo novo método em todos os pontos de referência de tabela:

| Renderer | Ocorrência | Mudança |
|---|---|---|
| `renderSelect` | `FROM` clause | `dialect.qualifyTableName(query.table.name)` |
| `renderJoinSelect` | `FROM` e `JOIN` | `dialect.qualifyTableName(query.leftTable.name)`, idem right |
| `renderInsert` | `INSERT INTO` | `dialect.qualifyTableName(query.table.name)` |
| `renderUpdate` | `UPDATE` | `dialect.qualifyTableName(query.table.name)` |
| `renderDelete` | `DELETE FROM` | `dialect.qualifyTableName(query.table.name)` |
| `renderAggregateSelect` | `FROM` | `dialect.qualifyTableName(query.table.name)` |
| `renderProjectionSelect` | `FROM` | `dialect.qualifyTableName(query.table.name)` |

`ORDER BY` column references usam já `quoteIdentifier(col.table.name)` — estas também devem mudar:

```kotlin
// Antes:
"${dialect.quoteIdentifier(o.column.table.name)}.${dialect.quoteIdentifier(o.column.name)}"

// Depois:
"${dialect.qualifyTableName(o.column.table.name)}.${dialect.quoteIdentifier(o.column.name)}"
```

### M-2b: `PredicateRenderer.kt`

`Operand.Col` renderiza a referência de tabela de coluna em predicates:

```kotlin
is Operand.Col<*, *> -> {
    val col = operand.column
    // Antes:
    "${ctx.dialect.quoteIdentifier(col.table.name)}.${ctx.dialect.quoteIdentifier(col.name)}"
    // Depois:
    "${ctx.dialect.qualifyTableName(col.table.name)}.${ctx.dialect.quoteIdentifier(col.name)}"
}
```

### M-2c: `Session.kt` — tabela de migração

`ensureVersionTable` usa SQL raw com nome de tabela hardcoded. Para que a tabela de versões viva dentro do schema do tenant:

```kotlin
private suspend fun ensureVersionTable() {
    val versionTable = dialect.qualifyTableName("aggo_schema_versions")
    executeMigrationSql(
        """
        CREATE TABLE IF NOT EXISTS $versionTable (
            "version"          TEXT PRIMARY KEY,
            "previous_version" TEXT,
            "description"      TEXT NOT NULL,
            "applied_at"       TIMESTAMPTZ NOT NULL DEFAULT now(),
            "checksum"         TEXT
        );
        """.trimIndent()
    )
    // ALTER TABLE também usa qualifyTableName
    executeMigrationSql(
        "ALTER TABLE $versionTable ADD COLUMN IF NOT EXISTS \"checksum\" TEXT;"
    )
}
```

Os demais `queryLong` e `executeMigrationSql` dentro de `applyMigration` que referenciam `"aggo_schema_versions"` sofrem a mesma substituição.

---

## M-3 — `TenantContext` e `TenantResolver`

**Novo arquivo:** `runtime/multitenancy/TenantContext.kt`

```kotlin
/**
 * Coroutine context element carrying the current tenant identifier.
 *
 * Place it with `withContext(TenantContext("acme")) { ... }` at the request boundary
 * (HTTP filter, gRPC interceptor, message consumer, etc.). All Aggo calls within
 * that coroutine scope will automatically use the correct tenant.
 *
 * Example — Quarkus reactive filter:
 * ```kotlin
 * @ServerRequestFilter
 * suspend fun tenantFilter(ctx: RoutingContext): Unit = withContext(
 *     TenantContext(ctx.request().getHeader("X-Tenant-Id") ?: error("missing X-Tenant-Id"))
 * ) { ctx.next() }
 * ```
 */
class TenantContext(val id: String) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TenantContext>
    override val key: CoroutineContext.Key<*> get() = Key
}

/** Returns the tenant ID from the current coroutine context, or null if none is set. */
suspend fun currentTenantOrNull(): String? =
    currentCoroutineContext()[TenantContext]?.id

/** Returns the tenant ID from the current coroutine context, or throws [IllegalStateException]. */
suspend fun requireCurrentTenant(): String =
    currentTenantOrNull()
        ?: error("No TenantContext in current coroutine context. Wrap the call with withContext(TenantContext(id)) { ... }")
```

**Novo arquivo:** `runtime/multitenancy/TenantResolver.kt`

```kotlin
/**
 * Resolves the current tenant identifier. Implement this to read the tenant
 * from any source — coroutine context, thread-local, JWT claim, etc.
 *
 * The default [CoroutineTenantResolver] reads [TenantContext] from the coroutine context.
 */
fun interface TenantResolver {
    suspend fun currentTenantId(): String
}

/** Reads the tenant ID from [TenantContext] in the current coroutine context. */
object CoroutineTenantResolver : TenantResolver {
    override suspend fun currentTenantId(): String = requireCurrentTenant()
}
```

---

## M-4 — `MultiSchemaSqlDialect` (dialect decorator)

**Novo arquivo:** `dialect/MultiSchemaSqlDialect.kt`

```kotlin
/**
 * [SqlDialect] decorator that prefixes every table reference with a schema name.
 *
 * All other dialect behaviours (placeholder style, identifier quoting, pagination,
 * type mapping) are delegated to [base] unchanged.
 *
 * ```
 * -- Without decorator:
 * SELECT "id", "email" FROM "users" WHERE ...
 *
 * -- With MultiSchemaSqlDialect(base = PostgresDialect, schema = "acme"):
 * SELECT "id", "email" FROM "acme"."users" WHERE ...
 * ```
 */
class MultiSchemaSqlDialect(
    private val base: SqlDialect,
    val schema: String,
) : SqlDialect by base {

    init {
        requireValidIdentifier(schema)
    }

    override fun qualifyTableName(tableName: String): String =
        "${base.quoteIdentifier(schema)}.${base.quoteIdentifier(tableName)}"
}

/** Extension for one-liner construction. */
fun SqlDialect.forSchema(schema: String): MultiSchemaSqlDialect =
    MultiSchemaSqlDialect(this, schema)
```

---

## M-5 — `MultiSchemaAggo` (decorator: schema-per-tenant)

**Novo arquivo:** `runtime/multitenancy/MultiSchemaAggo.kt`

Mirrors the full public API of `Aggo`. Acquires connections from a **single shared pool** and injects a per-request `MultiSchemaSqlDialect`.

```kotlin
/**
 * Schema-per-tenant decorator over [AggoPool].
 *
 * A single connection pool is shared across all tenants. Before each `read` or
 * `tx` block, the current tenant ID is resolved via [resolver] and a
 * [MultiSchemaSqlDialect] is built for that schema. The SQL sent to Postgres
 * will qualify every table reference with the tenant schema:
 * `"acme"."users"`, `"globex"."orders"`, etc.
 *
 * No connection state is modified — connections are pool-safe.
 *
 * ```kotlin
 * val aggo = MultiSchemaAggo(
 *     pool    = AggoPool.postgres(config),   // single pool
 *     schema  = { tenantId -> tenantId },    // "acme" → schema "acme"
 * )
 *
 * withContext(TenantContext("acme")) {
 *     aggo.read { fetchAll(UsersTable) }     // SELECT ... FROM "acme"."users"
 * }
 * withContext(TenantContext("globex")) {
 *     aggo.tx { insert(OrdersTable, order) } // INSERT INTO "globex"."orders"
 * }
 * ```
 *
 * @param pool     Shared connection pool (single database, multiple schemas).
 * @param resolver Resolves the current tenant ID. Defaults to [CoroutineTenantResolver].
 * @param schema   Maps a tenant ID to a PostgreSQL schema name. Defaults to identity.
 */
class MultiSchemaAggo(
    private val pool: AggoPool,
    private val resolver: TenantResolver = CoroutineTenantResolver,
    private val schema: (tenantId: String) -> String = { it },
) : AutoCloseable {

    private suspend fun tenantDialect(): SqlDialect {
        val tenantId = resolver.currentTenantId()
        val schemaName = schema(tenantId)
        return pool.dialect.forSchema(schemaName)
    }

    suspend fun <T> read(block: suspend Session.() -> T): T {
        val dialect = tenantDialect()
        val conn = pool.acquire()
        return try {
            block(Session(conn, dialect))
        } finally {
            withContext(NonCancellable) { runCatching { pool.release(conn) } }
        }
    }

    suspend fun <T> readQuery(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend Session.() -> T,
    ): Query<T, AggoError> =
        try { Query.Success(read(block)) } catch (t: Throwable) { Query.Failure(errorMap.map(t)) }

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
                if (!committed) runCatching { conn.rollbackTransaction().awaitFirstOrNull() }
                    .exceptionOrNull()?.let { t.addSuppressed(it) }
                throw t
            }
        } finally {
            withContext(NonCancellable) { runCatching { pool.release(conn) } }
        }
    }

    suspend fun <T> transaction(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend Session.() -> T,
    ): Transaction<T, AggoError> =
        try { Query.Success(tx(block)) } catch (t: Throwable) { Query.Failure(errorMap.map(t)) }

    // ----- one-shot convenience (mirrors Aggo) ----------------------------

    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        read { fetchAll(table, block) }

    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        read { fetchOne(table, block) }

    suspend fun <E> insert(table: Table<E>, entity: E): Long =
        tx { insert(table, entity) }

    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long =
        tx { insert(table, block) }

    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long =
        tx { update(table, block) }

    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long =
        tx { delete(table, block) }

    suspend fun applyMigration(plan: MigrationPlan): MigrationResult =
        tx { applyMigration(plan) }

    suspend fun applyMigrations(migrationsDir: Path): List<MigrationResult> =
        tx { applyMigrations(readMigrationFiles(migrationsDir)) }

    // ----- tenant provisioning --------------------------------------------

    /**
     * Creates the schema for [tenantId] and applies [plan] in a single transaction.
     * Safe to call on an already-provisioned tenant — CREATE SCHEMA IF NOT EXISTS
     * is idempotent and the migration plan checks `aggo_schema_versions`.
     *
     * ```kotlin
     * aggo.provisionTenant("new-corp", migrationPlan)
     * ```
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
     * Applies [plan] to every tenant in [tenantIds], sequentially.
     * Failed tenants are collected and reported — one failure does not abort the rest.
     *
     * Returns a map of tenantId → result or exception.
     */
    suspend fun applyMigrationToAll(
        plan: MigrationPlan,
        tenantIds: List<String>,
    ): Map<String, Result<MigrationResult>> = tenantIds.associate { tenantId ->
        tenantId to runCatching {
            withContext(TenantContext(tenantId)) { applyMigration(plan) }
        }
    }

    override fun close() = pool.close()
}
```

---

## M-6 — `TenantPoolCache` (cache LRU de pools)

**Novo arquivo:** `runtime/multitenancy/TenantPoolCache.kt`

```kotlin
/**
 * Thread-safe LRU cache of [AggoPool] instances, one per tenant.
 *
 * Pools that haven't been accessed within [evictionTtl] are closed graciously
 * and removed. Eviction runs lazily on the next access.
 *
 * @param maxSize     Maximum number of pools kept alive simultaneously.
 * @param evictionTtl Time after which an idle pool is eligible for eviction.
 */
class TenantPoolCache(
    val maxSize: Int = 50,
    val evictionTtl: Duration = Duration.ofMinutes(30),
) {
    private data class Entry(val pool: AggoPool, val lastAccessed: Instant)

    private val lock = ReentrantReadWriteLock()
    private val cache = LinkedHashMap<String, Entry>(maxSize, 0.75f, true)

    suspend fun getOrCreate(tenantId: String, factory: suspend () -> AggoPool): AggoPool {
        evictStale()
        lock.read { cache[tenantId]?.let { return touch(tenantId, it).pool } }
        lock.write {
            cache[tenantId]?.let { return touch(tenantId, it).pool }
            if (cache.size >= maxSize) evictOldest()
            val pool = runBlocking { factory() }
            cache[tenantId] = Entry(pool, Instant.now())
            return pool
        }
    }

    fun invalidate(tenantId: String) {
        lock.write { cache.remove(tenantId)?.pool?.close() }
    }

    fun close() {
        lock.write { cache.values.forEach { runCatching { it.pool.close() } }; cache.clear() }
    }

    private fun touch(tenantId: String, entry: Entry): Entry =
        entry.copy(lastAccessed = Instant.now()).also { cache[tenantId] = it }

    private fun evictStale() {
        val threshold = Instant.now().minus(evictionTtl)
        lock.write {
            val stale = cache.entries.filter { it.value.lastAccessed.isBefore(threshold) }
            stale.forEach { (id, entry) -> runCatching { entry.pool.close() }; cache.remove(id) }
        }
    }

    private fun evictOldest() {
        val oldest = cache.entries.minByOrNull { it.value.lastAccessed } ?: return
        runCatching { oldest.value.pool.close() }
        cache.remove(oldest.key)
    }

    companion object {
        fun lru(maxSize: Int = 50, evictionTtl: Duration = Duration.ofMinutes(30)) =
            TenantPoolCache(maxSize, evictionTtl)
    }
}
```

---

## M-7 — `MultiDatabaseAggo` (decorator: database-per-tenant)

**Novo arquivo:** `runtime/multitenancy/MultiDatabaseAggo.kt`

```kotlin
/**
 * Database-per-tenant decorator.
 *
 * Each tenant gets an isolated [AggoPool] backed by its own database. Pools are
 * created lazily on first access and cached in a [TenantPoolCache]. Idle pools
 * are evicted after [cache.evictionTtl].
 *
 * ```kotlin
 * val aggo = MultiDatabaseAggo(
 *     poolFactory = { tenantId ->
 *         AggoPool.postgres(
 *             PostgresConfig(
 *                 host     = "db.internal",
 *                 database = "tenant_$tenantId",
 *                 user     = "app",
 *                 password = secret,
 *             )
 *         )
 *     },
 *     cache = TenantPoolCache.lru(maxSize = 100, evictionTtl = Duration.ofMinutes(30)),
 * )
 *
 * withContext(TenantContext("acme")) {
 *     aggo.tx { insert(OrdersTable, order) }  // connects to database "tenant_acme"
 * }
 * ```
 *
 * @param poolFactory Builds a fresh [AggoPool] for a given tenant ID. Called at most
 *                    once per tenant while the pool remains cached.
 * @param resolver    Resolves the current tenant ID. Defaults to [CoroutineTenantResolver].
 * @param cache       Pool lifecycle manager. Defaults to LRU with max 50 pools, 30 min TTL.
 */
class MultiDatabaseAggo(
    private val poolFactory: suspend (tenantId: String) -> AggoPool,
    private val resolver: TenantResolver = CoroutineTenantResolver,
    private val cache: TenantPoolCache = TenantPoolCache.lru(),
) : AutoCloseable {

    private suspend fun tenantAggo(): Aggo {
        val tenantId = resolver.currentTenantId()
        val pool = cache.getOrCreate(tenantId) { poolFactory(tenantId) }
        return Aggo(pool)
    }

    suspend fun <T> read(block: suspend Session.() -> T): T = tenantAggo().read(block)
    suspend fun <T> tx(block: suspend Session.() -> T): T = tenantAggo().tx(block)

    suspend fun <T> readQuery(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend Session.() -> T,
    ): Query<T, AggoError> = tenantAggo().readQuery(errorMap, block)

    suspend fun <T> transaction(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend Session.() -> T,
    ): Transaction<T, AggoError> = tenantAggo().transaction(errorMap, block)

    // ----- one-shot convenience -------------------------------------------

    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E> =
        read { fetchAll(table, block) }

    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E? =
        read { fetchOne(table, block) }

    suspend fun <E> insert(table: Table<E>, entity: E): Long = tx { insert(table, entity) }
    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long = tx { insert(table, block) }
    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long = tx { update(table, block) }
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long = tx { delete(table, block) }

    suspend fun applyMigration(plan: MigrationPlan): MigrationResult = tenantAggo().applyMigration(plan)

    // ----- tenant lifecycle -----------------------------------------------

    /**
     * Applies [plan] to every tenant in [tenantIds], sequentially.
     * Failures are isolated — one broken tenant does not skip the rest.
     */
    suspend fun applyMigrationToAll(
        plan: MigrationPlan,
        tenantIds: List<String>,
    ): Map<String, Result<MigrationResult>> = tenantIds.associate { tenantId ->
        tenantId to runCatching {
            withContext(TenantContext(tenantId)) { applyMigration(plan) }
        }
    }

    /**
     * Evicts the cached pool for [tenantId] and closes it.
     * Call after removing a tenant so the database connection is released.
     */
    fun evictTenant(tenantId: String) = cache.invalidate(tenantId)

    override fun close() = cache.close()
}
```

---

## M-8 — Fluxo de migration por tenant

### Schema-per-tenant — primeiro deploy

```kotlin
val aggo = MultiSchemaAggo(pool = AggoPool.postgres(config))
val plan  = migrationPlan(migrationSchema("2026.06.01.001", listOf(UsersTable, OrdersTable), PostgresDialect), PostgresDialect)

// Novo tenant:
aggo.provisionTenant("acme", plan)
// → CREATE SCHEMA IF NOT EXISTS "acme";
// → CREATE TABLE "acme"."users" ...
// → CREATE TABLE "acme"."orders" ...
// → INSERT INTO "acme"."aggo_schema_versions" ...

// Tenant já existente (idempotente):
aggo.provisionTenant("acme", plan)  // sem efeito — version já registrada
```

### Rollout de migration para todos os tenants

```kotlin
// Lista de tenants vinda de um repository de controle
val tenants = tenantRepository.findAllActive()

val results = aggo.applyMigrationToAll(newPlan, tenants.map { it.id })

results.forEach { (id, result) ->
    result.onSuccess { r -> logger.info("tenant=$id migrated to ${r.toVersion}") }
    result.onFailure { e -> logger.error("tenant=$id FAILED: ${e.message}") }
}
```

### Database-per-tenant — provisioning

```kotlin
val aggo = MultiDatabaseAggo(
    poolFactory = { tenantId ->
        // Esta função é chamada uma vez, na primeira operação do tenant
        // A criação do banco deve ser feita externamente (antes desta chamada)
        AggoPool.postgres(config.copy(database = "tenant_$tenantId"))
    }
)

// Após criar o banco via DBA/terraform:
withContext(TenantContext("acme")) {
    aggo.applyMigration(plan)
}
```

---

## M-9 — Estratégia alternativa: `SET search_path` (opt-in)

O modo SQL prefix (padrão) é seguro para pools sem estado. Para ambientes onde o DBA prefere `search_path` (e.g., ferramentas de auditoria que leem `current_schema()`), a estratégia alternativa pode ser usada.

**Somente suportada dentro de blocos `tx { }`** — `SET LOCAL` é transaction-scoped e reverte no COMMIT/ROLLBACK automaticamente.

```kotlin
val aggo = MultiSchemaAggo(
    pool     = pool,
    strategy = SchemaStrategy.SET_LOCAL_SEARCH_PATH,  // default: SQL_PREFIX
)
```

```kotlin
enum class SchemaStrategy {
    /**
     * Qualifies every table reference in the generated SQL: `"schema"."table"`.
     * No connection state change. Safe for pooled connections. Default.
     */
    SQL_PREFIX,

    /**
     * Executes `SET LOCAL search_path TO "schema"` at the start of each [tx] block.
     * The setting reverts at COMMIT/ROLLBACK. [read] blocks are NOT supported with
     * this strategy — they throw [UnsupportedOperationException].
     * Use only when external tooling requires `current_schema()` to reflect the tenant.
     */
    SET_LOCAL_SEARCH_PATH,
}
```

Implementação para `SET_LOCAL_SEARCH_PATH` dentro de `MultiSchemaAggo.tx`:

```kotlin
// No início do tx block, antes de beginTransaction:
if (strategy == SchemaStrategy.SET_LOCAL_SEARCH_PATH) {
    conn.beginTransaction().awaitFirstOrNull()
    @OptIn(AggoUnsafe::class)
    session.executeRaw("SET LOCAL search_path TO ${pool.dialect.quoteIdentifier(schemaName)};")
}
// O resto do tx segue igual — o search_path reverte no COMMIT/ROLLBACK.
```

---

## M-10 — Segurança: validação do tenant ID

O tenant ID é inserido como **nome de schema** (identificador SQL) ou **nome de banco de dados** (parâmetro de conexão). Ambos devem ser validados rigorosamente.

### Schema name (multi-schema)

`MultiSchemaSqlDialect.init` já chama `requireValidIdentifier(schema)` — rejeita injeção.

### Database name (multi-database)

O tenant ID é passado para `PostgresConfig(database = ...)` que já valida via `SAFE_NAME` regex:

```kotlin
val SAFE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
```

### `schema` lambda no `MultiSchemaAggo`

A lambda `schema: (tenantId: String) -> String` é de responsabilidade do caller. Deve-se sempre sanitizar o valor antes de usá-lo:

```kotlin
// ERRADO — tenant ID arbitrário vira nome de schema direto:
schema = { tenantId -> tenantId }

// CERTO — validação explícita antes de usar:
schema = { tenantId ->
    tenantId.lowercase().replace(Regex("[^a-z0-9_]"), "_").also {
        require(it.matches(Regex("[a-z_][a-z0-9_]*"))) { "invalid tenant id: $tenantId" }
    }
}
```

A spec não impõe transformação automática — o caller tem contexto de negócio para decidir o formato correto.

---

## M-11 — `AggoPool` — helpers internos

**Arquivo:** `runtime/Pool.kt`

Adicionar dois helpers para uso pelos decorators:

```kotlin
class AggoPool internal constructor(
    private val delegate: ConnectionPool,
    val dialect: SqlDialect,
) : AutoCloseable {
    // existing...

    /**
     * Returns a view of this pool with a different [SqlDialect].
     * The underlying [ConnectionPool] is shared — no new connections are created.
     * Used internally by [MultiSchemaAggo] to inject per-tenant dialects.
     */
    internal fun withDialect(dialect: SqlDialect): AggoPool =
        AggoPool(delegate, dialect)
}
```

---

## M-12 — Testes

### Unit tests (sem I/O)

**`MultiSchemaSqlDialectTest`**
- `qualifyTableName("users")` com schema `"acme"` → `"acme"."users"`
- `requireValidIdentifier` rejeita schema name com espaço ou SQL injection
- `renderSelect` com `MultiSchemaSqlDialect` produz SQL com schema prefix em FROM, ORDER BY e predicates
- `renderInsert` com `MultiSchemaSqlDialect` qualifica o INSERT INTO
- `renderJoinSelect` com `MultiSchemaSqlDialect` qualifica left e right tables

**`TenantContextTest`**
- `currentTenantOrNull()` retorna null fora de `withContext(TenantContext(...))`
- `requireCurrentTenant()` lança `IllegalStateException` fora de contexto
- `withContext(TenantContext("x")) { currentTenantOrNull() }` retorna `"x"`
- Nested contexts: inner context wins

**`TenantPoolCacheTest`**
- `getOrCreate` chama `factory` apenas uma vez por tenant
- Evicção por TTL fecha o pool e remove da cache
- Evicção LRU ao atingir `maxSize` fecha o pool mais antigo
- `invalidate` fecha o pool do tenant específico
- `close` fecha todos os pools

**`MultiSchemaAggoTest`** (mock do `AggoPool`)
- `read` cria `Session` com `MultiSchemaSqlDialect` correto
- `tx` usa o schema do tenant resolvido pelo `TenantResolver`
- `provisionTenant` emite `CREATE SCHEMA IF NOT EXISTS` antes de `applyMigration`
- `applyMigrationToAll` continua após falha de um tenant

**`MultiDatabaseAggoTest`** (mock do `poolFactory`)
- `read` chama `poolFactory` apenas uma vez por tenant (cache hit na segunda)
- `evictTenant` invalida o pool e o fecha
- `tenantAggo` propaga exceção do `resolver`

### Integration tests (Testcontainers)

- **`MultiSchemaIntegrationTest`**: cria dois schemas `test_acme` e `test_globex` no mesmo container `postgres:16-alpine`, insere uma linha em cada, confirma que as queries retornam somente os dados do schema correto.
- **`MultiDatabaseIntegrationTest`**: requer dois bancos distintos no container (criados no `@BeforeAll`). Confirma isolamento físico entre tenants.
- **`ProvisionTenantIntegrationTest`**: `provisionTenant` cria schema e tabelas corretamente; segunda chamada é idempotente.
- **`MigrationToAllIntegrationTest`**: aplica um migration em 3 schemas, confirma `aggo_schema_versions` em cada um.

Todos os integration tests seguem a convenção existente: `@Tag("integration")`, skip automático sem Docker.

---

## Sumário de arquivos

| Arquivo | Ação | Motivo |
|---|---|---|
| `dialect/SqlDialect.kt` | **modificar** | Adicionar `qualifyTableName` com default |
| `dialect/MultiSchemaSqlDialect.kt` | **criar** | Dialect decorator que prefixa schema |
| `render/Renderers.kt` | **modificar** | Usar `qualifyTableName` nos 7 pontos de referência de tabela |
| `render/PredicateRenderer.kt` | **modificar** | Usar `qualifyTableName` em `Operand.Col` |
| `runtime/Session.kt` | **modificar** | `ensureVersionTable` e queries de migration usam `qualifyTableName` |
| `runtime/Pool.kt` | **modificar** | Adicionar `withDialect` internal helper |
| `runtime/multitenancy/TenantContext.kt` | **criar** | Coroutine context element + helpers |
| `runtime/multitenancy/TenantResolver.kt` | **criar** | Functional interface + `CoroutineTenantResolver` |
| `runtime/multitenancy/TenantPoolCache.kt` | **criar** | LRU cache de pools com evicção por TTL |
| `runtime/multitenancy/MultiSchemaAggo.kt` | **criar** | Decorator schema-per-tenant |
| `runtime/multitenancy/MultiDatabaseAggo.kt` | **criar** | Decorator database-per-tenant |
| `MultiSchemaSqlDialectTest.kt` | **criar** | Unit tests do dialect decorator |
| `TenantContextTest.kt` | **criar** | Unit tests do context element |
| `TenantPoolCacheTest.kt` | **criar** | Unit tests da cache |
| `MultiSchemaAggoTest.kt` | **criar** | Unit tests do decorator (mock pool) |
| `MultiDatabaseAggoTest.kt` | **criar** | Unit tests do decorator (mock factory) |
| `MultiSchemaIntegrationTest.kt` | **criar** | Integration test com dois schemas |
| `MultiDatabaseIntegrationTest.kt` | **criar** | Integration test com dois bancos |

---

## Checklist

- [ ] M-1: `SqlDialect.qualifyTableName` com default
- [ ] M-2a: 7 pontos de renderer atualizados
- [ ] M-2b: `PredicateRenderer.Operand.Col` atualizado
- [ ] M-2c: `Session.ensureVersionTable` e queries de migration atualizadas
- [ ] M-3: `TenantContext` + helpers de coroutine + `TenantResolver`
- [ ] M-4: `MultiSchemaSqlDialect` + `SqlDialect.forSchema()`
- [ ] M-5: `MultiSchemaAggo` com `read`, `tx`, `provisionTenant`, `applyMigrationToAll`
- [ ] M-6: `TenantPoolCache` com LRU + TTL eviction
- [ ] M-7: `MultiDatabaseAggo` com pool cache
- [ ] M-8: `applyMigrationToAll` em ambos os decorators
- [ ] M-9: `SchemaStrategy.SET_LOCAL_SEARCH_PATH` opt-in
- [ ] M-10: Validação de tenant ID documentada
- [ ] M-11: `AggoPool.withDialect` internal helper
- [ ] M-12: Unit tests + integration tests
