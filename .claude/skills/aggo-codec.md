---
name: aggo-codec
description: Guide for adding new Codecs in Aggo — built-in primitive codecs, ValueClassCodec for @JvmInline value classes, MigratableCodec for custom SQL types, and enumNameCodec. Use when creating a new Codec or diagnosing encode/decode issues.
---

# Aggo Codec Skill

You are adding or fixing a `Codec<V>` in Aggo — the single encode/decode boundary between Kotlin domain types and the R2DBC driver.

## Codec contract

```kotlin
interface Codec<V> {
    val sqlType: Class<*>   // concrete Java type the R2DBC driver accepts
    fun encode(value: V?): Any?   // domain → driver (Binder calls this)
    fun decode(raw: Any?): V?     // driver → domain (Column.read calls this)
}
```

**Key invariant (from Binder):** `encode(null)` is **never called** — `Binder.bindOne` calls `statement.bindNull(index, codec.sqlType)` before `encode()`. Codecs only receive non-null values in encode. Do not write null-guards in `encode`.

`decode` receives raw driver values and must return `null` for a null input.

## Adding a built-in primitive codec

File: `src/main/kotlin/com/aggitech/aggo/schema/Codec.kt`

```kotlin
object FooCodec : Codec<Foo> {
    override val sqlType: Class<*> = Foo::class.java   // or javaObjectType for primitives
    override fun encode(value: Foo?): Any? = value
    override fun decode(raw: Any?): Foo? = raw as? Foo
}
```

Rules:
- `sqlType` must be the **concrete driver type** (e.g. `Int::class.javaObjectType`, not `Object::class.java`).
- Use `.javaObjectType` for Kotlin primitives (Int, Long, Short, Float, Double, Boolean) — this gives the boxed type that R2DBC expects.
- For types that may come back from the driver as a different class, use a `when` in `decode` (see `BooleanCodec`, `BigDecimalCodec`).

## ValueClassCodec — mandatory for @JvmInline value classes

Without `ValueClassCodec`, the Kotlin compiler passes the wrapper class to `Statement.bind()` and the R2DBC driver crashes.

```kotlin
@JvmInline value class Email(val value: String)

val EmailCodec: Codec<Email> = ValueClassCodec(
    raw    = StringCodec,       // underlying primitive codec
    wrap   = ::Email,           // raw → domain (validation happens here — throw if invalid)
    unwrap = Email::value,      // domain → raw (must never throw for a valid value)
)
```

`sqlType` is inherited from `raw`. The driver only ever sees the raw type.

```kotlin
@JvmInline value class Money(val cents: Long)

val MoneyCodec: Codec<Money> = ValueClassCodec(
    raw    = LongCodec,
    wrap   = ::Money,
    unwrap = Money::cents,
)
```

## MigratableCodec — for custom PostgreSQL types (ENUM, DOMAIN, etc.)

Implement `MigratableCodec<V>` when the codec maps to a custom PG type that needs its own `CREATE TYPE` DDL emitted in the migration plan.

```kotlin
class PgStatusCodec : MigratableCodec<Status> {
    override val sqlType: Class<*> = String::class.java
    override val ddlTypeName: String = "status_type"
    override val createDdl: String = "CREATE TYPE status_type AS ENUM ('PENDING','ACTIVE','CLOSED');"
    override fun encode(value: Status?): Any? = value?.name
    override fun decode(raw: Any?): Status? = raw?.toString()?.let { Status.valueOf(it) }
}
```

`migrationSchema()` scans all column codecs for `MigratableCodec` instances and emits their `createDdl` as `CREATE TYPE` steps before any `CREATE TABLE`.

## enumNameCodec — inline enum by Enum.name

For enums stored as VARCHAR constrained via `Checks.oneOf(...)`. No custom type needed.

```kotlin
enum class Role { ADMIN, EDITOR, VIEWER }
val RoleCodec: Codec<Role> = enumNameCodec<Role>()
// encode: value?.name ("ADMIN", "EDITOR", …)
// decode: raw?.toString()?.let { enumValueOf<Role>(it) }
```

In a table, use the `enumName<E>(name)` builder which wires up the codec and check automatically:

```kotlin
val role = enumName<Role>("role").required { it.role }
```

## Built-in codecs summary

| Codec | Kotlin type | Driver type | PG DDL type |
|-------|-------------|-------------|-------------|
| `StringCodec` | `String` | `String` | `TEXT` |
| `IntCodec` | `Int` | `Integer` | `INTEGER` |
| `LongCodec` | `Long` | `Long` | `BIGINT` |
| `ShortCodec` | `Short` | `Short` | `SMALLINT` |
| `FloatCodec` | `Float` | `Float` | `REAL` |
| `DoubleCodec` | `Double` | `Double` | `DOUBLE PRECISION` |
| `BooleanCodec` | `Boolean` | `Boolean` | `BOOLEAN` |
| `BigDecimalCodec` | `BigDecimal` | `BigDecimal` | `NUMERIC` |
| `UuidCodec` | `UUID` | `UUID` | `UUID` |
| `InstantCodec` | `Instant` | `OffsetDateTime` | `TIMESTAMPTZ` |
| `LocalDateTimeCodec` | `LocalDateTime` | `LocalDateTime` | `TIMESTAMP` |
| `LocalDateCodec` | `LocalDate` | `LocalDate` | `DATE` |
| `ByteArrayCodec` | `ByteArray` | `ByteArray` | `BYTEA` |
| `UlidCodec` | `Ulid` | `String` | `TEXT` |
| `TsidCodec` | `Tsid` | `String` | `VARCHAR(13)` |

## Where codecs are called — do not violate this

- `encode()` is called **only** by `Binder.bindOne` (in `runtime/Binder.kt`).
- `decode()` is called **only** by `Column.read(row)` / `Column.readRequired(row)`.

Never call `codec.encode()` or `codec.decode()` directly in production code.

## Testing a new codec

Add a round-trip test in `RendererTest` (unit, no I/O) and a real-driver test in `IntegrationTest`.
