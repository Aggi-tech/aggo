# Aggo — LLM Contributor Spec

Aggo is a **reflection-free, type-safe R2DBC DSL** for PostgreSQL, built in Kotlin.
It replaces JPA/Hibernate in Quarkus microservices. Every design decision optimises for:
zero reflection at runtime (required for GraalVM native), compile-time type safety, and
correct transactional behaviour.

Read this document before touching any file in `libs/aggo/`.

---

## Architecture map

```
schema/         ← Declarative layer: Codec, Column, Table, Checks
query/          ← Immutable AST: Select, Insert, Update, Delete, Predicate, Operand
dsl/            ← Builder DSL: select {...}, insert {...}, update {...}, delete {...}
render/         ← SQL generation: renderSelect/Insert/Update/Delete + RenderContext
runtime/        ← Execution: Aggo, Session, AggoPool, Binder, Logging
dialect/        ← SQL dialect abstraction: SqlDialect, PostgresDialect
```

Data flows in one direction: **schema → dsl → query AST → render → runtime**.
Nothing flows backwards. Renderers never call Session; Session never calls DSL builders.

---

## Core contracts — never violate these

### 1. Zero reflection
No `KClass`, `::class.java`, `getDeclaredFields`, or annotation scanning at runtime.
All mappings (column → getter, codec encode/decode) are explicit lambdas captured at
schema definition time. The entire `schema/` package runs at native-image build time.

### 2. Codec is the single encode/decode boundary
A `Codec<V>` bridges exactly one Kotlin type `V` to one R2DBC-compatible type.
`encode()` is called only by `Binder`; `decode()` is called only by `Column.read()`.
Never call `codec.encode()` or `codec.decode()` outside those two sites.

### 3. Column identity is (table.name, column.name)
`Column.equals` / `hashCode` are based on the string pair, not object identity.
This matters for sets/maps used in future join or projection features.

### 4. RenderContext owns parameter ordering
All SQL parameters are registered through `RenderContext.bind()`, which assigns
positional `$1, $2, …` placeholders and accumulates `Bound` entries in order.
Never construct placeholder strings manually — always call `ctx.bind(value, codec)`.

### 5. Session is bound to one Connection
`Session` holds a single `Connection` for its entire lifetime. Every method on `Session`
uses `this.connection`. This is what makes `Aggo.tx { }` actually transactional.
Do not introduce connection-pooling logic inside `Session`.

### 6. tx() rollback must not mask the original exception
In `Aggo.tx`, rollback errors are added as `addSuppressed()`, never re-thrown.
Do not change this pattern — callers rely on catching the original exception type.

---

## How to add a new built-in Codec

1. Add an `object FooCodec : Codec<Foo>` in `schema/Codec.kt`.
2. Set `sqlType` to the concrete Java type the R2DBC driver understands.
3. Implement `encode` (domain → driver) and `decode` (driver → domain).
4. Handle `null` in both directions explicitly.
5. Add a unit test in `RendererTest` (bind round-trip) and `IntegrationTest` (real DB).

Example — adding `ShortCodec`:
```kotlin
object ShortCodec : Codec<Short> {
    override val sqlType: Class<*> = java.lang.Short::class.java
    override fun encode(value: Short?): Any? = value
    override fun decode(raw: Any?): Short? = (raw as? Number)?.toShort()
}
```

---

## How to add a new DSL operator

Operators live in `dsl/Operators.kt` as extension functions on `Column<E, V>`.
They must produce `Predicate` nodes — never raw SQL strings.

Pattern:
```kotlin
infix fun <E, V> Column<E, V>.myOp(value: V?): Predicate =
    Predicate.Cmp(Operand.Col(this), ComparisonOp.MY_OP, Operand.Literal(value, codec))
```

If the operator doesn't map to an existing `ComparisonOp`, add a new variant to
`ComparisonOp` enum and a matching branch in `PredicateRenderer.render()`.

---

## How to add a new Predicate shape

1. Add a `data class MyPredicate(…) : Predicate` in `query/Predicate.kt`.
2. Handle it in `PredicateRenderer.render()` — the `when` is exhaustive, so the
   compiler will flag any missing branch.
3. Add the DSL entry point in `dsl/Operators.kt`.
4. Add a renderer unit test in `RendererTest`.

---

## How to add a new SQL dialect

1. Implement `SqlDialect` in a new file under `dialect/`.
2. Provide `placeholder(index)` and `quoteIdentifier(name)`.
3. Call `requireValidIdentifier(name)` inside `quoteIdentifier` — this blocks
   identifier injection (the same validation PostgresDialect uses).
4. Wire it into `AggoPool` if the dialect needs its own pool configuration.

---

## Schema definition pattern (for microservices using Aggo)

```kotlin
// infrastructure/persistence/aggo/Codecs.kt
val IdCodec: Codec<ID> = ValueClassCodec(StringCodec, ID.Companion::from, ID::value)
val EmailCodec: Codec<Email> = ValueClassCodec(StringCodec, Email.Companion::of) { it.toString() }

// infrastructure/persistence/aggo/UsersTable.kt
object UsersTable : Table<User>("users") {

    val id    = column("id",    IdCodec,    isPrimaryKey = true,
                    check = Checks.tsid())                          { it.id }
    val email = column("email", EmailCodec,
                    check = Checks.email())                         { it.email }
    val name  = column("name",  StringCodec,
                    check = Checks.all(Checks.notBlank(), Checks.length(max = 100))) { it.name }
    val active = column("active", BooleanCodec)                     { it.active }

    override fun fromRow(row: Row): User = User(
        id     = id.readRequired(row),
        email  = email.readRequired(row),
        name   = name.readRequired(row),
        active = active.readRequired(row),
    )
}

// Generate Liquibase migration SQL for existing tables:
// UsersTable.addCheckConstraintsSql().forEach(::println)
```

---

## CHECK constraint system (schema/Checks.kt + schema/Table.kt)

`Table.column()` accepts an optional `check: ((columnName: String) -> String)?` parameter.
The lambda receives the bare column name and must return a valid PostgreSQL boolean expression.
The column name is your responsibility to quote inside the expression — all `Checks.*` helpers
do this automatically using `"$col"`.

**Generating SQL from a Table:**

```kotlin
// Inline clauses for CREATE TABLE:
UsersTable.checkConstraintClauses()
// → ["CONSTRAINT chk_users_id CHECK (char_length(\"id\") = 13 AND ...)", ...]

// ALTER TABLE statements for existing tables (paste into Liquibase migration):
UsersTable.addCheckConstraintsSql()
// → ["ALTER TABLE \"users\" ADD CONSTRAINT chk_users_id CHECK (...);", ...]
```

**Available Checks helpers:**
| Helper | Example | Notes |
|--------|---------|-------|
| `Checks.notBlank()` | any text col | `trim(col) <> ''` |
| `Checks.length(min, max)` | `length(max = 100)` | char_length bounds |
| `Checks.matches(pattern)` | `matches("^[A-Z]+$")` | POSIX `~` |
| `Checks.matchesIgnoreCase(p)` | email | POSIX `~*` |
| `Checks.oneOf(vararg)` | enums | `IN ('A','B','C')` |
| `Checks.between(min, max)` | numerics | `BETWEEN` |
| `Checks.positive()` | counters | `> 0` |
| `Checks.nonNegative()` | amounts | `>= 0` |
| `Checks.tsid()` | ID columns | 13-char Crockford base32 |
| `Checks.email()` | email columns | loose format + 255 chars |
| `Checks.uuid()` | UUID columns | 8-4-4-4-12 hex |
| `Checks.all(vararg)` | compose | joins with AND |
| `Checks.any(vararg)` | compose | joins with OR |

To add a custom expression without a helper:
```kotlin
check = { col -> "\"$col\" > 0 AND \"$col\" < 1000000" }
```

---

## ValueClassCodec — the most important codec pattern

`@JvmInline value class` types must be wrapped in `ValueClassCodec`. Without it the
Kotlin compiler passes the wrapper type to `Statement.bind()` and the R2DBC driver crashes.

```kotlin
class ValueClassCodec<V : Any, R : Any>(
    private val raw: Codec<R>,     // the underlying primitive codec
    private val wrap: (R) -> V,    // factory: raw → domain (used in decode)
    private val unwrap: (V) -> R,  // extractor: domain → raw (used in encode)
) : Codec<V>
```

Rules:
- `wrap` must throw if the raw value is invalid (domain validation happens here).
- `unwrap` must be total (never throw for a valid domain value).
- `sqlType` is inherited from `raw` — the driver only ever sees the raw type.

---

## Binder — null safety contract

`Binder.bindOne` checks `bound.value == null` BEFORE calling `codec.encode()`.
This means `encode(null)` is never called — codecs only receive non-null values.
`bindNull(index, codec.sqlType)` is called for null, which is why `sqlType` must be
the concrete driver type (e.g. `java.lang.Integer::class.java`, not `Object`).

Do not change this null-check order. It prevents the "could not determine data type
of parameter $N" error that plagued the original AggORM.

---

## Transaction model

```
Aggo.read { session -> ... }   // autocommit, single statement or read-only queries
Aggo.tx   { session -> ... }   // BEGIN → block → COMMIT, or ROLLBACK on throw
```

Both scopes:
1. Acquire a connection from the pool.
2. Wrap it in a `Session`.
3. Release (close proxy) in `finally` — always, even on rollback error.

Never cache a `Session` across coroutine suspension points outside the lambda.
Never store a `Connection` reference outside `Session`.

---

## Testing conventions

- **Unit tests** (`RendererTest`, `DialectTest`): pure, no I/O. Test SQL string output
  and parameter lists. Run fast, always enabled.
- **Integration tests** (`IntegrationTest`): require Docker (Testcontainers postgres:16-alpine).
  Skip automatically if Docker is unavailable. Cover the actual driver round-trip.
- Each test case is annotated with the bug code it regresses (C-1…C-13) for traceability.
- Do not mock R2DBC connections in new tests — the integration suite is the truth.

---

## What NOT to do

| Forbidden | Reason |
|-----------|--------|
| `KClass<*>` / reflection in schema layer | Breaks GraalVM native |
| Raw SQL via `session.executeRaw()` in production code | Bypasses codec safety, injection risk |
| `dsl.execute()` / `plainSQL()` style (jOOQ pattern) | Use Aggo DSL builders instead |
| Mutable state on `Table` after construction | Tables are singletons, must be thread-safe |
| `statement.bind(index, value)` without `Binder` | Breaks null handling and value class unwrapping |
| `SELECT *` in `renderSelect` | Already avoided — Renderers enumerate `table.columns` explicitly |
| Adding columns to `Table` outside `column()` builder | `mutableColumns` is private; only `column()` writes it |

---

## File ownership summary

| File | Change when |
|------|-------------|
| `schema/Codec.kt` | Adding a new built-in type codec |
| `schema/Column.kt` | Changing column metadata (rare — break schema API) |
| `schema/Table.kt` | Changing schema registration or DDL generation |
| `schema/Checks.kt` | Adding new CHECK expression helpers |
| `query/Predicate.kt` | Adding a new WHERE operator shape |
| `query/Queries.kt` | Adding new query shapes (e.g. UPSERT, batch) |
| `dsl/Operators.kt` | Adding new infix WHERE operators |
| `dsl/*Dsl.kt` | Changing the builder API for a query type |
| `render/PredicateRenderer.kt` | Handling new Predicate variants |
| `render/Renderers.kt` | Changing how SELECT/INSERT/UPDATE/DELETE render |
| `render/RenderContext.kt` | Changing parameter binding or placeholder logic |
| `runtime/Binder.kt` | Changing how R2DBC Statement parameters are bound |
| `runtime/Session.kt` | Adding new query execution methods |
| `runtime/Aggo.kt` | Changing transaction or connection lifecycle |
| `runtime/Pool.kt` | Changing pool config or connection factory |
| `dialect/PostgresDialect.kt` | Postgres-specific rendering quirks |
