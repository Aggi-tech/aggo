---
name: aggo-migration
description: Guide for Aggo's migration workflow — migrationSchema, migrationPlan, applyMigration, file-based migrations, MigrationCli, handling requiresManualMigration steps, and the aggo_schema_versions table. Use when generating, applying, or debugging migrations.
---

# Aggo Migration Skill

You are working with Aggo's migration layer — schema DDL generation and versioned application.

Import path: `import com.aggitech.aggo.migration.*`

## Core flow

```
Table descriptors
    ↓  migrationSchema(version, tables, dialect)
MigrationSchema (immutable snapshot)
    ↓  migrationPlan(current, dialect, previous?)
MigrationPlan (list of MigrationSteps with SQL)
    ↓  aggo.applyMigration(plan)   OR   session.applyMigration(plan)
PostgreSQL database
```

## Step 1 — Create a MigrationSchema

```kotlin
val schema = migrationSchema(
    version = "2026.05.27.001",   // must match ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$
    tables  = listOf(UsersTable, OrdersTable, ProductsTable),
    dialect = PostgresDialect,
)
```

`migrationSchema` also scans for `MigratableCodec` instances and emits `CREATE TYPE` steps automatically.

## Step 2 — Generate a MigrationPlan

### First migration (no previous schema)

```kotlin
val plan = migrationPlan(schema, PostgresDialect)
// → CREATE TABLE for every table
// → ALTER TABLE ADD CONSTRAINT for FKs (after all CREATEs)
// → CREATE INDEX for declared indexes
```

### Incremental migration (diff against previous snapshot)

```kotlin
val previous: MigrationSchema = /* load from migrationSnapshot file */
val plan = migrationPlan(current = schema, dialect = PostgresDialect, previous = previous)
```

The diff engine produces:
- `ADD COLUMN` for new nullable columns (automatic SQL)
- `requiresManualMigration = true` for: new NOT NULL columns, type changes, column drops, PK rewrites, check/FK/unique changes that aren't purely additive

### IF NOT EXISTS (idempotent)

```kotlin
val plan = migrationPlan(schema, PostgresDialect, ifNotExists = true)
```

## Step 3 — Inspect the plan before applying

```kotlin
plan.steps.forEach { step ->
    println("${step.change}  manual=${step.requiresManualMigration}")
    step.sql?.let { println(it) }
}
```

Steps with `requiresManualMigration = true` have no SQL. `applyMigration` will throw if any such step exists — you must resolve them manually before applying.

## Step 4 — Apply

```kotlin
// Atomic: apply + version record in one transaction
aggo.applyMigration(plan)

// Or inside an existing tx:
aggo.tx { session ->
    session.applyMigration(plan)
}
```

`applyMigration` auto-creates the `aggo_schema_versions` table on first run and skips the plan if its version is already recorded (idempotent).

## File-based migrations (MigrationCli / AggoMigrate)

Generate versioned migration files via the CLI:

```
./gradlew aggoMigrate --tables=com.example.UsersTable,com.example.OrdersTable
```

Or use `MigrationCli` programmatically. Generated files are stored in `src/main/resources/db/migrations/`.

Apply all pending file-based migrations at startup:

```kotlin
val entries: List<MigrationFileEntry> = MigrationFileIO.load("db/migrations")
aggo.applyMigrations(entries)
// or inside a tx:
aggo.tx { session -> session.applyMigrations(entries) }
```

`applyMigrations` verifies the SHA-256 checksum of each entry before executing, then inserts a row into `aggo_schema_versions`.

## Manual DDL helpers (for scripts or one-off operations)

```kotlin
// CREATE TABLE DDL string
UsersTable.createTableSql(PostgresDialect)

// ALTER TABLE … ADD CONSTRAINT … CHECK …
UsersTable.addCheckConstraintsSql(PostgresDialect).forEach { println(it) }

// ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY …
OrdersTable.addForeignKeyConstraintsSql(PostgresDialect).forEach { println(it) }

// ALTER TABLE … ADD CONSTRAINT … UNIQUE …
UsersTable.addUniqueConstraintsSql(PostgresDialect).forEach { println(it) }

// CREATE INDEX …
UsersTable.addIndexesSql(PostgresDialect).forEach { println(it) }

// DROP TABLE
UsersTable.dropTableSql(PostgresDialect, ifExists = true)
```

## aggo_schema_versions table

Aggo manages its own version registry. Schema:

```sql
CREATE TABLE "aggo_schema_versions" (
    "version"          TEXT PRIMARY KEY,
    "previous_version" TEXT,
    "description"      TEXT NOT NULL,
    "applied_at"       TIMESTAMPTZ NOT NULL DEFAULT now(),
    "checksum"         TEXT
);
```

Never write to this table directly. It is maintained by `applyMigration` / `applyMigrations`.

## Handling requiresManualMigration steps

These changes cannot be automated safely:
- New `NOT NULL` column without a default → backfill data first, then add constraint
- Column type change → write a custom `ALTER COLUMN … TYPE … USING` expression
- Column drop → verify no code depends on it, then `ALTER TABLE DROP COLUMN`
- Primary key rewrite → recreate the table
- Modified CHECK / UNIQUE / FK → drop old constraint, add new

Workflow:
1. Inspect `plan.steps.filter { it.requiresManualMigration }`.
2. Write manual SQL for each step.
3. Either patch the generated migration file or apply the SQL manually.
4. Update the `MigrationSchema` snapshot to reflect the final state.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Calling `migrationPlan` without a `previous` when tables already exist | Load the snapshot with `MigrationFileIO` or `MigrationSnapshotIO` |
| Applying a plan with manual steps | Resolve all manual steps first |
| Generating DDL in `Table` itself | Use the extension functions from `migration/` |
| Calling `session.applyMigration` outside `aggo.tx` | Wrap in `aggo.tx {}` so DDL and version record commit atomically |
