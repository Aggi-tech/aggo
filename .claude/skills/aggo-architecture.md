---
name: aggo-architecture
description: Enforces Aggo's context-separation rules and zero-reflection law. Use when reviewing new code, adding capabilities, or diagnosing a boundary violation between schema/query/dsl/render/migration/runtime packages.
---

# Aggo Architecture Skill

You are reviewing or writing code for Aggo — a reflection-free, type-safe R2DBC DSL for PostgreSQL targeting GraalVM native.

## The one law that overrides everything else

**Zero reflection at runtime.** No `KClass<*>`, `::class.java`, `getDeclaredFields`, annotation scanning, or any runtime introspection in `schema/`, `query/`, `dsl/`, or `render/`. All mappings are explicit lambdas captured at schema definition time.

## Package boundaries — strict, one-directional

Data flows: **schema → dsl → query AST → render → runtime**. Nothing flows backwards.

| Package | Allowed | Forbidden |
|---------|---------|-----------|
| `schema/` | Declare tables, columns, FK relationships, checks | Generate SQL, bind parameters, open connections |
| `query/` | Represent immutable query structures and expressions | Execute queries, touch connections |
| `dsl/` | Compose query AST via Kotlin-idiomatic builders | Access connections, generate DDL |
| `render/` | Produce SQL strings + bound parameters from AST | Execute SQL, read results |
| `migration/` | Generate DDL, diff schemas, snapshot state | Execute DML, hold connections |
| `runtime/` | Execute queries via R2DBC, manage transactions | Generate DDL, define schema |

## What to check when reviewing code

1. Does anything in `schema/` import from `runtime/`, `render/`, or `migration/`? **Violation.**
2. Does anything in `render/` call `Session` or open a connection? **Violation.**
3. Does `Table` have DDL methods? **Violation** — DDL belongs in `migration/`.
4. Is `codec.encode()` or `codec.decode()` called outside `Binder` / `Column.read()`? **Violation.**
5. Is a placeholder string like `$1` built manually instead of via `ctx.bind()`? **Violation.**
6. Is mutable state added to a `Table` object after construction? **Violation** — Tables are singletons.
7. Does rollback in `Aggo.tx` re-throw the rollback error instead of using `addSuppressed()`? **Violation.**

## Table contract

- `Table<E>` is a pure metadata descriptor — columns, FKs, codecs, and `fromRow`.
- `column()` and `references()` builders are `protected` — only callable during object construction.
- `mutableColumns` is private — never access it from outside the `Table` class.

## Session contract

- `Session` holds one `Connection` for its entire lifetime.
- `Session` executes DML only (`fetchAll`, `fetchOne`, `stream`, `insert`, `update`, `delete`, `fetchAggregate`).
- `Session` cannot generate DDL. Use `session.applyMigration(plan)` with a pre-built `MigrationPlan`.
- Never cache a `Session` or `Connection` across coroutine suspension points outside the lambda.

## Transaction model

```
Aggo.read { session -> ... }   // autocommit, read-only
Aggo.tx   { session -> ... }   // BEGIN → block → COMMIT, ROLLBACK on throw
```

Both scopes: acquire connection → wrap in Session → release in `finally` (always, even on rollback error).

## Common forbidden patterns and their correct replacements

| Forbidden | Correct replacement |
|-----------|---------------------|
| `statement.bind(index, value)` directly | Always use `Binder.bind(statement, rendered.params)` |
| `SELECT *` in renderers | Enumerate `table.columns` explicitly |
| `session.executeRaw()` in production code | Use DSL builders — `insert {}`, `update {}`, etc. |
| Adding columns outside `column()` builder | Only `column()` writes `mutableColumns` |
| `dsl.execute()` / `plainSQL()` (jOOQ style) | Use Aggo DSL builders |
| `KClass<*>` anywhere in schema/query/dsl/render | No reflection — explicit lambdas only |
