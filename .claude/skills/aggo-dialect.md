---
name: aggo-dialect
description: Guide for adding or modifying an Aggo SQL dialect — implement SqlDialect and MigrationDialect, quoteIdentifier with injection prevention, placeholder generation, columnSqlType mapping, and MigratableCodec integration. Use when adding a new database backend or customising DDL generation.
---

# Aggo SQL Dialect Skill

You are adding a new SQL dialect or modifying `PostgresDialect`. Dialects live in `src/main/kotlin/com/aggitech/aggo/dialect/`.

## Two interfaces

```kotlin
// DML: placeholder generation and identifier quoting — used by Renderers and Session
interface SqlDialect {
    fun placeholder(oneBasedIndex: Int): String
    fun quoteIdentifier(name: String): String
}

// DDL: extends SqlDialect with type mapping — used by MigrationGenerator
interface MigrationDialect : SqlDialect {
    fun columnSqlType(codec: Codec<*>): String
}
```

## Implementing a new dialect

```kotlin
object MyDialect : MigrationDialect {

    override fun placeholder(oneBasedIndex: Int): String = "\$$oneBasedIndex"   // Postgres style
    // MySQL/MariaDB would return "?"

    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)      // MUST call this — blocks identifier injection
        val escaped = name.replace("`", "``")
        return "`$escaped`"               // backtick for MySQL; double-quote for Postgres/ANSI
    }

    override fun columnSqlType(codec: Codec<*>): String {
        if (codec is MigratableCodec<*>) return codec.ddlTypeName   // custom PG types
        return when (codec.sqlType) {
            String::class.java           -> "TEXT"
            Int::class.javaObjectType    -> "INT"
            Long::class.javaObjectType   -> "BIGINT"
            Short::class.javaObjectType  -> "SMALLINT"
            Float::class.javaObjectType  -> "FLOAT"
            Double::class.javaObjectType -> "DOUBLE"
            Boolean::class.javaObjectType-> "TINYINT(1)"
            BigDecimal::class.java       -> "DECIMAL"
            OffsetDateTime::class.java   -> "DATETIME"
            LocalDateTime::class.java    -> "DATETIME"
            LocalDate::class.java        -> "DATE"
            UUID::class.java             -> "CHAR(36)"
            ByteArray::class.java        -> "BLOB"
            else -> throw UnsupportedOperationException(
                "No DDL type mapping for '${codec.sqlType.name}'. Implement MigratableCodec or extend this dialect."
            )
        }
    }
}
```

## requireValidIdentifier — mandatory

`requireValidIdentifier(name)` is defined in `dialect/SqlDialect.kt`. Call it inside **every** `quoteIdentifier` implementation. It validates against `IDENTIFIER_REGEX`:

```kotlin
val IDENTIFIER_REGEX = Regex("^[a-z_][a-z0-9_]{0,62}$")
```

This blocks SQL injection through the identifier surface. PostgresDialect doubles the double-quote character as defence-in-depth in addition to the regex.

**Never skip this call**, even if the name "looks safe". The same validation runs at `Table`/`Column` construction time (fail-fast), but the dialect is the last line of defence in DDL generation.

## PostgresDialect type mapping reference

`PostgresDialect.columnSqlType` checks `MigratableCodec` first, then resolves by `codec.sqlType`:

| Driver type (`Codec.sqlType`) | Postgres DDL |
|-------------------------------|-------------|
| `String` | `TEXT` |
| `Integer` | `INTEGER` |
| `Long` | `BIGINT` |
| `Short` | `SMALLINT` |
| `Float` | `REAL` |
| `Double` | `DOUBLE PRECISION` |
| `Boolean` | `BOOLEAN` |
| `BigDecimal` | `NUMERIC` |
| `OffsetDateTime` | `TIMESTAMPTZ` |
| `LocalDateTime` | `TIMESTAMP` |
| `LocalDate` | `DATE` |
| `UUID` | `UUID` |
| `ByteArray` | `BYTEA` |

Note: `InstantCodec` uses `OffsetDateTime` as its driver type because the Postgres R2DBC driver does not accept `java.time.Instant` directly — hence it maps to `TIMESTAMPTZ`.

## ValueClassCodec and dialect resolution

`ValueClassCodec` delegates `sqlType` to its wrapped `raw` codec. So an `EmailCodec = ValueClassCodec(StringCodec, ...)` has `sqlType = String::class.java` and maps to `TEXT` automatically — no special dialect handling needed.

## Column-level sqlType override (takes precedence over dialect)

`Table.column()` accepts an optional `sqlType: String?` parameter. When set, this string is used verbatim in DDL instead of calling `dialect.columnSqlType(codec)`. The fluent builders (`varchar`, `text`, `integer`, etc.) use this to produce precise DDL (e.g. `VARCHAR(100)` instead of `TEXT`).

If you add a new dialect, columns with an explicit `sqlType` will still use their overrides unchanged — your `columnSqlType` implementation is only called for columns without one.

## Wiring the dialect into AggoPool

```kotlin
val aggo = Aggo(
    pool    = AggoPool(config),
    dialect = MyDialect,
)
```

The dialect flows from `Aggo` → `Session` for DML placeholder generation, and is passed explicitly to `migrationSchema` / `migrationPlan` for DDL generation.

## Testing a new dialect

Add tests in `DialectTest` — unit tests, no I/O. Verify:
1. `quoteIdentifier` correctly wraps identifiers.
2. `quoteIdentifier` throws on identifiers with invalid characters (injection attempts).
3. `columnSqlType` returns the correct string for each supported codec.
4. `placeholder(1)` returns the correct string for the dialect.
