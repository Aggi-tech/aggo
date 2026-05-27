---
name: aggo-schema-table
description: Guide for declaring Table<E> objects in Aggo — column builders (typed/fluent/legacy), value-class columns, nullable vs required, primary keys, generated columns, check constraints, unique constraints, indexes, foreign keys, and fromRow. Use when creating or modifying a Table definition.
---

# Aggo Table Schema Skill

You are defining or modifying an Aggo `Table<E>` — the compile-time metadata descriptor for a PostgreSQL table.

## Skeleton

```kotlin
object UsersTable : Table<User>("users") {

    val id     = tsid("id").primaryKey() { it.id }
    val email  = varchar("email", 255)
                    .unique(key = "email_taken")
                    .check(Checks.email())
                    .required { it.email }
    val name   = varchar("name", 100)
                    .check(Checks.all(Checks.notBlank(), Checks.length(max = 100)))
                    .required { it.name }
    val active = boolean("active").required { it.active }

    override fun fromRow(row: Row): User = User(
        id     = id.readRequired(row),
        email  = email.readRequired(row),
        name   = name.readRequired(row),
        active = active.readRequired(row),
    )
}
```

## Column builder methods (fluent API — preferred)

Each typed method returns a `ColumnBuilder` that you chain before calling `.required { }` or `.optional { }` to register the column.

| Builder | SQL type | Kotlin type |
|---------|----------|-------------|
| `tsid(name)` | `VARCHAR(13)` + tsid CHECK | `Tsid` |
| `ulid(name)` | `VARCHAR(26)` + ulid CHECK | `Ulid` |
| `uuid(name)` | `UUID` | `UUID` |
| `varchar(name, length)` | `VARCHAR(n)` | `String` |
| `text(name)` | `TEXT` | `String` |
| `integer(name)` | `INTEGER` | `Int` |
| `bigint(name)` | `BIGINT` | `Long` |
| `smallint(name)` | `SMALLINT` | `Short` |
| `boolean(name)` | `BOOLEAN` | `Boolean` |
| `decimal(name, precision, scale)` | `NUMERIC(p,s)` | `BigDecimal` |
| `timestamptz(name)` | `TIMESTAMPTZ` | `Instant` |
| `timestamp(name)` | `TIMESTAMP` | `LocalDateTime` |
| `date(name)` | `DATE` | `LocalDate` |
| `bytea(name)` | `BYTEA` | `ByteArray` |
| `enumName<E>(name)` | `VARCHAR(64)` + oneOf CHECK | `E : Enum<E>` |

All builders accept a custom `codec` overload for value-class columns, e.g. `varchar("email", 255, EmailCodec)`.

## Fluent builder chain options

```kotlin
// nullable column
val bio = text("bio").optional { it.bio }

// not-null column (most common)
val name = varchar("name", 100).required { it.name }

// primary key (always not-null)
val id = tsid("id").primaryKey() { it.id }

// database-generated column (INSERT skips it)
val createdAt = timestamptz("created_at").generated().required { it.createdAt }

// sensitive — value is redacted in query logs
val token = varchar("token", 64).sensitive().required { it.token }

// check constraint
val score = integer("score").check(Checks.between(0, 100)).required { it.score }

// unique constraint with error-map key
val email = varchar("email", 255).unique(key = "email_taken").required { it.email }

// index
val createdAt = timestamptz("created_at").index().required { it.createdAt }

// GIN index (full-text / JSONB)
val body = text("body").index(method = IndexMethod.GIN).required { it.body }

// foreign key
val customerId = tsid("customer_id", CustomerIdCodec)
    .references(CustomersTable.id, onDelete = ForeignKeyAction.CASCADE)
    .required { it.customerId }
```

## Value-class columns

`@JvmInline value class` types **must** use `ValueClassCodec` — the Kotlin compiler passes the wrapper to the R2DBC driver otherwise, causing a crash.

```kotlin
@JvmInline value class Email(val value: String)
val EmailCodec = ValueClassCodec(StringCodec, ::Email, Email::value)

// In the table:
val email = varchar("email", 255, EmailCodec).required { it.email }
```

## Enum columns

```kotlin
enum class Status { PENDING, ACTIVE, CLOSED }

val status = enumName<Status>("status").required { it.status }
// → VARCHAR(64) with CHECK IN ('PENDING', 'ACTIVE', 'CLOSED')
```

## Foreign key with onDelete

```kotlin
val orderId = tsid("order_id", OrderIdCodec)
    .references(OrdersTable.id, onDelete = ForeignKeyAction.CASCADE)
    .required { it.orderId }
```

Available actions: `RESTRICT` (default), `CASCADE`, `SET_NULL`, `SET_DEFAULT`, `NO_ACTION`.

## fromRow implementation rules

- Call `col.readRequired(row)` for non-null columns — throws if the value is null.
- Call `col.read(row)` for nullable columns — returns `null` safely.
- Never use `row.get(name, SomeClass::class.java)` directly — always go through the column descriptor.

## CHECK constraint helpers (Checks.kt)

```kotlin
Checks.notBlank()                   // trim(col) <> ''
Checks.length(min = 1, max = 100)   // char_length bounds
Checks.matches("^[A-Z0-9]+$")       // POSIX ~
Checks.matchesIgnoreCase("pattern") // POSIX ~*
Checks.oneOf("A", "B", "C")         // IN (...)
Checks.between(0, 100)              // BETWEEN
Checks.positive()                   // > 0
Checks.nonNegative()                // >= 0
Checks.tsid()                       // 13-char Crockford base32
Checks.email()                      // loose format + 255 chars
Checks.uuid()                       // 8-4-4-4-12 hex
Checks.ulid()                       // 26-char Crockford base32
Checks.all(Checks.notBlank(), Checks.length(max = 100))  // AND
Checks.any(...)                     // OR
```

## What NOT to do

- Do not add DDL methods to `Table` — DDL belongs in `migration/`.
- Do not call `column()` from outside the Table object body.
- Do not mutate `columns` after construction — `Table` is a singleton.
- Do not use reflection or `KClass` anywhere in schema code.
