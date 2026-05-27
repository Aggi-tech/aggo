# spec-8 — Multi-dialect support: MySQL 8+ and Oracle 19c+

> Status: **planned**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

---

## Motivation

The current `SqlDialect` interface covers only two runtime variables: placeholder style and identifier quoting. `Renderers.kt` has four behaviors hardcoded to PostgreSQL:

| Hardcoded location | PostgreSQL | MySQL 8 | Oracle 19c |
|---|---|---|---|
| `LIMIT n OFFSET m` in every SELECT renderer | valid | valid (with caveat) | **invalid** — must use `OFFSET m ROWS FETCH NEXT n ROWS ONLY` |
| `RETURNING id` in `renderInsert` | native | **does not exist** | `RETURNING id INTO :out` (OUT param) |
| `~` / `~*` regex predicates (via `Predicate.Like` pattern) | native | **invalid** — use `REGEXP` | **invalid** — use `REGEXP_LIKE()` |
| `ILIKE` (planned) | native | **does not exist** — case-insensitivity via collation | **does not exist** — emulate with `REGEXP_LIKE(..., 'i')` |

This spec adds **MySQL 8+** and **Oracle 19c+** dialects, expands the `SqlDialect` interface to make all these behaviors pluggable, and defines a Decorator layer for vendor-exclusive features that cannot be emulated neutrally.

**Design constraints agreed during planning:**

1. Every dialect must implement every feature — emulation is mandatory, `UnsupportedOperationException` is forbidden for core SQL behaviors.
2. Database-specific features with no neutral equivalent (upsert, hints) use the **Decorator** pattern applied only to the `SqlDialect` layer. The DSL builders (`InsertBuilder`, `SelectBuilder`, etc.) are **not** changed.
3. `INSERT RETURNING` is emulated per dialect: MySQL uses a post-insert `SELECT`; Oracle uses `RETURNING ... INTO :out` with R2DBC OUT parameters.

---

## D-1 Expand `SqlDialect` interface

**File:** `dialect/SqlDialect.kt`

Add four new required methods. All existing implementations (`PostgresDialect`) must be updated. No default implementations are provided — the compiler enforces dialect completeness.

```kotlin
interface SqlDialect {
    // existing
    fun placeholder(oneBasedIndex: Int): String
    fun quoteIdentifier(name: String): String

    // D-1a: pagination
    fun renderPagination(limit: Int?, offset: Int?): String

    // D-1b: case-insensitive LIKE — emulated differently per DB
    fun renderLikeIgnoreCase(operand: String, pattern: String, negated: Boolean): String

    // D-1c: regex match — emulated differently per DB
    fun renderRegexMatch(operand: String, pattern: String, caseInsensitive: Boolean, negated: Boolean): String

    // D-1d: INSERT returning strategy
    fun insertReturnStrategy(primaryKeyColumns: List<Column<*, *>>): InsertReturnStrategy
}
```

### D-1a: `renderPagination` contract

Implementors receive nullable values. An empty string means "no clause appended". Renderers append the result directly with a leading space guard.

| Dialect | `limit=10, offset=20` | `limit=10, offset=null` | `limit=null, offset=20` |
|---|---|---|---|
| PostgreSQL | `LIMIT 10 OFFSET 20` | `LIMIT 10` | `OFFSET 20` |
| MySQL | `LIMIT 10 OFFSET 20` | `LIMIT 10` | `LIMIT 18446744073709551615 OFFSET 20`¹ |
| Oracle | `OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY` | `FETCH FIRST 10 ROWS ONLY` | `OFFSET 20 ROWS` |

¹ MySQL requires `LIMIT` before `OFFSET`. The max-BIGINT constant is the documented workaround.

### D-1b: `renderLikeIgnoreCase` contract

| Dialect | rendered SQL |
|---|---|
| PostgreSQL | `operand ILIKE $n` |
| MySQL | `operand LIKE $n`² |
| Oracle | `REGEXP_LIKE(operand, $n, 'i')` |

² MySQL's default collation (`utf8mb4_0900_ai_ci`) is accent- and case-insensitive, so bare `LIKE` matches case-insensitively. Callers using case-sensitive collations must set their collation explicitly — the dialect cannot override it per-query without breaking portability.

### D-1c: `renderRegexMatch` contract

| Dialect | `caseInsensitive=false` | `caseInsensitive=true` |
|---|---|---|
| PostgreSQL | `operand ~ $n` | `operand ~* $n` |
| MySQL | `operand REGEXP $n` | `operand REGEXP $n`³ |
| Oracle | `REGEXP_LIKE(operand, $n)` | `REGEXP_LIKE(operand, $n, 'i')` |

³ MySQL 8's `REGEXP` case-sensitivity depends on the column collation. The dialect cannot control it per-expression; this is documented as a known difference.

### D-1d: `InsertReturnStrategy` — sealed interface

**New file:** `dialect/InsertReturnStrategy.kt`

```kotlin
sealed interface InsertReturnStrategy {
    /** PostgreSQL: appends `RETURNING col1, col2` to the INSERT SQL. */
    data class AppendClause(val clause: String) : InsertReturnStrategy

    /** MySQL: executes `SELECT col FROM table WHERE pk = ?` after INSERT.
     *  The PK value is extracted from the Insert.assignments by the Session. */
    data class PostInsertSelect(val sql: String) : InsertReturnStrategy

    /** Oracle: appends `RETURNING col1 INTO :out1` and registers OUT params via
     *  R2DBC `Statement.bind(":outN", Parameters.out(type))`. */
    data class ReturningInto(val clause: String, val outParams: List<OutParam>) : InsertReturnStrategy

    data class OutParam(val bindName: String, val sqlType: Class<*>)
}
```

---

## D-2 New predicates: `ILike` and `RegexMatch`

**File:** `query/Predicate.kt`

Add two new variants to the sealed interface. The `when` in `PredicateRenderer` is exhaustive — the compiler flags the missing branches immediately.

```kotlin
sealed interface Predicate {
    // ... existing variants ...

    data class ILike(
        val operand: Operand,
        val pattern: String,
        val negated: Boolean = false,
    ) : Predicate

    data class RegexMatch(
        val operand: Operand,
        val pattern: String,
        val caseInsensitive: Boolean,
        val negated: Boolean = false,
    ) : Predicate
}
```

---

## D-3 New DSL operators

**File:** `dsl/Operators.kt`

Add inside `WhereScope`:

```kotlin
infix fun <E> Column<E, String>.ilike(pattern: String): Predicate =
    Predicate.ILike(Operand.Col(this), pattern)

infix fun <E> Column<E, String>.notIlike(pattern: String): Predicate =
    Predicate.ILike(Operand.Col(this), pattern, negated = true)

infix fun <E> Column<E, String>.matchesRegex(pattern: String): Predicate =
    Predicate.RegexMatch(Operand.Col(this), pattern, caseInsensitive = false)

infix fun <E> Column<E, String>.matchesRegexIgnoreCase(pattern: String): Predicate =
    Predicate.RegexMatch(Operand.Col(this), pattern, caseInsensitive = true)

infix fun <E> Column<E, String>.notMatchesRegex(pattern: String): Predicate =
    Predicate.RegexMatch(Operand.Col(this), pattern, caseInsensitive = false, negated = true)
```

These operators are typed to `Column<E, String>` — regex and ILIKE are string-only operations.

---

## D-4 Update `PredicateRenderer`

**File:** `render/PredicateRenderer.kt`

Add two branches to the exhaustive `when`:

```kotlin
is Predicate.ILike -> {
    val sourceColumn = predicate.operand.columnRef()
    val operand = renderOperand(predicate.operand, ctx, sourceColumn)
    val placeholder = ctx.bind(predicate.pattern, StringCodec, sourceColumn)
    ctx.dialect.renderLikeIgnoreCase(operand, placeholder, predicate.negated)
}

is Predicate.RegexMatch -> {
    val sourceColumn = predicate.operand.columnRef()
    val operand = renderOperand(predicate.operand, ctx, sourceColumn)
    val placeholder = ctx.bind(predicate.pattern, StringCodec, sourceColumn)
    ctx.dialect.renderRegexMatch(operand, placeholder, predicate.caseInsensitive, predicate.negated)
}
```

Sensitive-column attribution (V-6) follows the same pattern as `Predicate.Like`.

---

## D-5 Update `Renderers.kt` — pagination

**File:** `render/Renderers.kt`

Replace every hardcoded `LIMIT/OFFSET` block across all four renderers (`renderSelect`, `renderJoinSelect`, `renderAggregateSelect`, `renderProjectionSelect`) with a single call:

```kotlin
// Before (4 occurrences):
effectiveLimit?.let { append(" LIMIT ").append(it) }
query.offset?.let { append(" OFFSET ").append(it) }

// After:
val pagination = dialect.renderPagination(effectiveLimit, query.offset)
if (pagination.isNotEmpty()) append(' ').append(pagination)
```

The `limitOverride` path in `renderSelect` and `renderProjectionSelect` computes `effectiveLimit` before calling `renderPagination`, unchanged.

---

## D-6 Update `renderInsert` — return type and strategy

**File:** `render/Renderers.kt`

The current signature returns `RenderedSql`. Change it to a new type that carries the strategy so `Session` can dispatch on it without re-running the renderer.

### New type: `RenderedInsert`

```kotlin
data class RenderedInsert(
    val sql: String,
    val params: List<Bound>,
    val returnStrategy: InsertReturnStrategy?,
)
```

### Updated `renderInsert`

```kotlin
fun renderInsert(
    query: Insert<*>,
    dialect: SqlDialect,
    returningPk: Boolean = false,
): RenderedInsert {
    val ctx = RenderContext(dialect)
    val table = dialect.quoteIdentifier(query.table.name)
    val cols = query.assignments.joinToString(", ") { dialect.quoteIdentifier(it.column.name) }
    val placeholders = query.assignments.joinToString(", ") { bindAssignment(it, ctx) }

    val baseSql = buildString(48 + table.length + cols.length + placeholders.length) {
        append("INSERT INTO ").append(table)
        append(" (").append(cols).append(")")
        append(" VALUES (").append(placeholders).append(")")
    }

    if (!returningPk) return RenderedInsert(baseSql, ctx.params, null)

    val pks = query.table.primaryKeys
    require(pks.isNotEmpty()) {
        "RETURNING requested but table '${query.table.name}' has no primary key"
    }
    val strategy = dialect.insertReturnStrategy(pks)

    val finalSql = when (strategy) {
        is InsertReturnStrategy.AppendClause -> "$baseSql ${strategy.clause}"
        is InsertReturnStrategy.ReturningInto -> "$baseSql ${strategy.clause}"
        is InsertReturnStrategy.PostInsertSelect -> baseSql  // SELECT runs separately
    }

    return RenderedInsert(finalSql, ctx.params, strategy)
}
```

---

## D-7 Update `Session` — dispatch on `InsertReturnStrategy`

**File:** `runtime/Session.kt`

The current `Session.insert(query, returningPk)` call path must be updated to handle the three strategies. The exact R2DBC APIs depend on whether the project already imports the Oracle or MySQL R2DBC drivers; the spec describes the logic, not the driver API calls.

```kotlin
// Conceptual dispatch inside Session.insert / Session.insertReturning:
when (val strategy = rendered.returnStrategy) {
    null -> {
        // Fire and forget — no returning value consumed
        executeStatement(rendered)
    }
    is InsertReturnStrategy.AppendClause -> {
        // PostgreSQL: result set has one row with the PK columns
        executeStatement(rendered).awaitFirst()  // read PK from result row
    }
    is InsertReturnStrategy.PostInsertSelect -> {
        // MySQL: run INSERT, then SELECT pk FROM table WHERE pk = ?
        // The PK value is already in rendered.params (it was bound as an assignment)
        executeStatement(rendered)
        executeRaw(strategy.sql, pkValues)  // pkValues extracted from assignments
    }
    is InsertReturnStrategy.ReturningInto -> {
        // Oracle: register OUT params before execution
        // statement.bind(":out1", Parameters.out(R2dbcType.VARCHAR)) etc.
        executeStatementWithOutParams(rendered, strategy.outParams)
    }
}
```

**Constraint:** `Session` must not acquire a new connection for `PostInsertSelect` — both queries run on the same `this.connection` to maintain transactional guarantees (contract 5 in CLAUDE.md).

---

## D-8 `PostgresDialect` — implement new interface methods

**File:** `dialect/PostgresDialect.kt`

```kotlin
object PostgresDialect : MigrationDialect {
    // existing unchanged
    override fun placeholder(oneBasedIndex: Int) = "\$$oneBasedIndex"
    override fun quoteIdentifier(name: String): String { /* unchanged */ }
    override fun columnSqlType(codec: Codec<*>): String { /* unchanged */ }

    // D-1a
    override fun renderPagination(limit: Int?, offset: Int?) = buildString {
        limit?.let { append("LIMIT ").append(it) }
        offset?.let { if (isNotEmpty()) append(' '); append("OFFSET ").append(it) }
    }

    // D-1b
    override fun renderLikeIgnoreCase(operand: String, pattern: String, negated: Boolean) =
        if (negated) "$operand NOT ILIKE $pattern" else "$operand ILIKE $pattern"

    // D-1c
    override fun renderRegexMatch(operand: String, pattern: String, caseInsensitive: Boolean, negated: Boolean): String {
        val op = when {
            !negated && !caseInsensitive -> "~"
            !negated &&  caseInsensitive -> "~*"
             negated && !caseInsensitive -> "!~"
             else                        -> "!~*"
        }
        return "$operand $op $pattern"
    }

    // D-1d
    override fun insertReturnStrategy(primaryKeyColumns: List<Column<*, *>>) =
        InsertReturnStrategy.AppendClause(
            "RETURNING ${primaryKeyColumns.joinToString(", ") { quoteIdentifier(it.name) }}"
        )
}
```

---

## D-9 `MySqlDialect` — new file

**New file:** `dialect/MySqlDialect.kt`

```kotlin
object MySqlDialect : MigrationDialect {

    override fun placeholder(oneBasedIndex: Int) = "?"

    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)
        return "`${name.replace("`", "``")}`"
    }

    // D-1a: MySQL requires LIMIT before OFFSET; OFFSET without LIMIT uses the max BIGINT constant.
    override fun renderPagination(limit: Int?, offset: Int?) = buildString {
        when {
            limit != null && offset != null -> append("LIMIT ").append(limit).append(" OFFSET ").append(offset)
            limit != null                   -> append("LIMIT ").append(limit)
            offset != null                  -> append("LIMIT 18446744073709551615 OFFSET ").append(offset)
        }
    }

    // D-1b: MySQL utf8mb4_0900_ai_ci (default) treats LIKE as case-insensitive.
    // Negation wraps in NOT (...).
    override fun renderLikeIgnoreCase(operand: String, pattern: String, negated: Boolean) =
        if (negated) "$operand NOT LIKE $pattern" else "$operand LIKE $pattern"

    // D-1c: MySQL 8 REGEXP; case-sensitivity follows column collation.
    override fun renderRegexMatch(operand: String, pattern: String, caseInsensitive: Boolean, negated: Boolean): String {
        val op = if (negated) "NOT REGEXP" else "REGEXP"
        return "$operand $op $pattern"
    }

    // D-1d: post-insert SELECT using the known PK value (which is in the assignments).
    override fun insertReturnStrategy(primaryKeyColumns: List<Column<*, *>>): InsertReturnStrategy {
        require(primaryKeyColumns.isNotEmpty())
        val pk = primaryKeyColumns.first()
        val table = quoteIdentifier(pk.table.name)
        val col = quoteIdentifier(pk.name)
        return InsertReturnStrategy.PostInsertSelect("SELECT * FROM $table WHERE $col = ?")
    }

    override fun columnSqlType(codec: Codec<*>): String {
        if (codec is MigratableCodec<*>) return codec.ddlTypeName
        return when (codec.sqlType) {
            String::class.java              -> "LONGTEXT"
            Int::class.javaObjectType       -> "INT"
            Long::class.javaObjectType      -> "BIGINT"
            Short::class.javaObjectType     -> "SMALLINT"
            Float::class.javaObjectType     -> "FLOAT"
            Double::class.javaObjectType    -> "DOUBLE"
            Boolean::class.javaObjectType   -> "TINYINT(1)"
            BigDecimal::class.java          -> "DECIMAL(38, 4)"
            OffsetDateTime::class.java      -> "DATETIME"  // no TZ type; normalize to UTC at app layer
            LocalDateTime::class.java       -> "DATETIME"
            LocalDate::class.java           -> "DATE"
            UUID::class.java                -> "CHAR(36)"
            ByteArray::class.java           -> "LONGBLOB"
            else -> throw UnsupportedOperationException(
                "No MySQL DDL type mapping for R2DBC driver type '${codec.sqlType.name}'. " +
                "Implement MigratableCodec or use a supported built-in Codec."
            )
        }
    }
}
```

**Known differences from PostgreSQL:**

| Behavior | PostgreSQL | MySQL |
|---|---|---|
| Timestamp with timezone | `TIMESTAMPTZ` (driver stores offset) | `DATETIME` (no TZ; store UTC, convert at app layer) |
| Boolean | native `BOOLEAN` | `TINYINT(1)` — `0`/`1` only |
| UUID | native `UUID` type | `CHAR(36)` |
| Text | `TEXT` | `LONGTEXT` (up to 4 GiB; use `VARCHAR(n)` via `Table.varchar()` if size is known) |
| Regex case control | per-operator `~` vs `~*` | depends on column collation; `REGEXP` operator follows it |
| Numeric precision | `NUMERIC` (unconstrained) | `DECIMAL(38, 4)` hardcoded; use `MigratableCodec` for custom precision |

---

## D-10 `OracleDialect` — new file

**New file:** `dialect/OracleDialect.kt`

```kotlin
object OracleDialect : MigrationDialect {

    // Oracle 12.2+ supports 128-char identifiers; pre-12.2 is 30 chars.
    // The shared requireValidIdentifier enforces 63 chars (safe for 12.2+).
    // Teams on pre-12.2 must keep identifiers ≤ 30 chars.
    override fun placeholder(oneBasedIndex: Int) = ":$oneBasedIndex"

    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)
        // Double-quotes preserve case in Oracle. Without quotes Oracle uppercases identifiers.
        return "\"${name.replace("\"", "\"\"")}\""
    }

    // D-1a: Oracle 12c+ pagination syntax.
    override fun renderPagination(limit: Int?, offset: Int?) = buildString {
        offset?.let { append("OFFSET ").append(it).append(" ROWS") }
        limit?.let {
            if (isNotEmpty()) append(' ')
            append("FETCH NEXT ").append(it).append(" ROWS ONLY")
        }
    }

    // D-1b: Oracle has no ILIKE; emulate with REGEXP_LIKE and 'i' flag.
    override fun renderLikeIgnoreCase(operand: String, pattern: String, negated: Boolean): String {
        val expr = "REGEXP_LIKE($operand, $pattern, 'i')"
        return if (negated) "NOT $expr" else expr
    }

    // D-1c: Oracle uses REGEXP_LIKE function.
    override fun renderRegexMatch(operand: String, pattern: String, caseInsensitive: Boolean, negated: Boolean): String {
        val flags = if (caseInsensitive) ", 'i'" else ""
        val expr = "REGEXP_LIKE($operand, $pattern$flags)"
        return if (negated) "NOT $expr" else expr
    }

    // D-1d: RETURNING ... INTO :outN — R2DBC Oracle driver registers OUT params.
    override fun insertReturnStrategy(primaryKeyColumns: List<Column<*, *>>): InsertReturnStrategy {
        val cols = primaryKeyColumns.joinToString(", ") { quoteIdentifier(it.name) }
        val outs = primaryKeyColumns.mapIndexed { i, col ->
            InsertReturnStrategy.OutParam(":out${i + 1}", col.codec.sqlType)
        }
        val intoClause = outs.joinToString(", ") { it.bindName }
        return InsertReturnStrategy.ReturningInto("RETURNING $cols INTO $intoClause", outs)
    }

    override fun columnSqlType(codec: Codec<*>): String {
        if (codec is MigratableCodec<*>) return codec.ddlTypeName
        return when (codec.sqlType) {
            String::class.java              -> "VARCHAR2(4000)"
            Int::class.javaObjectType       -> "NUMBER(10)"
            Long::class.javaObjectType      -> "NUMBER(19)"
            Short::class.javaObjectType     -> "NUMBER(5)"
            Float::class.javaObjectType     -> "FLOAT(24)"
            Double::class.javaObjectType    -> "FLOAT(53)"
            Boolean::class.javaObjectType   -> "NUMBER(1)"  // Oracle has no BOOLEAN in SQL (23c adds it)
            BigDecimal::class.java          -> "NUMBER(38, 4)"
            OffsetDateTime::class.java      -> "TIMESTAMP WITH TIME ZONE"
            LocalDateTime::class.java       -> "TIMESTAMP"
            LocalDate::class.java           -> "DATE"
            UUID::class.java                -> "CHAR(36)"
            ByteArray::class.java           -> "BLOB"
            else -> throw UnsupportedOperationException(
                "No Oracle DDL type mapping for R2DBC driver type '${codec.sqlType.name}'. " +
                "Implement MigratableCodec or use a supported built-in Codec."
            )
        }
    }
}
```

**Known differences from PostgreSQL:**

| Behavior | PostgreSQL | Oracle |
|---|---|---|
| Boolean | native `BOOLEAN` | `NUMBER(1)` — `0`/`1`; Oracle 23c adds native `BOOLEAN` |
| UUID | native `UUID` type | `CHAR(36)` |
| Text | `TEXT` (unbounded) | `VARCHAR2(4000)`; use `CLOB` for larger via `MigratableCodec` |
| Identifier case | lowercase preserved | lowercase preserved when quoted; **unquoted** names are uppercased (Aggo always quotes) |
| Identifier max length | 63 (NAMEDATALEN−1) | 30 pre-12.2, 128 on 12.2+ |
| Sequence / auto-inc | `SERIAL`/`IDENTITY` | `SEQUENCE` + trigger (pre-12c), `IDENTITY` (12c+); Aggo avoids both — use TSID/ULID |
| RETURNING | clause in SELECT list | `RETURNING ... INTO :out` — OUT parameters, not a result set |

---

## D-11 Decorator pattern — vendor-exclusive features

Decorators wrap `SqlDialect` via Kotlin interface delegation. They add rendering methods for features with no cross-dialect equivalent. The DSL builders are **not modified**.

### D-11a: `OnConflictDecorator` (PostgreSQL upsert)

**New file:** `dialect/decorators/OnConflictDecorator.kt`

```kotlin
class OnConflictDecorator(private val base: PostgresDialect) : SqlDialect by base {

    fun renderOnConflictUpdate(
        conflictColumns: List<Column<*, *>>,
        updateAssignments: List<Assignment<*, *>>,
    ): String {
        val target = conflictColumns.joinToString(", ") { base.quoteIdentifier(it.name) }
        val updates = updateAssignments.joinToString(", ") { a ->
            val col = base.quoteIdentifier(a.column.name)
            "$col = EXCLUDED.$col"
        }
        return "ON CONFLICT ($target) DO UPDATE SET $updates"
    }

    fun renderOnConflictDoNothing(conflictColumns: List<Column<*, *>>): String {
        val target = conflictColumns.joinToString(", ") { base.quoteIdentifier(it.name) }
        return "ON CONFLICT ($target) DO NOTHING"
    }
}

fun PostgresDialect.withOnConflict() = OnConflictDecorator(this)
```

### D-11b: `OnDuplicateKeyDecorator` (MySQL upsert)

**New file:** `dialect/decorators/OnDuplicateKeyDecorator.kt`

```kotlin
class OnDuplicateKeyDecorator(private val base: MySqlDialect) : SqlDialect by base {

    fun renderOnDuplicateKeyUpdate(updateAssignments: List<Assignment<*, *>>): String {
        val updates = updateAssignments.joinToString(", ") { a ->
            val col = base.quoteIdentifier(a.column.name)
            "$col = VALUES($col)"
        }
        return "ON DUPLICATE KEY UPDATE $updates"
    }
}

fun MySqlDialect.withOnDuplicateKey() = OnDuplicateKeyDecorator(this)
```

### Usage example (caller code, not in the library)

```kotlin
// PostgreSQL upsert — dialect decorator at call site
val pg = PostgresDialect.withOnConflict()

val insertQuery = insert(UsersTable, user)
val rendered = renderInsert(insertQuery, pg)
val conflictClause = pg.renderOnConflictDoNothing(listOf(UsersTable.email))
val upsertSql = "${rendered.sql} $conflictClause"

@OptIn(AggoUnsafe::class)
session.executeRaw(upsertSql, rendered.params)
```

Vendor extensions are always reached via `@AggoUnsafe` + `session.executeRaw`, keeping the type-safe execution path dialect-neutral.

---

## D-12 `Checks.kt` — dialect limitation (deferred)

`Checks.*` helpers produce PostgreSQL-specific SQL expressions:
- `Checks.matches()` uses POSIX `~` — invalid in MySQL and Oracle
- `Checks.matchesIgnoreCase()` uses POSIX `~*` — invalid in MySQL and Oracle
- `Checks.email()` and `Checks.tsid()` use these internally

**Current state:** Check expressions are DDL-only (generated in `CREATE TABLE ... CHECK (...)` and `ALTER TABLE ... ADD CONSTRAINT`). They are not part of DML rendering. A `MigrationDialect.renderCheckExpression()` method would be needed to emit dialect-appropriate DDL. This is **deferred** to a follow-up spec.

**Interim workaround:** Teams migrating from PostgreSQL to MySQL/Oracle must replace `Checks.matches()` with raw lambdas using the target database's syntax, or skip check constraints and enforce them at the application layer.

---

## D-13 `requireValidIdentifier` — identifier length

`MAX_IDENTIFIER_LENGTH = 63` (PostgreSQL NAMEDATALEN−1) is applied at schema definition time. Oracle pre-12.2 has a 30-character limit.

**Approach:** Oracle 12.2+ supports 128-char identifiers, so the 63-char cap is safe. Teams targeting pre-12.2 Oracle must additionally enforce a 30-char cap in their own schema definitions. `OracleDialect.quoteIdentifier` does **not** re-validate length — a schema-time check would require a dialect reference, breaking the schema/dialect separation.

No change to `requireValidIdentifier`.

---

## D-14 Testing

### Unit tests (`RendererTest` / new `DialectTest`)

All tests are pure, no I/O.

- **D-1a pagination:** For each of the 3 dialects × 4 combinations (null/null, limit-only, offset-only, both): assert `renderPagination()` produces the exact expected string.
- **D-1b ILIKE:** For each dialect, assert `renderLikeIgnoreCase()` output, both `negated=false` and `negated=true`.
- **D-1c regex:** For each dialect, assert `renderRegexMatch()` for all 4 combinations of `caseInsensitive × negated`.
- **D-1d strategy:** For each dialect, call `insertReturnStrategy(listOf(pkColumn))` and assert the returned subtype and clause string.
- **Full SELECT rendering:** Assert `renderSelect(query, MySqlDialect)` and `renderSelect(query, OracleDialect)` produce correct SQL strings with the right pagination, placeholder style (`?` vs `:1`), and quoting style (backtick vs double-quote).
- **ILike predicate:** Assert `PredicateRenderer.render(Predicate.ILike(...), ctx)` for all 3 dialects.
- **RegexMatch predicate:** Assert `PredicateRenderer.render(Predicate.RegexMatch(...), ctx)` for all 3 dialects × 4 combinations.
- **Decorator rendering:** Assert `OnConflictDecorator.renderOnConflictUpdate(...)` and `renderOnConflictDoNothing(...)` output.

### Integration tests (Testcontainers)

- **MySQL:** Add `mysql:8.0` container. Reproduce all existing `IntegrationTest` cases with `MySqlDialect`. Use `io.asyncer:r2dbc-mysql` driver.
- **Oracle:** Use `gvenzl/oracle-xe:21-slim` image via Testcontainers. Integration tests are `@Tag("oracle")` and skipped by default (slow image pull). Use `com.oracle.database.r2dbc:oracle-r2dbc` driver.
- **No R2DBC mocks** — do not mock `Connection` or `Statement` in new tests (CLAUDE.md convention).

---

## File ownership summary

| File | Action | Reason |
|---|---|---|
| `dialect/SqlDialect.kt` | **modify** | Add 4 new interface methods |
| `dialect/InsertReturnStrategy.kt` | **create** | New sealed interface |
| `dialect/PostgresDialect.kt` | **modify** | Implement new interface methods |
| `dialect/MySqlDialect.kt` | **create** | MySQL 8+ dialect |
| `dialect/OracleDialect.kt` | **create** | Oracle 19c+ dialect |
| `dialect/decorators/OnConflictDecorator.kt` | **create** | PostgreSQL upsert decorator |
| `dialect/decorators/OnDuplicateKeyDecorator.kt` | **create** | MySQL upsert decorator |
| `query/Predicate.kt` | **modify** | Add `ILike`, `RegexMatch` variants |
| `dsl/Operators.kt` | **modify** | Add `ilike`, `notIlike`, `matchesRegex`, `matchesRegexIgnoreCase`, `notMatchesRegex` |
| `render/PredicateRenderer.kt` | **modify** | Handle new predicate variants |
| `render/Renderers.kt` | **modify** | Pagination via `dialect.renderPagination()`; `renderInsert` returns `RenderedInsert` |
| `render/RenderedInsert.kt` | **create** | New return type for `renderInsert` |
| `runtime/Session.kt` | **modify** | Dispatch on `InsertReturnStrategy` |
| `schema/Checks.kt` | **no change** | Deferred — see D-12 |
| `RendererTest.kt` | **modify** | Add dialect-coverage cases |
| `DialectTest.kt` | **modify** | Add MySQL and Oracle cases |

---

## Checklist

- [ ] D-1: `SqlDialect` interface — 4 new methods
- [ ] D-1d: `InsertReturnStrategy` sealed interface
- [ ] D-2: `Predicate.ILike` + `Predicate.RegexMatch`
- [ ] D-3: DSL operators (`ilike`, `matchesRegex`, etc.)
- [ ] D-4: `PredicateRenderer` — new branches
- [ ] D-5: `Renderers` — pagination via dialect
- [ ] D-6: `renderInsert` returns `RenderedInsert`
- [ ] D-7: `Session` — `InsertReturnStrategy` dispatch
- [ ] D-8: `PostgresDialect` — implement new methods
- [ ] D-9: `MySqlDialect` — new file
- [ ] D-10: `OracleDialect` — new file
- [ ] D-11a: `OnConflictDecorator`
- [ ] D-11b: `OnDuplicateKeyDecorator`
- [ ] D-14: Unit tests for all 3 dialects × all new behaviors
- [ ] D-14: Integration tests with `mysql:8.0` container
