# Schema Definition

A schema in Aggo is a plain Kotlin `object` that extends `Table<E>`. It lives
in your infrastructure layer and is the single source of truth for:
- which columns exist in the table
- how each Kotlin type maps to an R2DBC driver type (the Codec)
- which columns have database-level constraints (CHECK, PRIMARY KEY)
- how a result `Row` is mapped back to an entity (no reflection)

## Declaring a Table

```kotlin
import com.aggitech.aggo.schema.*
import io.r2dbc.spi.Row
import java.time.Instant

data class User(
    val id: String,
    val email: String,
    val name: String,
    val active: Boolean,
    val createdAt: Instant,
)

object UsersTable : Table<User>("users") {

    val id        = column("id",         StringCodec,  isPrimaryKey = true)             { it.id }
    val email     = column("email",      StringCodec,  check = Checks.email())          { it.email }
    val name      = column("name",       StringCodec,  check = Checks.notBlank())       { it.name }
    val active    = column("active",     BooleanCodec)                                  { it.active }
    val createdAt = column("created_at", InstantCodec, isGenerated = true)              { it.createdAt }

    override fun fromRow(row: Row) = User(
        id        = id.required(row),
        email     = email.required(row),
        name      = name.required(row),
        active    = active.required(row),
        createdAt = createdAt.required(row),
    )
}
```

## Column flags

| Flag | Default | Meaning |
|------|---------|---------|
| `isPrimaryKey` | `false` | Included in `PRIMARY KEY (…)` DDL clause and `RETURNING` on insert |
| `isGenerated` | `false` | Skipped in INSERT (value comes from the database: sequences, DEFAULT, triggers) |
| `isNullable` | `false` | Omits `NOT NULL` in generated DDL; `Column.read()` may return null |
| `sensitive` | `false` | Value is logged as `<redacted>` in query logs (for passwords, tokens, PII) |

```kotlin
val id         = column("id",       UuidCodec,   isPrimaryKey = true, isGenerated = true) { it.id }
val token      = column("token",    StringCodec, sensitive = true)                        { it.token }
val deletedAt  = column("deleted_at", InstantCodec, isNullable = true)                   { it.deletedAt }
```

## Built-in Codecs

| Codec | Kotlin type | PostgreSQL type |
|-------|-------------|-----------------|
| `StringCodec` | `String` | `TEXT` |
| `IntCodec` | `Int` | `INTEGER` |
| `LongCodec` | `Long` | `BIGINT` |
| `ShortCodec` | `Short` | `SMALLINT` |
| `DoubleCodec` | `Double` | `DOUBLE PRECISION` |
| `BooleanCodec` | `Boolean` | `BOOLEAN` |
| `BigDecimalCodec` | `BigDecimal` | `NUMERIC` |
| `InstantCodec` | `Instant` | `TIMESTAMPTZ` |
| `LocalDateTimeCodec` | `LocalDateTime` | `TIMESTAMP` |
| `LocalDateCodec` | `LocalDate` | `DATE` |
| `UuidCodec` | `UUID` | `UUID` |
| `TsidCodec` | `Tsid` | `TEXT` (13-char Crockford base-32) |
| `UlidCodec` | `Ulid` | `TEXT` (26-char Crockford base-32) |

## ValueClassCodec — wrapping domain types

Use `ValueClassCodec` to create a codec for any `@JvmInline value class` (or any
type you want to wrap over a primitive). This is the recommended pattern for
domain identifiers, email addresses, and other value types.

```kotlin
@JvmInline value class UserId(val value: String)
@JvmInline value class Email(val raw: String)

// ValueClassCodec(rawCodec, wrap, unwrap)
val UserIdCodec = ValueClassCodec(StringCodec, ::UserId, UserId::value)
val EmailCodec  = ValueClassCodec(StringCodec, ::Email,  Email::raw)

// Use them just like any built-in codec:
object UsersTable : Table<User>("users") {
    val id    = column("id",    UserIdCodec, isPrimaryKey = true) { it.id }
    val email = column("email", EmailCodec,  check = Checks.email()) { it.email }
    ...
}
```

The `wrap` lambda runs on every read from the database. If the raw value is
invalid for the domain type, throw an exception inside `wrap` — that surfaces
corrupt data early.

## CHECK constraints

Attach a PostgreSQL CHECK constraint to any column by passing `check = …` to
`column()`. The lambda receives the (already-quoted) column name and returns a
SQL boolean expression.

```kotlin
val name   = column("name",   StringCodec, check = Checks.notBlank())           { it.name }
val email  = column("email",  StringCodec, check = Checks.email())               { it.email }
val age    = column("age",    IntCodec,    check = Checks.between(0, 150))        { it.age }
val status = column("status", StringCodec, check = Checks.oneOf("ACTIVE","INACTIVE","PENDING")) { it.status }
val bio    = column("bio",    StringCodec, check = Checks.length(max = 500))      { it.bio }
val code   = column("code",   StringCodec, check = Checks.matches("^[A-Z]{3}$")) { it.code }
```

### Composing constraints

```kotlin
// All conditions must hold (AND)
val name = column("name", StringCodec,
    check = Checks.all(Checks.notBlank(), Checks.length(max = 100))) { it.name }

// Any condition must hold (OR)
val contact = column("contact", StringCodec,
    check = Checks.any(Checks.email(), Checks.matches("^\\+[0-9]+$"))) { it.contact }
```

### Custom expressions

For constraints not covered by the built-in helpers:

```kotlin
val score = column("score", IntCodec,
    check = { col -> "\"$col\" > 0 AND \"$col\" % 5 = 0" }) { it.score }
```

### Available Checks helpers

| Helper | Example SQL expression |
|--------|------------------------|
| `Checks.notBlank()` | `trim("col") <> ''` |
| `Checks.length(min, max)` | `char_length("col") >= 2 AND char_length("col") <= 100` |
| `Checks.matches(pattern)` | `"col" ~ '^[A-Z]+'` |
| `Checks.matchesIgnoreCase(p)` | `"col" ~* '^[a-z]+'` |
| `Checks.email()` | `"col" ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' AND char_length("col") <= 255` |
| `Checks.uuid()` | `"col" ~ '^[0-9a-f]{8}-...'` |
| `Checks.tsid()` | `char_length("col") = 13 AND "col" ~ '^[0-9A-HJKMNP-TV-Z]{13}$'` |
| `Checks.ulid()` | `char_length("col") = 26 AND "col" ~ '^[0-9A-HJKMNP-TV-Z]{26}$'` |
| `Checks.oneOf("A","B")` | `"col" IN ('A', 'B')` |
| `Checks.between(1, 100)` | `"col" BETWEEN 1 AND 100` |
| `Checks.positive()` | `"col" > 0` |
| `Checks.nonNegative()` | `"col" >= 0` |
| `Checks.all(...)` | `(expr1) AND (expr2) AND ...` |
| `Checks.any(...)` | `(expr1) OR (expr2) OR ...` |

## Reading rows — `required` and `nullable`

`Column` exposes two concise methods for use inside `fromRow`:

| Method | Returns | Use when |
|--------|---------|----------|
| `col.required(row)` | `V` (non-null) | column is `NOT NULL` — throws if the database returns null |
| `col.nullable(row)` | `V?` | column is nullable — returns null without throwing |

```kotlin
override fun fromRow(row: Row) = User(
    id        = id.required(row),        // NOT NULL — throws on null
    email     = email.required(row),
    name      = name.required(row),
    bio       = bio.nullable(row),       // nullable — returns null safely
    createdAt = createdAt.required(row),
)
```

Both methods are aliases for `readRequired` and `read` respectively — there is no
behavioural difference. `readRequired` and `read` remain available for
backward-compatibility.

### Function references

Because `required` and `nullable` are regular methods, they can be used as Kotlin
function references wherever a `(Row) -> T` is expected:

```kotlin
val readId:    (Row) -> UserId   = UsersTable.id::required
val readBio:   (Row) -> String?  = UsersTable.bio::nullable

// Map a raw row list without repeating table boilerplate:
val users = rawRows.map { row ->
    User(id = readId(row), bio = readBio(row), ...)
}
```

## ID generation — TSID and ULID

Aggo ships with time-sortable ID generators.

```kotlin
import com.aggitech.aggo.schema.ids.Tsid
import com.aggitech.aggo.schema.ids.Ulid

// TSID: 13 uppercase Crockford base-32 characters, time-sortable
val id: Tsid = Tsid.generate()     // e.g. "01HZ7Y3XKWPQR"
val str = id.value                 // String representation
val parsed = Tsid.parse("01HZ7Y3XKWPQR")

// ULID: 26 uppercase Crockford base-32 characters, time-sortable
val id: Ulid = Ulid.generate()
val str = id.value
```

Use `TsidCodec` / `UlidCodec` with a `ValueClassCodec` wrapper for typed ID columns:

```kotlin
@JvmInline value class OrderId(val value: String)
val OrderIdCodec = ValueClassCodec(TsidCodec, { OrderId(it.value) }, { Tsid.parse(it.value) })

object OrdersTable : Table<Order>("orders") {
    val id = column("id", OrderIdCodec, isPrimaryKey = true, check = Checks.tsid()) { it.id }
    ...
}
```

## MigratableCodec — custom PostgreSQL DDL types

By default, `PostgresDialect` maps every codec to a standard SQL type (TEXT,
INTEGER, etc.) based on the R2DBC driver type. Implement `MigratableCodec` when
you want a column to use a **PostgreSQL ENUM** or **DOMAIN** type instead.

```kotlin
import com.aggitech.aggo.schema.MigratableCodec

enum class Status { ACTIVE, INACTIVE, SUSPENDED }

object StatusCodec : MigratableCodec<Status> {
    override val sqlType     = String::class.java   // what the R2DBC driver sees
    override val ddlTypeName = "status_type"        // used in CREATE TABLE / ALTER TABLE
    override val createDdl   =                      // emitted before CREATE TABLE
        "CREATE TYPE status_type AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');"

    override fun encode(value: Status?): Any? = value?.name
    override fun decode(raw: Any?): Status? =
        (raw as? String)?.let { Status.valueOf(it) }
}
```

Use it like any codec in a table definition:

```kotlin
object OrdersTable : Table<Order>("orders") {
    val id     = column("id",     TsidCodec,   isPrimaryKey = true) { it.id }
    val status = column("status", StatusCodec)                      { it.status }

    override fun fromRow(row: Row) = Order(
        id     = id.required(row),
        status = status.required(row),
    )
}
```

The migration generator automatically emits the `CREATE TYPE` statement before
the `CREATE TABLE` that references it. See [Migration Generation](06-migrations.md)
for the full workflow including diffing and snapshot storage.

### DOMAIN types

The same pattern works for PostgreSQL DOMAIN types:

```kotlin
object EmailCodec : MigratableCodec<Email> {
    override val sqlType     = String::class.java
    override val ddlTypeName = "email_domain"
    override val createDdl   = """
        CREATE DOMAIN email_domain AS TEXT
            CHECK (VALUE ~* '^[^@\s]+@[^@\s]+\.[^@\s]+${'$'}' AND char_length(VALUE) <= 255);
    """.trimIndent()

    override fun encode(value: Email?): Any? = value?.toString()
    override fun decode(raw: Any?): Email? = (raw as? String)?.let { Email.of(it) }
}
```

### Externally managed types

Set `createDdl = null` when the type already exists in the database and should
not be created by the migration generator:

```kotlin
object LegacyStatusCodec : MigratableCodec<LegacyStatus> {
    override val sqlType     = String::class.java
    override val ddlTypeName = "legacy_status_enum"  // referenced in DDL
    override val createDdl: String? = null           // type pre-exists, no CREATE emitted

    override fun encode(value: LegacyStatus?): Any? = value?.name
    override fun decode(raw: Any?): LegacyStatus? = (raw as? String)?.let { LegacyStatus.valueOf(it) }
}
```
