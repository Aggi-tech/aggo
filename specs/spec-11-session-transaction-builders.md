# spec-11 - Session and Transaction Builders

> Status: **proposed**.
> Parent: [spec-0-overview.md](spec-0-overview.md).
> Tracking issue: [#7](https://github.com/Aggi-tech/aggo/issues/7).
> Target version: **0.6.0-SNAPSHOT**.

## 1. Problem

The current public entry point exposes too many execution shortcuts directly on
`Aggo`:

```kotlin
aggo.fetchAll(Users)
aggo.fetchOne(Users) { where { Users.email eq email } }
aggo.insert(Users, user)
aggo.update(Users) { Users.active setTo false }
aggo.delete(Users) { where { Users.id eq id } }
```

This hides the execution boundary. A call site cannot see whether a statement
is autocommit read work, transaction work, or a one-shot transaction unless it
knows the implementation. The same surface also makes write helpers visible
next to read helpers on `Aggo`, even though writes should be intentionally
scoped to a transaction.

The current `Aggo.read { ... }` / `Aggo.tx { ... }` receiver API improved
ergonomics, but it still exposes the same broad `Session` receiver in both
scopes. It also relies on an implicit receiver, which hides the execution object
inside larger service methods. That means `insert`, `update`, and `delete` are
available in a read scope at compile time, even though the intended model is:

- a session scope is read-only/autocommit work;
- a transaction scope is read/write atomic work;
- `Aggo` owns lifecycle and routing only, not CRUD methods.

## 2. Goals

1. Move the public execution surface back under explicit `aggo.session` and
   `aggo.tx` objects.
2. Make `aggo.tx` return a `TransactionBuilder`.
3. Make `aggo.session` return a `SessionBuilder`.
4. Remove `fetch*`, `insert*`, `update*`, and `delete*` visibility from the
   `Aggo` object.
5. Prevent `SessionBuilder` from accepting `Insert`, `Update`, and `Delete`
   operations at compile time.
6. Use explicit lambda parameters (`session ->`, `tx ->`) instead of implicit
   receivers so transaction/session capabilities are visible at the call site.
7. Deprecate the old scope vocabulary (`read`, `readQuery`, `transaction`) and
   reserve/deprecate any `write` alias in favor of the single `tx` builder.
8. Centralize execution rules so lifecycle, transaction boundaries,
   error-mapping, unsafe access, and capability checks are defined once.
9. Keep transaction rollback semantics unchanged: rollback errors are added as
   suppressed exceptions on the original failure.
10. Preserve the zero-reflection law and the architecture flow:
   `schema -> dsl -> query -> render -> runtime`.
11. Keep query ASTs as data. Builders execute `Select`, `Insert`, `Update`, and
   `Delete` values produced by the existing DSL; they do not replace the DSL.

## 3. Non-goals

- No runtime reflection, annotation scanning, or entity introspection.
- No changes to `Table`, `Column`, `Codec`, or row mapping contracts.
- No changes to renderer SQL output.
- No new SQL feature such as UPSERT, batch insert, window functions, or
  subqueries.
- No connection or transaction reuse outside the builder lambda.
- No public exposure of R2DBC `Connection` from safe builder APIs.
- No implicit write methods on `SessionBuilder`.
- No second write vocabulary. `write { ... }` must not be added as a synonym
  for `tx { ... }`.

## 4. Target usage

### 4.1 Read-only session

`aggo.session` is a property whose value is a `SessionBuilder`.

```kotlin
val users = aggo.session { session ->
    session.fetchAll(Users) {
        where { Users.active eq true }
        orderBy { Users.createdAt.desc() }
        limit(50)
    }
}
```

Direct builder method form is also supported for one operation:

```kotlin
val users = aggo.session.fetchAll(
    select(Users) { where { Users.active eq true } }
)
```

The following must not compile:

```kotlin
aggo.session { session ->
    session.insert(Users, user)
}

aggo.session.insert(Users, user)
aggo.session.update(Users) { Users.active setTo false }
aggo.session.delete(Users)
```

### 4.2 Transaction

`aggo.tx` is a property whose value is a `TransactionBuilder`.

```kotlin
val userId = aggo.tx { tx ->
    val id = tx.insertReturning(Users, Users.id) {
        Users.email setTo email
        Users.name setTo name
    }

    tx.insert(AuditEvents) {
        AuditEvents.userId setTo id
        AuditEvents.event setTo "user.created"
    }

    id
}
```

Direct builder method form is also supported:

```kotlin
val rows = aggo.tx.update(Users) {
    Users.active setTo false
    where { Users.id eq userId }
}
```

Transaction scopes can read as well as write:

```kotlin
aggo.tx { tx ->
    val existing = tx.fetchOne(Users) { where { Users.email eq email } }
    require(existing == null) { "email already exists" }
    tx.insert(Users, user)
}
```

### 4.3 Typed-result variants

Typed-result execution moves under the same builders:

```kotlin
val queryResult: Query<List<User>, AggoError> =
    aggo.session.query(constraintErrorMap(Users)) { session ->
        session.fetchAll(Users) { where { Users.active eq true } }
    }

val txResult: Transaction<UserId?, AggoError> =
    aggo.tx.result(constraintErrorMap(Users)) { tx ->
        tx.insertReturning(Users, Users.id) {
            Users.email setTo user.email
            Users.name setTo user.name
        }
    }
```

`Transaction<T, AggoError>` remains the existing alias/shape used today. The
rename is only the entry point: `aggo.transaction(...)` becomes
`aggo.tx.result(...)`.

## 5. Public API design

### 5.1 `Aggo`

```kotlin
class Aggo(private val pool: AggoPool) : AutoCloseable {
    val session: SessionBuilder
    val tx: TransactionBuilder

    override fun close()
}
```

`Aggo` becomes a lifecycle object: pool ownership, close behavior, and access to
the two execution builders. It no longer exposes CRUD helpers.

### 5.2 `SessionBuilder`

```kotlin
class SessionBuilder internal constructor(
    private val pool: AggoPool,
) {
    suspend operator fun <T> invoke(block: suspend (SessionScope) -> T): T

    suspend fun <T> query(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend (SessionScope) -> T,
    ): Query<T, AggoError>

    suspend fun <E> fetchAll(query: Select<E>): List<E>
    suspend fun <E> fetchAll(query: Select<E>, errorMap: ConstraintErrorMap): Query<List<E>, AggoError>
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E>
    suspend fun <E> fetchOne(query: Select<E>): E?
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E?
    fun <E> stream(query: Select<E>): Flow<E>
    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E>

    suspend fun <E> paginate(query: Select<E>, page: Int, size: Int): Triple<List<E>, Long, Int>
    suspend fun <E> paginate(
        query: Select<E>,
        page: Int,
        size: Int,
        errorMap: ConstraintErrorMap,
    ): Query<Triple<List<E>, Long, Int>, AggoError>

    suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>>
    fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>>

    suspend fun <E> fetchProjection(query: ProjectionSelect<E>): List<ProjectedRow>
    suspend fun <E> fetchOneProjection(query: ProjectionSelect<E>): ProjectedRow?
    fun <E> streamProjection(query: ProjectionSelect<E>): Flow<ProjectedRow>

    suspend fun <E> fetchAggregate(query: AggregateSelect<E>): List<AggRow>
    fun <E> streamAggregate(query: AggregateSelect<E>): Flow<AggRow>
}
```

`SessionBuilder` has no overload that accepts:

- `Insert<E>`;
- `Update<E>`;
- `Delete<E>`;
- `MigrationPlan`;
- migration file entries;
- raw SQL;
- raw R2DBC `Connection`.

### 5.3 `TransactionBuilder`

```kotlin
class TransactionBuilder internal constructor(
    private val pool: AggoPool,
) {
    suspend operator fun <T> invoke(block: suspend (TransactionScope) -> T): T

    suspend fun <T> result(
        errorMap: ConstraintErrorMap = ConstraintErrorMap.empty,
        block: suspend (TransactionScope) -> T,
    ): Transaction<T, AggoError>

    // Read operations, same as SessionBuilder.
    suspend fun <E> fetchAll(query: Select<E>): List<E>
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E>
    suspend fun <E> fetchOne(query: Select<E>): E?
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E?
    fun <E> stream(query: Select<E>): Flow<E>
    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E>
    suspend fun <E> paginate(query: Select<E>, page: Int, size: Int): Triple<List<E>, Long, Int>
    suspend fun <L, R> fetchAllJoined(query: JoinSelect<L, R>): List<JoinedRow<L, R>>
    fun <L, R> streamJoined(query: JoinSelect<L, R>): Flow<JoinedRow<L, R>>
    suspend fun <E> fetchProjection(query: ProjectionSelect<E>): List<ProjectedRow>
    suspend fun <E> fetchOneProjection(query: ProjectionSelect<E>): ProjectedRow?
    fun <E> streamProjection(query: ProjectionSelect<E>): Flow<ProjectedRow>
    suspend fun <E> fetchAggregate(query: AggregateSelect<E>): List<AggRow>
    fun <E> streamAggregate(query: AggregateSelect<E>): Flow<AggRow>

    // Write operations.
    suspend fun <E> insert(query: Insert<E>): Long
    suspend fun <E> insert(table: Table<E>, entity: E): Long
    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long
    suspend fun <E, V> insertReturning(query: Insert<E>, pkColumn: Column<E, V>): V?
    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V?
    suspend fun <E> update(query: Update<E>): Long
    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long
    suspend fun <E> delete(query: Delete<E>): Long
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long

    // Transaction-only schema execution.
    suspend fun applyMigration(plan: MigrationPlan): MigrationResult
    suspend fun applyMigrations(entries: List<MigrationFileEntry>): List<MigrationResult>
    suspend fun applyMigrations(migrationsDir: Path): List<MigrationResult>
}
```

`TransactionBuilder` is the only public safe place where `Insert`, `Update`,
`Delete`, and migrations are accepted.

### 5.4 Scope interfaces

The lambda parameter must be interface-based so read scopes and transaction
scopes differ at compile time while remaining explicit at the call site.

```kotlin
interface SessionScope {
    suspend fun <E> fetchAll(query: Select<E>): List<E>
    suspend fun <E> fetchAll(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): List<E>
    suspend fun <E> fetchOne(query: Select<E>): E?
    suspend fun <E> fetchOne(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): E?
    fun <E> stream(query: Select<E>): Flow<E>
    fun <E> stream(table: Table<E>, block: SelectBuilder<E>.() -> Unit = {}): Flow<E>
    // plus paginate, joins, projections, aggregates
}

interface TransactionScope : SessionScope {
    suspend fun <E> insert(query: Insert<E>): Long
    suspend fun <E> insert(table: Table<E>, entity: E): Long
    suspend fun <E> insert(table: Table<E>, block: InsertBuilder<E>.() -> Unit): Long
    suspend fun <E, V> insertReturning(query: Insert<E>, pkColumn: Column<E, V>): V?
    suspend fun <E, V> insertReturning(
        table: Table<E>,
        pkColumn: Column<E, V>,
        block: InsertBuilder<E>.() -> Unit,
    ): V?
    suspend fun <E> update(query: Update<E>): Long
    suspend fun <E> update(table: Table<E>, block: UpdateBuilder<E>.() -> Unit): Long
    suspend fun <E> delete(query: Delete<E>): Long
    suspend fun <E> delete(table: Table<E>, block: DeleteBuilder<E>.() -> Unit = {}): Long
    suspend fun applyMigration(plan: MigrationPlan): MigrationResult
    suspend fun applyMigrations(entries: List<MigrationFileEntry>): List<MigrationResult>
}
```

The implementation can keep an internal executor that owns `Connection` and
`SqlDialect`, but that executor must not be passed as the same public type for
both read and transaction blocks.

## 6. Internal implementation plan

### 6.0 Central execution rules

The business rules for execution must live in one internal component instead of
being reimplemented by `Aggo`, `SessionBuilder`, `TransactionBuilder`,
`MultiSchemaAggo`, and `MultiDatabaseAggo`.

Introduce an internal coordinator responsible for:

1. acquiring and releasing connections;
2. enforcing `NonCancellable` release;
3. beginning, committing, and rolling back transactions;
4. preserving rollback errors via `addSuppressed`;
5. mapping thrown database errors into `Query` / `Transaction` values;
6. creating the correct explicit scope object (`SessionScope` or
   `TransactionScope`);
7. keeping unsafe raw SQL/connection access out of safe read scopes.

Suggested shape:

```kotlin
internal class ExecutionCoordinator(
    private val acquire: suspend () -> Connection,
    private val release: suspend (Connection) -> Unit,
    private val dialect: suspend () -> SqlDialect,
) {
    suspend fun <T> session(block: suspend (SessionScope) -> T): T
    suspend fun <T> transaction(block: suspend (TransactionScope) -> T): T
    suspend fun <T> sessionResult(
        errorMap: ConstraintErrorMap,
        block: suspend (SessionScope) -> T,
    ): Query<T, AggoError>
    suspend fun <T> transactionResult(
        errorMap: ConstraintErrorMap,
        block: suspend (TransactionScope) -> T,
    ): Transaction<T, AggoError>
}
```

`Aggo`, `MultiSchemaAggo`, and `MultiDatabaseAggo` configure this coordinator
instead of copying transaction/read lifecycle code. The only per-wrapper
variation should be how the dialect and pool are resolved.

Rules that must not be duplicated outside the coordinator:

- connection release behavior;
- rollback/commit behavior;
- typed result capture;
- construction of internal runtime scopes;
- unsafe access gates.

### 6.1 Runtime executor split

Introduce an internal execution class:

```kotlin
internal class RuntimeSession(
    private val connection: Connection,
    private val dialect: SqlDialect,
) : TransactionScope
```

`RuntimeSession` may start as a renamed version of the current `Session` class.
It keeps the existing renderer calls, binding behavior, logging, row mapping,
and migration execution helpers.

The public `Session` type should stop being the primary API surface. Either:

1. rename it to `RuntimeSession` in the same breaking release; or
2. keep `Session` as a deprecated typealias/wrapper temporarily if binary
   compatibility is required.

Because Aggo is still pre-1.0, option 1 is preferred if it keeps the codebase
cleaner.

### 6.2 Connection lifecycle

`SessionBuilder.invoke` delegates to `ExecutionCoordinator.session`:

1. acquire connection from `AggoPool`;
2. create `RuntimeSession(connection, pool.dialect)`;
3. run the block with a `SessionScope` argument;
4. release the connection in a `NonCancellable` `finally` block.

`TransactionBuilder.invoke` delegates to `ExecutionCoordinator.transaction`:

1. acquire connection from `AggoPool`;
2. begin transaction;
3. create `RuntimeSession(connection, pool.dialect)`;
4. run the block with a `TransactionScope` argument;
5. commit on success;
6. rollback on failure;
7. attach rollback failure with `original.addSuppressed(rollbackError)`;
8. release the connection in a `NonCancellable` `finally` block.

This must preserve the current transaction bug fix: every statement inside the
transaction uses the same R2DBC `Connection`.

### 6.3 Direct builder methods

Direct methods on `SessionBuilder` and `TransactionBuilder` are thin wrappers
around `invoke`:

```kotlin
suspend fun <E> SessionBuilder.fetchAll(query: Select<E>): List<E> =
    invoke { session -> session.fetchAll(query) }

suspend fun <E> TransactionBuilder.insert(query: Insert<E>): Long =
    invoke { tx -> tx.insert(query) }
```

They must not duplicate rendering or connection management.

To avoid broad copy/paste between `SessionBuilder` and `TransactionBuilder`,
shared read-only direct methods should be implemented once through a small
internal delegate:

```kotlin
internal interface ReadExecution {
    suspend fun <T> read(block: suspend (SessionScope) -> T): T
}
```

`SessionBuilder` and `TransactionBuilder` can both implement or hold this
delegate, while `TransactionBuilder` adds write-only methods. The important
constraint is that shared code must not make write operations visible on
`SessionBuilder`.

### 6.4 DSL builder-block overload policy

The new block API should prefer explicit scope objects with table overloads for
ordinary CRUD:

```kotlin
aggo.tx { tx -> tx.insert(Users, user) }
aggo.tx { tx -> tx.update(Users) { Users.active setTo false } }
aggo.session { session -> session.fetchAll(Users) { where { Users.active eq true } } }
```

Query-value overloads still remain available for composed queries:

```kotlin
val activeUsers = select(Users) { where { Users.active eq true } }
aggo.session { session -> session.fetchAll(activeUsers) }
```

The overload split is:

- `SessionScope`: `fetchAll(table)`, `fetchOne(table)`, `stream(table)`,
  query-value reads, projection, aggregate, and joined read helpers;
- `TransactionScope`: all `SessionScope` helpers plus `insert(table, entity)`,
  `insert(table)`, `update(table)`, `delete(table)`, `insertReturning`, and
  write query-value overloads.

Direct table + block overloads must not return to `Aggo`.

## 7. Multitenancy impact

`MultiSchemaAggo` and `MultiDatabaseAggo` must expose the same public shape:

```kotlin
multiSchemaAggo.session { session -> session.fetchAll(Users) }
multiSchemaAggo.tx { tx -> tx.insert(Users, user) }

multiDatabaseAggo.session { session -> session.fetchOne(Users) { where { Users.id eq id } } }
multiDatabaseAggo.tx { tx -> tx.update(Users) { Users.active setTo false } }
```

Rules:

1. `MultiSchemaAggo.session` resolves the tenant before acquiring the
   connection and uses the tenant-qualified dialect.
2. `MultiSchemaAggo.tx` uses the same tenant-qualified dialect for the full
   transaction.
3. `MultiDatabaseAggo.session` and `MultiDatabaseAggo.tx` delegate to the
   tenant's `Aggo` instance and must not expose direct CRUD methods on
   `MultiDatabaseAggo`.
4. Existing direct multitenancy methods (`fetchAll`, `insert`, `update`,
   `delete`, etc.) follow the same deprecation/removal policy as `Aggo`.

## 8. Deprecation and removal plan

### 8.1 Breaking name replacement

`fun Aggo.tx(block: suspend Session.() -> T)` cannot coexist cleanly with
`val Aggo.tx: TransactionBuilder` as the preferred call shape, because
`aggo.tx { ... }` would resolve to the old function while it exists.

Decision: the implementation release is a source-breaking pre-1.0 API cleanup.
The old `tx(block)` function is removed when `val tx` is introduced. The
migration path is straightforward:

```kotlin
// before
aggo.tx {
    insert(Users, user)
}

// after
aggo.tx { tx ->
    tx.insert(Users, user)
}
```

If the implementation chooses a two-release transition, the temporary name must
be `aggo.transactionScope { ... }` or similar. That is not the preferred plan
because the requested final API is `aggo.tx`.

### 8.2 Methods to deprecate/remove from `Aggo`

The following methods are removed from `Aggo` in the final API and must be
listed in release notes:

| Current method | Replacement |
|---|---|
| `read(block)` | `session(block)` |
| `readQuery(errorMap, block)` | `session.query(errorMap, block)` |
| `write(block)` if present in a branch/consumer fork | `tx(block)` on `TransactionBuilder` property |
| `tx(block)` | `tx { tx -> ... }` on `TransactionBuilder` property |
| `transaction(errorMap, block)` | `tx.result(errorMap, block)` |
| `fetchAll(query)` | `session.fetchAll(query)` or `session { session -> session.fetchAll(query) }` |
| `fetchAll(query, errorMap)` | `session.query(errorMap) { session -> session.fetchAll(query) }` |
| `fetchAll(table, block)` | `session.fetchAll(table, block)` |
| `fetchAll(table, errorMap, block)` | `session.query(errorMap) { session -> session.fetchAll(table, block) }` |
| `paginate(query, page, size)` | `session.paginate(query, page, size)` |
| `paginate(query, page, size, errorMap)` | `session.query(errorMap) { session -> session.paginate(query, page, size) }` |
| `paginate(table, page, size, block)` | `session.paginate(select(table, block), page, size)` |
| `fetchOne(query)` | `session.fetchOne(query)` |
| `fetchOne(table, block)` | `session.fetchOne(table, block)` |
| `insert(table, entity)` | `tx.insert(table, entity)` or `tx { tx -> tx.insert(table, entity) }` |
| `insert(table, block)` | `tx.insert(table, block)` or `tx { tx -> tx.insert(table, block) }` |
| `insertReturning(table, pkColumn, block)` | `tx.insertReturning(table, pkColumn, block)` |
| `update(table, block)` | `tx.update(table, block)` |
| `delete(table, block)` | `tx.delete(table, block)` |
| `applyMigration(plan)` | `tx.applyMigration(plan)` |
| `applyMigrations(migrationsDir)` | `tx.applyMigrations(migrationsDir)` |

The same removal table applies to `MultiSchemaAggo` and `MultiDatabaseAggo`.

There is no `Aggo.write` method in the current mainline code. If such an alias
exists in an implementation branch or consuming project, it must be treated as
deprecated and must not be added to the new public API. The library should have
one write scope name only: `tx`.

### 8.3 Deprecated names policy

All deprecated names must point to a single replacement and should not preserve
old business rules locally. If a compatibility shim is kept for one release, it
must delegate immediately to the new builder:

```kotlin
@Deprecated(
    message = "Use aggo.session { session -> ... }",
    replaceWith = ReplaceWith("session(block)"),
    level = DeprecationLevel.WARNING,
)
suspend fun <T> read(block: suspend (SessionScope) -> T): T =
    session(block)
```

For `tx(block)`, a compatibility shim is not preferred because the final API
requires `val tx: TransactionBuilder`. If a transition release is needed, use a
temporary differently named function, then remove it before exposing `val tx`.

### 8.4 Methods to keep

These stay public:

- top-level DSL builders in `com.aggitech.aggo.dsl`: `select`, `insert`,
  `update`, `delete`, `aggregate`, `selectProjection`, joins;
- renderers in `render` according to their current visibility;
- schema/table/column APIs;
- `AggoPool` and pool config APIs;
- result types: `Query`, `Transaction`, `AggoError`, `ConstraintErrorMap`.

### 8.5 Unsafe APIs

`executeRaw` and `rawConnection` stay out of safe builders. If still needed,
they must be available only through a clearly named unsafe transaction path:

```kotlin
@OptIn(AggoUnsafe::class)
aggo.tx.unsafe {
    executeRaw("CREATE EXTENSION IF NOT EXISTS ...")
}
```

This keeps unsafe behavior opt-in, transaction-bound, and unavailable from
read-only `SessionBuilder`.

## 9. Documentation plan

### 9.1 README

Update the execution section to make the first public examples use:

```kotlin
aggo.session { session -> session.fetchAll(Payers) }
aggo.tx { tx -> tx.insert(Payers, payer) }
aggo.tx.result(errors) { tx -> tx.insert(Payers, payer) }
```

Remove examples using:

- `aggo.read { ... }`;
- `aggo.tx { insert(Payers, payer) }` with implicit receiver;
- `aggo.fetchAll(...)`;
- `aggo.insert(...)`;

### 9.2 `docs/01-getting-started.md`

Rewrite setup and first query examples around:

- `Aggo(...).session`;
- `Aggo(...).tx`;
- query values passed into builders.

The getting-started page must include one explicit read-only compile-time rule:
`SessionBuilder` accepts only read operations.

### 9.3 `docs/03-querying.md`

Update all query execution examples to:

```kotlin
aggo.session.fetchAll(Users) { ... }
aggo.session { session -> session.stream(Reports) { ... }.collect { ... } }
```

Document that reads inside `aggo.tx { ... }` are allowed when they are part of
the same atomic workflow.

### 9.4 `docs/04-writes.md`

Replace one-shot write helpers with transaction builder examples:

```kotlin
aggo.tx { tx -> tx.insert(Users, user) }
aggo.tx { tx -> tx.update(Users) { ... } }
aggo.tx { tx -> tx.delete(Users) { ... } }
```

Add a short "No writes in SessionBuilder" section with a non-compiling example.

### 9.5 `docs/05-joins.md`

Move joined query examples to `aggo.session` unless they intentionally need a
transaction.

### 9.6 `docs/06-migrations.md`

Move migration application examples to:

```kotlin
aggo.tx.applyMigration(plan)
aggo.tx.applyMigrations(Paths.get("src/main/resources/aggo/migrations"))
```

Make clear that generation remains in `migration/`, while application remains
runtime transaction work.

### 9.7 `docs/07-multitenancy.md`

Update `MultiSchemaAggo` and `MultiDatabaseAggo` examples to the same builder
shape and remove direct CRUD examples from tenant-aware `Aggo` decorators.

### 9.8 Dokka/KDoc

Regenerate Dokka after code changes and ensure the public entry point docs list
`Aggo.session` and `Aggo.tx` as the main surface.

## 10. Testing plan

### 10.1 Renderer tests

No SQL output should change. Add or update `RendererTest` only if a query value
construction path changes. The expected SQL for select, insert, update, and
delete must remain byte-for-byte identical.

### 10.2 Unit/API compile tests

Add focused tests in `ErgonomicsTest` or a new `BuilderApiTest`:

1. `aggo.session { session -> session.fetchAll(...) }` compiles.
2. `aggo.tx { tx -> tx.insert(...) }` compiles.
3. `aggo.tx.insert(...)` compiles for one-shot writes.
4. `aggo.session.fetchAll(...)` compiles for one-shot reads.
5. `SessionBuilder` exposes no write methods. Prefer `kotlin-compile-testing`
   only if already accepted as a dependency; otherwise keep this as a source
   compatibility check in documentation and use API surface assertions.
6. `Aggo` exposes no `read`, `readQuery`, `write`, `transaction`, direct CRUD,
   or direct migration methods after the final API change.
7. Direct read methods are implemented through the shared read delegate or
   coordinator path, not duplicate connection lifecycle code.

### 10.3 Integration tests

Add/update `IntegrationTest` coverage:

1. session read fetches rows in autocommit mode;
2. transaction insert/update/delete commits on success;
3. transaction rollback keeps the current behavior;
4. rollback failure is suppressed on the original failure;
5. direct transaction builder methods use one connection per transaction call;
6. migration application works through `aggo.tx.applyMigration(plan)`;
7. old `aggo.insert`, `aggo.update`, and `aggo.delete` call sites are removed
   from tests.

### 10.4 Multitenancy tests

If tenant tests already exist, update them to validate:

1. `MultiSchemaAggo.session` qualifies reads with the tenant schema;
2. `MultiSchemaAggo.tx` qualifies writes and migrations with the tenant schema;
3. `MultiDatabaseAggo.session` and `MultiDatabaseAggo.tx` delegate through the
   tenant pool cache without exposing direct CRUD methods.

## 11. Acceptance criteria

- [ ] `Aggo` exposes `session: SessionBuilder` and `tx: TransactionBuilder`.
- [ ] `Aggo` no longer exposes direct `fetch*`, `insert*`, `update*`, or
      `delete*` methods.
- [ ] `Aggo` no longer exposes `read`, `readQuery`, `write`, or `transaction`
      as public execution methods.
- [ ] `SessionBuilder` has no `insert`, `insertReturning`, `update`, `delete`,
      migration, raw SQL, or raw connection methods.
- [ ] `TransactionBuilder` exposes read operations, write operations, and
      migration application.
- [ ] Connection lifecycle and typed-result capture are centralized in one
      internal coordinator or equivalent delegate.
- [ ] Shared read direct methods are not copy/pasted between builders.
- [ ] Existing render SQL output is unchanged.
- [ ] Transaction commit/rollback behavior is unchanged.
- [ ] `MultiSchemaAggo` and `MultiDatabaseAggo` match the same builder API.
- [ ] README and docs pages are updated.
- [ ] Release notes list every removed/deprecated method and replacement.
- [ ] Any implementation deviation from this spec is added as a comment to the
      tracking issue before the task is closed.

## 12. Migration guide for consumers

### Reads

```kotlin
// before
val users = aggo.fetchAll(Users) {
    where { Users.active eq true }
}

// after
val users = aggo.session.fetchAll(
    Users,
) { where { Users.active eq true } }
```

```kotlin
// before
val users = aggo.read {
    fetchAll(Users) { where { Users.active eq true } }
}

// after
val users = aggo.session { session ->
    session.fetchAll(Users) { where { Users.active eq true } }
}
```

### Writes

```kotlin
// before
aggo.insert(Users, user)

// after
aggo.tx { tx ->
    tx.insert(Users, user)
}
```

```kotlin
// before
aggo.tx {
    update(Users) {
        Users.active setTo false
        where { Users.id eq id }
    }
}

// after
aggo.tx { tx ->
    tx.update(Users) {
        Users.active setTo false
        where { Users.id eq id }
    }
}
```

### Typed results

```kotlin
// before
val result = aggo.transaction(errors) {
    insert(Users, user)
}

// after
val result = aggo.tx.result(errors) { tx ->
    tx.insert(Users, user)
}
```

## 13. Implementation order

1. Add `SessionScope` and `TransactionScope` interfaces.
2. Add the internal execution coordinator that owns lifecycle and error rules.
3. Rename or wrap the current `Session` implementation as internal
   `RuntimeSession`.
4. Add `SessionBuilder` and route read-only execution through it.
5. Add `TransactionBuilder` and route transaction execution through it.
6. Replace `Aggo.read`, `Aggo.readQuery`, any `write` alias,
   `Aggo.transaction`, and direct CRUD
   methods with `Aggo.session` and `Aggo.tx`.
7. Update `MultiSchemaAggo` and `MultiDatabaseAggo`.
8. Update integration tests and compile/API tests.
9. Update README, docs, and KDoc.
10. Run `mvn -q test` and targeted integration tests according to local Docker
   availability.
11. Comment on the GitHub issue with any deviation from this plan.
