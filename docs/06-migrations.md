# Package com.aggitech.aggo.migration

# Migration Generation and Execution

Languages: English first, Portuguese below.

Aggo can generate and apply database migrations from your `Table` descriptors.
The generated plan is detached from the runtime: Aggo reads `Table`/`Codec`
metadata once, materializes an immutable `MigrationSchema`, and then renders SQL
from that snapshot.

Liquibase is no longer part of the workflow. You may still export `plan.sql()`
to any external migration tool, but Aggo can execute the same plan itself and
record the applied version in `aggo_schema_versions`.

Compared with Hibernate `hbm2ddl`, Aggo migrations are intended to be reviewed,
versioned, and applied deliberately. Do not use automatic schema mutation at
application startup for production.

## AggoMigrateTask — zero-boilerplate Maven entry point

`AggoMigrateTask` is the recommended way to trigger migration generation from
Maven. It replaces manual `main()` functions with a single-object declaration.

### 1. Declare the task

```kotlin
// src/main/kotlin/com/example/db/Migrations.kt
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.AggoMigrateTask

object Migrations : AggoMigrateTask() {
    override val tables  = listOf(UsersTable, OrdersTable, ProductsTable)
    override val dialect = PostgresDialect
}

fun main(args: Array<String>) = Migrations.runFromArgs(args)
```

### 2. Wire exec-maven-plugin in pom.xml

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.4.1</version>
  <executions>
    <execution>
      <id>aggo-migrate</id>
      <goals><goal>java</goal></goals>
      <configuration>
        <mainClass>com.example.db.MigrationsKt</mainClass>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### 3. Run subcommands

`AggoMigrateTask` exposes a small set of subcommands as the first positional
argument. With no subcommand, the legacy "generate with optional name" form is
preserved.

```bash
# Generate a new migration file from current Tables (default)
mvn compile exec:java
mvn compile exec:java -Dexec.args="generate add_orders_table"

# Apply pending migrations against the configured database
mvn compile exec:java -Dexec.args="apply"

# Show applied vs pending without running anything
mvn compile exec:java -Dexec.args="status"

# Print the pending SQL to stdout without applying
mvn compile exec:java -Dexec.args="dry-run"

# Drop every declared table and aggo_schema_versions (dev only by default)
mvn compile exec:java -Dexec.args="drop"
mvn compile exec:java -Dexec.args="drop --force"   # bypass prod guard

# Drop + apply in one shot (e.g. reset a local DB after schema churn)
mvn compile exec:java -Dexec.args="reset"

# Print help
mvn compile exec:java -Dexec.args="help"
```

`generate` produces a `{timestamp}_name.sql` file under `migrationsDir` and
updates the snapshot. On a clean run with no schema changes it prints
`No changes detected.` and exits without writing anything.

#### Production safety

`drop` and `reset` are destructive. They refuse to run when the resolved
[environment](#environment-and-database-credentials) equals `prod`
(case-insensitive) unless the caller also passes `--force`:

```
Refusing to drop in production. Set AGGO_ENV=dev/staging or pass --force to override.
```

CI pipelines should set `AGGO_ENV=prod` on real deploy targets so an accidental
`reset` cannot wipe live data.

#### Environment and database credentials

Apply / status / drop / reset need a live database connection. Resolve it in
one of two ways:

1. **Override `poolConfig` in your `AggoMigrateTask` subclass** (preferred when
   you want to reuse the application's connection settings):

   ```kotlin
   object Migrations : AggoMigrateTask() {
       override val tables  = listOf(UsersTable, OrdersTable)
       override val dialect = PostgresDialect
       override val poolConfig = PostgresConfig(
           host = "localhost",
           database = "myapp",
           user = "app",
           password = System.getenv("DB_PASSWORD"),
       )
   }
   ```

2. **System properties / environment variables**:

   | System property       | Env var               |
   |-----------------------|-----------------------|
   | `aggo.db.host`        | `AGGO_DB_HOST`        |
   | `aggo.db.port`        | `AGGO_DB_PORT`        |
   | `aggo.db.database`    | `AGGO_DB_DATABASE`    |
   | `aggo.db.user`        | `AGGO_DB_USER`        |
   | `aggo.db.password`    | `AGGO_DB_PASSWORD`    |
   | `aggo.db.sslMode`     | `AGGO_DB_SSL_MODE`    |
   | `aggo.env`            | `AGGO_ENV`            |

   Prefer the env var for the password — system properties show up in
   `ps aux` on shared hosts.

### Default file locations

| What | Default path | Override via |
|------|-------------|--------------|
| Schema snapshot | `src/main/resources/aggo/snapshot.json` | `-Daggo.snapshotFile=…` or `override val snapshotFile` |
| Migration files | `src/main/resources/aggo/migrations/` | `-Daggo.migrationsDir=…` or `override val migrationsDir` |
| Version label | _(timestamp only)_ | `-Daggo.name=…` or `args[0]` |

Override paths as system properties or by overriding the `open val` in the
object:

```kotlin
object Migrations : AggoMigrateTask() {
    override val tables       = listOf(UsersTable)
    override val dialect      = PostgresDialect
    override val snapshotFile = Paths.get("infra/db/snapshot.json")
    override val migrationsDir = Paths.get("infra/db/migrations")
}
```

## Versioned schema snapshot

```kotlin
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema

val schema = migrationSchema(
    version = "2026.05.25.001",
    tables = listOf(UsersTable, OrdersTable),
    dialect = PostgresDialect,
)

val plan = migrationPlan(schema, PostgresDialect)
println(plan.sql())
```

`version` must match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. Use an ordered value
such as `YYYY.MM.DD.NNN`, a release tag, or a monotonic build migration id.

## How to use

Use migrations as a deployment step, before the application starts serving
traffic that depends on the new schema.

### 1. Declare the current schema

Keep one explicit list with every table owned by the service:

```kotlin
val currentSchema = migrationSchema(
    version = "2026.05.25.001",
    tables = listOf(
        UsersTable,
        OrdersTable,
        ProductsTable,
    ),
    dialect = PostgresDialect,
)
```

### 2. Bootstrap an empty database

For the first migration, generate a plan without `previous`:

```kotlin
val plan = migrationPlan(
    current = currentSchema,
    dialect = PostgresDialect,
)

aggo.applyMigration(plan)
```

This creates all tables in the schema and records `2026.05.25.001` in
`aggo_schema_versions`.

### 3. Evolve an existing database

When the schema changes, keep the previous snapshot available in code or a
generated fixture, then diff it against the new snapshot:

```kotlin
val previousSchema = migrationSchema(
    version = "2026.05.25.001",
    tables = previousTables,
    dialect = PostgresDialect,
)

val currentSchema = migrationSchema(
    version = "2026.05.25.002",
    tables = currentTables,
    dialect = PostgresDialect,
)

val plan = migrationPlan(
    current = currentSchema,
    dialect = PostgresDialect,
    previous = previousSchema,
)
```

Review the plan before applying it:

```kotlin
plan.steps.forEach { step ->
    println("${step.change}: ${step.sql ?: "manual"}")
}
```

If every step has SQL, execute it:

```kotlin
aggo.applyMigration(plan)
```

If any step is marked manual, `applyMigration` fails before running SQL. Write
the domain-specific migration first, usually with backfill and compatibility
checks, then update the schema snapshot once the database is ready.

### 4. Recommended startup hook

Run migrations from a controlled deploy job or startup gate:

```kotlin
suspend fun migrate(aggo: Aggo) {
    val schema = migrationSchema(
        version = "2026.05.25.002",
        tables = listOf(UsersTable, OrdersTable, ProductsTable),
        dialect = PostgresDialect,
    )

    val plan = migrationPlan(schema, PostgresDialect)
    aggo.applyMigration(plan)
}
```

For production services, prefer a single migrator process per environment. Do
not let multiple application replicas race to apply the same schema version.

## Applying with Aggo

```kotlin
val result = aggo.applyMigration(plan)

println(result.toVersion)
println(result.statementsExecuted)
```

`Aggo.applyMigration(plan)` runs in a transaction, executes every SQL step, and
then inserts a row into `aggo_schema_versions`:

```sql
CREATE TABLE IF NOT EXISTS "aggo_schema_versions" (
    "version" TEXT PRIMARY KEY,
    "previous_version" TEXT,
    "description" TEXT NOT NULL,
    "applied_at" TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

If a plan contains manual steps, Aggo refuses it before running any SQL. Manual
steps are emitted for destructive or ambiguous changes, including dropped
tables, dropped columns, type changes, changed CHECK expressions, and primary-key
rewrites.

## Diffing schema versions

Keep the previous snapshot in source control next to the migration that produced
it, then diff it against the new snapshot:

```kotlin
val previous = migrationSchema("2026.05.25.001", oldTables, PostgresDialect)
val current = migrationSchema("2026.05.25.002", newTables, PostgresDialect)

val plan = migrationPlan(
    current = current,
    dialect = PostgresDialect,
    previous = previous,
)

plan.steps.forEach { step ->
    println("${step.change}: ${step.sql ?: "manual"}")
}
```

Safe additive changes receive SQL:

```sql
ALTER TABLE "users" ADD COLUMN "nickname" TEXT;
ALTER TABLE "users" ALTER COLUMN "deleted_at" DROP NOT NULL;
```

Risky changes are tracked as `requiresManualMigration = true` so you can write a
domain-aware migration with backfill, locking, compatibility, and rollback
rules. This includes new `NOT NULL` columns and nullable-to-not-null changes,
because existing rows may need a default or backfill.

## Standalone SQL generation

The older helpers remain available for scripts and tests:

```kotlin
import com.aggitech.aggo.migration.createTableSql
import com.aggitech.aggo.migration.dropTableSql

println(UsersTable.createTableSql(PostgresDialect))
println(UsersTable.dropTableSql(PostgresDialect, ifExists = true))
```

Output:

```sql
CREATE TABLE "users" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "active" BOOLEAN NOT NULL,
    "created_at" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "chk_users_email" CHECK ("email" ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    PRIMARY KEY ("id")
);
```

## Generated columns

Columns with `isGenerated = true` appear in the DDL with their type but without
a DEFAULT or GENERATED expression. Aggo cannot infer your database-side
generation strategy, so add that in a manual migration when needed:

```sql
"id" INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY
"created_at" TIMESTAMPTZ NOT NULL DEFAULT now()
```

## CHECK constraints for existing tables

Prefer the dialect-aware overload:

```kotlin
UsersTable.addCheckConstraintsSql(PostgresDialect).forEach(::println)
```

Output:

```sql
ALTER TABLE "users" ADD CONSTRAINT "chk_users_email" CHECK (...);
```

## Custom DDL types — MigratableCodec

When a column should use a PostgreSQL ENUM or DOMAIN type instead of a standard
SQL type, implement `MigratableCodec` on the codec. The migration generator
collects all `createDdl` statements from the current schema's codecs and emits
them **before** any `CREATE TABLE` that references them.

```kotlin
enum class Priority { LOW, MEDIUM, HIGH }

object PriorityCodec : MigratableCodec<Priority> {
    override val sqlType     = String::class.java
    override val ddlTypeName = "priority_level"
    override val createDdl   =
        "CREATE TYPE priority_level AS ENUM ('LOW', 'MEDIUM', 'HIGH');"

    override fun encode(value: Priority?): Any? = value?.name
    override fun decode(raw: Any?): Priority? =
        (raw as? String)?.let { Priority.valueOf(it) }
}
```

Generated migration plan (first run with `TasksTable` using `PriorityCodec`):

```sql
-- step 1: custom type
CREATE TYPE priority_level AS ENUM ('LOW', 'MEDIUM', 'HIGH');

-- step 2: table
CREATE TABLE IF NOT EXISTS "tasks" (
    "id"       TEXT           NOT NULL,
    "priority" priority_level NOT NULL,
    PRIMARY KEY ("id")
);
```

### Custom type diffing

| Change | Action |
|--------|--------|
| New type (first time in snapshot) | Auto-emits `createDdl` |
| Unchanged type | No step emitted |
| `createDdl` changed | Manual step — review and migrate |
| Type removed from all columns | Manual step — drop manually if safe |

The snapshot stores custom types alongside tables so the diff is stable across
generations. See [MigratableCodec](02-schema.md#migratable-codec--custom-postgresql-ddl-types)
in the schema reference for full codec patterns including DOMAIN types and
externally managed types.

## SQL type mapping reference

`PostgresDialect` resolves a column's DDL type in this order:

1. If the column was declared with `sqlType =` (or via a typed builder like
   `varchar(length)`, `decimal(precision, scale)`) → use that string verbatim
2. If the codec implements `MigratableCodec` → use `codec.ddlTypeName`
3. Otherwise → use the built-in mapping below

| Codec | PostgreSQL column type |
|-------|------------------------|
| `StringCodec` / `ValueClassCodec(StringCodec, ...)` | `TEXT` |
| `IntCodec` | `INTEGER` |
| `LongCodec` | `BIGINT` |
| `ShortCodec` | `SMALLINT` |
| `FloatCodec` | `REAL` |
| `DoubleCodec` | `DOUBLE PRECISION` |
| `BooleanCodec` | `BOOLEAN` |
| `BigDecimalCodec` | `NUMERIC` |
| `InstantCodec` | `TIMESTAMPTZ` |
| `LocalDateTimeCodec` | `TIMESTAMP` |
| `LocalDateCodec` | `DATE` |
| `UuidCodec` | `UUID` |
| `ByteArrayCodec` | `BYTEA` |
| `TsidCodec` | `TEXT` |
| `UlidCodec` | `TEXT` |
| `ValueClassCodec(XCodec, ...)` | same as `XCodec` |
| `MigratableCodec` implementation | `codec.ddlTypeName` |

Typed column builders (`varchar`, `decimal`, `smallint`, …) bypass this
mapping and emit a sized SQL type instead. See the
[Typed column builders](02-schema.md#typed-column-builders-sized-sql-types)
section in the schema reference for the full list.

## Supporting other databases

Implement `MigrationDialect` for a different database engine:

```kotlin
import com.aggitech.aggo.dialect.MigrationDialect
import com.aggitech.aggo.dialect.requireValidIdentifier
import com.aggitech.aggo.schema.Codec

object MySqlDialect : MigrationDialect {
    override fun placeholder(oneBasedIndex: Int) = "?"
    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)
        return "`$name`"
    }
    override fun columnSqlType(codec: Codec<*>) = when (codec.sqlType) {
        String::class.java -> "TEXT"
        Int::class.javaObjectType -> "INT"
        Long::class.javaObjectType -> "BIGINT"
        Boolean::class.javaObjectType -> "TINYINT(1)"
        else -> throw UnsupportedOperationException("No MySQL type for ${codec.sqlType.name}")
    }
}
```

## Aggo vs Hibernate schema updates

| Hibernate | Aggo |
|-----------|------|
| `hbm2ddl.auto=update` can mutate schema at startup | Generate a migration plan and review SQL |
| Entity annotations drive DDL | `Table<E>` descriptors drive DDL |
| Runtime ORM metadata | Immutable `MigrationSchema` snapshot |
| Provider decides diff behavior | Aggo emits explicit `MigrationStep`s |
| Production auto-update is risky | Versioned files and `aggo_schema_versions` |

Aggo's migration flow is closer to Flyway or Liquibase discipline, but the SQL
is generated from the same schema descriptors used by the query DSL.

## Geracao e Execucao de Migracoes

Aggo consegue gerar migracoes a partir dos objetos `Table<E>`. O fluxo e:

1. declarar as tabelas atuais;
2. criar um `MigrationSchema`;
3. comparar com o snapshot anterior;
4. gerar um `MigrationPlan`;
5. revisar o SQL;
6. aplicar e registrar a versao em `aggo_schema_versions`.

```kotlin
val schema = migrationSchema(
    version = "2026.05.27.001",
    tables = listOf(UsersTable, OrdersTable),
    dialect = PostgresDialect,
)

val plan = migrationPlan(schema, PostgresDialect)
println(plan.sql())
```

### AggoMigrateTask

Para projetos Maven, declare uma task:

```kotlin
object Migrations : AggoMigrateTask() {
    override val tables = listOf(UsersTable, OrdersTable)
    override val dialect = PostgresDialect
    override val poolConfig = PostgresConfig(
        host = "localhost",
        database = "myapp",
        user = "app",
        password = System.getenv("DB_PASSWORD"),
    )
}

fun main(args: Array<String>) = Migrations.runFromArgs(args)
```

Comandos principais:

```bash
mvn compile exec:java -Dexec.args="generate add_users"
mvn compile exec:java -Dexec.args="status"
mvn compile exec:java -Dexec.args="dry-run"
mvn compile exec:java -Dexec.args="apply"
mvn compile exec:java -Dexec.args="reset"
```

`drop` e `reset` sao destrutivos e recusam rodar em `AGGO_ENV=prod` sem
`--force`.

### Tipos customizados

`MigratableCodec` permite gerar `CREATE TYPE` ou `CREATE DOMAIN` antes das
tabelas:

```kotlin
object StatusCodec : MigratableCodec<Status> {
    override val sqlType = String::class.java
    override val ddlTypeName = "status_type"
    override val createDdl =
        "CREATE TYPE status_type AS ENUM ('ACTIVE', 'INACTIVE')"

    override fun encode(value: Status?) = value?.name
    override fun decode(raw: Any?) = (raw as? String)?.let(Status::valueOf)
}
```

### Comparacao com Hibernate

Hibernate pode atualizar schema automaticamente no startup. Isso e conveniente
em desenvolvimento, mas arriscado em producao. Aggo favorece migracoes
versionadas e revisaveis: o SQL fica em arquivo, entra no controle de versao e
e aplicado de forma explicita.
