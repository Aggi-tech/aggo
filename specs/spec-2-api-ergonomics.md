# spec-2 — API ergonomics: eliminating `session.update(update { … })`

> Status: **implemented**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

## 1. Pain (verbatim user complaint)

```kotlin
aggo.tx { session ->
    session.update(update(Projects) {
        Projects.name        setTo project.name
        Projects.description setTo project.description
        Projects.status      setTo project.status.name
        Projects.startDate   setTo project.startDate
        Projects.dueDate     setTo project.dueDate
        Projects.updatedAt   setTo project.updatedAt
        where { Projects.id eq project.id }
    })
}
```

Two redundancies stacked:

1. `session -> session.update(...)` — the lambda receives `Session` only to invoke one of its methods.
2. `session.update(update(Projects) { … })` — build + execute are two calls when they could be one.

## 2. Target API (now live in 0.2.0)

```kotlin
aggo.tx {
    update(Projects) {                       // (1) tx body is Session.() -> T
        Projects.name        setTo project.name
        Projects.description setTo project.description
        Projects.status      setTo project.status.name
        Projects.startDate   setTo project.startDate
        Projects.dueDate     setTo project.dueDate
        Projects.updatedAt   setTo project.updatedAt
        where { Projects.id eq project.id }
    }                                        // (2) build+execute fused
}
```

One-shot version (single-statement → autocommit-equivalent BEGIN/COMMIT pair):

```kotlin
aggo.update(Projects) {
    Projects.active setTo false
    where { Projects.id eq project.id }
}
```

## 3. Concrete changes

### `runtime/Aggo.kt`

```kotlin
suspend fun <T> read(block: suspend Session.() -> T): T          // BREAKING: receiver
suspend fun <T> tx(block: suspend Session.() -> T): T            // BREAKING: receiver

// One-shot helpers — auto-wrap; writes get tx, reads get autocommit.
suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E>
suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E?
suspend fun <E> insert(table: Table<E>, entity: E): Long
suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long
suspend fun <E, V> insertReturning(table: Table<E>, pkColumn: Column<E, V>, block: InsertBuilder<E>.() -> Unit): V?
suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long
suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long
```

**Design call:** mutations on `Aggo.*` *auto-wrap* in `tx`. The semantic alternative (run on autocommit) was rejected because if a caller chains two `aggo.update(...)` calls they would silently lose atomicity guarantees the moment they reach for the shortcut. Reads on `Aggo.*` use `read` (autocommit) because reads are idempotent.

### `runtime/Session.kt`

Each existing method gained a builder-block sibling that builds and executes in one call:

```kotlin
suspend fun <E> fetchAll(query: Select<E>): List<E>
suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E>

suspend fun <E> fetchOne(query: Select<E>): E?
suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E?

fun <E> stream(query: Select<E>): Flow<E>
fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E>

suspend fun <E> insert(query: Insert<E>): Long
suspend fun <E> insert(table: Table<E>, entity: E): Long
suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long

suspend fun <E> update(query: Update<E>): Long
suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long

suspend fun <E> delete(query: Delete<E>): Long
suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long

suspend fun <E, V> insertReturning(query: Insert<E>, pkColumn: Column<E, V>): V?
suspend fun <E, V> insertReturning(table: Table<E>, pkColumn: Column<E, V>, block: InsertBuilder<E>.() -> Unit): V?
```

Why both signatures (Query value vs. builder block)?

- **Query value** keeps the "queries are data" promise — you can persist them, audit them, or share rendered SQL.
- **Builder block** is for one-off call sites where the query value is throwaway.

They share the same renderer path; there is no semantic difference.

## 4. What broke

One change shape: `Aggo.tx` and `Aggo.read` go from `suspend (Session) -> T` to `suspend Session.() -> T`.

### Migration script

Run inside each consuming microservice:

```bash
# Drop `session ->` and the session. qualifier inside aggo.tx / aggo.read blocks.
rg -l '\baggo\.(tx|read)\b' --type kotlin \
  | xargs sed -i -E 's/aggo\.(tx|read) \{ session ->/aggo.\1 {/g'

rg -l 'session\.(fetch|insert|update|delete|stream)' --type kotlin \
  | xargs sed -i -E 's/\bsession\.(fetch|insert|update|delete|stream)/\1/g'
```

`session.executeRaw` and `session.rawConnection` are intentionally left for human review — they need a new `@OptIn(AggoUnsafe::class)` (see [spec-1 V-3](spec-1-security-hardening.md#v-3-high-executeraw--rawconnection-bypass-every-safety-net--fixed)).

## 5. Files changed

| File | Change |
|------|--------|
| `runtime/Aggo.kt` | `tx`/`read` receiver-style; new one-shot helpers. |
| `runtime/Session.kt` | New builder-block overloads on every CRUD method. |

The DSL builders (`SelectBuilder`, `InsertBuilder`, `UpdateBuilder`, `DeleteBuilder`) are untouched — `Session.update(table) { … }` simply delegates to `com.aggitech.aggo.dsl.update(table, block)` and forwards the resulting `Update<E>` to the existing render path.

## 6. Test

`IntegrationTest` was fully rewritten to use the receiver form, with one new case (`one-shot update and tx { update } produce the same row state`) verifying the shortcut and the explicit form converge.
