# spec-7 — Developer Ergonomics: Migration Tasks, Row-mapping Shortcuts, Custom DDL Types

> Status: **proposed**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

---

## 1. Goals

1. **E-1 — Maven Migration Task**: eliminate the boilerplate `main()` every consuming project must write and provide a standard exec-maven-plugin recipe so `mvn aggo:migrate` is a one-liner.
2. **E-2 — Row-mapping shortcuts**: replace `col.readRequired(row)` / `col.read(row)` with `col.required(row)` / `col.nullable(row)` — shorter names that double as Kotlin function references (`col::required`, `col::nullable`).
3. **E-3 — Custom DDL types**: let consuming projects map custom `Codec` implementations to PostgreSQL enum or domain types via a `MigratableCodec` interface, so `MigrationGenerator` can emit the correct DDL without hardcoding every mapping in `PostgresDialect`.

---

## 2. Non-goals

- No standalone `aggo-maven-plugin` Maven plugin module (would require a separate artifact lifecycle; exec-maven-plugin covers the need).
- No dynamic `fromRow` builder that infers the entity constructor via reflection.
- No auto-generation of Kotlin data classes from a live database schema.
- No runtime type mapping for R2DBC — `MigratableCodec` affects DDL only, not query execution.

---

## 3. E-1 — Maven Migration Task

### 3.1 Pain

Every consuming project currently writes:

```kotlin
fun main(args: Array<String>) {
    AggoMigrate.generate(
        tables        = listOf(UsersTable, OrdersTable),
        dialect       = PostgresDialect,
        snapshotFile  = Paths.get("src/main/resources/aggo/snapshot.json"),
        migrationsDir = Paths.get("src/main/resources/aggo/migrations"),
        migrationName = args.firstOrNull(),
    )
}
```

This is pure ceremony: every project uses the same paths, the same dialect, the same arg-parsing pattern.

### 3.2 New API — `migration/AggoMigrateTask.kt`

```kotlin
abstract class AggoMigrateTask {
    abstract val tables: List<Table<*>>
    abstract val dialect: MigrationDialect

    open val snapshotFile: Path
        get() = Paths.get(
            System.getProperty("aggo.snapshotFile", "src/main/resources/aggo/snapshot.json")
        )

    open val migrationsDir: Path
        get() = Paths.get(
            System.getProperty("aggo.migrationsDir", "src/main/resources/aggo/migrations")
        )

    fun runFromArgs(args: Array<String>) {
        val name = args.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("aggo.name")?.takeIf { it.isNotBlank() }
        AggoMigrate.generate(tables, dialect, snapshotFile, migrationsDir, name)
    }
}
```

### 3.3 Consuming-project usage

```kotlin
// db/Migrations.kt
object Migrations : AggoMigrateTask() {
    override val tables  = listOf(UsersTable, OrdersTable)
    override val dialect = PostgresDialect
}

fun main(args: Array<String>) = Migrations.runFromArgs(args)
```

```xml
<!-- pom.xml — standard recipe (copy-paste) -->
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.4.1</version>
  <executions>
    <execution>
      <id>aggo-migrate</id>
      <goals><goal>java</goal></goals>
      <configuration>
        <mainClass>com.example.db.MigrationsKt</mainClass>
      </configuration>
    </execution>
  </executions>
</plugin>
```

```bash
# Generate with optional name label
mvn compile exec:java -Daggo.name=add_orders_table

# Override paths when needed
mvn compile exec:java \
  -Daggo.snapshotFile=infra/db/snapshot.json \
  -Daggo.migrationsDir=infra/db/migrations
```

### 3.4 Convention overrides via system properties

| Property | Default |
|---|---|
| `aggo.name` | _(none — timestamp only)_ |
| `aggo.snapshotFile` | `src/main/resources/aggo/snapshot.json` |
| `aggo.migrationsDir` | `src/main/resources/aggo/migrations` |

`snapshotFile` and `migrationsDir` can also be overridden by overriding the open properties directly on the `object`.

---

## 4. E-2 — Row-mapping shortcuts

### 4.1 Pain

```kotlin
override fun fromRow(row: Row): User = User(
    id     = id.readRequired(row),
    email  = email.readRequired(row),
    name   = name.readRequired(row),
    active = active.read(row),
)
```

`readRequired` is 12 characters. With four or more columns the noise dominates the signal. Neither `read` nor `readRequired` can be used as a Kotlin function reference in pipeline-style code.

### 4.2 New API — additions to `schema/Column.kt`

```kotlin
/**
 * Shorthand for [readRequired]. Use when the column is NOT NULL.
 * Enables function-reference form: `id::required`.
 */
fun required(row: Row): V = readRequired(row)

/**
 * Shorthand for [read]. Use when the column is nullable.
 * Enables function-reference form: `active::nullable`.
 */
fun nullable(row: Row): V? = read(row)
```

`read` and `readRequired` are kept. They are not deprecated — they remain valid for code that was written against them.

### 4.3 Updated fromRow pattern

```kotlin
override fun fromRow(row: Row): User = User(
    id     = id.required(row),
    email  = email.required(row),
    name   = name.required(row),
    active = active.nullable(row),
)
```

### 4.4 Function-reference form

Because `required` and `nullable` are regular methods on `Column<E, V>`, bound method references are valid `(Row) -> V` and `(Row) -> V?` values:

```kotlin
val readId:     (Row) -> UserId   = UsersTable.id::required
val readActive: (Row) -> Boolean? = UsersTable.active::nullable

// Usable anywhere a (Row) -> T mapper is expected
val users = rows.map { row ->
    User(
        id     = readId(row),
        active = readActive(row),
    )
}
```

---

## 5. E-3 — Custom DDL types

### 5.1 Pain

`PostgresDialect.columnSqlType` maps `Codec.sqlType` (a Java `Class<*>`) to a Postgres SQL type string. This is a closed mapping — custom codecs that want a PostgreSQL `ENUM` or `DOMAIN` type must either override `PostgresDialect` or live with `TEXT` in the generated DDL.

### 5.2 New interface — `migration/MigratableCodec.kt`

```kotlin
/**
 * Optional extension for [Codec] implementations that want to control
 * their own PostgreSQL DDL type.
 *
 * Implement this on any [Codec] (including [ValueClassCodec] wrappers) to
 * declare a custom SQL type name — for example a Postgres ENUM or DOMAIN.
 *
 * [createDdl] is emitted once per migration plan, before any CREATE TABLE
 * statement that references the type. Use `CREATE TYPE … IF NOT EXISTS` or
 * `CREATE DOMAIN … IF NOT EXISTS` to make it idempotent.
 *
 * Example:
 * ```kotlin
 * object StatusCodec : MigratableCodec<Status> {
 *     override val sqlType    = String::class.java
 *     override val ddlTypeName = "status_type"
 *     override val createDdl  = "CREATE TYPE status_type AS ENUM ('ACTIVE','INACTIVE');"
 *     override fun encode(v: Status?) = v?.name
 *     override fun decode(raw: Any?) = raw?.let { Status.valueOf(it as String) }
 * }
 * ```
 */
interface MigratableCodec<V> : Codec<V> {
    /** The SQL type name to use in column DDL, e.g. `"status_type"` or `"email_domain"`. */
    val ddlTypeName: String

    /**
     * Full DDL statement that creates the type. Emitted before the first
     * CREATE TABLE that references it. Must be idempotent.
     * Return null if the type is pre-existing or managed externally.
     */
    val createDdl: String? get() = null
}
```

### 5.3 `MigrationDialect.columnSqlType` — resolution order

`PostgresDialect.columnSqlType` consults codecs in this order:

1. If `codec` is `MigratableCodec<*>` → return `codec.ddlTypeName`.
2. If `codec` is `ValueClassCodec<*, *>` and its inner raw codec is `MigratableCodec<*>` → return inner `ddlTypeName`. (`ValueClassCodec.rawCodec` must be exposed as an internal/package-private getter.)
3. Existing closed `when (codec.sqlType)` mapping (unchanged).
4. `UnsupportedOperationException` for unmapped types.

### 5.4 `MigrationSchema` — custom types

`MigrationSchema` gains a `customTypes` list to enable diffing across generations:

```kotlin
data class MigrationSchema(
    val version: String,
    val tables: List<MigrationTable>,
    val customTypes: List<MigrationCustomType> = emptyList(),   // NEW
)

data class MigrationCustomType(
    val name: String,       // ddlTypeName
    val createDdl: String,  // the full DDL statement
)
```

`MigrationSnapshotIO` serialises/deserialises `customTypes` with the existing hand-written JSON path (add `"customTypes": [...]` array; tolerate absence for backwards-compatible snapshot reads).

### 5.5 `MigrationGenerator` — custom type steps

When building a `MigrationPlan`:

1. Collect all distinct `MigratableCodec` instances from the current schema's table columns.
2. Emit `createDdl` (non-null only) as `MigrationStep` entries **before** any `CREATE TABLE` step. Each step has `requiresManualMigration = false`.
3. Diff custom types against the previous snapshot:
   - **New type**: auto-emit `createDdl`.
   - **Changed `createDdl`**: flag as `requiresManualMigration = true` with description `"custom type '$name' DDL changed — review and migrate manually"`.
   - **Dropped type**: flag as `requiresManualMigration = true` with description `"custom type '$name' removed — drop manually if safe"`.

### 5.6 Usage example

```kotlin
object StatusCodec : MigratableCodec<Status> {
    override val sqlType     = String::class.java
    override val ddlTypeName = "status_type"
    override val createDdl   =
        "CREATE TYPE status_type AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');"

    override fun encode(value: Status?): Any? = value?.name
    override fun decode(raw: Any?): Status? =
        (raw as? String)?.let { Status.valueOf(it) }
}

object OrdersTable : Table<Order>("orders") {
    val id     = column("id",     TsidCodec,   isPrimaryKey = true) { it.id }
    val status = column("status", StatusCodec)                      { it.status }

    override fun fromRow(row: Row): Order = Order(
        id     = id.required(row),
        status = status.required(row),
    )
}
```

Generated migration plan (first run):

```sql
-- step 1: custom type
CREATE TYPE status_type AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');

-- step 2: table
CREATE TABLE IF NOT EXISTS "orders" (
    "id"     TEXT        NOT NULL,
    "status" status_type NOT NULL,
    PRIMARY KEY ("id")
);
```

---

## 6. Files changed

| File | Change |
|---|---|
| `migration/AggoMigrateTask.kt` | **New.** Abstract base class with `runFromArgs`, convention-based path resolution. |
| `migration/MigratableCodec.kt` | **New.** `MigratableCodec<V>` interface with `ddlTypeName` + `createDdl`. |
| `migration/MigrationGenerator.kt` | Collect custom type steps; add `customTypes` field to `MigrationSchema`; diff custom types. |
| `migration/MigrationSnapshotIO.kt` | Serialise/deserialise `customTypes` array; tolerate absent field for backwards compatibility. |
| `dialect/MigrationDialect.kt` | Document new resolution order; no interface change. |
| `dialect/PostgresDialect.kt` | Consult `MigratableCodec` and inner `ValueClassCodec.rawCodec` before existing `when` map. |
| `schema/Column.kt` | Add `required(Row): V` and `nullable(Row): V?` methods. |

---

## 7. Tests

| Test | Coverage |
|---|---|
| `RendererTest` (unit) | `col.required(row)` / `col.nullable(row)` round-trip; function reference compiles and returns correct type. |
| `MigrationGeneratorTest` (unit) | Custom type DDL emitted before CREATE TABLE; new/changed/dropped custom type diff behaviour. |
| `MigrationSnapshotIOTest` (unit) | `customTypes` survives JSON round-trip; absent field deserialises as empty list. |
| `AggoMigrateTaskTest` (unit) | `runFromArgs` picks name from args then from system property; respects overridden paths. |
| `IntegrationTest` (Testcontainers) | `MigratableCodec` round-trip against a real Postgres ENUM column. |
