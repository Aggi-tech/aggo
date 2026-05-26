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
| `FloatCodec` | `Float` | `REAL` |
| `DoubleCodec` | `Double` | `DOUBLE PRECISION` |
| `BooleanCodec` | `Boolean` | `BOOLEAN` |
| `BigDecimalCodec` | `BigDecimal` | `NUMERIC` |
| `InstantCodec` | `Instant` | `TIMESTAMPTZ` |
| `LocalDateTimeCodec` | `LocalDateTime` | `TIMESTAMP` |
| `LocalDateCodec` | `LocalDate` | `DATE` |
| `UuidCodec` | `UUID` | `UUID` |
| `ByteArrayCodec` | `ByteArray` | `BYTEA` |
| `TsidCodec` | `Tsid` | `TEXT` (13-char Crockford base-32) |
| `UlidCodec` | `Ulid` | `TEXT` (26-char Crockford base-32) |

## Typed column builders (sized SQL types)

For most columns you want a precise SQL type — `VARCHAR(100)` instead of `TEXT`,
`NUMERIC(12, 2)` instead of unbounded `NUMERIC`, `SMALLINT` instead of
`INTEGER`. Use the typed column builders directly instead of `column(...)`:

```kotlin
object UsersTable : Table<User>("users") {
    val id        = uuid("id", isPrimaryKey = true)                      { it.id }
    val email     = varchar("email", length = 255, check = Checks.email()) { it.email }
    val name      = varchar("name", length = 100, check = Checks.notBlank()) { it.name }
    val bio       = text("bio", isNullable = true)                       { it.bio }
    val balance   = decimal("balance", precision = 12, scale = 2)        { it.balance }
    val followers = integer("followers", check = Checks.nonNegative())   { it.followers }
    val priority  = smallint("priority")                                 { it.priority }
    val active    = boolean("active")                                    { it.active }
    val createdAt = timestamptz("created_at", isGenerated = true)        { it.createdAt }
    val birthday  = date("birthday", isNullable = true)                  { it.birthday }
    ...
}
```

Each builder produces the exact DDL — no fallback type mapping happens:

```sql
"id" UUID NOT NULL,
"email" VARCHAR(255) NOT NULL,
"name" VARCHAR(100) NOT NULL,
"bio" TEXT,
"balance" NUMERIC(12, 2) NOT NULL,
"followers" INTEGER NOT NULL,
"priority" SMALLINT NOT NULL,
"active" BOOLEAN NOT NULL,
"created_at" TIMESTAMPTZ NOT NULL,
"birthday" DATE,
```

| Builder | Kotlin type | SQL type |
|---------|-------------|----------|
| `varchar(name, length)` | `String` | `VARCHAR(length)` |
| `text(name)` | `String` | `TEXT` |
| `integer(name)` | `Int` | `INTEGER` |
| `bigint(name)` | `Long` | `BIGINT` |
| `smallint(name)` | `Short` | `SMALLINT` |
| `real(name)` | `Float` | `REAL` |
| `doublePrecision(name)` | `Double` | `DOUBLE PRECISION` |
| `decimal(name, precision, scale)` | `BigDecimal` | `NUMERIC(precision, scale)` |
| `numeric(...)` | `BigDecimal` | _alias for `decimal`_ |
| `boolean(name)` | `Boolean` | `BOOLEAN` |
| `uuid(name)` | `UUID` | `UUID` |
| `timestamptz(name)` | `Instant` | `TIMESTAMPTZ` |
| `timestamp(name)` | `LocalDateTime` | `TIMESTAMP` |
| `date(name)` | `LocalDate` | `DATE` |
| `bytea(name)` | `ByteArray` | `BYTEA` |
| `tsid(name)` | `Tsid` | `VARCHAR(13)` + `Checks.tsid()` constraint |
| `ulid(name)` | `Ulid` | `VARCHAR(26)` + `Checks.ulid()` constraint |

`varchar`, `text`, `uuid`, `tsid`, `ulid`, and `decimal` ship with an overload
accepting an explicit `codec: Codec<V>` for value-class domain types — the
SQL type stays the same while the Kotlin type follows the codec:

```kotlin
val UserIdCodec = ValueClassCodec(StringCodec, ::UserId, UserId::value)
val id = varchar("id", length = 30, codec = UserIdCodec, isPrimaryKey = true) { it.id }
```

When you need a SQL type the builders don't cover (a domain type, an
extension type, a vendor-specific keyword), pass `sqlType =` to the generic
`column(...)`. The string is validated against an allowlist to block
injection through this surface.

```kotlin
val embedding = column("embedding", ByteArrayCodec, sqlType = "VECTOR(1536)") { it.embedding }
```

## Fluent schema builders

The typed builders also support a fluent declaration style. This keeps
nullability, primary keys, checks, unique constraints, and foreign keys close to
the column type, similar to validation libraries such as Zod.

```kotlin
private enum class LeadStatus { NEW, QUALIFIED, LOST }

object Leads : Table<Lead>("leads") {
    val id = varchar("id", 26)
        .required()
        .primaryKey()
        .check(Checks.ulid(), key = "lead.id.invalid") { it.id }

    val email = varchar("email", 255)
        .required()
        .unique(key = "lead.email.taken")
        .check(Checks.email(), key = "lead.email.invalid") { it.email }

    val status = enumName<LeadStatus>("status")
        .required() { it.status }

    val notes = text("notes")
        .optional() { it.notes }

    val estimatedValue = decimal("estimated_value", precision = 12, scale = 2)
        .optional()
        .check(Checks.nonNegative(), key = "lead.estimated_value.negative") { it.estimatedValue }

    override fun fromRow(row: Row) = Lead(
        id = id.required(row),
        email = email.required(row),
        status = status.required(row),
        notes = notes.nullable(row),
        estimatedValue = estimatedValue.nullable(row),
    )
}
```

The chain is finalized by passing the entity getter to any terminal form:

```kotlin
val name = varchar("name", 160).required() { it.name }
val slug = varchar("slug", 80).required().unique() { it.slug }
val bio  = text("bio").optional() { it.bio }
```

You can also use `.map { ... }` when that reads better:

```kotlin
val ownerId = varchar("owner_id", 26)
    .required()
    .check(Checks.ulid())
    .map { it.ownerId }
```

### Fluent modifiers

| Modifier | Effect |
|----------|--------|
| `required()` | Emits `NOT NULL`; pairs naturally with `column.required(row)` |
| `optional()` | Omits `NOT NULL`; pairs naturally with `column.nullable(row)` |
| `primaryKey()` | Adds the column to `PRIMARY KEY (...)` and marks it not-null |
| `generated()` | Skips the column in entity INSERTs |
| `sensitive()` | Redacts values in query logs |
| `check(expr, name, key)` | Adds a CHECK constraint with optional database name and error key |
| `unique(name, key)` | Adds a single-column UNIQUE constraint |
| `references(target, ..., key)` | Adds a FOREIGN KEY constraint |

The old constructor-style API remains supported, so schemas can be migrated
incrementally:

```kotlin
val legacy = column("legacy", StringCodec, check = Checks.notBlank()) { it.legacy }
val fluent = varchar("fluent", 120).required().check(Checks.notBlank()) { it.fluent }
```

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

In fluent declarations, use `.check(...)`:

```kotlin
val email = varchar("email", 255)
    .required()
    .check(Checks.email(), key = "user.email.invalid") { it.email }
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

## UNIQUE constraints

Use `.unique()` in the fluent builder to declare a single-column UNIQUE
constraint.

```kotlin
val email = varchar("email", 255)
    .required()
    .unique(key = "user.email.taken")
    .check(Checks.email(), key = "user.email.invalid") { it.email }
```

Generated DDL:

```sql
CONSTRAINT "uq_users_email" UNIQUE ("email")
```

The optional `name` controls the database constraint name. The optional `key`
is application-facing metadata used by typed error mapping.

```kotlin
val handle = varchar("handle", 40)
    .required()
    .unique(name = "uq_users_handle", key = "user.handle.taken") { it.handle }
```

## Foreign keys with error keys

Foreign keys can be declared in the fluent chain and can also carry an
application-facing error key.

```kotlin
object Profiles : Table<Profile>("profiles") {
    val userId = varchar("user_id", 26)
        .required()
        .references(
            Users.id,
            onDelete = ForeignKeyAction.CASCADE,
            key = "profile.user.missing",
        ) { it.userId }
}
```

The database constraint still defaults to `fk_<table>_<column>`, for example
`fk_profiles_user_id`. The key is only used when mapping database errors.

## Constraint error mapping

Checks, unique constraints, and foreign keys can be mapped to typed errors by
building a `ConstraintErrorMap` from your table descriptors:

```kotlin
val errors = constraintErrorMap(Users, Profiles)

val result = aggo.transaction(errors) {
    insert(Users, user)
}

result.fold(
    onSuccess = { rows -> rows },
    onFailure = { error ->
        when (error) {
            is ConstraintError -> error.key
            is DatabaseError -> "database.error"
            else -> "unknown.error"
        }
    },
)
```

Use stable keys such as `user.email.taken` or `profile.user.missing` in your API
responses instead of exposing database constraint names.

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

## DomainType — native PostgreSQL DOMAIN types

A PostgreSQL `DOMAIN` is a named type that wraps an existing one and attaches
reusable `CHECK` constraints. Use `DomainType.create` to build a codec backed
by a DOMAIN; the migration generator emits the `CREATE DOMAIN …` statement
before any `CREATE TABLE` that references it.

```kotlin
@JvmInline value class Slug(val raw: String)

val SlugCodec = DomainType.create(
    name        = "slug_domain",
    base        = StringCodec,
    sqlBaseType = "VARCHAR(64)",
    wrap        = ::Slug,
    unwrap      = Slug::raw,
    checks      = listOf(Checks.notBlank(), Checks.length(min = 3, max = 64)),
)

object PostsTable : Table<Post>("posts") {
    val id   = uuid("id", isPrimaryKey = true) { it.id }
    val slug = column("slug", SlugCodec)        { it.slug }
    ...
}
```

Generated DDL:

```sql
DO $do$ BEGIN
    CREATE DOMAIN "slug_domain" AS VARCHAR(64)
        CHECK ((trim(VALUE) <> '') AND (char_length(VALUE) >= 3 AND char_length(VALUE) <= 64));
EXCEPTION WHEN duplicate_object THEN NULL; END $do$;

CREATE TABLE "posts" (
    "id" UUID NOT NULL,
    "slug" slug_domain NOT NULL,
    ...
);
```

The same `Checks.*` helpers used on regular columns work inside `DomainType.create`
— Aggo rewrites the column-name placeholder to PostgreSQL's `VALUE` keyword so
the constraint applies to the value being inserted into the DOMAIN.

Set `createIfMissing = false` if you prefer the bare `CREATE DOMAIN …;` form
(useful when each migration runs once and idempotency is handled upstream).

For DOMAINs that don't wrap a value class, use `DomainType.createSimple`:

```kotlin
val PositiveIntCodec = DomainType.createSimple(
    name        = "positive_int",
    base        = IntCodec,
    sqlBaseType = "INTEGER",
    checks      = listOf(Checks.positive()),
)
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
