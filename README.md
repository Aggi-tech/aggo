# aggo — internal Dabble utility lib

Reflection-free Kotlin DSL on top of R2DBC + Postgres. Standalone Maven project; not part of any aggregator. Published to GitHub Packages and consumed by `payment-ms` / `person-ms` as a regular Maven dependency.

API reference generated from KDoc with Dokka is published to GitHub Pages:
https://aggi-tech.github.io/aggo/

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
    val id = varchar("id", 26, IdCodec)
        .required()
        .primaryKey()
        .check(Checks.ulid(), key = "payer.id.invalid") { it.id }

    val email = varchar("email", 255, EmailCodec)
        .required()
        .unique(key = "payer.email.taken")
        .check(Checks.email(), key = "payer.email.invalid") { it.email }

    val firstName = varchar("first_name", 100)
        .required()
        .check(Checks.notBlank(), key = "payer.first_name.blank") { it.firstName }

    val active = boolean("active")
        .required() { it.active }

    val createdAt = timestamptz("created_at")
        .required()
        .generated() { it.createdAt }

    override fun fromRow(row: Row) = Payer(
        id        = id.required(row),
        email     = email.required(row),
        firstName = firstName.required(row),
        active    = active.required(row),
        createdAt = createdAt.required(row),
    )
}
```

The legacy `column("name", Codec) { ... }` style remains supported. The fluent
style is preferred for new schemas because the column type, nullability,
constraints, and error keys stay in one chain:

```kotlin
val workspaceId = varchar("workspace_id", 26, WorkspaceIdCodec)
    .required()
    .references(Workspaces.id, key = "workspace.missing") { it.workspaceId }

val status = enumName<PayerStatus>("status")
    .required() { it.status }
```

Use `constraintErrorMap(Payers, Workspaces)` with typed transactions to map
database CHECK, UNIQUE, and FOREIGN KEY failures into stable application error
keys.

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

For API handlers that should not catch database exceptions directly, use the
typed-result helpers:

```kotlin
val errors = constraintErrorMap(Payers)

val result: Transaction<Long, AggoError> = aggo.transaction(errors) {
    insert(Payers, newPayer)
}

result.fold(
    onSuccess = { rows -> rows },
    onFailure = { error ->
        when (error) {
            is ConstraintError -> error.key       // e.g. "payer.email.taken"
            is DatabaseError -> "database.error"
            else -> "unknown.error"
        }
    },
)
```

`Query<Success, Error>` and `Transaction<Success, Error>` support `map`,
`flatMap`, and `fold`, so callers can compose database operations without
throwing through application boundaries.

### 4. Fetch nested objects with LEFT JOIN

```kotlin
import com.aggitech.aggo.dsl.*
import com.aggitech.aggo.query.JoinedRow

val q = UsersTable.leftJoin(MagicPinChallengesTable) {
    UsersTable.id eq MagicPinChallengesTable.userId
}
    .where { UsersTable.active eq true }
    .orderBy { UsersTable.id.asc() }
    .limit(50)

val rows: List<JoinedRow<User, MagicPinChallenge>> =
    aggo.read { session -> session.fetchAllJoined(q) }

rows.forEach { row ->
    val user: User = row.left
    val challenge: MagicPinChallenge? = row.right
}
```

`LEFT JOIN` results are nested as `JoinedRow(left, right)`. `right` is `null`
when PostgreSQL returns no matching right-side row. Aggo reads joined rows by
position internally, so duplicate column names like `id` remain safe without
aliases or reflection.

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

## Aggo CLI

Install the `aggo` executable without adding a Maven plugin or Gradle task:

```bash
curl -fsSL https://raw.githubusercontent.com/Aggi-tech/aggo/main/scripts/install-aggo-cli.sh \
  | sh -s -- --main-class com.example.db.MigrationsKt
```

Then run migrations from the project shell:

```bash
aggo migrate generate --name add_orders
aggo migrate run
```

Full migration setup options are in `docs/06-migrations.md`.

## Build / publish

```bash
mvn verify                # build + tests
mvn deploy                # publish to GitHub Packages (requires GITHUB_TOKEN with write:packages)
mvn deploy -P central     # publish to Maven Central (requires Central + GPG secrets)
```

For a local release version override, run:

```bash
mvn org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion=0.3.1 -DgenerateBackupPoms=false
mvn deploy -P central
```

The `Publish to GitHub Packages` and `Publish to Maven Central` GitHub Actions
also accept `release_version` when run manually. Tag pushes still derive the
version from tags such as `v0.3.1`.

`~/.m2/settings.xml` for publishing:

```xml
<servers>
  <server>
    <id>github-aggi-tech</id>
    <username>YOUR_GH_USER</username>
    <password>${env.GITHUB_TOKEN}</password>
  </server>
  <server>
    <id>central</id>
    <username>${env.CENTRAL_USERNAME}</username>
    <password>${env.CENTRAL_PASSWORD}</password>
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
