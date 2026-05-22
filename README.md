# aggo — internal Dabble utility lib

Reflection-free Kotlin DSL on top of R2DBC + Postgres. Standalone Maven project; not part of any aggregator. Published to GitHub Packages and consumed by `payment-ms` / `person-ms` as a regular Maven dependency.

## Why it exists

This is a fork-style refactor of [AggORM](https://github.com/yurimoinhos/AggORM) that:

- **Removes all runtime reflection** — required for Quarkus Native (GraalVM).
- **Fixes the upstream "transactions are a no-op" bug** — every query inside `aggo.tx { … }` runs on the same Connection.
- **Fixes R2DBC-Postgres placeholder mismatch** — renderer emits `$1, $2` instead of `?`.
- **Fixes multi-row SELECT** — collects every row instead of `awaitSingle()` (which threw for `n > 1`).
- **Validates identifiers** at the dialect boundary (blocks SQL injection via table/column names).
- **Redacts sensitive log parameters** by default.
- Wraps the official `io.r2dbc.pool.ConnectionPool` instead of the broken in-house pool.

Full audit: see the spec in the parent Dabble plan file.

## Usage

### 1. Declare the schema (manual; no annotations)

```kotlin
import com.aggitech.aggo.schema.*
import io.r2dbc.spi.Row
import java.time.Instant

@JvmInline value class ID(val value: String)
val IdCodec = ValueClassCodec(StringCodec, ::ID, ID::value)

@JvmInline value class Email(val raw: String)
val EmailCodec = ValueClassCodec(StringCodec, ::Email, Email::raw)

data class Payer(
    val id: ID,
    val email: Email,
    val firstName: String,
    val active: Boolean,
    val createdAt: Instant,
)

object Payers : Table<Payer>("payers") {
    val id        = column("id",         IdCodec,     isPrimaryKey = true) { it.id }
    val email     = column("email",      EmailCodec)                       { it.email }
    val firstName = column("first_name", StringCodec)                      { it.firstName }
    val active    = column("active",     BooleanCodec)                     { it.active }
    val createdAt = column("created_at", InstantCodec, isGenerated = true) { it.createdAt }

    override fun fromRow(row: Row) = Payer(
        id        = id.readRequired(row),
        email     = email.readRequired(row),
        firstName = firstName.readRequired(row),
        active    = active.readRequired(row),
        createdAt = createdAt.readRequired(row),
    )
}
```

### 2. Build queries as composable blocks

```kotlin
import com.aggitech.aggo.dsl.*

val q = select(Payers) {
    where { (Payers.active eq true) and (Payers.email like Email("%@gmail.com")) }
    orderBy { Payers.createdAt.desc() }
    limit(50)
}
```

`select`/`insert`/`update`/`delete` all return immutable data classes — pass them around, persist them, compose them.

### 3. Execute on an `Aggo` instance

```kotlin
val aggo = Aggo(AggoPool.postgres(PostgresConfig(
    host = "168.231.96.48",
    database = "dabble",
    user = "dabble",
    password = System.getenv("DB_PASSWORD"),
)))

// Single connection, autocommit:
val list: List<Payer> = aggo.read { session -> session.fetchAll(q) }

// Real transaction — every call below uses the SAME connection:
aggo.tx { session ->
    val newPayer = Payer(ID("01J…"), Email("a@b"), "Anna", true, Instant.now())
    session.insert(Payers, newPayer)
    session.update(update(Payers) {
        Payers.active setTo false
        where { Payers.id eq ID("01H…") }
    })
}
```

## Quarkus integration

The lib does not depend on Quarkus. Each microservice declares its own producer (≈ 15 lines):

```kotlin
@ApplicationScoped
class AggoProducer {
    @ConfigProperty(name = "aggo.host") lateinit var host: String
    @ConfigProperty(name = "aggo.database") lateinit var database: String
    @ConfigProperty(name = "aggo.user") lateinit var user: String
    @ConfigProperty(name = "aggo.password") lateinit var password: String

    @Produces @ApplicationScoped
    fun aggo(): Aggo = Aggo(AggoPool.postgres(PostgresConfig(
        host = host, database = database, user = user, password = password
    )))

    fun close(@Disposes aggo: Aggo) = aggo.close()
}
```

Native build: no `reflect-config.json` needed for aggo itself.

## Build / publish

```bash
cd libs/aggo
mvn verify                # build + tests
mvn deploy                # publish to GitHub Packages (requires GITHUB_TOKEN with write:packages)
```

`~/.m2/settings.xml` for publishing:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GH_USER</username>
    <password>${env.GITHUB_TOKEN}</password>
  </server>
</servers>
```

## Consume from a microservice

In each MS `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Aggi-tech/aggo</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.aggitech</groupId>
  <artifactId>aggo</artifactId>
  <version>0.1.0</version>
</dependency>
```
