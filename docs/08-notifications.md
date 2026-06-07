# Package com.aggitech.aggo.notify

# Reactive Notifications

Languages: English first, Portuguese below.

Aggo's pull model (`fetchAll`, `fetchOne`, …) answers questions the application
asks. Reactive notifications add the opposite direction: the database tells the
application when a row changed, without polling and without an external broker
(Kafka, Redis, …).

The feature has three integrated parts, each owned by its usual layer:

| Part | Package | What it does |
|------|---------|--------------|
| `NotifyTrigger<E>` | `com.aggitech.aggo.schema` | Declares *what* fires a notification — a table, a set of row events, a channel, a SQL payload expression |
| `TriggerDialect` | `com.aggitech.aggo.dialect` | Renders *how* the database emits it — `pg_notify` on PostgreSQL, an outbox `INSERT` on MySQL/Oracle |
| `NotificationBackend` (this package) | `com.aggitech.aggo.notify` | Delivers the notification to business code as `Flow<Notification<P>>`, the same API regardless of dialect |

```text
schema.NotifyTrigger -> dialect.TriggerDialect (DDL) -> migration.MigrationPlan
                                                              |
                                                       applyMigration
                                                              |
                                          INSERT/UPDATE/DELETE on the table
                                                              |
                          PostgreSQL: pg_notify         MySQL/Oracle: outbox row
                                  |                                |
                            AggoListener                    OutboxListener
                                  \                               /
                                   ------ NotificationBackend ------
                                                  |
                                     Flow<Notification<P>>.collect { ... }
```

Business code depends only on `NotificationBackend` and `NotifyChannel<P>` —
it never branches on which database is behind it.

## NotifyChannel and NotifyCodec — typed, validated channels

A `NotifyChannel<P>` pairs a stable logical name with a `NotifyCodec<P>` that
bridges the wire format (`TEXT`, in both `NOTIFY` payloads and the outbox table)
to a domain type. This mirrors the role `Codec<V>` plays for table columns:
explicit, no reflection, safe for GraalVM native.

```kotlin
import com.aggitech.aggo.notify.LongNotifyCodec
import com.aggitech.aggo.notify.NotifyChannel
import com.aggitech.aggo.notify.StringNotifyCodec

val UserEventsChannel = NotifyChannel("user_events", StringNotifyCodec)
val OrderChannel      = NotifyChannel("order_created", LongNotifyCodec)
```

`NotifyChannel`'s `init` block runs the channel name through the same
identifier allowlist (`requireValidIdentifier`) used by table and column names.
Channel names are interpolated into generated trigger DDL and `LISTEN`
statements, so this constructor is the **single point of defence** against
channel-name injection — a name containing `;`, a space, or a quote throws
`IllegalArgumentException` immediately.

`NotifyCodec<P>` ships two built-ins:

```kotlin
interface NotifyCodec<P : Any> {
    fun encode(value: P?): String?
    fun decode(raw: String?): P?
}

object StringNotifyCodec : NotifyCodec<String>   // pass-through; blank decodes to null
object LongNotifyCodec   : NotifyCodec<Long>     // toString / toLongOrNull; unparseable decodes to null
```

For structured payloads, write an explicit `kotlinx.serialization` codec —
Aggo never calls a JSON library through reflection:

```kotlin
class JsonNotifyCodec<P : Any>(
    private val serializer: KSerializer<P>,
    private val json: Json = Json.Default,
) : NotifyCodec<P> {
    override fun encode(value: P?) = value?.let { json.encodeToString(serializer, it) }
    override fun decode(raw: String?) = raw?.takeIf { it.isNotBlank() }
        ?.let { json.decodeFromString(serializer, it) }
}
```

PostgreSQL caps `NOTIFY` payloads at 8 kB. Keep the payload small — an ID or a
compact key — and `fetchOne` the full row when the listener needs more.

## NotificationBackend — the dialect-agnostic delivery API

```kotlin
interface NotificationBackend {
    fun <P : Any> listen(channel: NotifyChannel<P>): Flow<Notification<P>>
    fun listenRaw(vararg channels: NotifyChannel<*>): Flow<RawNotification>
}
```

`listen` returns a **cold** `Flow`: nothing is registered until the first
`collect`, and listening stops on cancellation. Always collect inside a
`coroutineScope` or a structured application scope — never detach it from a
lifecycle.

```kotlin
val backend: NotificationBackend = AggoListener(connectionFactory)   // or OutboxListener(aggo)

backend.listen(UserEventsChannel).collect { notification ->
    println("user inserted: ${notification.payload}")   // Notification<String>
}
```

`listenRaw` listens to several channels through a single underlying
connection or poll loop and yields `RawNotification` — the undecoded channel
name and text payload — useful when one service cares about many channels and
wants to avoid one dedicated connection per channel:

```kotlin
data class Notification<P : Any>(val channel: String, val payload: P?, val processId: Int = 0)
data class RawNotification(val channel: String, val parameter: String?, val processId: Int)
```

`processId` is the PostgreSQL backend PID that sent the notification — `0` for
outbox-polling backends, which have no equivalent concept.

`AggoListener` (PostgreSQL) and `OutboxListener` (MySQL/Oracle) are the two
implementations. Business code is written once, against `NotificationBackend`,
and stays correct when a service moves between dialects.

## AggoListener — PostgreSQL `LISTEN`/`NOTIFY`

```kotlin
class AggoListener(
    private val connectionFactory: ConnectionFactory,
    private val qualifier: ChannelNameQualifier = ChannelNameQualifier.None,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.ExponentialBackoff(),
) : NotificationBackend
```

`AggoListener` holds its **own dedicated connection** — never one borrowed from
`AggoPool`. A `LISTEN` session must stay open for the listener's entire
lifetime, which is incompatible with pooled borrow/return semantics. Pass the
same `ConnectionFactory` the application pool was built from
(`ConnectionFactories.get(...)`); creating it is the caller's responsibility, so
`AggoListener` stays agnostic of how the rest of the application wires its pool.

```kotlin
import com.aggitech.aggo.notify.AggoListener
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions

val connectionFactory = ConnectionFactories.get(
    ConnectionFactoryOptions.builder()
        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
        .option(ConnectionFactoryOptions.HOST, "db.internal")
        .option(ConnectionFactoryOptions.DATABASE, "app")
        .option(ConnectionFactoryOptions.USER, "app")
        .option(ConnectionFactoryOptions.PASSWORD, System.getenv("DB_PASSWORD"))
        .build(),
)

val backend = AggoListener(connectionFactory)

backend.listen(UserEventsChannel).collect { notification ->
    println("PID ${notification.processId}: ${notification.payload}")
}
```

Internally, each collection opens a fresh connection, issues `LISTEN "<name>"`
for every requested channel, and relays the driver's native
`PostgresqlConnection.getNotifications()` push stream. `UNLISTEN` and `close`
always run in `NonCancellable`, so a cancelled collector still leaves the
backend session clean — no leaked `LISTEN` registrations, no leaked connections.

### Reconnection — `Flow.retryWhen` plus `ReconnectPolicy`

Reconnection is expressed entirely through `Flow.retryWhen` driven by
`reconnectPolicy` — there is no hand-rolled retry loop. Every retry restarts
from scratch: a fresh connection, fresh `LISTEN` registrations, a fresh
subscription. `CancellationException` always rethrows immediately (normal
collector cancellation is not a connection failure).

```kotlin
sealed interface ReconnectPolicy {
    fun nextDelay(attempt: Int): Duration?   // null = give up

    data class ExponentialBackoff(
        val initialDelay: Duration = 200.milliseconds,
        val maxDelay: Duration = 30.seconds,
        val multiplier: Double = 2.0,
        val maxAttempts: Int = 10,
    ) : ReconnectPolicy

    object NoRetry : ReconnectPolicy
}
```

When `nextDelay` returns `null`, `AggoListener` throws `AggoListenerException`
so the failure surfaces to the collector instead of retrying forever silently.

### Sharing one connection across collectors

The returned `Flow` is cold: each collector opens its own `LISTEN` connection.
To multiplex one physical connection across many collectors, reach for the
standard library operator — Aggo does not implement bespoke multiplexing:

```kotlin
val shared = backend.listen(UserEventsChannel)
    .shareIn(applicationScope, SharingStarted.WhileSubscribed(), replay = 0)
```

### Multitenancy — `ChannelNameQualifier`

`ChannelNameQualifier` maps a logical channel name to the physical name used on
the wire — the same idea `MultiSchemaSqlDialect` applies to table references.
Use `SchemaPrefix` in schema-per-tenant deployments so each tenant gets an
isolated `LISTEN`/`NOTIFY` namespace without redeclaring channels:

```kotlin
val backend = AggoListener(
    connectionFactory,
    qualifier = ChannelNameQualifier.SchemaPrefix("acme"),
)
// logical "user_events" <-> physical "acme__user_events"
```

`ChannelNameQualifier.None` (the default) leaves names untouched.
`SchemaPrefix` validates `schemaName` through the same identifier allowlist as
everything else.

## OutboxListener — MySQL/Oracle outbox polling

MySQL and Oracle have no native pub/sub. There, a `NotifyTrigger` writes a row
into a managed `aggo_notifications` outbox table (see `TriggerDialect` in the
[Migration Generation guide](06-migrations.md#notification-trigger-ddl)), and
`OutboxListener` polls it incrementally, replaying new rows as notifications:

```kotlin
class OutboxListener(
    private val aggo: Aggo,
    private val qualifier: ChannelNameQualifier = ChannelNameQualifier.None,
    private val pollInterval: Duration = 500.milliseconds,
    private val batchSize: Int = 100,
    outboxTableName: String = OUTBOX_TABLE,   // "aggo_notifications"
) : NotificationBackend
```

```kotlin
val backend = OutboxListener(aggo)

backend.listen(OrderChannel).collect { notification ->
    println("order created: ${notification.payload}")   // Notification<Long>
}
```

Polling runs entirely through `aggo.session` — the regular DSL surface, not raw
SQL — so it benefits from the same codecs, pooling, and logging as the rest of
the application. Each poll issues a single indexed query:

```sql
SELECT * FROM "aggo_notifications"
WHERE "channel" IN (...) AND "id" > :lastSeenId
ORDER BY "id" ASC LIMIT :batchSize
```

`outboxTableName` **must match** the name passed to
`TriggerDialect.renderOutboxTableDdl` (and to `migrationPlan`'s
`outboxTableName` parameter) when the migration plan was generated — otherwise
the listener polls a table the triggers never write to. Both default to
`OUTBOX_TABLE` (`"aggo_notifications"`), so most services never need to pass it
explicitly; pass it explicitly, and consistently, only when you customize it.

The listener starts from the current maximum `id` (`fetchMaxId`), so it never
replays history accumulated before the first `collect` — only rows inserted
from that point on.

## Session.notify — manual notifications

For the rare case where a payload can't be expressed as a single SQL expression
(and therefore can't be a `NotifyTrigger.payloadSql`), emit a notification
manually inside the current transaction:

```kotlin
import com.aggitech.aggo.notify.notify

aggo.tx {
    val order = insert(OrdersTable, newOrder)
    notify(OrderChannel, order.id)
}
```

`Session.notify` goes through `pg_notify`, so it inherits the same
**commit-only delivery guarantee** as trigger-based notifications: a
rolled-back transaction never reaches listeners. That is native PostgreSQL
behavior, not something Aggo adds on top.

`pg_notify` requires a raw connection statement — Aggo's `Session` DML surface
intentionally has no generic "execute arbitrary SQL" entry point — so the
extension opts into `@AggoUnsafe`. The call itself is fully parameterized
(`$1`, `$2`) and injection-safe; the opt-in marks "raw connection access", not
"unsafe SQL".

> **PostgreSQL-only.** MySQL/Oracle have no native pub/sub: their notifications
> always travel through a `NotifyTrigger` writing to the outbox, and business
> code never calls `notify` directly on those dialects.

## End-to-end flow

```kotlin
// 1. Declare channel and trigger (schema layer — see the Schema Definition guide)
val UserEventsChannel = NotifyChannel("user_events", StringNotifyCodec)

val UserInsertedTrigger = NotifyTrigger(
    name       = "trg_notify_user_inserted",
    table      = UsersTable,
    channel    = UserEventsChannel,
    events     = setOf(TriggerEvent.Insert),
    payloadSql = "NEW.id",
)

// 2. Generate and apply DDL once, at startup / in the migration pipeline
val schema = migrationSchema(
    version  = "2026.06.01.001",
    tables   = listOf(UsersTable),
    dialect  = PostgresDialect,
    triggers = listOf(UserInsertedTrigger),
)
aggo.applyMigration(migrationPlan(schema, PostgresDialect, triggerDialect = PostgresTriggerDialect))

// 3. Listen
val backend = AggoListener(connectionFactory)   // OutboxListener(aggo) for MySQL/Oracle
backend.listen(UserEventsChannel).collect { notification ->
    println("user inserted: ${notification.payload}")
}

// 4. Notifications arrive automatically on every INSERT into users —
//    no extra call in business code:
aggo.tx { insert(UsersTable, newUser) }
```

## Aggo vs ad-hoc CDC / polling

| Typical approach | Aggo |
|------------------|------|
| Poll a `updated_at` column on a timer | Database pushes the change; no polling loop in business code |
| External broker (Kafka, Debezium) for simple fanout | Native `LISTEN`/`NOTIFY` on PostgreSQL, managed outbox elsewhere — no extra infrastructure |
| Hand-rolled triggers and listener code per service | `NotifyTrigger` + `TriggerDialect` generate reviewable DDL from the same `Table` descriptors used everywhere else |
| Different consumer code per database | One `NotificationBackend` interface; `AggoListener`/`OutboxListener` are interchangeable |
| Notifications that can leak past a rollback | `pg_notify` (and the outbox row) only become visible on `COMMIT` — correct domain-event semantics by construction |

This is intentionally narrower than a full CDC pipeline: payloads are capped at
8 kB, and there is no replay of history before the first `collect`. For
"replay everything since the beginning of time" use cases, pair Aggo with a
proper CDC tool; for "tell my service when a row changed" use cases, this
feature removes the need for one.

## Notificacoes Reativas PT

O modelo padrao de Aggo e *pull*: a aplicacao pergunta (`fetchAll`, `fetchOne`)
e o banco responde. Notificacoes reativas adicionam o sentido oposto — o banco
avisa a aplicacao quando uma linha muda, sem polling e sem broker externo
(Kafka, Redis, ...).

A funcionalidade tem tres partes, cada uma na sua camada usual:

- `NotifyTrigger<E>` (`schema`) declara *o que* dispara uma notificacao — uma
  tabela, um conjunto de eventos de linha, um canal, uma expressao SQL de payload;
- `TriggerDialect` (`dialect`) gera *como* o banco emite — `pg_notify` no
  PostgreSQL, `INSERT` em outbox no MySQL/Oracle;
- `NotificationBackend` (este pacote) entrega a notificacao ao codigo de
  negocio como `Flow<Notification<P>>`, com a mesma API independente do dialeto.

### Canais e codecs

```kotlin
val UserEventsChannel = NotifyChannel("user_events", StringNotifyCodec)
```

`NotifyChannel` valida o nome com a mesma allowlist de identificadores usada em
tabelas e colunas — esse e o ponto unico de defesa contra injecao de nome de
canal. `NotifyCodec<P>` e o limite explicito de encode/decode do payload
(`TEXT` na fiacao), do mesmo jeito que `Codec<V>` funciona para colunas: sem
reflexao, seguro para GraalVM native.

### Escutando notificacoes

```kotlin
val backend: NotificationBackend = AggoListener(connectionFactory)

backend.listen(UserEventsChannel).collect { notification ->
    println(notification.payload)
}
```

`listen` retorna um `Flow` frio: nada e registrado ate o primeiro `collect`, e
a escuta para no cancelamento. Sempre colete dentro de um escopo estruturado.

### AggoListener (PostgreSQL)

Mantem uma conexao **dedicada e propria** — nunca emprestada do `AggoPool` —
porque uma sessao `LISTEN` precisa ficar aberta pela vida inteira do listener.
Reconexao e feita via `Flow.retryWhen` guiado por `ReconnectPolicy` (por
exemplo `ExponentialBackoff`); cada nova tentativa recomeca do zero — conexao
nova, `LISTEN` novo, subscricao nova. `UNLISTEN` e `close` sempre rodam em
`NonCancellable`, entao um collector cancelado nao deixa conexoes ou
registros `LISTEN` vazando.

Para multitenancy, use `ChannelNameQualifier.SchemaPrefix("acme")` para isolar
o namespace de canais por tenant sem redeclarar canais.

### OutboxListener (MySQL/Oracle)

MySQL e Oracle nao tem pub/sub nativo. La, um `NotifyTrigger` insere uma linha
na tabela gerenciada `aggo_notifications`, e `OutboxListener` faz polling
incremental (`WHERE channel IN (...) AND id > :lastSeenId`) usando a superficie
DSL normal — mesmos codecs, pool e logging do resto da aplicacao.
`outboxTableName` precisa ser o mesmo nome passado para
`TriggerDialect.renderOutboxTableDdl` e para `migrationPlan`, senao o listener
faz polling de uma tabela que os triggers nunca escrevem.

### Session.notify — notificacao manual

```kotlin
aggo.tx {
    val order = insert(OrdersTable, newOrder)
    notify(OrderChannel, order.id)
}
```

So PostgreSQL. Passa por `pg_notify`, entao herda a mesma garantia de entrega
apenas-apos-commit dos triggers — rollback nunca chega aos listeners. Exige
`@AggoUnsafe` porque usa a conexao crua (a chamada em si e parametrizada e
segura contra injecao); em MySQL/Oracle o codigo de negocio nunca chama
`notify` diretamente, a notificacao sempre passa pelo trigger + outbox.

### Comparacao com abordagens ad-hoc

Hibernate e bibliotecas tipicas nao oferecem nada equivalente de forma
integrada — o caminho usual e poll manual de `updated_at`, triggers e listeners
escritos a mao por servico, ou um pipeline CDC completo (Debezium + Kafka) para
um problema que muitas vezes e simplesmente "avise meu servico quando uma linha
mudar". Aggo cobre esse meio-termo: DDL gerado a partir dos mesmos `Table`
descriptors, uma API de consumo unica por tras de `NotificationBackend`, e
semantica transacional correta (commit-only) por construcao — sem broker
externo e sem pipeline CDC completo.
