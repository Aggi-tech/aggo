# Getting Started

Aggo is a reflection-free, type-safe R2DBC DSL for PostgreSQL.
It is designed for Quarkus microservices compiled with GraalVM Native — every
column mapping is an explicit lambda; no annotation scanning or reflection
happens at runtime.

## Adding the dependency

Add the GitHub Packages repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github-aggi-tech</id>
    <url>https://maven.pkg.github.com/Aggi-tech/aggo</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.aggitech</groupId>
  <artifactId>aggo</artifactId>
  <version>0.3.0</version>
</dependency>
```

Add your GitHub token to `~/.m2/settings.xml` (read-only scope is enough to download):

```xml
<servers>
  <server>
    <id>github-aggi-tech</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_TOKEN}</password>
  </server>
</servers>
```

## Minimal working example

```kotlin
import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.schema.*
import com.aggitech.aggo.dsl.*
import io.r2dbc.spi.Row

// 1. Define your domain type
data class User(val id: Int, val name: String, val active: Boolean)

// 2. Declare the table — one singleton per table
object UsersTable : Table<User>("users") {
    val id     = column("id",     IntCodec,     isPrimaryKey = true, isGenerated = true) { it.id }
    val name   = column("name",   StringCodec)                                           { it.name }
    val active = column("active", BooleanCodec)                                          { it.active }

    override fun fromRow(row: Row) = User(
        id     = id.required(row),
        name   = name.required(row),
        active = active.required(row),
    )
}

// 3. Create the Aggo instance (once, at application startup)
val aggo = Aggo(
    AggoPool.postgres(
        PostgresConfig(
            host     = "localhost",
            database = "mydb",
            user     = "appuser",
            password = System.getenv("DB_PASSWORD"),
        )
    )
)

// 4. Read data (no transaction)
val activeUsers: List<User> = aggo.read {
    fetchAll(UsersTable) { where { UsersTable.active eq true } }
}

// 5. Write data (transactional)
aggo.tx {
    insert(UsersTable) {
        UsersTable.name   setTo "Alice"
        UsersTable.active setTo true
    }
}

// 6. Close on shutdown
aggo.close()
```

## Quarkus CDI integration

```kotlin
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class AggoProducer {
    @ConfigProperty(name = "aggo.host")     lateinit var host: String
    @ConfigProperty(name = "aggo.database") lateinit var database: String
    @ConfigProperty(name = "aggo.user")     lateinit var user: String
    @ConfigProperty(name = "aggo.password") lateinit var password: String

    @Produces @ApplicationScoped
    fun aggo(): Aggo = Aggo(
        AggoPool.postgres(
            PostgresConfig(host = host, database = database, user = user, password = password)
        )
    )

    fun close(@Disposes aggo: Aggo) = aggo.close()
}
```

No `reflect-config.json` is needed — Aggo contains no reflection.

## What's next

- [Schema Definition](02-schema.md) — tables, columns, codecs, constraints
- [Querying](03-querying.md) — SELECT, filters, ordering, streaming
- [Writing Data](04-writes.md) — INSERT, UPDATE, DELETE, transactions
- [JOIN Queries](05-joins.md) — LEFT JOIN with typed result pairs
- [Migration Generation](06-migrations.md) — generate and apply versioned DDL from your schema
- [Multitenancy](07-multitenancy.md) — schema-per-tenant and database-per-tenant decorators

## API reference

Generate the Dokka HTML API reference locally:

```bash
mvn -q -DskipTests dokka:dokka
```

The generated site is written to `target/dokka`. The GitHub Pages workflow uses
the same command and publishes that directory.
