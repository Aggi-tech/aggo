# spec-10 — Reactive Notifications: Triggers + LISTEN/NOTIFY

> Status: **planned**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

---

## Motivação

Aggo cobre hoje apenas o modelo pull — o cliente pergunta, o banco responde.
Para eventos de domínio assíncronos (cache invalidation, fanout entre serviços,
CDC leve) o modelo push elimina polling periódico, reduz latência e não exige
broker externo (Kafka, Redis).

Esta spec adiciona três capacidades integradas:

1. **Trigger DSL** — declaração type-safe de triggers ligados a canais de notificação,
   dentro do modelo de schema existente.
2. **DDL generation** — `MigrationGenerator` aprende a emitir DDL de trigger/função
   por dialeto.
3. **Consumer API** — `Flow<Notification<P>>` agnóstico de dialeto:
   PostgreSQL usa `LISTEN`/`NOTIFY` nativo; MySQL e Oracle usam outbox table +
   polling. A API de negócio não muda de acordo com o dialeto.

---

## Decisões de design

1. **Trigger é metadado de schema, separado de `Table`.** `NotifyTrigger<E>` é
   declarado ao lado da tabela, não dentro dela — preserva o invariante de que
   `Table` não tem DDL próprio.

2. **Cross-dialect via `NotificationBackend`.** A interface `NotificationBackend`
   abstrai a entrega de notificações. Cada dialeto fornece sua implementação:
   PostgreSQL usa LISTEN/NOTIFY; MySQL/Oracle usam polling de uma tabela de outbox
   (`aggo_notifications`) que o trigger popula via `INSERT`.

3. **Geração de DDL via `TriggerDialect`.** Uma nova interface `TriggerDialect`
   encapsula a sintaxe de trigger/função por banco. Ela é implementada junto ao
   `MigrationDialect` existente. O `MigrationGenerator` aceita opcionalmente uma
   lista de `NotifyTrigger<*>` e inclui o DDL de triggers no `MigrationPlan`.

4. **Payload como expressão SQL.** O payload da notificação é uma expressão SQL
   referenciando `NEW`/`OLD` (e.g., `"NEW.id"`). Isso mantém zero-reflexão —
   nenhum mapeamento de domínio é chamado dentro do trigger.

5. **NOTIFY on commit.** Em PostgreSQL, `pg_notify` dentro de um trigger `AFTER`
   só dispara quando a transação faz `COMMIT`. Notificações não chegam ao listener
   se a transação faz rollback — semântica correta para eventos de domínio.
   No modelo outbox, o mesmo vale: a linha em `aggo_notifications` só existe
   após commit da transação.

6. **Outbox table gerenciada pelo Aggo.** Para dialetos sem pub/sub nativo, Aggo
   cria e mantém a tabela `aggo_notifications` como parte do migration plan.
   O `OutboxNotificationBackend` faz polling incremental com `WHERE id > lastSeenId`.

7. **Conexão dedicada para PostgreSQL.** `LISTEN` vincula estado à conexão —
   `AggoListener` gerencia uma conexão persistente separada do pool, com reconexão
   automática via `ReconnectPolicy`.

8. **Sem reflexão em nenhuma camada.** `NotifyCodec<P>` é uma interface de
   encode/decode explícita. Payloads JSON requerem `NotifyCodec` implementado
   com `kotlinx.serialization` com decoders explícitos — compatível com GraalVM
   native.

---

## Mapa de arquivos e camadas

```
schema/
└── Trigger.kt              ← NotifyTrigger<E>, TriggerTiming, TriggerEvent (metadado puro)

dialect/
└── TriggerDialect.kt       ← interface TriggerDialect: renderTriggerDdl(), renderFunctionDdl()

migration/
└── MigrationGenerator.kt   ← (existente) + suporte a triggers no migrationSchema() e migrationPlan()

notify/
├── NotifyCodec.kt          ← interface NotifyCodec<P>: encode/decode TEXT payload
├── NotifyChannel.kt        ← data class NotifyChannel<P>(name, codec) com validação
├── Notification.kt         ← data class Notification<P>(channel, payload, processId)
├── NotificationBackend.kt  ← interface NotificationBackend: fun <P> listen(): Flow<Notification<P>>
├── AggoListener.kt         ← implementação PostgreSQL: LISTEN via conexão dedicada
├── OutboxListener.kt       ← implementação outbox: polling incremental de aggo_notifications
├── NotifySession.kt        ← extension Session.notify(channel, payload): para NOTIFY manual
├── ChannelNameQualifier.kt ← prefixo de tenant para nome de canal
└── ReconnectPolicy.kt      ← backoff exponencial para AggoListener
```

Nenhum arquivo existente fora de `migration/MigrationGenerator.kt` e
`dialect/TriggerDialect.kt` (novo) é alterado.

---

## T-1 — `schema/Trigger.kt` — Metadado de trigger

**Arquivo:** `schema/Trigger.kt`

```kotlin
enum class TriggerTiming { Before, After }
enum class TriggerEvent  { Insert, Update, Delete }

/**
 * Declara um trigger AFTER {events} ON {table} que emite uma notificação
 * no [channel] com payload derivado da expressão SQL [payloadSql].
 *
 * [payloadSql] é uma expressão SQL literal (e.g. "NEW.id", "NEW.id::text",
 * "NEW.email || ':' || NEW.id"). Ela é embutida no corpo do trigger pelo
 * gerador de DDL — o integrador é responsável por garantir que a expressão
 * é válida para o banco alvo.
 *
 * Nenhuma reflexão, nenhum acesso ao domínio Kotlin ocorre aqui.
 */
data class NotifyTrigger<E : Any>(
    val name: String,
    val table: Table<E>,
    val channel: NotifyChannel<*>,
    val timing: TriggerTiming = TriggerTiming.After,
    val events: Set<TriggerEvent> = setOf(TriggerEvent.Insert),
    val payloadSql: String = "NULL",
    val forEachRow: Boolean = true,
) {
    init {
        requireValidIdentifier(name)
        require(events.isNotEmpty()) { "events must not be empty" }
    }
}
```

**Padrão de declaração:**

```kotlin
// channels.kt
val UserEventsChannel   = NotifyChannel("user_events",   StringNotifyCodec)
val OrderChannel        = NotifyChannel("order_created", LongNotifyCodec)

// UsersTable.kt — ao lado do objeto da tabela
val UserInsertedTrigger = NotifyTrigger(
    name       = "trg_notify_user_inserted",
    table      = UsersTable,
    channel    = UserEventsChannel,
    timing     = TriggerTiming.After,
    events     = setOf(TriggerEvent.Insert),
    payloadSql = "NEW.id",
)

val OrderCreatedTrigger = NotifyTrigger(
    name       = "trg_notify_order_created",
    table      = OrdersTable,
    channel    = OrderChannel,
    events     = setOf(TriggerEvent.Insert, TriggerEvent.Update),
    payloadSql = "NEW.id::text",
)
```

---

## T-2 — `dialect/TriggerDialect.kt` — DDL de trigger por banco

**Arquivo:** `dialect/TriggerDialect.kt`

```kotlin
interface TriggerDialect {

    /**
     * Gera o DDL completo para um trigger de notificação.
     * Para PostgreSQL: CREATE FUNCTION + CREATE TRIGGER.
     * Para MySQL/Oracle: CREATE TRIGGER com INSERT no outbox.
     * Retorna uma lista de statements na ordem de execução.
     */
    fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>): List<String>

    /**
     * Gera o DDL para a tabela de outbox usada em dialetos sem pub/sub nativo.
     * PostgreSQL retorna lista vazia.
     */
    fun renderOutboxTableDdl(): List<String>

    /**
     * Gera o DDL de DROP para um trigger (usado em migração destrutiva manual).
     */
    fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String>
}
```

### T-2a — `PostgresTriggerDialect`

```kotlin
object PostgresTriggerDialect : TriggerDialect {

    override fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>): List<String> {
        val fnName  = "aggo_notify_${trigger.name}"
        val tbl     = "\"${trigger.table.name}\""
        val timing  = trigger.timing.name.uppercase()
        val events  = trigger.events.joinToString(" OR ") { it.name.uppercase() }
        val channel = trigger.channel.name

        val function = """
            CREATE OR REPLACE FUNCTION $fnName() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                PERFORM pg_notify('$channel', (${trigger.payloadSql})::text);
                RETURN COALESCE(NEW, OLD);
            END;
            $$;
        """.trimIndent()

        val triggerDdl = """
            DROP TRIGGER IF EXISTS "${trigger.name}" ON $tbl;
            CREATE TRIGGER "${trigger.name}"
            $timing ${events} ON $tbl
            FOR EACH ROW EXECUTE FUNCTION $fnName();
        """.trimIndent()

        return listOf(function, triggerDdl)
    }

    override fun renderOutboxTableDdl(): List<String> = emptyList()

    override fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String> = listOf(
        "DROP TRIGGER IF EXISTS \"${trigger.name}\" ON \"${trigger.table.name}\";",
        "DROP FUNCTION IF EXISTS aggo_notify_${trigger.name}();",
    )
}
```

### T-2b — `MySqlTriggerDialect`

```kotlin
object MySqlTriggerDialect : TriggerDialect {

    override fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>): List<String> {
        val tbl    = "`${trigger.table.name}`"
        val timing = trigger.timing.name.uppercase()
        val events = trigger.events.joinToString(", ") { it.name.uppercase() }

        // MySQL não suporta múltiplos eventos em um único CREATE TRIGGER —
        // um trigger por evento é necessário.
        return trigger.events.mapIndexed { i, event ->
            val suffix = if (trigger.events.size > 1) "_${event.name.lowercase()}" else ""
            val row    = if (event == TriggerEvent.Delete) "OLD" else "NEW"
            """
            DROP TRIGGER IF EXISTS `${trigger.name}$suffix`;
            CREATE TRIGGER `${trigger.name}$suffix`
            $timing ${event.name.uppercase()} ON $tbl
            FOR EACH ROW
            INSERT INTO `aggo_notifications` (`channel`, `payload`, `created_at`)
            VALUES ('${trigger.channel.name}', ($row.${trigger.payloadSql.removePrefix("NEW.").removePrefix("OLD.")}), NOW());
            """.trimIndent()
        }
    }

    override fun renderOutboxTableDdl(): List<String> = listOf("""
        CREATE TABLE IF NOT EXISTS `aggo_notifications` (
            `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
            `channel`    VARCHAR(255) NOT NULL,
            `payload`    TEXT,
            `created_at` DATETIME(3) NOT NULL DEFAULT NOW(3),
            INDEX `idx_aggo_notifications_channel_id` (`channel`, `id`)
        );
    """.trimIndent())

    override fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String> =
        trigger.events.map { event ->
            val suffix = if (trigger.events.size > 1) "_${event.name.lowercase()}" else ""
            "DROP TRIGGER IF EXISTS `${trigger.name}$suffix`;"
        }
}
```

### T-2c — `OracleTriggerDialect`

```kotlin
object OracleTriggerDialect : TriggerDialect {

    override fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>): List<String> {
        val tbl    = "\"${trigger.table.name}\""
        val timing = trigger.timing.name.uppercase()
        val events = trigger.events.joinToString(" OR ") { it.name.uppercase() }
        val row    = if (TriggerEvent.Delete in trigger.events) ":OLD" else ":NEW"

        return listOf("""
            CREATE OR REPLACE TRIGGER "${trigger.name}"
            $timing $events ON $tbl
            FOR EACH ROW
            BEGIN
                INSERT INTO aggo_notifications (channel, payload, created_at)
                VALUES ('${trigger.channel.name}', $row.${trigger.payloadSql.removePrefix("NEW.").removePrefix("OLD.")}, SYSTIMESTAMP);
            END;
            /
        """.trimIndent())
    }

    override fun renderOutboxTableDdl(): List<String> = listOf("""
        CREATE TABLE aggo_notifications (
            id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            channel    VARCHAR2(255) NOT NULL,
            payload    CLOB,
            created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
        )
    """.trimIndent(), """
        CREATE INDEX idx_aggo_notif_channel_id ON aggo_notifications (channel, id)
    """.trimIndent())

    override fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String> = listOf(
        "DROP TRIGGER \"${trigger.name}\";"
    )
}
```

---

## T-3 — Integração com `MigrationGenerator`

**Arquivo:** `migration/MigrationGenerator.kt` (extensão)

### T-3a — `MigrationSchema` e `migrationSchema()`

```kotlin
// Extensão do modelo existente:
data class MigrationSchema(
    val version: String,
    val tables: List<MigrationTable>,
    val triggers: List<NotifyTrigger<*>> = emptyList(),   // novo campo opcional
)

// Assinatura atualizada (backward-compatible — triggers default = emptyList):
fun migrationSchema(
    version: String,
    tables: Iterable<Table<*>>,
    dialect: MigrationDialect,
    triggers: Iterable<NotifyTrigger<*>> = emptyList(),
): MigrationSchema
```

### T-3b — `MigrationPlan` com trigger steps

`MigrationStep` ganha um novo tipo de step:

```kotlin
sealed interface MigrationStep {
    // existentes: CreateTable, AddColumn, AddCheckConstraint, ...

    data class CreateTrigger(
        val triggerName: String,
        val statements: List<String>,
        val change: String,
    ) : MigrationStep

    data class DropTrigger(
        val triggerName: String,
        val statements: List<String>,
        val change: String,
    ) : MigrationStep

    data class CreateOutboxTable(
        val statements: List<String>,
    ) : MigrationStep
}
```

### T-3c — `migrationPlan()` com diff de triggers

O diff de triggers funciona como o diff de tabelas: compara triggers do schema
anterior com o atual por `name`. Triggers novos → `CreateTrigger`. Triggers
removidos → marcados como manual (destrutivo). Triggers modificados → `CreateTrigger`
(idempotente via `CREATE OR REPLACE FUNCTION` / `DROP + CREATE`).

```kotlin
// Uso completo — igual ao fluxo de tabelas:
val schema = migrationSchema(
    version  = "2026.05.27.001",
    tables   = listOf(UsersTable, OrdersTable),
    dialect  = PostgresMigrationDialect,
    triggers = listOf(UserInsertedTrigger, OrderCreatedTrigger),
)
val plan = migrationPlan(schema, PostgresMigrationDialect)
aggo.applyMigration(plan)
```

`applyMigration` executa todos os statements na mesma transação — tabelas e
triggers são criados atomicamente.

---

## N-1 — `notify/NotifyCodec.kt`

```kotlin
interface NotifyCodec<P : Any> {
    fun encode(value: P?): String?
    fun decode(raw: String?): P?
}

object StringNotifyCodec : NotifyCodec<String> {
    override fun encode(value: String?): String? = value
    override fun decode(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
}

object LongNotifyCodec : NotifyCodec<Long> {
    override fun encode(value: Long?): String? = value?.toString()
    override fun decode(raw: String?): Long? = raw?.toLongOrNull()
}

// Implementação JSON — responsabilidade do integrador, sem reflexão:
class JsonNotifyCodec<P : Any>(
    private val serializer: KSerializer<P>,
    private val json: Json = Json.Default,
) : NotifyCodec<P> {
    override fun encode(value: P?): String? =
        value?.let { json.encodeToString(serializer, it) }
    override fun decode(raw: String?): P? =
        raw?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(serializer, it) }
}
```

---

## N-2 — `notify/NotifyChannel.kt`

```kotlin
data class NotifyChannel<P : Any>(
    val name: String,
    val codec: NotifyCodec<P>,
) {
    init { requireValidIdentifier(name) }
}
```

---

## N-3 — `notify/Notification.kt`

```kotlin
data class Notification<P : Any>(
    val channel: String,
    val payload: P?,
    val processId: Int = 0,   // PID do backend PG (0 para outbox polling)
)

internal data class RawNotification(
    val channel: String,
    val parameter: String?,
    val processId: Int,
)
```

---

## N-4 — `notify/NotificationBackend.kt`

Interface unificadora — o código de negócio não vê o dialeto:

```kotlin
interface NotificationBackend {

    /**
     * Retorna um Flow frio que emite notificações do canal.
     * O Flow inicia a escuta no primeiro collect e para no cancelamento.
     */
    fun <P : Any> listen(channel: NotifyChannel<P>): Flow<Notification<P>>

    /**
     * Escuta múltiplos canais na mesma conexão/poll.
     * Útil para reduzir conexões em cenários com muitos canais.
     */
    fun listenRaw(vararg channels: NotifyChannel<*>): Flow<RawNotification>
}
```

---

## N-5 — `notify/AggoListener.kt` (PostgreSQL)

```kotlin
class AggoListener(
    private val connectionFactory: ConnectionFactory,
    private val qualifier: ChannelNameQualifier = ChannelNameQualifier.None,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.ExponentialBackoff(),
) : NotificationBackend {

    override fun <P : Any> listen(channel: NotifyChannel<P>): Flow<Notification<P>> =
        listenRaw(channel)
            .filter { it.channel == qualifier.qualify(channel.name) }
            .map { raw ->
                Notification(
                    channel   = channel.name,
                    payload   = channel.codec.decode(raw.parameter),
                    processId = raw.processId,
                )
            }

    override fun listenRaw(vararg channels: NotifyChannel<*>): Flow<RawNotification> = flow {
        var attempt = 0
        while (true) {
            val qualified = channels.map { qualifier.qualify(it.name) }
            runCatching { listenOnce(qualified) { emit(it) } }
                .onSuccess { return@flow }   // cancelamento normal
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    val delay = reconnectPolicy.nextDelay(attempt++)
                        ?: throw AggoListenerException("Max reconnect attempts reached", ex)
                    kotlinx.coroutines.delay(delay)
                }
        }
    }

    private suspend fun listenOnce(
        channelNames: List<String>,
        emit: suspend (RawNotification) -> Unit,
    ) {
        val connection = connectionFactory.create().awaitSingle()
        try {
            channelNames.forEach { name ->
                connection.createStatement("LISTEN \"$name\"").execute().awaitFirstOrNull()
            }
            // getNotifications() é a API do driver R2DBC PostgreSQL
            (connection as PostgresqlConnection).notifications.asFlow()
                .map { RawNotification(it.name, it.parameter, it.processId) }
                .collect { emit(it) }
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    channelNames.forEach { name ->
                        connection.createStatement("UNLISTEN \"$name\"").execute().awaitFirstOrNull()
                    }
                }
                connection.close().awaitFirstOrNull()
            }
        }
    }
}

class AggoListenerException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
```

---

## N-6 — `notify/OutboxListener.kt` (MySQL / Oracle)

```kotlin
class OutboxListener(
    private val aggo: Aggo,
    private val qualifier: ChannelNameQualifier = ChannelNameQualifier.None,
    private val pollInterval: Duration = 500.milliseconds,
    private val batchSize: Int = 100,
) : NotificationBackend {

    override fun <P : Any> listen(channel: NotifyChannel<P>): Flow<Notification<P>> =
        listenRaw(channel)
            .filter { it.channel == qualifier.qualify(channel.name) }
            .map { raw ->
                Notification(
                    channel = channel.name,
                    payload = channel.codec.decode(raw.parameter),
                )
            }

    override fun listenRaw(vararg channels: NotifyChannel<*>): Flow<RawNotification> = flow {
        val qualifiedNames = channels.map { qualifier.qualify(it.name) }.toSet()
        var lastSeenId = fetchMaxId()

        while (true) {
            delay(pollInterval)
            val rows = fetchBatch(qualifiedNames, lastSeenId, batchSize)
            rows.forEach { row ->
                if (row.id > lastSeenId) lastSeenId = row.id
                emit(RawNotification(row.channel, row.payload, processId = 0))
            }
        }
    }

    private suspend fun fetchMaxId(): Long = aggo.read {
        fetchOne(OutboxTable, select(OutboxTable) {
            orderBy { OutboxTable.id.desc() }
            limit(1)
        })?.id ?: 0L
    }

    private suspend fun fetchBatch(
        channels: Set<String>,
        afterId: Long,
        limit: Int,
    ): List<OutboxRow> = aggo.read {
        fetchAll(OutboxTable, select(OutboxTable) {
            where {
                (OutboxTable.channel inList channels) and (OutboxTable.id gt afterId)
            }
            orderBy { OutboxTable.id.asc() }
            limit(limit)
        })
    }
}

// Tabela interna de outbox — instanciada pelo Aggo, não exposta ao integrador
internal object OutboxTable : Table<OutboxRow>("aggo_notifications") {
    val id      = column("id",         LongCodec,   isPrimaryKey = true) { it.id }
    val channel = column("channel",    StringCodec)                      { it.channel }
    val payload = column("payload",    StringCodec)                      { it.payload }

    override fun fromRow(row: Row) = OutboxRow(
        id      = id.readRequired(row),
        channel = channel.readRequired(row),
        payload = payload.read(row),
    )
}

internal data class OutboxRow(val id: Long, val channel: String, val payload: String?)
```

---

## N-7 — `notify/NotifySession.kt` — NOTIFY manual

Extension para emitir notificações explicitamente dentro de uma transação,
independente de triggers (útil em casos onde o trigger não é viável):

```kotlin
suspend fun <P : Any> Session.notify(channel: NotifyChannel<P>, payload: P?) {
    val encoded = channel.codec.encode(payload)
    connection
        .createStatement("SELECT pg_notify(\$1, \$2)")
        .bind(0, channel.name)
        .apply {
            if (encoded == null) bindNull(1, String::class.java)
            else bind(1, encoded)
        }
        .execute()
        .awaitFirstOrNull()
}
```

> **Nota:** `Session.notify` é PostgreSQL-only. Em MySQL/Oracle, a notificação
> chega ao consumer via trigger + outbox — o código de negócio não chama `notify`
> diretamente nesses dialetos.

---

## N-8 — `notify/ReconnectPolicy.kt`

```kotlin
sealed interface ReconnectPolicy {

    /** Retorna o delay antes da próxima tentativa, ou null para desistir. */
    suspend fun nextDelay(attempt: Int): Duration?

    data class ExponentialBackoff(
        val initialDelay: Duration = 200.milliseconds,
        val maxDelay: Duration     = 30.seconds,
        val multiplier: Double     = 2.0,
        val maxAttempts: Int       = 10,
    ) : ReconnectPolicy {
        override suspend fun nextDelay(attempt: Int): Duration? {
            if (attempt >= maxAttempts) return null
            val ms = (initialDelay.inWholeMilliseconds * multiplier.pow(attempt))
                .toLong().coerceAtMost(maxDelay.inWholeMilliseconds)
            return ms.milliseconds
        }
    }

    object NoRetry : ReconnectPolicy {
        override suspend fun nextDelay(attempt: Int): Duration? = null
    }
}
```

---

## N-9 — `notify/ChannelNameQualifier.kt`

```kotlin
fun interface ChannelNameQualifier {
    fun qualify(channelName: String): String

    object None : ChannelNameQualifier {
        override fun qualify(channelName: String) = channelName
    }

    class SchemaPrefix(private val schemaName: String) : ChannelNameQualifier {
        init { requireValidIdentifier(schemaName) }
        override fun qualify(channelName: String) = "${schemaName}__$channelName"
    }
}
```

---

## Fluxo completo de uso

```kotlin
// 1. Declarar canal e trigger
val UserEventsChannel   = NotifyChannel("user_events", StringNotifyCodec)
val UserInsertedTrigger = NotifyTrigger(
    name       = "trg_notify_user_inserted",
    table      = UsersTable,
    channel    = UserEventsChannel,
    payloadSql = "NEW.id",
)

// 2. Gerar e aplicar DDL (uma vez, no startup / migration pipeline)
val schema = migrationSchema(
    version  = "2026.05.27.001",
    tables   = listOf(UsersTable),
    dialect  = PostgresMigrationDialect,
    triggers = listOf(UserInsertedTrigger),
)
aggo.applyMigration(migrationPlan(schema, PostgresMigrationDialect))

// 3. Escutar notificações
val backend = AggoListener(aggoPool.connectionFactory)  // ou OutboxListener(aggo) para MySQL
backend.listen(UserEventsChannel).collect { notification ->
    println("User inserted: ${notification.payload}")
}

// 4. As notificações chegam automaticamente a cada INSERT em users,
//    sem nenhuma chamada extra no código de negócio:
aggo.tx { insert(UsersTable, newUser) }
```

---

## Critérios de aceitação

| ID | Critério |
|---|---|
| N-ACC-1 | `INSERT` em tabela com `NotifyTrigger` entrega `Notification` ao listener após commit |
| N-ACC-2 | Rollback da transação não entrega notificação ao listener |
| N-ACC-3 | `OutboxListener` entrega notificações emitidas por triggers MySQL/Oracle via polling |
| N-ACC-4 | `AggoListener` reconecta automaticamente após queda de conexão (sem exceção no collector) |
| N-ACC-5 | Cancelar o collector executa `UNLISTEN` e fecha a conexão (sem leak) |
| N-ACC-6 | `migrationSchema(..., triggers = listOf(t))` inclui DDL de função + trigger no `MigrationPlan` |
| N-ACC-7 | `PostgresTriggerDialect.renderNotifyTriggerDdl` gera `CREATE OR REPLACE FUNCTION` + `CREATE TRIGGER` válidos |
| N-ACC-8 | `MySqlTriggerDialect.renderNotifyTriggerDdl` com `events = {Insert, Update}` gera dois triggers separados |
| N-ACC-9 | `MySqlTriggerDialect.renderOutboxTableDdl` gera `CREATE TABLE IF NOT EXISTS aggo_notifications` |
| N-ACC-10 | `NotifyChannel` com nome inválido (`;`, espaço, `'`) lança `IllegalArgumentException` no construtor |
| N-ACC-11 | `LongNotifyCodec.decode(null)` e `decode("")` retornam `null` sem exceção |
| N-ACC-12 | Dois collectors em `listen(UserEventsChannel)` concorrentes recebem notificações independentemente |
| N-ACC-13 | Payload de 8 kB (limite PostgreSQL) é transmitido sem truncação |

---

## Estratégia de testes

### Unitários (sem I/O)

- `TriggerDialectTest` — DDL gerado por `PostgresTriggerDialect`, `MySqlTriggerDialect`, `OracleTriggerDialect`
- `NotifyCodecTest` — encode/decode: null, blank, overflow, round-trip
- `ChannelNameQualifierTest` — `SchemaPrefix`, `None`, nomes inválidos
- `ReconnectPolicyTest` — backoff exponencial, `maxAttempts`, `NoRetry`
- `MigrationTriggerTest` — `migrationPlan` com triggers novos, modificados, removidos

### Integração (Testcontainers `postgres:16-alpine`)

- **N-INT-1** — INSERT → listener recebe notificação (trigger automático)
- **N-INT-2** — Rollback → listener não recebe nada
- **N-INT-3** — UPDATE com trigger `{Insert, Update}` → listener recebe em ambos
- **N-INT-4** — Queda de conexão → listener reconecta e recebe próximas notificações
- **N-INT-5** — `Session.notify()` manual dentro de tx → entregue após commit
- **N-INT-6** — Dois listeners concorrentes → ambos recebem
- **N-INT-7** — Cancelamento → `pg_listening_channels()` não lista o canal após cancelar

### Integração MySQL (Testcontainers `mysql:8`)

- **N-INT-M1** — INSERT → outbox recebe linha → `OutboxListener` emite notificação
- **N-INT-M2** — Rollback → linha não aparece no outbox → listener não recebe

---

## Não incluso nesta spec

| Exclusão | Justificativa |
|---|---|
| Trigger BEFORE com retorno de valor modificado | Semântica complexa e dialect-específica; `AFTER` cobre o caso de notificação |
| Payload > 8 kB | Limite hard do PostgreSQL; usar ID no payload + fetch subsequente |
| Retry de `pg_notify` | Responsabilidade do chamador (outbox pattern do domínio) |
| WebSocket / SSE bridge | Camada de apresentação fora do escopo |
| Oracle DBMS_AQ | Outbox pattern é suficiente e mais simples; AQ pode ser adicionado como backend alternativo em spec posterior |
| Limpeza automática da outbox | Estratégia de TTL/archiving pertence ao operador; Aggo não deleta dados silenciosamente |
