# spec-6 — Aggo-owned migrations

> Status: **implemented**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

## 1. Goals

1. Decouple migration generation from Liquibase.
2. Keep generated migrations independent of Aggo runtime descriptors after the
   schema snapshot is materialized.
3. Let Aggo execute generated SQL itself.
4. Record schema versions and preserve visibility into changes from the previous
   schema to the new schema.
5. Preserve prior security decisions from spec-1: no general-purpose raw SQL
   shortcut and no dependency additions.

## 2. Non-goals

- No Liquibase/Flyway/dbmate adapter.
- No automatic destructive migrations.
- No data backfill DSL.
- No lock-management framework.
- No attempt to infer generated-column defaults.

## 3. API

### `migration/MigrationGenerator.kt`

```kotlin
data class MigrationSchema(val version: String, val tables: List<MigrationTable>)
data class MigrationTable(...)
data class MigrationColumn(...)
data class MigrationCheck(...)
data class MigrationStep(...)
data class MigrationPlan(...)

fun Table<*>.toMigrationTable(dialect: MigrationDialect): MigrationTable
fun migrationSchema(version: String, tables: Iterable<Table<*>>, dialect: MigrationDialect): MigrationSchema
fun migrationPlan(current: MigrationSchema, dialect: MigrationDialect, previous: MigrationSchema? = null): MigrationPlan
```

`MigrationSchema` is the independence boundary. It contains strings and immutable
lists only; after it is produced, rendering and execution do not need `Table`,
`Column`, or `Codec` references.

`version` is validated with `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` so it can be
safely recorded by Aggo without becoming a SQL-literal injection source.

### `runtime/Session.kt` and `runtime/Aggo.kt`

```kotlin
suspend fun Session.applyMigration(plan: MigrationPlan): MigrationResult
suspend fun Aggo.applyMigration(plan: MigrationPlan): MigrationResult
```

`Aggo.applyMigration` wraps execution in `tx`, so schema statements and version
recording commit or roll back together.

## 4. Version Table

Aggo creates the table on first migration execution:

```sql
CREATE TABLE IF NOT EXISTS "aggo_schema_versions" (
    "version" TEXT PRIMARY KEY,
    "previous_version" TEXT,
    "description" TEXT NOT NULL,
    "applied_at" TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Each successful `MigrationPlan` inserts one row:

- `version`: `plan.toVersion`
- `previous_version`: `plan.fromVersion`
- `description`: semicolon-separated `MigrationStep.change` values
- `applied_at`: database timestamp

## 5. Diff Behavior

With no previous schema, Aggo emits `CREATE TABLE` for every table.

With a previous schema, Aggo emits SQL for safe additive changes:

- new table
- new nullable column
- not-null-to-nullable transition
- new CHECK constraint

Aggo marks ambiguous or destructive changes as manual before any SQL can run:

- dropped table
- dropped column
- new `NOT NULL` column
- nullable-to-not-null transition
- column type change
- changed/dropped CHECK constraint
- primary-key rewrite

This gives consumers a precise review list without pretending data migration,
backfill, or locking rules can be inferred from descriptors alone.

## 6. Security Notes

- Identifiers still pass through `MigrationDialect.quoteIdentifier`, which uses
  the spec-1 V-2 identifier validation path.
- `applyMigration` is not a replacement for `executeRaw`; it only accepts a
  `MigrationPlan` and refuses `requiresManualMigration` steps.
- No new dependencies are introduced, preserving spec-4.
- Generated SQL is deployment-time DDL, not a runtime user-input surface.

## 7. Files Changed

| File | Change |
|------|--------|
| `migration/MigrationGenerator.kt` | Snapshot types, versioned plans, schema diffing, dialect-aware CHECK SQL. |
| `runtime/Session.kt` | `applyMigration(plan)` executor and `MigrationResult`. |
| `runtime/Aggo.kt` | One-shot transactional `applyMigration(plan)`. |
| `docs/06-migrations.md` | Liquibase workflow replaced with Aggo-owned generation/execution. |
| `src/test/.../MigrationGeneratorTest.kt` | Snapshot and diff coverage. |
| `src/test/.../IntegrationTest.kt` | Postgres smoke test for applying generated plans and recording versions. |
