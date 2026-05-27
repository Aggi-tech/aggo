# Module aggo

# Getting Started

Languages: English first, Portuguese below.

Aggo is a reflection-free, type-safe R2DBC DSL for PostgreSQL.
It is designed for Quarkus microservices compiled with GraalVM Native — every
column mapping is an explicit lambda; no annotation scanning or reflection
happens at runtime.

If you are coming from Hibernate, the first mental shift is this: Aggo does not
manage entities. There is no persistence context, lazy proxy, dirty checking, or
annotation scan. Your `Table<E>` is explicit metadata, and every read or write
is an explicit SQL-shaped operation.

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
  <version>0.5.1</version>
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

## Aggo vs Hibernate quick map

| Hibernate concept | Aggo equivalent | Difference |
|-------------------|-----------------|------------|
| `@Entity` class | Kotlin domain class plus `Table<E>` object | Mapping is explicit code, not annotations |
| `@Column` | `column(...)`, `varchar(...)`, `uuid(...)`, etc. | Column codecs and getters are declared directly |
| `EntityManager` / `Session` | `Aggo.read {}` and `Aggo.tx {}` | No first-level cache or dirty checking |
| JPQL / Criteria | Aggo DSL: `select`, `where`, `orderBy` | SQL shape is visible and predictable |
| Lazy relations | Explicit `leftJoin` or separate query | No proxy objects |
| Schema generation | `migrationSchema` / `migrationPlan` | Migrations are generated from table descriptors |
| Exception translation | `Query<T, AggoError>` and `ConstraintErrorMap` | Errors can be returned as typed values |

## Primeiros Passos

Aggo e uma DSL Kotlin type-safe sobre R2DBC. Ela foi desenhada para servicos
Quarkus e GraalVM Native, onde reflexao em runtime e um custo e uma fonte de
configuracao extra. Toda coluna e todo mapeamento de linha sao declarados de
forma explicita.

Em Hibernate, voce costuma modelar uma classe anotada e deixar o ORM descobrir
metadados. Em Aggo, voce declara a entidade Kotlin e um objeto `Table<E>` que
descreve a tabela. Isso deixa o SQL previsivel, facilita native-image, e evita
surpresas de lazy loading.

### Dependencia Maven

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
  <version>0.5.1</version>
</dependency>
```

Configure o token no `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github-aggi-tech</id>
    <username>SEU_USUARIO_GITHUB</username>
    <password>${env.GITHUB_TOKEN}</password>
  </server>
</servers>
```

### Exemplo minimo

```kotlin
data class User(val id: Int, val name: String, val active: Boolean)

object UsersTable : Table<User>("users") {
    val id = column("id", IntCodec, isPrimaryKey = true, isGenerated = true) { it.id }
    val name = column("name", StringCodec) { it.name }
    val active = column("active", BooleanCodec) { it.active }

    override fun fromRow(row: Row) = User(
        id = id.required(row),
        name = name.required(row),
        active = active.required(row),
    )
}

val aggo = Aggo(
    AggoPool.postgres(
        PostgresConfig(
            host = "localhost",
            database = "mydb",
            user = "appuser",
            password = System.getenv("DB_PASSWORD"),
        )
    )
)

val users = aggo.read {
    fetchAll(UsersTable) {
        where { UsersTable.active eq true }
    }
}

aggo.tx {
    insert(UsersTable) {
        UsersTable.name setTo "Alice"
        UsersTable.active setTo true
    }
}
```

### Integracao com Quarkus

Crie um produtor CDI para ter uma instancia unica de `Aggo` por aplicacao:

```kotlin
@ApplicationScoped
class AggoProducer {
    @ConfigProperty(name = "aggo.host") lateinit var host: String
    @ConfigProperty(name = "aggo.database") lateinit var database: String
    @ConfigProperty(name = "aggo.user") lateinit var user: String
    @ConfigProperty(name = "aggo.password") lateinit var password: String

    @Produces
    @ApplicationScoped
    fun aggo(): Aggo = Aggo(
        AggoPool.postgres(
            PostgresConfig(host = host, database = database, user = user, password = password)
        )
    )

    fun close(@Disposes aggo: Aggo) = aggo.close()
}
```

### Comparacao rapida com Hibernate

| Hibernate | Aggo | Impacto |
|-----------|------|---------|
| Entidades anotadas | `data class` + `Table<E>` | Mapeamento fica explicito |
| `EntityManager` | `Aggo.read` / `Aggo.tx` | Sem cache de primeiro nivel |
| Lazy loading | `leftJoin` explicito | Sem proxies ou N+1 escondido |
| JPQL/Criteria | DSL de query | O formato do SQL fica claro |
| Excecoes do driver | `Query<T, AggoError>` opcional | Erros podem virar valores |

### Proximos guias

- [Definicao de Schema](02-schema.md)
- [Consultas](03-querying.md)
- [Escritas](04-writes.md)
- [JOIN](05-joins.md)
- [Migracoes](06-migrations.md)
- [Multitenancy](07-multitenancy.md)

## API reference

Generate the Dokka HTML API reference locally:

```bash
mvn -q -DskipTests dokka:dokka
```

The generated site is written to `target/dokka`. The GitHub Pages workflow uses
the same command and publishes that directory.
