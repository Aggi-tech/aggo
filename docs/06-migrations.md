# Migration Generation and Execution

Aggo can generate and apply database migrations from your `Table` descriptors.
The generated plan is detached from the runtime: Aggo reads `Table`/`Codec`
metadata once, materializes an immutable `MigrationSchema`, and then renders SQL
from that snapshot.

Liquibase is no longer part of the workflow. You may still export `plan.sql()`
to any external migration tool, but Aggo can execute the same plan itself and
record the applied version in `aggo_schema_versions`.

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

## SQL type mapping reference

| Codec | PostgreSQL column type |
|-------|------------------------|
| `StringCodec` / `ValueClassCodec(StringCodec, ...)` | `TEXT` |
| `IntCodec` | `INTEGER` |
| `LongCodec` | `BIGINT` |
| `ShortCodec` | `SMALLINT` |
| `DoubleCodec` | `DOUBLE PRECISION` |
| `BooleanCodec` | `BOOLEAN` |
| `BigDecimalCodec` | `NUMERIC` |
| `InstantCodec` | `TIMESTAMPTZ` |
| `LocalDateTimeCodec` | `TIMESTAMP` |
| `LocalDateCodec` | `DATE` |
| `UuidCodec` | `UUID` |
| `TsidCodec` | `TEXT` |
| `UlidCodec` | `TEXT` |
| `ValueClassCodec(XCodec, ...)` | same as `XCodec` |

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
