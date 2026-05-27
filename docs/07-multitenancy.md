# Multitenancy

Aggo supports two tenant-isolation models without adding tenant parameters to
repository methods:

| Model | Isolation | Pooling | Entry point |
|------|-----------|---------|-------------|
| Schema per tenant | PostgreSQL schema | One shared pool | `MultiSchemaAggo` |
| Database per tenant | Separate database | One pool per active tenant | `MultiDatabaseAggo` |

Both models resolve the tenant from the coroutine context by default:

```kotlin
import com.aggitech.aggo.runtime.multitenancy.TenantContext
import kotlinx.coroutines.withContext

withContext(TenantContext("acme")) {
    aggo.read {
        fetchAll(UsersTable)
    }
}
```

No runtime reflection is used. Tenant selection happens at the runtime/dialect
boundary.

## TenantContext

`TenantContext` carries the current tenant ID in the coroutine context.

```kotlin
withContext(TenantContext("acme")) {
    val user = aggo.read {
        fetchOne(UsersTable) {
            where { UsersTable.id eq userId }
        }
    }
}
```

The default resolver is `CoroutineTenantResolver`, which calls
`requireCurrentTenant()`. If a request reaches Aggo without a `TenantContext`,
the operation fails fast instead of falling back to a shared tenant.

Framework filters and interceptors should set the context at the request
boundary:

```kotlin
suspend fun <T> withTenant(tenantId: String, block: suspend () -> T): T =
    withContext(TenantContext(tenantId)) {
        block()
    }
```

## Schema per tenant

Use `MultiSchemaAggo` when all tenants live in the same PostgreSQL database,
but each tenant has its own schema.

```kotlin
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.runtime.multitenancy.MultiSchemaAggo
import com.aggitech.aggo.runtime.multitenancy.TenantContext
import kotlinx.coroutines.withContext

val aggo = MultiSchemaAggo(
    pool = AggoPool.postgres(
        PostgresConfig(
            host = "db.internal",
            database = "saas",
            user = "app",
            password = System.getenv("DB_PASSWORD"),
        )
    ),
    schema = { tenantId -> tenantId },
)

withContext(TenantContext("acme")) {
    aggo.tx {
        insert(UsersTable, user)
    }
}
```

The SQL sent to PostgreSQL is schema-qualified:

```sql
INSERT INTO "acme"."users" ("email", "name") VALUES ($1, $2)
```

Aggo does not call `SET search_path`, so pooled connections do not retain tenant
state after release.

### Custom schema mapping

If tenant IDs are public values and schema names use an internal naming scheme,
map them at construction time:

```kotlin
val aggo = MultiSchemaAggo(
    pool = pool,
    schema = { tenantId -> "tenant_$tenantId" },
)
```

The resulting schema name is validated with the same strict identifier rules
used by dialect quoting.

### Provisioning a schema tenant

`provisionTenant` creates the schema if needed and applies an initial migration
plan inside that tenant schema.

```kotlin
val dialect = PostgresDialect.forSchema("acme")
val schema = migrationSchema(
    version = "2026.06.01.001",
    tables = listOf(UsersTable, OrdersTable),
    dialect = dialect,
)
val plan = migrationPlan(schema, dialect)

aggo.provisionTenant("acme", plan)
```

The tenant has its own version table:

```sql
CREATE TABLE IF NOT EXISTS "acme"."aggo_schema_versions" (...)
```

## Database per tenant

Use `MultiDatabaseAggo` when each tenant has a separate database.

```kotlin
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.runtime.multitenancy.MultiDatabaseAggo
import com.aggitech.aggo.runtime.multitenancy.TenantContext
import com.aggitech.aggo.runtime.multitenancy.TenantPoolCache
import java.time.Duration
import kotlinx.coroutines.withContext

val aggo = MultiDatabaseAggo(
    poolFactory = { tenantId ->
        AggoPool.postgres(
            PostgresConfig(
                host = "db.internal",
                database = "tenant_$tenantId",
                user = "app",
                password = System.getenv("DB_PASSWORD"),
            )
        )
    },
    cache = TenantPoolCache.lru(maxSize = 100, evictionTtl = Duration.ofMinutes(30)),
)

withContext(TenantContext("acme")) {
    aggo.read {
        fetchAll(OrdersTable)
    }
}
```

Pools are created lazily. The first operation for a tenant calls `poolFactory`;
later operations reuse the cached pool until it is evicted.

### Evicting a tenant pool

Evict a pool after tenant deletion, credential rotation, or database recreation:

```kotlin
aggo.evictTenant("acme")
```

Application shutdown should close the decorator:

```kotlin
aggo.close()
```

## Tenant migrations

Both decorators expose `applyMigrationToAll` for sequential rollout:

```kotlin
val results = aggo.applyMigrationToAll(
    plan = plan,
    tenantIds = listOf("acme", "globex", "initech"),
)

results.forEach { (tenantId, result) ->
    result.onSuccess { migration ->
        println("$tenantId migrated to ${migration.toVersion}")
    }
    result.onFailure { error ->
        println("$tenantId failed: ${error.message}")
    }
}
```

Failures are isolated per tenant. A failure for one tenant does not skip the
remaining tenants in the list.

## Choosing a model

Use schema-per-tenant when tenants share the same PostgreSQL instance and need
namespace isolation with high density. Use database-per-tenant when tenant data
must live in separate databases for operational, compliance, or sizing reasons.

In both cases, keep tenant resolution at the request boundary and keep business
repositories tenant-agnostic.

## API reference by class and function

This section mirrors the KDoc examples used by Dokka. It is intentionally
development-oriented: each class and function is shown in the place where it is
normally used.

### `TenantContext`

Use `TenantContext` at the boundary where the application knows the tenant:

```kotlin
suspend fun <T> handleHttpRequest(headers: Headers, block: suspend () -> T): T {
    val tenantId = headers["X-Tenant-Id"] ?: error("missing X-Tenant-Id")

    return withContext(TenantContext(tenantId)) {
        block()
    }
}
```

Repository code does not receive the tenant:

```kotlin
class UserRepository(private val aggo: MultiSchemaAggo) {
    suspend fun findActiveUsers(): List<User> =
        aggo.read {
            fetchAll(UsersTable) {
                where { UsersTable.active eq true }
            }
        }
}
```

### `currentTenantOrNull`

Use `currentTenantOrNull()` when missing tenant context is acceptable, usually
for diagnostics or non-database branches:

```kotlin
suspend fun logTenantScope(logger: Logger) {
    val tenantLabel = currentTenantOrNull() ?: "system"
    logger.info("tenant=$tenantLabel running maintenance step")
}
```

Do not use this helper to silently choose a default database tenant.

### `requireCurrentTenant`

Use `requireCurrentTenant()` when code must fail if the tenant is missing:

```kotlin
suspend fun buildAuditPrefix(): String {
    val tenantId = requireCurrentTenant()
    return "tenant=$tenantId"
}
```

`CoroutineTenantResolver` uses this function internally.

### `TenantResolver`

Implement `TenantResolver` when your runtime has a request context that is not
stored in Kotlin coroutine context:

```kotlin
class JwtTenantResolver(private val principal: Principal) : TenantResolver {
    override suspend fun currentTenantId(): String =
        principal.claim("tenant_id") ?: error("missing tenant_id claim")
}

val aggo = MultiSchemaAggo(
    pool = pool,
    resolver = JwtTenantResolver(principal),
)
```

The resolver should fail fast. Returning a fallback tenant can route data to the
wrong isolation boundary.

### `CoroutineTenantResolver`

Use `CoroutineTenantResolver` when you place `TenantContext` with
`withContext(...)`:

```kotlin
val aggo = MultiSchemaAggo(
    pool = pool,
    resolver = CoroutineTenantResolver,
)

withContext(TenantContext("acme")) {
    aggo.fetchAll(UsersTable)
}
```

This is the default resolver for both multitenant decorators.

### `MultiSchemaSqlDialect`

Use `MultiSchemaSqlDialect` directly when you need to render SQL for a tenant
schema without creating a runtime decorator:

```kotlin
val dialect = MultiSchemaSqlDialect(PostgresDialect, schema = "acme")
val rendered = renderSelect(select(UsersTable), dialect)

check(rendered.sql == """SELECT "id", "email" FROM "acme"."users"""")
```

In normal application code, prefer the extension:

```kotlin
val dialect = PostgresDialect.forSchema("acme")
```

### `MultiSchemaSqlDialect.qualifyTableName`

`qualifyTableName` is the render-time hook used by renderers:

```kotlin
val dialect = PostgresDialect.forSchema("acme")

check(dialect.qualifyTableName("users") == """"acme"."users"""")
```

You rarely call this directly in application code. It exists so renderers and
migration generators do not assemble schema prefixes manually.

### `MultiSchemaMigrationDialect`

Use `MultiSchemaMigrationDialect` when generating tenant-local DDL:

```kotlin
val dialect = PostgresDialect.forSchema("acme")
val schema = migrationSchema(
    version = "2026.06.01.001",
    tables = listOf(UsersTable, OrdersTable),
    dialect = dialect,
)
val plan = migrationPlan(schema, dialect)

println(plan.sql())
```

The plan creates tenant-qualified tables:

```sql
CREATE TABLE "acme"."users" (...)
CREATE TABLE "acme"."orders" (...)
```

### `SqlDialect.forSchema`

Use this overload for DML rendering:

```kotlin
fun renderTenantDelete(schemaName: String): String {
    val dialect = PostgresDialect.forSchema(schemaName)
    return renderDelete(delete(UsersTable), dialect).sql
}
```

### `MigrationDialect.forSchema`

Use this overload for migrations:

```kotlin
fun buildTenantPlan(schemaName: String): MigrationPlan {
    val dialect = PostgresDialect.forSchema(schemaName)
    return migrationPlan(
        current = migrationSchema(
            version = "2026.06.01.001",
            tables = listOf(UsersTable),
            dialect = dialect,
        ),
        dialect = dialect,
    )
}
```

### `MultiSchemaAggo`

`MultiSchemaAggo` is the main application entry point for schema-per-tenant
systems:

```kotlin
val aggo = MultiSchemaAggo(
    pool = AggoPool.postgres(config),
    schema = { tenantId -> "tenant_$tenantId" },
)
```

Every operation resolves the current tenant and renders table references with
that schema.

#### `MultiSchemaAggo.read`

Use `read` for tenant-scoped reads:

```kotlin
val users = withContext(TenantContext("acme")) {
    aggo.read {
        fetchAll(UsersTable) {
            where { UsersTable.active eq true }
        }
    }
}
```

#### `MultiSchemaAggo.readQuery`

Use `readQuery` when callers want `Query.Success` / `Query.Failure` instead of
exceptions:

```kotlin
val errors = constraintErrorMap(UsersTable)

val result = withContext(TenantContext("acme")) {
    aggo.readQuery(errors) {
        fetchOne(UsersTable) { where { UsersTable.email eq email } }
    }
}
```

#### `MultiSchemaAggo.tx`

Use `tx` for multi-statement writes in one tenant schema:

```kotlin
withContext(TenantContext("acme")) {
    aggo.tx {
        insert(UsersTable, user)
        insert(AuditEventsTable) {
            AuditEventsTable.actorId setTo user.id
            AuditEventsTable.event setTo "user.created"
        }
    }
}
```

#### `MultiSchemaAggo.transaction`

Use `transaction` when service handlers return typed database errors:

```kotlin
val result = withContext(TenantContext("acme")) {
    aggo.transaction(constraintErrorMap(UsersTable)) {
        insert(UsersTable, user)
    }
}
```

#### `MultiSchemaAggo.fetchAll`

Both overloads run a SELECT in the tenant schema:

```kotlin
val query = select(UsersTable) {
    where { UsersTable.active eq true }
}

val fromQuery = withContext(TenantContext("acme")) {
    aggo.fetchAll(query)
}

val fromBuilder = withContext(TenantContext("acme")) {
    aggo.fetchAll(UsersTable) {
        where { UsersTable.active eq true }
    }
}
```

#### `MultiSchemaAggo.fetchOne`

Both overloads return the first matching row:

```kotlin
val query = select(UsersTable) {
    where { UsersTable.id eq userId }
}

val fromQuery = withContext(TenantContext("acme")) {
    aggo.fetchOne(query)
}

val fromBuilder = withContext(TenantContext("acme")) {
    aggo.fetchOne(UsersTable) {
        where { UsersTable.email eq email }
    }
}
```

#### `MultiSchemaAggo.insert`

Use entity insert when you already have the domain object:

```kotlin
withContext(TenantContext("acme")) {
    aggo.insert(UsersTable, user)
}
```

Use builder insert for partial rows:

```kotlin
withContext(TenantContext("acme")) {
    aggo.insert(UsersTable) {
        UsersTable.email setTo email
        UsersTable.name setTo name
    }
}
```

#### `MultiSchemaAggo.insertReturning`

Use `insertReturning` when the database generates the primary key:

```kotlin
val id = withContext(TenantContext("acme")) {
    aggo.insertReturning(UsersTable, UsersTable.id) {
        UsersTable.email setTo email
        UsersTable.name setTo name
    }
}
```

#### `MultiSchemaAggo.update`

Use `update` for tenant-local modifications:

```kotlin
val affected = withContext(TenantContext("acme")) {
    aggo.update(UsersTable) {
        UsersTable.active setTo false
        where { UsersTable.id eq userId }
    }
}
```

#### `MultiSchemaAggo.delete`

Use `delete` for tenant-local deletes:

```kotlin
val affected = withContext(TenantContext("acme")) {
    aggo.delete(UsersTable) {
        where { UsersTable.id eq userId }
    }
}
```

#### `MultiSchemaAggo.applyMigration`

Apply a tenant-qualified plan inside the current tenant:

```kotlin
val dialect = PostgresDialect.forSchema("acme")
val plan = migrationPlan(
    migrationSchema("2026.06.01.001", listOf(UsersTable), dialect),
    dialect,
)

withContext(TenantContext("acme")) {
    aggo.applyMigration(plan)
}
```

#### `MultiSchemaAggo.applyMigrations`

Apply pending migration files to the current tenant:

```kotlin
withContext(TenantContext("acme")) {
    aggo.applyMigrations(Paths.get("src/main/resources/aggo/migrations"))
}
```

#### `MultiSchemaAggo.provisionTenant`

Create a schema and apply the initial plan during onboarding:

```kotlin
aggo.provisionTenant("acme", initialPlan)
```

This emits `CREATE SCHEMA IF NOT EXISTS "acme"` before applying the plan.

#### `MultiSchemaAggo.applyMigrationToAll`

Roll out one migration plan across tenants:

```kotlin
val results = aggo.applyMigrationToAll(
    plan = plan,
    tenantIds = listOf("acme", "globex", "initech"),
)

results.forEach { (tenantId, result) ->
    result.onFailure { error ->
        logger.error("tenant=$tenantId migration failed", error)
    }
}
```

#### `MultiSchemaAggo.close`

Close the shared pool on shutdown:

```kotlin
fun stop() {
    aggo.close()
}
```

### `TenantPoolCache`

Use `TenantPoolCache` to control pool reuse in database-per-tenant systems:

```kotlin
val cache = TenantPoolCache(
    maxSize = 100,
    evictionTtl = Duration.ofMinutes(30),
)
```

#### `TenantPoolCache.getOrCreate`

Use `getOrCreate` inside infrastructure code when you need direct cache
control:

```kotlin
val pool = cache.getOrCreate("acme") {
    AggoPool.postgres(config.copy(database = "tenant_acme"))
}
```

Application code normally reaches this through `MultiDatabaseAggo`.

#### `TenantPoolCache.invalidate`

Remove one tenant pool:

```kotlin
cache.invalidate("acme")
```

Use it after tenant deletion, credential rotation, or database recreation.

#### `TenantPoolCache.close`

Close every cached pool:

```kotlin
cache.close()
```

#### `TenantPoolCache.lru`

Create a default LRU cache:

```kotlin
val cache = TenantPoolCache.lru(
    maxSize = 50,
    evictionTtl = Duration.ofMinutes(30),
)
```

### `MultiDatabaseAggo`

`MultiDatabaseAggo` is the main application entry point for
database-per-tenant systems:

```kotlin
val aggo = MultiDatabaseAggo(
    poolFactory = { tenantId ->
        AggoPool.postgres(config.copy(database = "tenant_$tenantId"))
    },
    cache = TenantPoolCache.lru(maxSize = 100),
)
```

All methods mirror `Aggo`, but each call resolves a tenant pool first.

#### `MultiDatabaseAggo.read`

```kotlin
val users = withContext(TenantContext("acme")) {
    aggo.read {
        fetchAll(UsersTable)
    }
}
```

#### `MultiDatabaseAggo.tx`

```kotlin
withContext(TenantContext("acme")) {
    aggo.tx {
        insert(OrdersTable, order)
        update(UsersTable) {
            UsersTable.lastOrderAt setTo order.createdAt
            where { UsersTable.id eq order.userId }
        }
    }
}
```

#### `MultiDatabaseAggo.readQuery`

```kotlin
val result = withContext(TenantContext("acme")) {
    aggo.readQuery(constraintErrorMap(UsersTable)) {
        fetchOne(UsersTable) { where { UsersTable.email eq email } }
    }
}
```

#### `MultiDatabaseAggo.transaction`

```kotlin
val result = withContext(TenantContext("acme")) {
    aggo.transaction(constraintErrorMap(UsersTable)) {
        insert(UsersTable, user)
    }
}
```

#### `MultiDatabaseAggo.fetchAll`

```kotlin
val query = select(OrdersTable) {
    where { OrdersTable.status eq "OPEN" }
}

val rows = withContext(TenantContext("acme")) {
    aggo.fetchAll(query)
}
```

#### `MultiDatabaseAggo.fetchOne`

```kotlin
val user = withContext(TenantContext("acme")) {
    aggo.fetchOne(UsersTable) { where { UsersTable.email eq email } }
}
```

#### `MultiDatabaseAggo.insert`

```kotlin
withContext(TenantContext("acme")) {
    aggo.insert(UsersTable, user)
}
```

#### `MultiDatabaseAggo.insertReturning`

```kotlin
val id = withContext(TenantContext("acme")) {
    aggo.insertReturning(UsersTable, UsersTable.id) {
        UsersTable.email setTo email
        UsersTable.name setTo name
    }
}
```

#### `MultiDatabaseAggo.update`

```kotlin
val affected = withContext(TenantContext("acme")) {
    aggo.update(UsersTable) {
        UsersTable.active setTo false
        where { UsersTable.id eq userId }
    }
}
```

#### `MultiDatabaseAggo.delete`

```kotlin
val affected = withContext(TenantContext("acme")) {
    aggo.delete(UsersTable) { where { UsersTable.id eq userId } }
}
```

#### `MultiDatabaseAggo.applyMigration`

```kotlin
withContext(TenantContext("acme")) {
    aggo.applyMigration(plan)
}
```

#### `MultiDatabaseAggo.applyMigrations`

```kotlin
withContext(TenantContext("acme")) {
    aggo.applyMigrations(Paths.get("src/main/resources/aggo/migrations"))
}
```

#### `MultiDatabaseAggo.applyMigrationToAll`

```kotlin
val results = aggo.applyMigrationToAll(
    plan = plan,
    tenantIds = listOf("acme", "globex"),
)
```

#### `MultiDatabaseAggo.evictTenant`

```kotlin
aggo.evictTenant("acme")
```

#### `MultiDatabaseAggo.close`

```kotlin
fun stop() {
    aggo.close()
}
```
